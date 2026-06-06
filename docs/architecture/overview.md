# Adaptive Load Balancer — Tổng quan hệ thống

## Giới thiệu

Dự án này xây dựng một API Gateway có khả năng cân bằng tải thích nghi (Adaptive Load Balancing) cho hệ thống vi dịch vụ. Thay vì phân phối traffic theo kiểu luân phiên cứng nhắc (Round-Robin) hoặc đơn thuần dựa vào số kết nối đang mở (Least Connections), hệ thống liên tục đo lường sức khỏe thực tế của từng instance backend qua ba tiêu chí: độ trễ (latency), độ dài hàng chờ (queue length), và mức sử dụng CPU. Từ đó tính ra một điểm tổng hợp để quyết định route từng request đến instance phù hợp nhất tại thời điểm đó.

Mục tiêu cốt lõi là chứng minh rằng khi một instance bị quá tải, một gateway thông minh có thể phát hiện và né tránh instance đó trong vài trăm millisecond, thay vì tiếp tục đổ traffic vào đó và làm tệ đi tình trạng hệ thống.

---

## Kiến trúc tổng quan

Hệ thống gồm bốn lớp chính:

```
Client
  |
  v
API Gateway (port 8080)        <-- Điểm vào duy nhất, thực hiện routing
  |
  +-- Eureka Server (port 8761) <-- Đăng ký và khám phá dịch vụ
  |
  +-- Registration Service (8081)  -- 2.0 CPU, 768 MB RAM  (instance mạnh)
  +-- Registration Service (8082)  -- 1.5 CPU, 512 MB RAM  (instance trung bình)
  +-- Registration Service (8083)  -- 1.0 CPU, 384 MB RAM  (instance yếu)
```

API Gateway không chỉ là proxy trung gian. Nó chạy một vòng lặp nền (polling loop) mỗi 200ms để thu thập metrics từ tất cả instance, tính điểm, rồi lưu kết quả vào cache. Khi có request đến, gateway chỉ cần đọc điểm từ cache và đưa ra quyết định routing ngay lập tức mà không phải tính toán lại.

---

## Các dịch vụ

### Eureka Server

Chịu trách nhiệm đăng ký và theo dõi vòng đời của các service instance. Mỗi instance của Registration Service tự đăng ký vào Eureka khi khởi động. API Gateway truy vấn Eureka để biết danh sách instance đang UP, từ đó quyết định cần poll và route đến đâu.

### Registration Service

Đây là backend được cân bằng tải. Ba instance được cấp phát tài nguyên khác nhau để mô phỏng môi trường thực tế nơi các server không đồng đều. Mỗi instance phơi ra hai nhóm endpoint:

**Endpoint xử lý nghiệp vụ** — nơi traffic thực được gửi đến:

| Endpoint | Mô tả |
|---|---|
| `GET /api/register` | Xử lý đăng ký đơn giản, có thể kích hoạt các kịch bản chaos |
| `POST /api/register-user` | Xử lý đăng ký nặng hơn, mô phỏng mã hóa mật khẩu và ghi database |
| `GET /api/simulate-call` | Mô phỏng giao tiếp liên dịch vụ với các pha CPU và I/O xen kẽ |

**Endpoint quản trị** — dùng để kiểm soát kịch bản chaos trong quá trình benchmark:

| Endpoint | Mô tả |
|---|---|
| `POST /api/chaos/cpu-spike/enable` | Bật các background thread đốt 100% CPU |
| `POST /api/chaos/async-io/enable` | Bật kịch bản I/O bottleneck, mỗi request chờ 600–1000ms |
| `POST /api/chaos/hidden/enable` | Bật kịch bản CPU bị chiếm ngầm, latency request vẫn gần bình thường |
| `POST /api/chaos/enable` | Bật kịch bản gốc: vừa đốt CPU vừa thêm delay |
| `POST /api/chaos/reset` | Tắt tất cả chaos, dừng các background thread |

**Endpoint metrics** — dùng cho API Gateway thu thập dữ liệu:

| Endpoint | Mô tả |
|---|---|
| `GET /api/alb-metrics` | Trả về `{ cpu, count, totalTime, queue }` — bốn chỉ số mà gateway cần để tính điểm |

### API Gateway

Là thành phần trung tâm của hệ thống. Gateway đảm nhận hai vai trò độc lập chạy song song:

**Control Plane** — vòng lặp nền, không liên quan đến từng request:
- Mỗi 200ms, MetricsPoller thu thập metrics từ tất cả instance
- ScoreCalculator tính điểm tổng hợp cho từng instance
- Kết quả được lưu vào MetricsCache

**Data Plane** — xử lý từng request:
- AdaptiveLoadBalancer đọc điểm từ MetricsCache
- Kết hợp với inflight count tức thời để chọn instance tốt nhất
- Route request đến instance đó qua Spring Cloud Gateway

---

## Thuật toán cân bằng tải thích nghi

Đây là phần kỹ thuật cốt lõi của dự án. Toàn bộ logic chia làm hai tầng: **tính điểm** (chạy nền mỗi 200ms) và **chọn instance** (chạy khi có request).

### Tầng 1 — Tính điểm (Control Plane)

#### MetricsPoller

Được lên lịch chạy mỗi 200ms. Dùng một AtomicBoolean làm mutex để đảm bảo chỉ có một vòng poll đang chạy tại bất kỳ thời điểm nào. Nếu vòng poll trước chưa xong thì bỏ qua vòng mới.

Với mỗi instance, MetricsPoller gọi `GET /api/alb-metrics` và nhận về bốn giá trị thô: `cpu`, `count` (tổng tích lũy số request), `totalTime` (tổng tích lũy thời gian xử lý), `queue`. Từ `count` và `totalTime`, nó tính delta giữa hai lần poll liên tiếp để suy ra latency trung bình của 200ms vừa qua.

Nếu poll thất bại, MetricsPoller tích lũy penalty score tăng dần (tối đa 10.0) thay vì xóa instance khỏi danh sách — điều này cho phép gateway tự tránh instance đang có vấn đề mà không cần chờ Eureka deregister.

#### ScoreCalculator

Nhận ba giá trị thô (latency, queue, cpu) và đi qua một pipeline bốn bước:

**Bước 1 — Làm mượt latency bằng AEWMA**

Latency đo được mỗi 200ms có thể dao động do nhiễu (GC pause, network jitter, một request bất thường). AEWMA (Adaptive Exponentially Weighted Moving Average) làm mượt nhiễu này trong khi vẫn phản ứng nhanh với suy giảm thực sự.

Điểm khác biệt với EWMA thông thường là hằng số thời gian tau (τ) tự điều chỉnh:

```
tau(t) = tauMin + (tauMax - tauMin) × e^(−k × deviation)

deviation = |latency_raw − latency_ewma| / latency_ewma
```

Khi latency đột biến mạnh (deviation cao), tau thu nhỏ về tauMin (200ms) để EWMA phản ứng nhanh. Khi ổn định (deviation thấp), tau giữ ở tauMax (2000ms) để lọc nhiễu tốt hơn.

**Bước 2 — Chuẩn hóa về [0, 1]**

Ba tiêu chí được đưa về cùng thang đo trước khi cộng lại:

- Latency: Min-Max scaling trên dải [P5, P95] toàn hệ thống. Instance nhanh như top 5% nhận điểm 0; chậm như bottom 5% nhận điểm 1.
- Queue: Log-scale với ngưỡng P99 của instance. Log-scale phù hợp vì phân phối queue thường lệch phải, nhạy với queue nhỏ (1–5 request) nhưng không bị sốc khi spike lớn.
- CPU: Clamp đơn giản vào [0, 1].

Dải chuẩn hóa [P5, P95] được lấy từ **global HDR Histogram** — histogram ghi nhận latency của toàn bộ hệ thống, không phân biệt instance. Điều này đảm bảo các instance được so sánh trên cùng một thước đo chung, không phải so với lịch sử riêng của chúng.

**Bước 3 — MCDM baseScore**

```
baseScore = alpha × normLatency + beta × normQueue + gamma × normCpu
```

Ba trọng số alpha, beta, gamma được cập nhật động mỗi 5 giây bởi **DynamicWeightEngine** theo phương pháp kết hợp AHP và EWM (Entropy Weight Method):

- AHP (Analytic Hierarchy Process): trọng số tĩnh do chuyên gia xác định, đóng vai trò neo tránh thuật toán đi quá xa. Mặc định: alpha=0.648, beta=0.230, gamma=0.122.
- EWM (Entropy Weight Method): tiêu chí nào có sự phân tán lớn giữa các instance (tức đang phân biệt rõ ai tốt ai xấu) thì được tăng trọng số.

Công thức pha trộn:
```
fusion = 0.80 × EWM_weight + 0.20 × AHP_weight
final  = EMA(fusion, alpha=0.08)  →  clamp vào [min, max]
```

Kết quả: khi CPU spike xảy ra và ba instance có CPU rất khác nhau, gamma tự động tăng (tối đa 0.35) để CPU đóng góp nhiều hơn vào việc phân biệt instance tốt/xấu.

**Bước 4 — PID penalty**

PID Controller tính một giá trị penalty riêng cho từng instance, cộng vào baseScore để ra finalScore. Penalty xuất hiện khi instance chậm hơn P75 toàn hệ thống và tích lũy theo thời gian nếu tình trạng kéo dài.

```
error = normLatency − normalizedP75

P = kp × error
I += error × dt  (tích lũy theo thời gian)
D = kd × d(latency)/dt  (tốc độ thay đổi, qua bộ lọc thông thấp)

u = P + I + D
penalty = lambda × tanh(kappa × max(0, u))
```

- Thành phần P phản ứng ngay khi instance bắt đầu chậm.
- Thành phần I tích lũy để phạt instance chậm mãn tính.
- Thành phần D phát hiện sớm xu hướng latency đang tăng.
- Hàm tanh ép penalty vào khoảng [0, 0.8], tránh penalty tăng vô hạn.

PID có hai cơ chế bảo vệ tránh windup: Conditional Anti-Windup (dừng tích lũy khi output đã bão hòa), và decay tự động khi instance đang hoạt động gần với setpoint.

Kết quả cuối: `finalScore = baseScore + penalty`

Khoảng thực tế trong điều kiện bình thường:
- Instance khỏe: 0.05–0.30
- Instance trung bình: 0.30–0.70
- Instance đang quá tải: 0.70–1.80

MetricsPoller nhận ScoreBreakdown từ ScoreCalculator và áp thêm một lớp EMA bất đối xứng lên finalScore trước khi lưu vào MetricsCache:
- Khi score tăng đột biến (>30%): alpha = 0.60 — phản ứng nhanh để bảo vệ user
- Khi score tăng nhẹ: alpha = 0.35
- Khi score giảm (instance hồi phục): alpha = 0.25 — phản ứng chậm để tránh flapping

#### SlidingWindowManager

Lưu trữ phân phối latency và queue length dưới dạng HDR Histogram (High Dynamic Range Histogram). HDR Histogram giữ lại hình dạng toàn bộ phân phối thay vì chỉ một giá trị trung bình, cho phép truy vấn chính xác các percentile (P5, P50, P75, P95, P99) mà không cần lưu từng request riêng lẻ.

Mỗi instance có một cặp histogram riêng theo kỹ thuật double-buffer: một histogram đang nhận sample mới, một histogram phục vụ đọc percentile. Khi histogram active đủ 100 sample thì flip sang cái còn lại và reset. Kỹ thuật này đảm bảo không bao giờ có window trống và không block các thao tác đọc trong quá trình flip.

Global histogram gộp latency của tất cả instance để cung cấp P5, P75, P95 làm thước đo chung.

### Tầng 2 — Chọn instance (Data Plane)

#### AdaptiveLoadBalancer

Được gọi mỗi khi có HTTP request cần routing. Đọc score từ MetricsCache và kết hợp với inflight count tức thời (số request đang được xử lý tại thời điểm đó) để tính routing score cho từng instance:

```
share[i] = 1 / sqrt(rawMcdm)             -- instance tốt hơn được chia phần traffic lớn hơn
expected_inflight[i] = totalInflight × share[i]

relPenalty = 0.010 × (inflight − min_inflight)
absPenalty = 0.60  × ((inflight / expected_inflight) − 1)^1.3   (chỉ khi vượt phần công bằng)

routingScore = rawMcdm + relPenalty + absPenalty
```

Instance có routingScore thấp nhất được chọn. Instance có inflight >= 200 bị loại hoàn toàn.

Hai loại penalty inflight bù đắp cho khoảng mù giữa hai lần poll: MCDM score phản ánh trạng thái 200ms vừa qua, nhưng inflight có thể thay đổi nhanh hơn thế khi traffic đột biến.

Hàm căn bậc hai trong công thức share làm mềm sự chênh lệch, tránh dồn toàn bộ traffic vào một instance duy nhất ngay cả khi nó tốt hơn hẳn các instance còn lại. Đồng thời, một floor được áp để instance yếu nhất vẫn nhận ít nhất 1/3 lượng traffic của instance tốt nhất — đảm bảo MetricsPoller luôn có data thực để đo.

Trong 5 giây đầu khi một instance mới xuất hiện (warmup period), MetricsCache chưa có đủ data đáng tin cậy, gateway dùng Round-Robin thay thế cho instance đó.

---

## Các thuật toán cân bằng tải khác

Ngoài Adaptive, hệ thống hỗ trợ ba thuật toán bổ sung dùng để so sánh trong benchmark:

| Thuật toán | Cấu hình (`alb.strategy`) | Mô tả |
|---|---|---|
| Adaptive | `adaptive` (mặc định) | MCDM + PID + inflight penalty |
| Round-Robin | `round-robin` | Luân phiên đều, không quan tâm tải |
| Random | `random` | Chọn ngẫu nhiên |
| Least Connections | `least-connections` | Chọn instance có ít inflight nhất |

Thuật toán được chọn qua biến cấu hình trong `application.yml` mà không cần thay đổi code.

---

## Kịch bản Chaos Testing

Bốn kịch bản được tích hợp vào Registration Service để kiểm tra khả năng phản ứng của từng thuật toán:

**CPU Spike** — Hai background thread chạy liên tục, đốt hết CPU của container (1.0 CPU đối với instance 8083). Mỗi HTTP request cũng tự đốt thêm CPU. Kết quả: latency tăng do CPU contention, `process.cpu.usage` tiệm cận 100%.

**Async I/O Bottleneck** — Mỗi request sleep 600–1000ms để mô phỏng connection pool cạn kiệt hoặc chờ database lock. CPU không tăng nhưng latency tăng vọt và inflight count tích lũy.

**Hidden Degradation** — Background thread đốt CPU ngầm (80.000 iterations rồi sleep 4ms, lặp lại). Mỗi HTTP request chỉ làm một lượng tính toán rất nhỏ nên hoàn thành trong 20–50ms — gần như bình thường. Latency không tăng rõ ràng nhưng CPU đang ở 90%+. Đây là kịch bản để kiểm tra liệu thuật toán có đủ nhạy với tín hiệu CPU khi tín hiệu latency chưa xuất hiện.

**Original Chaos** — Kết hợp cả hai: mỗi request vừa đốt CPU vừa sleep thêm 100–300ms. Tạo ra cả CPU spike lẫn latency tăng đồng thời.

---

## Hạ tầng và triển khai

### Docker Compose

Tất cả dịch vụ chạy trong cùng một Docker network (`alb-network`). Các instance được giới hạn tài nguyên khác nhau:

| Container | CPU | RAM |
|---|---|---|
| eureka-server | 0.5 | 256 MB |
| registration-8081 | 2.0 | 768 MB |
| registration-8082 | 1.5 | 512 MB |
| registration-8083 | 1.0 | 384 MB |
| api-gateway-alb | 2.0 | 1 GB |

Eureka Server có health check tích hợp. Các dịch vụ khác chỉ khởi động sau khi Eureka báo UP, đảm bảo thứ tự khởi động đúng.

### Build

Tất cả module được build bằng multi-stage Docker: stage đầu dùng Maven để build toàn bộ project (tận dụng Maven Reactor để chỉ build một lần), stage hai chỉ copy file `.jar` kết quả vào JRE image tối giản (eclipse-temurin:21-jre-alpine).

### CI/CD

GitHub Actions chạy trên self-hosted runner. Mỗi lần push lên nhánh `main`, pipeline tự động chạy `docker compose build` rồi `docker compose up -d`. Nếu có nhiều commit liên tiếp, job cũ bị hủy ngay (`cancel-in-progress: true`) để tránh deployment chồng chéo.

---

## Monitoring

Mỗi instance Registration Service và API Gateway đều phơi ra Prometheus endpoint tại `/actuator/prometheus`. Các metrics quan trọng do hệ thống tự đẩy:

| Metric | Mô tả |
|---|---|
| `alb.latency.ewma{backend}` | EWMA latency (ms) của từng instance |
| `alb.queue.current{backend}` | Số request đang xử lý |
| `alb.final.score{backend}` | Score sau EMA smoothing (càng thấp càng tốt) |
| `alb.routing.score{backend}` | Score đã tính thêm inflight penalty, phản ánh xác suất được chọn |
| `alb.routing.selected{backend, port}` | Counter số lần mỗi instance được chọn |
| `alb.mcdm.weight{criterion}` | Trọng số alpha/beta/gamma hiện tại của MCDM |
| `http.server.requests.inflight` | Số request đang xử lý song song (trên backend) |

---

## Admin API

API Gateway cung cấp một endpoint quản trị để reset toàn bộ state trước mỗi lần benchmark:

```
POST /actuator/alb/reset
```

Lệnh này xóa đồng thời: EWMA state, PID integral, HDR Histogram, MetricsCache, EMA score, inflight counter, và warmup timer của AdaptiveLoadBalancer. Sau reset, tất cả instance vào lại giai đoạn warmup 5 giây và bắt đầu tích lũy data từ đầu — đảm bảo kết quả benchmark không bị nhiễm bởi dữ liệu của lần chạy trước.

---

## Luồng xử lý một request — tổng hợp

```
1. Client gửi request đến API Gateway (port 8080)

2. Spring Cloud Gateway nhận request, gọi AdaptiveLoadBalancer.choose()

3. AdaptiveLoadBalancer:
   a. Lấy danh sách instance UP từ Eureka
   b. Đọc finalScore của từng instance từ MetricsCache
   c. Đọc inflight count từ InflightTracker
   d. Tính routingScore = finalScore + relPenalty + absPenalty
   e. Chọn instance có routingScore thấp nhất

4. InflightLifecycle tăng inflight counter cho instance được chọn

5. Gateway forward request đến instance đó

6. Khi response trở về, InflightLifecycle giảm inflight counter

Song song (mỗi 200ms):

7. MetricsPoller gọi /api/alb-metrics trên tất cả instance
8. ScoreCalculator: EWMA smooth → normalize → MCDM → PID
9. MetricsPoller áp EMA bất đối xứng lên finalScore
10. Kết quả được lưu vào MetricsCache để AdaptiveLoadBalancer đọc ở lần tiếp theo
```