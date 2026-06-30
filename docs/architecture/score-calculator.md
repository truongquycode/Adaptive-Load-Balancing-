# ScoreCalculator

## 1. Vai trò

`ScoreCalculator` tính health score cho từng backend dựa trên metrics vừa được `MetricsPoller` thu thập.

Đầu vào:

```text
InstanceMetrics:
    - latency
    - queueLength
    - cpu
```

Đầu ra:

```text
ScoreBreakdown:
    - ewmaLatency
    - normLatency
    - normQueue
    - normCpu
    - baseScore
    - pidPenalty
    - finalScore
    - updatedAtMs
```

`finalScore` càng thấp thì backend càng tốt.

---

## 2. Vị trí trong pipeline

```text
MetricsPoller
    |
    | InstanceMetrics
    v
ScoreCalculator
    |
    | EWMA
    | normalization
    | Dynamic MCDM
    | PID
    v
ScoreBreakdown
    |
    v
MetricsCache
    |
    v
RoutingCostCalculator
```

`ScoreCalculator` không trực tiếp route request. Kết quả của nó được lưu vào `MetricsCache` để Adaptive Load Balancer dùng ở tầng data-plane.

---

## 3. Xử lý trường hợp không có metrics

Nếu `InstanceMetrics current == null`, `ScoreCalculator` trả về score rất xấu:

```text
SCORE_NULL_INSTANCE = 20.0
```

Score này cao hơn nhiều so với score bình thường, giúp Adaptive gần như không chọn backend không có metrics.

---

## 4. Lấy percentile snapshot

Đầu tiên, `ScoreCalculator` lấy snapshot theo instance:

```java
PercentileSnapshot snap = windowManager.getSnapshot(instanceId);
```

Snapshot gồm:

```text
p5
p50
p95
qP99
```

Trong đó:

- `p50` dùng làm fallback khi khởi tạo EWMA.
- `qP99` dùng làm ngưỡng chuẩn hóa queue.
- `p5`, `p95` theo instance vẫn được lưu trong snapshot, nhưng latency normalization chính hiện dùng system-wide snapshot.

Sau đó lấy system-wide snapshot:

```java
SlidingWindowManager.SystemSnapshot sysSs = windowManager.getSystemSnapshot();
```

System snapshot gồm:

```text
systemP5
systemP75
systemP95
```

---

## 5. EWMA latency

Latency thô được xác định:

```text
lRaw = current.latency > 0 ? current.latency : p50
```

Sau đó gọi:

```java
ewmaSmoother.smooth(instanceId, lRaw, tauMin, tauMax, k, p50)
```

Cấu hình hiện tại:

```yaml
alb:
    ewma:
        tau-min: 200.0
        tau-max: 2000.0
        k: 3.0
```

Kết quả là:

```text
ewmaLatency
```

Đây là latency đã được làm mượt, dùng cho bước chuẩn hóa.

---

## 6. Fallback system percentile

Nếu system snapshot chưa hợp lệ:

```text
sysP95 <= sysP5
sysP5 < 1.0
```

ScoreCalculator dùng fallback:

```text
SYSTEM_P5_FALLBACK = 30.0ms
SYSTEM_P95_FALLBACK = 300.0ms
```

Sau đó kiểm tra độ rộng dải latency:

```text
latencyRange = sysP95 - sysP5
```

Nếu dải nhỏ hơn:

```text
MIN_SYSTEM_LATENCY_RANGE_MS = 80.0
```

thì dải được mở rộng tối thiểu thành 80ms. Mục đích là tránh khuếch đại nhiễu nhỏ ở low load.

---

## 7. Chuẩn hóa latency

Latency được chuẩn hóa theo min-max trên system-wide P5/P95:

```text
normLatency = clamp((ewmaLatency - sysP5) / (sysP95 - sysP5), 0, 1)
```

Ý nghĩa:

- `0`: latency tốt gần system P5.
- `1`: latency xấu gần system P95.
- Giá trị nằm giữa phản ánh vị trí tương đối của backend trong cụm.

---

## 8. Chuẩn hóa queue

Queue dùng log scaling:

```text
qMax = max(qP99, 40.0)

logScore = log1p(qRaw) / log1p(qMax)
linearScore = min(1.0, qRaw / (qMax * 2.0))

normQueue = min(1.0, 0.6 * logScore + 0.4 * linearScore)
```

Lý do dùng log scaling:

- Queue thường tăng theo phân phối lệch phải.
- Chênh lệch từ 0 lên 5 request quan trọng hơn chênh lệch từ 100 lên 105.
- Log scaling giúp thuật toán nhạy với queue nhỏ nhưng không bị sốc khi queue spike lớn.

Nếu `qRaw <= 0`:

```text
normQueue = 0
```

---

## 9. Chuẩn hóa CPU

CPU được chuẩn hóa đơn giản:

```text
normCpu = clamp(cpuRaw, 0, 1)
```

Nếu giá trị CPU là NaN hoặc âm:

```text
normCpu = 0.5
```

Ở backend, `cpu` đã được chuẩn hóa theo CPU quota container trước khi trả về `/api/alb-metrics`.

---

## 10. MCDM base score

Sau khi có ba giá trị chuẩn hóa:

```text
normLatency
normQueue
normCpu
```

ScoreCalculator gọi:

```java
weightEngine.computeBaseScore(nL, nQ, nC)
```

Công thức:

```text
baseScore = alpha * normLatency
          + beta  * normQueue
          + gamma * normCpu
```

Với trọng số được `DynamicWeightEngine` cập nhật định kỳ.

Khi hệ thống vừa reset hoặc ổn định, trọng số quay về AHP prior:

```text
alpha = 0.648
beta  = 0.230
gamma = 0.122
```

---

## 11. PID penalty

Setpoint của PID là system P75 đã chuẩn hóa cùng thang với latency:

```text
normalizedP75 = normalizeLatency(systemP75, sysP5, invRange)
```

Sau đó tính penalty:

```java
pidPenalty = pidController.calculatePenalty(
    instanceId,
    normLatency,
    normalizedP75,
    props.getPid()
);
```

PID penalty tăng khi backend chậm hơn P75 toàn hệ thống trong thời gian đủ dài.

---

## 12. Final score

Công thức cuối:

```text
finalScore = baseScore + pidPenalty
```

Trong đó:

- `baseScore` thường nằm trong `[0,1]`.
- `pidPenalty` nằm trong `[0, lambda]`, với cấu hình hiện tại `lambda = 0.8`.

Vì vậy score thực tế thường nằm khoảng:

```text
0.0 đến 1.8
```

Nếu poll failure hoặc null metrics, score có thể cao hơn nhiều do cơ chế penalty của `MetricsPoller`.

---

## 13. ScoreBreakdown

Kết quả trả về:

```java
new ScoreBreakdown(
    instanceId,
    ewmaLatency,
    normLatency,
    normQueue,
    normCpu,
    baseScore,
    pidPenalty,
    finalScore,
    updatedAtMs
)
```

Các trường này giúp:

- Gateway định tuyến bằng `finalScore`.
- Grafana hiển thị health score.
- Debug được vì sao một backend bị giảm traffic.

---

## 14. Quan hệ với MetricsPoller

`ScoreCalculator` trả `finalScore` thô. Sau đó `MetricsPoller` áp thêm EMA bất đối xứng:

```text
raw finalScore
    |
    v
MetricsPoller.applyScoreEma()
    |
    v
smoothed finalScore
    |
    v
MetricsCache.putScore()
```

Do đó `alb_final_score` trên Prometheus là score đã qua bước smoothing cuối trong `MetricsPoller`.

---

## 15. Reset

`ScoreCalculator` không có state riêng cần reset. Các state ảnh hưởng đến kết quả của nó nằm ở:

- `EwmaSmoother`
- `PIDController`
- `SlidingWindowManager`
- `DynamicWeightEngine`
- `MetricsCache`
- `MetricsPoller`

Tất cả được reset thông qua:

```text
POST /actuator/alb/reset
```
