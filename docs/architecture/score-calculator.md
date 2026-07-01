# ScoreCalculator

## 1. Vai trò

`ScoreCalculator` tính health score cho từng backend dựa trên metrics runtime:

```text
latency + queue + CPU
    |
    v
normalized latency / normalized queue / normalized CPU
    |
    v
MCDM base score
    |
    v
PID-inspired latency penalty
    |
    v
final health score
```

Score càng thấp thì backend càng tốt. Score này chưa phải quyết định routing cuối cùng; `RoutingCostCalculator` sẽ kết hợp thêm inflight/capacity/stale để tạo final routing cost.

---

## 2. Đầu vào

| Đầu vào | Nguồn | Ý nghĩa |
|---|---|---|
| `latency` | `MetricsPoller` | delta-average latency trong cửa sổ poll |
| `queueLength` | backend gauge hoặc `InflightTracker` fallback | request đang xử lý/chờ |
| `cpu` | `/api/alb-metrics` | CPU đã chuẩn hóa theo capacity |
| Percentile snapshot | `SlidingWindowManager` | p50/p75/p99 của instance |
| System snapshot | `SlidingWindowManager` | p5/p75/p95 toàn cụm |
| MCDM weights | `DynamicWeightEngine` | trọng số latency/queue/CPU |
| PID state | `PIDController` | penalty khi latency xấu kéo dài |

---

## 3. Latency EWMA

Latency thô có thể nhiễu hoặc có outlier. Vì vậy score dùng `EwmaSmoother`:

```text
ewmaLatency = adaptiveEwma(instanceId, rawLatency)
```

Ablation `no-ewma-latency` dùng latency thô để kiểm tra EWMA có giúp giảm flapping hay không.

---

## 4. Chuẩn hóa latency

Latency được chuẩn hóa theo snapshot toàn hệ thống:

```text
normLatency = (ewmaLatency - sysP5) / (sysP95 - sysP5)
clamp vào [0, 1]
```

Nếu dải `sysP95 - sysP5` quá hẹp, code mở rộng dải tối thiểu để tránh phóng đại nhiễu nhỏ ở low load.

Giới hạn cần ghi rõ:

- đây là chuẩn hóa tương đối trong cụm;
- nếu tất cả backend cùng chậm, chuẩn hóa tương đối có thể che bớt tình trạng toàn cụm xấu;
- `RoutingCostCalculator` bổ sung absolute health penalty để giảm rủi ro này.

---

## 5. Chuẩn hóa queue

Queue được chuẩn hóa bằng log-scale so với p99 queue của instance:

```text
normQueue = normalizeQueue(currentQueue, instanceQueueP99)
```

Lý do dùng log-scale:

- queue nhỏ tăng từ 0 lên vài request cần phản ứng rõ;
- queue cực lớn không nên làm score nổ vô hạn;
- phù hợp với phân phối right-skewed của queue/inflight.

---

## 6. Chuẩn hóa CPU

CPU được clamp về [0,1]:

```text
normCpu = clamp(cpu, 0, 1)
```

Trong backend, `cpu` đã được chuẩn hóa theo capacity weight:

```text
cpu = rawCpu / capacityWeight
```

Cần kiểm tra thực nghiệm khi dùng container vì `process.cpu.usage` phụ thuộc cách JVM/Micrometer báo cáo CPU.

---

## 7. MCDM base score

Base score:

```text
baseScore = alpha * normLatency
          + beta  * normQueue
          + gamma * normCpu
```

Mặc định AHP prior:

```text
alpha = 0.648
beta  = 0.230
gamma = 0.122
```

Các giá trị này được suy ra từ ma trận so sánh cặp AHP giữa ba tiêu chí `latency`, `queue`, `cpu`:

```text
A = | 1    3    5 |
    | 1/3  1    2 |
    | 1/5  1/2  1 |
```

Ma trận có Consistency Ratio xấp xỉ `0.003 < 0.1`, nên đủ nhất quán để dùng làm trọng số nền. Chi tiết xem `ahp-default-weight-rationale.md`.

Khi có đủ real traffic, `DynamicWeightEngine` có thể cập nhật weights bằng EWM + AHP blend.

Ablation `fixed-weights` bỏ dynamic EWM và dùng fixed weights.

---

## 8. PID-inspired penalty

Sau base score, code thêm penalty từ `PIDController`:

```text
finalScore = baseScore + pidPenalty
```

Setpoint của PID-inspired controller dựa trên latency P75 toàn hệ thống đã chuẩn hóa. Nếu một instance chậm hơn setpoint trong thời gian dài, penalty tăng.

Không nên gọi đây là PID controller đầy đủ trong điều khiển học. Trong luận văn nên dùng thuật ngữ:

```text
PID-inspired latency penalty
```

Ablation `no-pid` đặt penalty bằng 0.

---

## 9. Đầu ra `ScoreBreakdown`

`ScoreCalculator` trả `ScoreBreakdown` gồm:

- instance id;
- EWMA latency;
- normalized latency;
- normalized queue;
- normalized CPU;
- base score;
- PID penalty;
- final score;
- timestamp.

Các giá trị này giúp Grafana phân tích vì sao một backend bị tăng cost.

---

## 10. Giới hạn khoa học

- Latency đầu vào là delta-average theo poll window, không phải p95/p99 raw từng request.
- Percentile từ `SlidingWindowManager` được xây từ sample metrics polling, không phải từ từng request riêng lẻ.
- Weight và ngưỡng vẫn là heuristic, cần ablation và sensitivity analysis để bảo vệ.
- Score thấp không tự động đảm bảo backend tốt nhất trong mọi hoàn cảnh; quyết định cuối còn phụ thuộc inflight, capacity và stale penalty.
