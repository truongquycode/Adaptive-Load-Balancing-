# SlidingWindowManager

## 1. Vai trò

`SlidingWindowManager` lưu phân phối latency và queue bằng HDR Histogram. Thành phần này cung cấp các percentile cần thiết cho `ScoreCalculator`.

Nó không tính score trực tiếp và không chọn backend.

---

## 2. Vì sao cần sliding window

Latency trong microservices thường không ổn định. Một giá trị trung bình không đủ để mô tả hệ thống, vì có thể tồn tại long-tail latency.

Ví dụ:

```text
95% request: 50ms
5% request: 2000ms
```

Trung bình có thể bị kéo lên, nhưng không cho biết rõ phần lớn request vẫn nhanh và một phần nhỏ bị rất chậm.

HDR Histogram giúp lưu phân phối để lấy các percentile như P5, P50, P75, P95 và P99.

---

## 3. Cấu hình histogram

Trong mã nguồn hiện tại:

```text
MAX_LATENCY_MS = 60000
MAX_QUEUE_SIZE = 10000
SIGNIFICANT_DIGITS = 2
WINDOW_SIZE = 100
GLOBAL_WIN_SIZE = 160
```

Ý nghĩa:

| Hằng số | Ý nghĩa |
|---|---:|
| `MAX_LATENCY_MS` | Latency tối đa ghi vào histogram, 60 giây |
| `MAX_QUEUE_SIZE` | Queue tối đa ghi vào histogram |
| `SIGNIFICANT_DIGITS` | Độ chính xác HDR Histogram |
| `WINDOW_SIZE` | Số sample tối đa cho mỗi cửa sổ theo instance |
| `GLOBAL_WIN_SIZE` | Số sample tối đa cho cửa sổ toàn hệ thống |

---

## 4. Double-buffer histogram

Mỗi instance có hai histogram latency và hai histogram queue:

```text
latHists[0], latHists[1]
qHists[0], qHists[1]
```

Khi histogram active vượt `WINDOW_SIZE`, manager chuyển sang histogram còn lại và reset histogram mới.

Mục đích:

- Không phải xóa từng sample cũ.
- Giảm chi phí cập nhật.
- Tránh cửa sổ bị rỗng hoàn toàn khi vừa flip.
- Giữ dữ liệu gần đây thay vì giữ toàn bộ lịch sử quá dài.

---

## 5. Global histogram

Ngoài histogram theo instance, hệ thống còn có global histogram cho latency toàn cụm:

```text
globalPair[0]
globalPair[1]
```

Global histogram dùng để tính system-wide snapshot:

```text
systemP5
systemP75
systemP95
```

`ScoreCalculator` dùng system snapshot để chuẩn hóa latency của từng backend theo cùng một thước đo.

---

## 6. Ghi metrics vào histogram

`MetricsPoller` gọi:

```java
windowManager.addMetrics(instanceId, latency, queue);
```

Trước khi ghi, giá trị được clamp:

```text
latency ∈ [1, 60000]
queue   ∈ [1, 10000]
```

Lưu ý: queue bằng 0 khi ghi vào histogram sẽ được clamp lên 1 vì HDR Histogram được tạo với lower bound là 1. Tuy nhiên khi normalize queue trong `ScoreCalculator`, giá trị queue raw vẫn có thể bằng 0 và được xử lý riêng.

---

## 7. Snapshot theo instance

Hàm:

```java
getSnapshot(String instanceId)
```

Trả về:

```text
PercentileSnapshot(p5, p50, p95, qP99)
```

Nếu chưa có dữ liệu, fallback là:

```text
p5 = 0.0
p50 = 50.0
p95 = 100.0
qP99 = 10.0
```

Cách dùng:

| Giá trị | Nơi dùng |
|---|---|
| `p50` | Fallback cho EWMA khi cold start hoặc idle |
| `qP99` | Ngưỡng normalize queue |
| `p5`, `p95` | Thông tin phân phối theo instance |

---

## 8. Snapshot toàn hệ thống

Hàm:

```java
getSystemSnapshot()
```

Trả về:

```text
SystemSnapshot(p5, p75, p95)
```

Nếu chưa có dữ liệu, fallback là:

```text
p5 = 5.0
p75 = 50.0
p95 = 200.0
```

`ScoreCalculator` dùng:

- `systemP5` làm cận dưới normalize latency.
- `systemP95` làm cận trên normalize latency.
- `systemP75` làm setpoint cho PID.

---

## 9. getSafeGlobalHistogram

Để tránh dùng histogram vừa reset và chưa đủ sample, manager kiểm tra:

```text
active histogram có >= 20 sample
```

Nếu chưa đủ, nó dùng histogram cũ nếu histogram cũ còn dữ liệu.

Mục đích là tránh trường hợp percentile bị rỗng hoặc bị lệch ngay sau khi flip.

---

## 10. Quan hệ với ScoreCalculator

Luồng sử dụng:

```text
MetricsPoller.addMetrics()
    |
    v
SlidingWindowManager ghi latency/queue
    |
    v
ScoreCalculator.getSnapshot(instanceId)
ScoreCalculator.getSystemSnapshot()
    |
    v
normalize latency/queue
PID setpoint
EWMA fallback
```

`SlidingWindowManager` không quyết định backend nào tốt hơn. Nó chỉ cung cấp dữ liệu phân phối để các thành phần khác tính toán.

---

## 11. Reset

Khi gọi:

```text
POST /actuator/alb/reset
```

`AdminController` gọi:

```java
windowManager.resetAll();
```

Hàm này:

- Xóa toàn bộ state theo instance.
- Reset cả hai global histogram.
- Đưa global active index về `0`.

Sau reset, cần có một khoảng thời gian ngắn để histogram tích lũy lại dữ liệu trước khi percentile phản ánh chính xác trạng thái hệ thống.
