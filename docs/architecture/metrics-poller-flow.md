# MetricsPoller

## Vai trò

MetricsPoller là component chạy nền trong api-gateway-alb, có nhiệm vụ định kỳ
thu thập metrics từ tất cả instance của registration-service-alb, tính toán score
cho từng instance, rồi lưu vào MetricsCache để AdaptiveLoadBalancer đọc khi cần
chọn instance cho request tiếp theo.

Nói ngắn gọn: đây là "mắt" của hệ thống. Không có MetricsPoller, AdaptiveLoadBalancer
không biết instance nào đang khỏe, instance nào đang quá tải.


## Luồng chạy tổng quát
	@Scheduled(200ms)
	       │
	       ├─ [mutex isPolling] skip nếu đang bận
	       │
	       ├─ Eureka → List<ServiceInstance>
	       │
	       ├─ topology changed? → cleanupStaleData()
	       │
	       └─ Parallel WebClient.GET /api/alb-metrics × N instances
	              │
	              ├─ SUCCESS → processMetrics()
	              │     ├─ calculateDeltaLatency() → latency (ms)
	              │     ├─ ScoreCalculator.calculateScore() → ScoreBreakdown
	              │     │     └─ [EWMA → normalize p5/p95 → MCDM → PID penalty]
	              │     ├─ applyScoreEma() → smoothedFinalScore
	              │     └─ metricsCache.putScore() ← AdaptiveLoadBalancer đọc từ đây
	              │
	              └─ FAILURE → penalty score tăng dần, lưu vào cache
## Các thành phần chính

### isPolling

AtomicBoolean dùng làm mutex. Mục đích là ngăn hai poll cycle chạy cùng lúc
khi backend chậm hơn polling interval. Nếu cycle trước chưa xong thì cycle mới
bị skip hoàn toàn, không xếp hàng chờ.


### lastActiveIds

Lưu tập instanceId của lần poll trước. Mỗi cycle so sánh với tập hiện tại từ
Eureka. Nếu khác nhau (có instance mới join hoặc down) thì gọi cleanupStaleData()
để xóa data cũ tránh memory leak và tránh score cũ ảnh hưởng routing.


### trafficStates

Lưu trạng thái (count, totalTimeSec, lastLatency) của lần poll trước theo từng
instanceId. Cần thiết vì Micrometer chỉ expose cumulative counter, không expose
latency trực tiếp. Mỗi cycle tính delta giữa hai lần poll để ra latency trung
bình của window ~200ms vừa qua.

Khi không có request nào hoàn thành trong window (idle instance), giữ nguyên
latency lần trước thay vì trả về 0, tránh làm nhiễu EWMA.


### consecutiveFailures

Đếm số lần poll thất bại liên tiếp theo từng instanceId. Score penalty tăng dần
theo công thức min(10.0, failures x 2.5). Reset về 0 ngay khi poll thành công
trở lại. Cho phép hệ thống tự động tránh instance down mà không cần chờ Eureka
deregister.


### smoothedScores

EMA state của finalScore theo từng instanceId. Đây là tầng EMA thứ hai, khác
với EwmaSmoother dùng cho latency. Mục đích là kiểm soát tốc độ phản ứng khi
score thay đổi.

Dùng hệ số alpha bất đối xứng:
- delta > 0.30  alpha = 0.60  phản ứng rất nhanh khi instance đột ngột xấu
- delta > 0.00  alpha = 0.35  phản ứng vừa khi score tăng nhẹ
- delta <= 0.00  alpha = 0.25  phản ứng chậm khi instance đang hồi phục

Lý do bất đối xứng: phát hiện degradation nhanh để bảo vệ user, nhưng phục hồi
chậm để tránh flapping (không vội route lại traffic khi instance mới ổn định
được vài giây).


### registeredGauges

Set lưu instanceId đã đăng ký Prometheus Gauge. Gauge.builder().register() chỉ
được gọi một lần duy nhất cho mỗi instance. Nếu gọi lại sẽ gây
DuplicateMeterException vì Micrometer không cho phép đăng ký trùng meter name
và tag.


## Các metric được đẩy lên Prometheus

| Tên metric           | Ý nghĩa                                                  |
|----------------------|----------------------------------------------------------|
| alb.latency.ewma     | EWMA latency (ms) của instance                           |
|----------------------|----------------------------------------------------------|
| alb.queue.current    | Số request đang chờ xử lý                                |
|----------------------|----------------------------------------------------------|
| alb.final.score      | Score sau EMA, càng thấp càng tốt                        |
|----------------------|----------------------------------------------------------|
|                      | Score có cộng thêm penalty nếu instance nhận quá nhiều   |
| alb.routing.score    | traffic so với phần chia công bằng. Dùng để debug tại	  |
|                      | sao một instance ít được chọn dù score thấp.             |
|----------------------|----------------------------------------------------------|


## Mối quan hệ với các component khác

MetricsPoller phụ thuộc vào:
- DiscoveryClient: lấy danh sách instance từ Eureka
- WebClient: gọi HTTP đến /api/alb-metrics của từng instance
- ScoreCalculator: tính score từ metrics thô
- SlidingWindowManager: lưu HDR Histogram, cung cấp percentile cho ScoreCalculator
- InflightTracker: fallback khi instance chưa có gauge inflight
- MetricsCache: nơi lưu kết quả cuối cùng

MetricsPoller được đọc bởi:
- AdaptiveLoadBalancer: đọc score từ MetricsCache mỗi khi cần chọn instance
- DynamicWeightEngine: đọc raw metrics từ MetricsCache để tính lại MCDM weights


## Lưu ý khi debug

Nếu một instance liên tục bị tránh dù đang chạy bình thường, kiểm tra:
1. /api/alb-metrics của instance đó có trả về đúng không (cpu, count, totalTime, queue)
2. consecutiveFailures của instance đó có đang tích lũy không (log warn "Poll failed")
3. smoothedScores có đang giảm đủ chậm không do alpha=0.25 khi recover

Để reset toàn bộ state về ban đầu, gọi POST /actuator/alb/reset.
Sau reset, lần poll tiếp theo sẽ cold-start lại: calculateDeltaLatency dùng p50
histogram làm baseline, applyScoreEma khởi tạo lại từ rawScore đầu tiên.ầu tiên.