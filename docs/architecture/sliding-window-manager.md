# SlidingWindowManager — Luồng hoạt động

## Mục đích

SlidingWindowManager lưu trữ phân phối latency và queue length của từng instance backend dưới dạng HDR Histogram (High Dynamic Range Histogram). Từ đó cung cấp các giá trị percentile (p5, p50, p95, p99) để các component khác dùng làm ngưỡng tính toán và chuẩn hóa.

Không giống moving-average chỉ lưu một giá trị trung bình, HDR Histogram giữ lại hình dạng toàn bộ phân phối, cho phép trả lời câu hỏi như "90% request hoàn thành trong bao nhiêu ms?" mà không cần lưu từng request riêng lẻ.

Trong hệ thống này, SlidingWindowManager phục vụ hai mục đích song song:

- Per-instance: cung cấp p50 làm giá trị fallback khi instance chưa có latency, cung cấp p95 làm ngưỡng trên để chuẩn hóa về [0,1].
- System-wide: cung cấp p5 và p95 tổng hợp của toàn bộ instance để ScoreCalculator dùng làm thước đo chung khi so sánh các instance với nhau.

---

## Các thành phần cốt lõi

### Hằng số cấu hình HDR Histogram

| Hằng số | Giá trị | Ý nghĩa |
|---|---|---|
| MAX_LATENCY_MS | 60000 | Giới hạn trên của histogram latency (60 giây). Giá trị vượt quá phải bị clamp trước khi ghi. |
| MAX_QUEUE_SIZE | 10000 | Giới hạn trên của histogram queue length. |
| SIGNIFICANT_DIGITS | 2 | Độ chính xác của histogram: sai số tối đa khoảng 1% so với giá trị thực. Tăng lên 3 thì chính xác hơn nhưng tốn RAM gấp khoảng 10 lần. |
| WINDOW_SIZE | 100 | Số sample tối đa trong một cửa sổ trượt của từng instance. Sau 100 sample thì histogram bị flip sang cái còn lại và reset. Với polling 200ms, tương đương khoảng 20 giây dữ liệu. |
| GLOBAL_WIN_SIZE | 160 | Số sample tối đa trong cửa sổ toàn hệ thống. Lớn hơn WINDOW_SIZE vì global nhận traffic từ tất cả instance cộng lại. |

### InstanceState

Record lưu trạng thái histogram riêng của một instance. Mỗi instance có bốn histogram: hai cái cho latency và hai cái cho queue, theo cấu trúc double-buffer.
	InstanceState
	latHists[0], latHists[1]  — cặp histogram latency, dùng luân phiên
	latIdx                    — trỏ vào histogram latency đang active (0 hoặc 1)
	qHists[0], qHists[1]      — cặp histogram queue length, dùng luân phiên
	qIdx                      — trỏ vào histogram queue đang active (0 hoặc 1)
### GlobalPair

Hai histogram dùng chung cho toàn bộ hệ thống, lưu latency của tất cả instance gộp lại. Không phân biệt request đến từ instance nào. Dùng để tính system-wide percentile làm thước đo chung khi chuẩn hóa.

Thao tác trên globalPair luôn đặt trong synchronized block để đảm bảo flip index và reset histogram xảy ra cùng một lúc, không có thread nào đọc vào khoảng giữa.

### Double-buffer (Ping-Pong) Pattern

Đây là kỹ thuật trung tâm của class. Thay vì dùng một histogram duy nhất rồi xóa data cũ, hệ thống luôn giữ hai histogram và luân phiên vai trò.

Lúc bình thường, histogram active đang nhận sample mới. Histogram còn lại đang phục vụ các lệnh đọc percentile. Khi histogram active vượt ngưỡng sample:
Bước 1: Xác định index kia = 1 - active
Bước 2: Reset histogram kia (xóa hết data cũ)
Bước 3: Flip index active sang cái vừa reset
Sau flip, histogram vừa reset trở thành active mới và bắt đầu nhận sample. Histogram cũ tiếp tục phục vụ đọc cho đến khi active mới tích lũy đủ data.

Lợi ích so với single histogram:
- Không có thời điểm window trống hoàn toàn, luôn có data để đọc percentile.
- Không phải xóa từng phần tử cũ, chi phí flip là hằng số O(1).
- Thread đọc percentile không bị block trong quá trình flip.

---

## Luồng chạy chính
	MetricsPoller.processMetrics()
	|
	|-- addMetrics(instanceId, latency, queue)
	|         |
	|         |-- clamp latency vào [1, MAX_LATENCY_MS]
	|         |-- clamp queue vào [1, MAX_QUEUE_SIZE]
	|         |
	|         |-- computeIfAbsent: tạo InstanceState nếu chưa có
	|         |
	|         |-- ghi vào latHists[latIdx] của instance
	|         |-- nếu count > WINDOW_SIZE: flip latIdx, reset histogram kia
	|         |
	|         |-- ghi vào qHists[qIdx] của instance
	|         |-- nếu count > WINDOW_SIZE: flip qIdx, reset histogram kia
	|         |
	|         |-- synchronized(globalLock):
	|               ghi vào globalPair[globalActiveIdx]
	|               nếu count > GLOBAL_WIN_SIZE: reset kia, flip globalActiveIdx
	|
	v
	ScoreCalculator.calculateScore()
	|
	|-- getSnapshot(instanceId)
	|         |
	|         |-- đọc latHists[latIdx] → p5, p50, p95
	|         |-- đọc qHists[qIdx]     → qP99
	|         |-- nếu không có data    → trả về fallback (0, 50, 100, 10)
	|
	|-- getSystemSnapshot()
	|
	|-- synchronized(globalLock)
	|-- getSafeGlobalHistogram()
	|         |
	|         |-- active có >= 20 sample? → dùng active
	|         |-- không: standby có data?  → dùng standby
	|         |-- không: trả về active (caller xử lý empty)
	|
	|-- nếu không có data → trả về fallback (5, 50, 200)
	|-- có data           → trả về p5, p75, p95
---

## Chi tiết từng bước

### Bước 1 — Ghi metric vào histogram

`addMetrics()` được gọi bởi MetricsPoller ngay sau khi tính xong latency từ delta counter. Đây là điểm nhận dữ liệu duy nhất của class.

Việc đầu tiên là clamp cả hai giá trị về khoảng hợp lệ của HDR Histogram. HDR Histogram yêu cầu giá trị từ 1 trở lên (không nhận 0) và không vượt quá upperBound khai báo lúc khởi tạo, nếu vi phạm sẽ throw exception.

Tiếp theo gọi `computeIfAbsent()` để lấy InstanceState, tạo mới nếu instance xuất hiện lần đầu. Mỗi InstanceState được cấp bốn histogram ngay lúc tạo, không tạo thêm sau đó.

Sau khi ghi sample vào histogram active, kiểm tra xem đã vượt WINDOW_SIZE chưa. Nếu vượt, thực hiện flip bằng CAS (compareAndSet). CAS đảm bảo chỉ một thread flip thành công dù nhiều thread cùng nhận diện ra ngưỡng. Thread thua CAS bỏ qua và tiếp tục.

### Bước 2 — Đọc percentile của instance

`getSnapshot()` được gọi bởi ScoreCalculator để lấy:
- p50: dùng làm fallback latency cho EwmaSmoother khi instance chưa có latency đo được.
- p95: dùng trong trường hợp system-wide snapshot không có data, ScoreCalculator dùng p95 của instance làm thay thế.
- qP99: dùng làm ngưỡng max queue trong NormalizationFunctions.normalizeQueue().

Nếu instance chưa có data (null state hoặc histogram rỗng sau flip), trả về bộ giá trị fallback. Các giá trị fallback được chọn để ScoreCalculator có thể hoạt động được trong giai đoạn warm-up mà không gây lỗi chia cho 0.

### Bước 3 — Đọc percentile toàn hệ thống

`getSystemSnapshot()` trả về p5, p75, p95 tổng hợp của toàn bộ traffic. Trước khi đọc, gọi `getSafeGlobalHistogram()` để chọn histogram an toàn nhất:

- Nếu histogram active có từ 20 sample trở lên: dùng active. 20 là ngưỡng tối thiểu thực nghiệm để percentile HDR Histogram có ý nghĩa thống kê ở SIGNIFICANT_DIGITS=2.
- Nếu active chưa đủ sample (vừa flip, đang tích lũy): dùng standby nếu còn data. Đây là lúc double-buffer phát huy tác dụng, tránh trả về percentile từ histogram quá ít data.
- Nếu cả hai đều không có data: trả về active, caller kiểm tra count để xử lý tiếp.

p75 từ SystemSnapshot được dùng làm setpoint cho PID controller trong ScoreCalculator. Khi một instance vượt p75 hệ thống, PID tích lũy penalty làm tăng score của instance đó.

### Bước 4 — Reset

`resetAll()` xóa toàn bộ per-instance state và đặt lại cả hai histogram global về 0 sample. Được gọi bởi AdminController khi nhận POST `/actuator/alb/reset`. Sau reset, lần `addMetrics()` tiếp theo sẽ `computeIfAbsent()` tạo lại InstanceState từ đầu. Lần `getSnapshot()` ngay sau reset sẽ trả về fallback vì chưa có data.

---

## Mối quan hệ với các component khác

| Component | Chiều | Mô tả |
|---|---|---|
| MetricsPoller | Gọi vào SWM | Gọi `addMetrics()` mỗi 200ms sau khi tính delta latency từ Micrometer counter. |
| ScoreCalculator | Gọi vào SWM | Gọi `getSnapshot()` để lấy p50 (EWMA fallback) và p95 (ngưỡng normalization) của từng instance. Gọi `getSystemSnapshot()` để lấy p5/p75/p95 toàn hệ thống. |
| AdminController | Gọi vào SWM | Gọi `resetAll()` khi POST `/actuator/alb/reset` để xóa toàn bộ data trước khi benchmark mới. |
| EwmaSmoother | Dùng kết quả gián tiếp | ScoreCalculator lấy p50 từ `getSnapshot()` rồi truyền vào EwmaSmoother làm fallback value khi instance idle. |
| PIDController | Dùng kết quả gián tiếp | ScoreCalculator lấy p75 từ `getSystemSnapshot()` rồi dùng làm setpoint khi tính PID penalty. |

---

## Tại sao dùng HDR Histogram thay vì moving-average

Moving-average (bao gồm EWMA) chỉ theo dõi một giá trị đại diện. Khi latency phân phối lệch (right-skewed) — điều rất phổ biến trong microservice khi có I/O wait hoặc GC pause — giá trị trung bình không phản ánh được đuôi phân phối.

Ví dụ: 95% request hoàn thành trong 50ms, 5% request bị GC pause và mất 2000ms. EWMA sẽ cho ra khoảng 150ms, không phản ánh đúng rằng hầu hết user thực ra trải nghiệm 50ms. HDR Histogram giữ lại cả hai thông tin: p50=50ms và p95=2000ms.

Hệ thống này dùng EWMA cho latency trực tiếp (để phản ứng nhanh với thay đổi) nhưng dùng HDR Histogram để lấy ngưỡng chuẩn hóa. Hai kỹ thuật bổ sung cho nhau, không thay thế nhau.

---

## Lưu ý khi debug

Nếu ScoreCalculator liên tục dùng fallback value (p5=5ms, p95=200ms cố định) thay vì data thực:

1. Kiểm tra `getSystemSnapshot()` có trả về default không bằng cách xem log ScoreCalculator. Nếu `sysP95 <= sysP5` hoặc `sysP5 < 1.0` thì ScoreCalculator đang dùng fallback, nghĩa là global histogram chưa đủ data.
2. Kiểm tra MetricsPoller có đang gọi `addMetrics()` không. Nếu poll thất bại liên tục thì SlidingWindowManager không nhận được data để ghi.
3. Sau `POST /actuator/alb/reset`, cần chờ ít nhất vài giây để histogram tích lũy đủ 20 sample trở lên trước khi `getSafeGlobalHistogram()` chuyển sang dùng histogram active.

Nếu một instance luôn có p95 rất cao dù đang hoạt động bình thường, kiểm tra xem chaos mode có đang bật trên instance đó không. Latency cao từ chaos sẽ bị ghi vào histogram và kéo p95 lên, ảnh hưởng đến score kể cả sau khi tắt chaos, cho đến khi histogram tự flip và xóa data cũ sau WINDOW_SIZE sample tiếp theo.