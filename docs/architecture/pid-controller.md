# PIDController

## 1. Vai trò

`PIDController` trong dự án không điều khiển trực tiếp CPU, thread pool hay số request gửi đi. Nó tính một penalty cộng thêm vào health score của backend khi latency của backend xấu hơn setpoint.

Cách gọi chính xác nên dùng trong tài liệu:

```text
PID-inspired latency penalty controller
```

Không nên mô tả là một bộ điều khiển PID đầy đủ trong lý thuyết điều khiển.

---

## 2. Vị trí trong pipeline

```text
MetricsPoller
    |
    v
ScoreCalculator
    |
    | baseScore = MCDM(normLatency, normQueue, normCpu)
    | penalty   = PIDController.calculatePenalty(...)
    v
finalScore = baseScore + penalty
```

`RoutingCostCalculator` dùng `finalScore` này như health raw.

---

## 3. Setpoint

Setpoint được lấy từ latency P75 toàn hệ thống đã chuẩn hóa. Ý nghĩa:

```text
Nếu backend chậm hơn mức P75 của cụm, penalty có thể tăng.
Nếu backend nhanh hơn hoặc chỉ lệch nhỏ trong deadband, penalty không tăng đáng kể.
```

---

## 4. Các thành phần P/I/D

| Thành phần | Ý nghĩa trong dự án |
|---|---|
| P | Phản ứng theo sai lệch latency hiện tại |
| I | Tích lũy khi backend chậm kéo dài |
| D | Phản ứng với xu hướng sai lệch thay đổi |

Các tham số nằm trong:

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

---

## 5. Cơ chế ổn định

PID-inspired penalty có các guard:

- deadband để bỏ qua dao động nhỏ;
- anti-windup qua `min-i` và `max-i`;
- derivative filtering bằng `tau-d`;
- clamp penalty để không lấn át hoàn toàn base score.

Reset state khi gọi:

```http
POST /actuator/alb/reset
```

---

## 6. Ablation

Variant:

```text
no-pid
```

Khi bật variant này, penalty = 0. Mục tiêu là kiểm tra PID-inspired penalty có giúp giảm traffic khỏi backend chậm kéo dài hay không.

---

## 7. Cách diễn giải khoa học

Nên trình bày:

```text
PID được dùng như một penalty dựa trên sai lệch latency, giúp backend chậm kéo dài bị tăng score mạnh hơn so với chỉ nhìn base MCDM tức thời.
```

Cần tránh tuyên bố:

```text
PID đảm bảo ổn định hệ thống hoặc tối ưu điều khiển.
```

Lý do: dự án chưa có mô hình điều khiển đầy đủ, chưa có tuning method chính thức và chưa chứng minh ổn định bằng phân tích điều khiển.

---

## 8. Câu hỏi phản biện thường gặp

**PID có thật sự cần không?**  
Cần trả lời bằng ablation `no-pid`: so sánh p95/p99, error rate, routing distribution và recovery time.

**PID có làm hệ thống dao động không?**  
Có nguy cơ nếu tuning sai. Code giảm rủi ro bằng deadband, clamp, derivative filter và score EMA, nhưng vẫn cần benchmark để chứng minh.

**Vì sao không dùng penalty đơn giản?**  
Đây là lý do cần ablation. Nếu `no-pid` không kém hơn `full`, cần ghi nhận trung thực rằng PID chưa chứng minh đóng góp rõ trong kịch bản đó.
