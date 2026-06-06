# ScoreCalculator — Luồng hoạt động

## Mục đích

ScoreCalculator là component trung tâm chịu trách nhiệm tính điểm tổng hợp
(finalScore) cho từng instance backend. Điểm này phản ánh "sức khỏe" hiện tại
của một instance theo ba tiêu chí: latency, queue length và CPU usage.

Điểm càng thấp nghĩa là instance càng tốt. AdaptiveLoadBalancer dựa vào điểm
này để quyết định route request đến instance nào.

ScoreCalculator không chạy theo lịch. Nó được gọi bởi MetricsPoller mỗi 200ms,
một lần cho mỗi instance đang hoạt động. Kết quả được lưu vào MetricsCache để
AdaptiveLoadBalancer đọc khi có request đến.

---

## Vị trí trong hệ thống

MetricsPoller là bên gọi ScoreCalculator. Trước khi gọi, MetricsPoller đã có:
- Raw latency (tính từ delta counter của Micrometer)
- Queue length (số request đang xử lý)
- CPU usage (process.cpu.usage từ Micrometer)

ScoreCalculator nhận ba giá trị thô này và đi qua một pipeline gồm bốn tầng:\

	raw metrics\
	|\
	|-- EWMA smoothing (EwmaSmoother)\
	|-- Normalization (NormalizationFunctions)\
	|-- MCDM base score (DynamicWeightEngine)\
	|-- PID penalty (PIDController)\
	|\
	finalScore → MetricsCache → AdaptiveLoadBalancer\
---

## Hằng số

### SCORE_NULL_INSTANCE = 20.0

Điểm trả về khi InstanceMetrics là null, tức là poll metrics thất bại hoàn toàn
và ScoreCalculator không có dữ liệu nào để tính. Giá trị 20.0 cao hơn nhiều
so với điểm bình thường trong khoảng 0.05 đến 1.8. Điều này đảm bảo
AdaptiveLoadBalancer gần như không bao giờ chọn instance không có dữ liệu.

### SYSTEM_P5_FALLBACK = 30.0 (ms)

Giá trị fallback cho P5 toàn hệ thống khi HDR Histogram chưa có đủ data.
Tình huống này xảy ra ngay sau khi hệ thống khởi động hoặc sau khi gọi
POST /actuator/alb/reset. 30ms phản ánh latency tối thiểu thực tế của một
Java microservice có kèm I/O delay.

### SYSTEM_P95_FALLBACK = 300.0 (ms)

Giá trị fallback cho P95 toàn hệ thống, dùng cùng với P5_FALLBACK để tạo
dải chuẩn hóa [30ms, 300ms] khi histogram chưa đủ data. Instance có latency
300ms sẽ nhận normLatency = 1.0 (tệ nhất có thể).

---

## Luồng chạy chính
	calculateScore(instanceId, current)
	|
	|-- [current == null] ----------------> trả về score = 20.0, bỏ qua toàn bộ pipeline
	|
	|-- getSnapshot(instanceId)
	|       |
	|       |-- p50: fallback EWMA khi instance idle
	|       |-- qP99: ngưỡng chuẩn hóa queue
	|
	|-- lRaw = current.latency > 0 ? latency : p50
	|
	|-- ewmaSmoother.smooth(lRaw, tauMin, tauMax, k, p50)
	|       |
	|       |-- ewmaLat: latency đã qua Adaptive EWMA
	|
	|-- getSystemSnapshot()
	|       |
	|       |-- sysP5, sysP75, sysP95 (toàn hệ thống)
	|       |-- kiểm tra tính hợp lệ → fallback nếu cần
	|
	|-- invRange = 1.0 / (sysP95 - sysP5)
	|
	|-- normalizeLatency(ewmaLat, sysP5, invRange)  → nL ∈ [0,1]
	|-- normalizeQueue(queueLength, qP99)            → nQ ∈ [0,1]
	|-- normalizeCpu(cpu)                            → nC ∈ [0,1]
	|
	|-- weightEngine.computeBaseScore(nL, nQ, nC)
	|       |
	|       |-- baseScore = alphanL + betanQ + gamma*nC
	|
	|-- normalizeLatency(sysP75, sysP5, invRange)   → normalizedP75
	|
	|-- pidController.calculatePenalty(nL, normalizedP75, pidConfig)
	|       |
	|       |-- penalty ∈ [0, lambda=0.8]
	|
	|-- finalScore = baseScore + penalty
	|
	return ScoreBreakdown(ewmaLat, nL, nQ, nC, baseScore, penalty, finalScore)
---

## Chi tiết từng bước

### Bước 0 — Kiểm tra null

Nếu InstanceMetrics là null, ScoreCalculator trả về ngay một ScoreBreakdown với
tất cả giá trị normalized bằng 1.0 và finalScore = 20.0. Bước này không tính
PID penalty vì không có dữ liệu latency nào để so sánh.

Trong thực tế, MetricsPoller đã có cơ chế penalty riêng cho trường hợp poll
thất bại. Nhánh null này là lớp bảo vệ cuối cùng nếu ScoreCalculator được
gọi từ luồng khác.

### Bước 1 — Lấy per-instance percentile snapshot

ScoreCalculator lấy snapshot từ HDR Histogram riêng của instance đó. Snapshot
trả về bốn giá trị:

- p5: không dùng trực tiếp trong ScoreCalculator nhưng có trong PercentileSnapshot.
- p50: dùng làm giá trị khởi tạo EWMA và fallback khi instance không có request.
- p95: không dùng trực tiếp ở đây; system snapshot đảm nhiệm vai trò chuẩn hóa.
- qP99: dùng làm ngưỡng trên khi chuẩn hóa queue length.

### Bước 2 — Xác định lRaw

Raw latency từ MetricsPoller (current.getLatency()) là giá trị trung bình của
các request hoàn thành trong window 200ms vừa qua. Nếu window không có request
nào hoàn thành (instance đang idle), giá trị này bằng 0. Trong trường hợp đó,
ScoreCalculator dùng p50 làm thay thế để EWMA không bị kéo xuống 0 một cách
giả tạo.

### Bước 3 — Adaptive EWMA smoothing

EwmaSmoother nhận lRaw và trả về ewmaLat — latency đã được làm mượt. Mục đích là
lọc bỏ noise ngắn hạn trong khi vẫn phản ứng nhanh với thay đổi thực sự.

Điểm khác biệt so với EWMA thông thường là tham số tau (thời hằng làm mượt) được
điều chỉnh tự động theo mức độ lệch giữa lRaw và ewmaLat hiện tại:

- Deviation lớn (latency đột ngột thay đổi nhiều): tau giảm về tauMin (200ms),
  EWMA phản ứng nhanh để bắt kịp thay đổi.
- Deviation nhỏ (latency ổn định): tau tăng về tauMax (2000ms), EWMA lọc nhiễu
  mạnh hơn.

ewmaLat là giá trị được dùng cho tất cả các bước còn lại, không phải lRaw.

### Bước 4 — Lấy system-wide snapshot

ScoreCalculator lấy snapshot tổng hợp latency của toàn bộ hệ thống từ global
HDR Histogram trong SlidingWindowManager. Snapshot này chứa sysP5, sysP75
và sysP95.

Sau khi lấy về, ScoreCalculator kiểm tra tính hợp lệ:

- Nếu sysP95 nhỏ hơn hoặc bằng sysP5: histogram bị đảo, không thể dùng.
- Nếu sysP5 nhỏ hơn 1.0: histogram chưa có đủ data.

Trong cả hai trường hợp, sysP5 và sysP95 được thay bằng SYSTEM_P5_FALLBACK
và SYSTEM_P95_FALLBACK.

Lý do dùng system-wide snapshot thay vì per-instance snapshot cho bước chuẩn
hóa: để so sánh các instance với nhau trên cùng một thước đo chung. Nếu mỗi
instance tự chuẩn hóa theo lịch sử của chính mình, một instance vốn chậm
sẽ luôn nhận normLatency tốt khi so với chính nó, dù thực ra đang chậm hơn
các instance khác.

### Bước 5 — Tính invRange

invRange = 1.0 / (sysP95 - sysP5)

Đây là giá trị được tính trước một lần để dùng cho cả hai lần gọi
normalizeLatency (cho ewmaLat và cho sysP75). Nhân với invRange nhanh hơn chia
mỗi lần khoảng 5 CPU cycle, không đáng kể trong một lần tính nhưng cộng lại
qua hàng nghìn lần gọi mỗi giây thì có ý nghĩa.

### Bước 6 — Chuẩn hóa ba tiêu chí

Ba giá trị normalized nL, nQ, nC đều nằm trong khoảng [0, 1].
Giá trị 0 nghĩa là tốt nhất, giá trị 1 nghĩa là tệ nhất.

**nL (normalized Latency):** Áp dụng Min-Max scaling trên dải [sysP5, sysP95].
	`nL = clamp((ewmaLat - sysP5) / (sysP95 - sysP5), 0, 1)`
Instance có ewmaLat bằng sysP5 (nhanh như top 5% toàn hệ thống) sẽ nhận nL = 0.
Instance có ewmaLat bằng sysP95 (chậm như bottom 5% toàn hệ thống) sẽ nhận nL = 1.

**nQ (normalized Queue):** Áp dụng log-scale với ngưỡng qP99 của chính instance.
Log-scale phù hợp vì phân phối queue thường lệch phải (right-skewed): hầu hết
thời gian queue thấp, chỉ spike khi overload. Log-scale nhạy với queue nhỏ
(1 đến 5 request) nhưng không bị sốc khi queue tăng rất cao đột ngột.

**nC (normalized CPU):** Đơn giản là clamp cpu vào [0, 1]. process.cpu.usage
từ Micrometer đã ở dạng tỉ lệ nhưng đôi khi JVM burst trả về giá trị hơi lớn
hơn 1.0. Nếu giá trị là NaN hoặc âm (chưa đo được), fallback về 0.5.

### Bước 7 — Tính MCDM baseScore

baseScore = alpha * nL + beta * nQ + gamma * nC

Ba trọng số alpha, beta, gamma được DynamicWeightEngine cập nhật mỗi 5 giây
dựa trên Entropy Weight Method kết hợp với AHP. Giá trị mặc định:

| Tiêu chí | Trọng số mặc định |
|----------|-------------------|
| alpha (latency) | 0.648 |
| beta  (queue)   | 0.230 |
| gamma (cpu)     | 0.122 |

Khi CPU bão tải và có sự chênh lệch lớn giữa các instance, gamma sẽ tăng lên
(tối đa 0.35) và alpha giảm xuống (tối thiểu 0.15).

baseScore nằm trong khoảng [0, 1].

### Bước 8 — Tính PID penalty

Trước khi gọi PIDController, ScoreCalculator chuẩn hóa sysP75 về cùng thang đo
với nL để PID có thể so sánh apples-to-apples: