# PIDController

## 1. Vai trò

`PIDController` tính thêm một khoản penalty cho backend có latency cao hơn mức tham chiếu của toàn hệ thống.

Trong pipeline hiện tại:

```text
ScoreCalculator
    |
    | baseScore = MCDM(latency, queue, cpu)
    |
    | pidPenalty = PIDController.calculatePenalty(...)
    v
finalScore = baseScore + pidPenalty
```

`PIDController` không thay thế MCDM. Nó chỉ bổ sung khả năng phản ứng theo sai số latency theo thời gian.

---

## 2. Đầu vào

Hàm chính:

```java
calculatePenalty(String instanceId, double rawLat, double setpoint, PidConfig cfg)
```

Trong thực tế:

| Tham số | Ý nghĩa |
|---|---|
| `instanceId` | ID backend |
| `rawLat` | `normLatency` của backend, đã chuẩn hóa về `[0,1]` |
| `setpoint` | P75 toàn hệ thống, cũng đã chuẩn hóa về `[0,1]` |
| `cfg` | Tham số PID trong `application.yml` |

Sai số PID:

```text
error = normLatency - normalizedSystemP75
```

Nếu `error > 0`, backend chậm hơn ngưỡng P75 hệ thống và có thể bị tăng penalty.

Nếu `error <= 0`, backend không bị thưởng thêm; penalty cuối cùng được clamp về không âm.

---

## 3. Cấu hình hiện tại

Trong `application.yml`:

```yaml
alb:
    pid:
        kp: 1.0
        ki: 0.08
        kd: 0.04
        tau-d: 2.0
        min-i: -0.8
        max-i: 2.5
        lambda: 0.8
        kappa: 1.2
```

Ý nghĩa:

| Tham số | Vai trò |
|---|---|
| `kp` | Hệ số proportional, phản ứng theo sai số hiện tại |
| `ki` | Hệ số integral, tích lũy sai số kéo dài |
| `kd` | Hệ số derivative, phản ứng theo tốc độ thay đổi latency |
| `tau-d` | Hằng số thời gian cho low-pass filter của D |
| `min-i` | Giới hạn dưới của integral |
| `max-i` | Giới hạn trên của integral |
| `lambda` | Biên trên của penalty |
| `kappa` | Độ dốc hàm `tanh` khi squash output |

---

## 4. Deadband

Mã nguồn có vùng chết:

```java
ERROR_DEADBAND = 0.08
```

Nếu sai số nằm trong khoảng nhỏ này, PID xem như bằng 0:

```text
|error| <= 0.08  =>  error = 0
```

Nếu sai số lớn hơn deadband, phần deadband được trừ ra:

```text
error > 0  =>  error = error - 0.08
error < 0  =>  error = error + 0.08
```

Mục đích:

- Bỏ qua dao động nhỏ ở low load.
- Tránh instance bị penalty chỉ vì chênh lệch latency vài mili giây.
- Giảm khả năng Adaptive tự dao động khi hệ thống ổn định.

---

## 5. Thành phần P

Thành phần P phản ứng tức thời với sai số hiện tại:

```text
P = kp * error
```

Nếu backend đột ngột chậm hơn P75 hệ thống, P tăng ngay trong chu kỳ tính score đó.

---

## 6. Thành phần I

Thành phần I tích lũy sai số theo thời gian:

```text
I_state = I_state + error * dt
I = ki * I_state
```

`dt` được tính bằng giây và bị clamp:

```text
dt ∈ [0.001, 5.0]
```

Integral bị giới hạn:

```text
I_state ∈ [minI, maxI]
```

Với cấu hình hiện tại:

```text
I_state ∈ [-0.8, 2.5]
```

Mục đích của I là phát hiện backend chậm kéo dài. Nếu backend chỉ chậm một lần ngắn, I không tăng nhiều. Nếu backend liên tục chậm, I tích lũy và penalty tăng rõ hơn.

---

## 7. Conditional anti-windup

Mã nguồn có cơ chế chống windup:

```java
boolean isSaturated = Math.abs(prevOutput) >= 2.0;
boolean sameSign = (error * prevOutput) > 0.0;
```

Nếu output đã bão hòa và error tiếp tục đẩy output theo cùng chiều, integral không tích lũy thêm.

Mục đích:

- Tránh integral tăng ngầm quá lớn.
- Khi backend phục hồi, penalty không bị kéo dài quá lâu do integral cũ.
- Giữ PID ổn định trong giai đoạn stress.

---

## 8. Integral decay

Khi sai số nhỏ:

```text
|error| < 0.1
```

Integral được giảm dần:

```text
I_state = I_state * exp(ln(0.97) * dt)
```

Tương đương giảm khoảng 3% mỗi giây khi backend gần về trạng thái ổn định.

---

## 9. Thành phần D

Thành phần D phản ứng với tốc độ thay đổi latency:

```text
rawD = (rawLat - previousRawLat) / dt
```

Để tránh nhiễu cao tần, D được đưa qua low-pass filter:

```text
filteredD = (1 - e^(-dt/tauD)) * rawD
          + e^(-dt/tauD) * previousFilteredD
```

Sau đó:

```text
D = kd * filteredD
```

D giúp PID phản ứng với xu hướng latency đang tăng, không chỉ giá trị latency hiện tại.

---

## 10. Output và squash

Output thô:

```text
u = P + I + D
```

Penalty cuối:

```text
pidPenalty = lambda * tanh(kappa * max(0, u))
```

Ý nghĩa:

- `max(0, u)`: chỉ phạt backend chậm, không thưởng backend nhanh.
- `tanh`: giới hạn output, tránh penalty vô hạn.
- `lambda`: biên trên của penalty.

Với cấu hình hiện tại, penalty nằm trong khoảng:

```text
[0, 0.8)
```

---

## 11. State theo instance

Mỗi backend có state PID riêng trong Caffeine cache:

```java
private final Cache<String, PidState> states = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .build();
```

`PidState` lưu:

- `lastError`
- `integral`
- `lastFilteredD`
- `lastOutput`
- `lastTimestamp`
- `lastRawLat`

State hết hạn sau 5 phút không truy cập.

---

## 12. Reset

Khi gọi:

```text
POST /actuator/alb/reset
```

`AdminController` gọi:

```java
pidController.resetAllStates();
```

Toàn bộ integral, derivative và output cũ bị xóa. Đây là bước quan trọng trước benchmark để PID không mang trạng thái từ lần chạy trước sang lần chạy mới.
