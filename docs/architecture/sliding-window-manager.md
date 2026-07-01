# SlidingWindowManager

## 1. Vai trò

`SlidingWindowManager` quản lý histogram trượt cho latency và queue. Nó cung cấp percentile snapshot cho `ScoreCalculator` và `PIDController`.

Nó không quyết định backend nào được chọn. Nó chỉ cung cấp dữ liệu phân phối.

---

## 2. Dữ liệu được ghi

`MetricsPoller` gọi:

```java
windowManager.addMetrics(instanceId, latency, realQueue);
```

Trong đó:

- `latency` là delta-average latency từ poll window;
- `realQueue` là queue/inflight hiện tại.

Lưu ý khoa học: percentile trong `SlidingWindowManager` được xây từ sample metrics polling, không phải từ từng request raw riêng lẻ.

---

## 3. Snapshot theo instance

`getSnapshot(instanceId)` cung cấp các giá trị như:

- p50 latency;
- p75 latency;
- p99 queue;
- các fallback khi histogram chưa đủ dữ liệu.

Các giá trị này phục vụ:

- EWMA initialization/fallback;
- normalize queue;
- PID setpoint theo instance/system.

---

## 4. System snapshot

`getSystemSnapshot()` tổng hợp dữ liệu toàn cụm để có:

- system p5;
- system p75;
- system p95.

`ScoreCalculator` dùng system p5/p95 để chuẩn hóa latency tương đối giữa backend.

---

## 5. Vì sao dùng sliding window

Nếu chỉ dùng toàn bộ lịch sử, metrics cũ sẽ kéo dài ảnh hưởng quá lâu. Sliding window giúp score phản ánh trạng thái gần đây hơn.

Nếu chỉ dùng sample mới nhất, routing dễ nhiễu. Histogram window giúp có percentile ổn định hơn.

---

## 6. Reset

Khi gọi:

```http
POST /actuator/alb/reset
```

`AdminController` gọi `windowManager.resetAll()` để xóa histogram cũ. Đây là bước bắt buộc trước benchmark để tránh run sau bị ảnh hưởng bởi run trước.

---

## 7. Giới hạn

- Percentile không phải per-request percentile tuyệt đối.
- Poll interval 200 ms có thể bỏ lỡ spike ngắn.
- Nếu JMeter ngừng gửi request, idle latency vẫn có thể được ghi để cache/routing ổn định.
- Khi mới reset, snapshot có thể dùng fallback trong vài chu kỳ đầu.

Khi viết luận văn, nên nói rõ rằng `SlidingWindowManager` cung cấp “percentile của mẫu metrics polling” thay vì “percentile request backend chính xác tuyệt đối”.
