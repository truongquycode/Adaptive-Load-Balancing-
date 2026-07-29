# Pipeline Xử Lý Metrics và Điều Hướng Request

Quá trình luân chuyển dữ liệu từ lúc lấy mẫu metrics định kỳ cho đến khi ra quyết định chọn instance để điều hướng request diễn ra theo một pipeline gồm các bước chính sau đây:

### 1. Lên Lịch và Thu Thập Dữ Liệu (Scheduled Polling)
- **Hàm xử lý:** `void pollMetrics()` (Class `MetricsPoller`)
- **Mô tả:** 
  Hàm được cấu hình chạy định kỳ (theo `fixedRateString`) để điều phối việc thu thập metrics. Đầu tiên, hàm lấy danh sách các instance đang hoạt động (UP) từ cache của Eureka (`discoveryClient.getInstances()`). Kế tiếp, nó so sánh tập instance hiện tại với chu kỳ trước để phát hiện sự thay đổi topology mạng (ví dụ: có node mới tham gia hoặc node vừa sập), từ đó tiến hành dọn dẹp các dữ liệu không còn giá trị (`cleanupStaleData`). Sau cùng, hàm kích hoạt việc thu thập metrics đồng thời (song song, non-blocking) cho tất cả các instances bằng reactive stream (`Mono.when`).

### 2. Truy Vấn Metrics Của Từng Backend (Single Instance Polling)
- **Hàm xử lý:** `Mono<Void> pollSingleInstance(ServiceInstance instance)` (Class `MetricsPoller`)
- **Mô tả:** 
  Thực hiện một HTTP GET request (thông qua `WebClient`) tới endpoint `/api/alb-metrics` của từng instance. 
  - **Happy Path:** Khi phản hồi thành công trong thời gian timeout cho phép, bộ đếm lỗi của instance được reset về 0 và payload JSON trả về được chuyển tiếp cho hàm xử lý metrics.
  - **Error Path:** Nếu gặp lỗi mạng hoặc timeout, bộ đếm thất bại (`consecutiveFailures`) sẽ tăng lên. Khi đó, hệ thống sẽ tự động gán một hình phạt (penalty score) tăng dần. Hình phạt này được đưa qua bộ lọc làm mịn trung bình động hàm mũ (EMA) và tạo ra một điểm số "xấu" giả lập nhằm báo hiệu cho Load Balancer ngưng đẩy request vào node đang lỗi.

### 3. Parse và Tính Toán Điểm Số (Metrics Processing & Scoring)
- **Hàm xử lý:** `void processMetrics(String instanceId, JsonNode node)` (Class `MetricsPoller`)
- **Mô tả:** 
  Hàm trích xuất các dữ liệu đo lường thô như CPU usage, số lượng request hoàn thành (count), tổng thời gian xử lý (totalTime) và hàng đợi (queue).
  Từ số liệu thô này, hàm sẽ:
  - Tính toán độ trễ chênh lệch (Delta Latency) giữa lần lấy mẫu này và lần lấy mẫu trước đó để ra được mức độ trễ thực tế.
  - Cập nhật số liệu vào `SlidingWindowManager`.
  - Kết hợp với hệ số phần cứng (`capacityWeight`), tiến hành gọi `ScoreCalculator` để đánh giá và tạo ra `ScoreBreakdown` dựa vào Latency, CPU và Queue.
  - Vận dụng hàm làm mịn `applyScoreEma()` để tránh điểm số (score) bị nhảy đột ngột do nhiễu tức thời. 
  - Cuối cùng, điểm số cuối cùng được lưu trữ vào bộ nhớ dùng chung (`metricsCache`, `scoreValues`...) và xuất ra Prometheus Gauges phục vụ quan trắc.

### 4. Lựa Chọn Node Xử Lý (Instance Selection)
- **Hàm xử lý:** `Response<ServiceInstance> selectInstance(List<ServiceInstance> instances)` (Class `AdaptiveLoadBalancer`)
- **Mô tả:** 
  Đây là điểm cuối của pipeline khi có một request thực tế gửi đến Gateway. Thuật toán cân bằng tải thích nghi sẽ nhận vào danh sách các instances khả dụng.
  - Trước hết, nó đánh giá chi phí định tuyến (`RoutingCostCalculator.calculate()`) tổng hợp từ điểm số đã lưu trong cache (từ quá trình polling) cộng với số lượng request đang bay (inflight requests) để ra quyết định theo thời gian thực.
  - Nếu toàn bộ hệ thống đang trong giai đoạn khởi động (warmup) hoặc tải đang cực kỳ thấp, Load Balancer dùng `Round-Robin` làm chiến lược dự phòng.
  - Tại điều kiện bình thường, hệ thống sử dụng thuật toán **P2C (Power of Two Choices)**. Hàm `chooseByP2C()` sẽ bốc ngẫu nhiên hai ứng viên, so sánh chi phí (`routingCostCalculator.better()`) và chọn ra node có chi phí thấp hơn (tốt hơn).
  - Thuật toán còn có cơ chế `maybeProbe` để gửi thử request (probe recovery) thỉnh thoảng vào một node đang bị loại (hard excluded) nhằm kiểm tra xem node đó đã thực sự hồi phục chưa mà không làm sập tail latency của toàn cụm. Node được chọn sẽ tiếp nhận request và metric tương ứng sẽ được ghi lại.
