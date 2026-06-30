# EwmaSmoother

## 1. Vai trò

`EwmaSmoother` làm mượt latency thô của từng backend bằng Adaptive EWMA.

Latency thô lấy từ Micrometer Timer có thể dao động mạnh do:

- request nhẹ/nặng khác nhau,
- I/O wait,
- DB pool giả lập,
- GC,
- network jitter,
- chaos/dependency slowdown,
- số lượng request hoàn thành trong mỗi chu kỳ poll không đều.

Nếu đưa latency thô trực tiếp vào `ScoreCalculator`, score sẽ dao động mạnh và làm thuật toán định tuyến không ổn định. `EwmaSmoother` giảm nhiễu nhưng vẫn cho phép phản ứng nhanh khi latency tăng bất thường.

---

## 2. Vị trí trong pipeline

```text
MetricsPoller
    |
    | tính delta latency từ count và totalTime
    v
ScoreCalculator
    |
    | gọi EwmaSmoother.smooth()
    v
ewmaLatency
    |
    v
normalize latency
    |
    v
MCDM + PID
```

`EwmaSmoother` không chọn backend. Nó chỉ tạo ra `ewmaLatency` để `ScoreCalculator` dùng.

---

## 3. Cấu hình

Cấu hình hiện tại trong `api-gateway-alb/src/main/resources/application.yml`:

```yaml
alb:
    ewma:
        tau-min: 200.0
        tau-max: 2000.0
        k: 3.0
```

Ý nghĩa:

| Tham số | Ý nghĩa |
|---|---|
| `tau-min` | Hằng số thời gian nhỏ nhất, dùng khi latency thay đổi mạnh |
| `tau-max` | Hằng số thời gian lớn nhất, dùng khi latency ổn định |
| `k` | Độ nhạy của tau đối với deviation |

---

## 4. Công thức Adaptive EWMA

EWMA thông thường dùng một hệ số alpha cố định. Trong dự án này, hệ số làm mượt được điều chỉnh theo mức biến động latency.

Đầu tiên tính độ lệch tương đối:

```text
deviation = |rawLatency - ewmaPrevious| / max(ewmaPrevious, 1.0)
```

Sau đó tính tau thích nghi:

```text
tau = tauMin + (tauMax - tauMin) * e^(-k * deviation)
```

Khi deviation thấp:

```text
tau gần tauMax
```

EWMA phản ứng chậm hơn, lọc nhiễu tốt hơn.

Khi deviation cao:

```text
tau gần tauMin
```

EWMA phản ứng nhanh hơn, phù hợp khi backend bắt đầu chậm hoặc có chaos.

---

## 5. Tính hệ số theta

Sau khi có tau, hệ số cập nhật được tính theo thời gian giữa hai lần cập nhật:

```text
theta = 1 - e^(-dt / tau)
```

Trong đó:

- `dt` là thời gian từ lần tính EWMA trước đến hiện tại.
- `tau` là hằng số thời gian thích nghi.

Giá trị EWMA mới:

```text
ewma = theta * rawLatency + (1 - theta) * ewmaPrevious
```

Nếu `theta` nhỏ, giá trị cũ được giữ nhiều hơn. Nếu `theta` lớn, latency mới có ảnh hưởng mạnh hơn.

---

## 6. Xử lý cold start

State EWMA được lưu theo `instanceId`.

Nếu instance chưa có state, `EwmaSmoother` khởi tạo bằng `fallbackP50`:

```java
return new EwmaState(fallbackP50, now);
```

`fallbackP50` đến từ `SlidingWindowManager.getSnapshot(instanceId).p50()`.

Lý do không khởi tạo từ `0ms`:

- Nếu bắt đầu từ 0, EWMA cần nhiều chu kỳ mới lên được latency thực tế.
- Trong thời gian đó backend có thể bị đánh giá tốt giả.
- P50 lịch sử là giá trị khởi tạo hợp lý hơn.

---

## 7. Giới hạn dt

Mã nguồn giới hạn `dt`:

```text
dt ∈ [1ms, 3 * tauMax]
```

Mục đích:

- Tránh `dt = 0` làm EWMA không cập nhật.
- Tránh instance offline lâu rồi quay lại khiến EWMA reset quá mạnh về raw latency mới.
- Giữ trạng thái làm mượt ổn định khi backend tạm thời không được poll.

---

## 8. Lưu state bằng Caffeine

State được lưu trong cache:

```java
private final Cache<String, EwmaState> states = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .build();
```

Mỗi backend có một `EwmaState` riêng gồm:

```text
value
lastTimestamp
```

Sau 5 phút không được truy cập, state sẽ tự hết hạn. Điều này giúp dọn state của instance đã down hoặc không còn xuất hiện trong Eureka.

---

## 9. Khác biệt giữa EWMA latency và EMA finalScore

Trong dự án có hai lớp smoothing khác nhau:

| Thành phần | Làm mượt cái gì | Nằm ở đâu |
|---|---|---|
| `EwmaSmoother` | latency thô | `ScoreCalculator` |
| `MetricsPoller.applyScoreEma()` | finalScore | `MetricsPoller` |

`EwmaSmoother` xử lý nhiễu latency đầu vào.

EMA finalScore xử lý dao động của điểm tổng hợp sau khi đã có MCDM và PID.

Hai lớp này không trùng vai trò.

---

## 10. Reset

Khi gọi:

```text
POST /actuator/alb/reset
```

`AdminController` gọi:

```java
ewmaSmoother.resetAllStates();
```

Lệnh này xóa toàn bộ state EWMA. Lần poll tiếp theo sẽ khởi tạo lại bằng fallback P50.
