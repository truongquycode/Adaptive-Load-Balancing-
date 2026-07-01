# EwmaSmoother

## 1. Vai trò

`EwmaSmoother` làm mượt latency trước khi `ScoreCalculator` chuẩn hóa và tính score.

Latency raw có thể dao động do:

- outlier request;
- network jitter;
- JVM/GC;
- poll window ngắn;
- backend vừa reset hoặc vừa nhận spike.

EWMA giúp routing không đổi hướng quá mạnh chỉ vì một vài sample bất thường.

---

## 2. Công thức tổng quát

Ý tưởng EWMA:

```text
smoothed = alpha * current + (1 - alpha) * previous
```

Trong dự án, alpha không cố định hoàn toàn. `EwmaSmoother` dùng tham số tau và điều chỉnh theo độ lệch giữa latency mới và latency đã làm mượt.

Cấu hình:

```yaml
alb:
  ewma:
    tau-min: 200.0
    tau-max: 2000.0
    k: 3.0
```

Ý nghĩa:

- tau nhỏ -> phản ứng nhanh hơn;
- tau lớn -> ổn định hơn;
- khi latency lệch mạnh, tau giảm để phản ứng nhanh;
- khi latency ổn định, tau tăng để lọc nhiễu.

---

## 3. Vị trí trong pipeline

```text
raw latency from MetricsPoller
    |
    v
EwmaSmoother.smooth(...)
    |
    v
ewmaLatency
    |
    v
ScoreCalculator normalization
```

Ablation `no-ewma-latency` bỏ bước này và dùng latency thô.

---

## 4. Lợi ích

- Giảm flapping khi latency chỉ dao động nhẹ.
- Giúp routing ổn định hơn trong low/medium load.
- Giảm khả năng một spike đơn lẻ làm backend bị phạt quá lâu.
- Vẫn phản ứng nhanh hơn EWMA cố định khi deviation lớn.

---

## 5. Trade-off

EWMA luôn có đánh đổi:

| Lợi ích | Rủi ro |
|---|---|
| Giảm nhiễu | Có thể phản ứng chậm hơn với degradation rất nhanh |
| Ổn định routing | Có thể che spike ngắn |
| Giảm flapping | Cần reset state trước benchmark |

Do đó benchmark cần reset ALB trước mỗi run:

```http
POST /actuator/alb/reset
```

---

## 6. Cách diễn giải trong luận văn

Nên viết:

```text
EWMA được dùng để cân bằng giữa độ nhạy và độ ổn định của tín hiệu latency. Khi latency thay đổi mạnh, hệ số làm mượt phản ứng nhanh hơn; khi latency ổn định, bộ lọc làm mượt mạnh hơn để giảm nhiễu.
```

Không nên viết:

```text
EWMA làm latency chính xác hơn tuyệt đối.
```

EWMA không làm metric “đúng hơn”, mà làm tín hiệu điều khiển ổn định hơn.
