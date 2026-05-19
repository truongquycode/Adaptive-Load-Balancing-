# THIẾT KẾ CƠ CHẾ CÂN BẰNG TẢI THÍCH NGHI CHO MICROSERVICES DỰA TRÊN ĐỘ TRỄ THỜI GIAN THỰC TRONG HỆ THỐNG SPRING BOOT


[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://java.com/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.4-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring_Cloud-2023.0.1-6DB33F?style=for-the-badge&logo=spring&logoColor=white)](https://spring.io/projects/spring-cloud)
[![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)](https://prometheus.io/)
[![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white)](https://grafana.com/)
[![Apache JMeter](https://img.shields.io/badge/Apache_JMeter-D22128?style=for-the-badge&logo=apachejmeter&logoColor=white)](https://jmeter.apache.org/)
[![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=github-actions&logoColor=white)](https://github.com/features/actions)

Một hệ thống API Gateway tích hợp cơ chế Adaptive Load Balancer tiên tiến dành cho kiến trúc Microservices. Dự án không chỉ dừng lại ở các thuật toán định tuyến cơ bản mà còn áp dụng thu thập số liệu thời gian thực thông qua HTTP REST, phân tích đa tiêu chí (MCDM), bộ điều khiển PID và làm mịn chuỗi thời gian (EWMA) để tối ưu hóa lưu lượng mạng và tránh quá tải hệ thống.

---

## Kiến trúc Hệ thống

Hệ thống được chia thành 3 thành phần chính:

1. **Eureka Server (`eureka-server`):** Đóng vai trò Service Registry, quản lý việc đăng ký và khám phá dịch vụ của toàn bộ các node trong cụm.
2. **API Gateway (`api-gateway-alb`):** Trái tim của hệ thống. Nơi nhận mọi request từ client và thực thi logic định tuyến. Nó liên tục thăm dò (poll) trạng thái của các backend để ra quyết định điều hướng thông minh nhất.
3. **Registration Service (`registration-service-alb`):** Các backend instance dùng để xử lý request thực tế. Đặc biệt, các backend này được tích hợp sẵn một `ChaosController` cho phép kích hoạt các lỗi hoặc độ trễ giả lập (chaos engineering) để kiểm thử độ nhạy bén của Gateway.

---

## Kiến trúc Nội bộ của Gateway

Bên trong API Gateway, hệ thống được tổ chức theo nguyên tắc phân chia trách nhiệm rõ ràng giữa hai tầng:

### Control Plane — Tầng Quan sát và Tính toán
* **`MetricsPoller`**: Thu thập metrics từ backend theo chu kỳ 1 giây, quản lý Circuit Breaker.
* **`SlidingWindowManager`**: Duy trì histogram HDR theo cơ chế rotating pair, tính percentile động.
* **`DynamicWeightEngine`**: Cập nhật trọng số MCDM theo entropy thông tin mỗi 5 giây.
* **`ScoreCalculator`**: Tổng hợp điểm số từ EWMA, normalization, MCDM và PID penalty.
* **`InstanceCircuitBreaker`**: Theo dõi trạng thái kết nối của từng instance.

### Data Plane — Tầng Định tuyến Thời gian Thực
* **`AdaptiveLoadBalancer`**: Thực thi thuật toán P2C, đọc điểm từ cache O(1).
* **`InflightTracker`**: Đếm số request đang xử lý tại mỗi instance, cập nhật tức thì.
* **`InflightLifecycle`**: Hook vào vòng đời request của Spring Cloud LoadBalancer để tăng/giảm counter.

---

## LUỒNG HOẠT ĐỘNG CHI TIẾT CỦA HỆ THỐNG

### Luồng Khởi động và Đồng bộ Trạng thái Ban đầu
Quy trình vận hành khởi đầu của hệ thống cân bằng tải thích nghi (ALB) đòi hỏi sự phối hợp chặt chẽ giữa tầng khám phá dịch vụ, tầng định tuyến và tầng thực thi nghiệp vụ để đảm bảo dữ liệu cấu trúc topo mạng luôn ở trạng thái nhất quán tuyệt đối trước khi tiếp nhận lưu lượng tải:
* **Bước 1 — Khởi tạo Service Registry:** Thành phần `eureka-server` được kích hoạt đầu tiên trên cổng mặc định `8761`, thiết lập vùng không gian lưu trữ standalone để quản lý bảng trạng thái topo mạng của toàn hệ thống vi dịch vụ.
* **Bước 2 — Đăng ký Dịch vụ Backend:** Ba instance của dịch vụ nghiệp vụ (`registration-service-alb`) khởi động song song. Ngay sau khi Spring Context nạp thành công, các Client này phát tín hiệu REST đăng ký trạng thái `UP` lên Eureka Server, đồng thời thiết lập chu kỳ gửi tín hiệu "nhịp tim" (Heartbeat) định kỳ mỗi 10 giây để duy trì trạng thái sống.
* **Bước 3 — Khởi tạo Metric tại Gateway:** Phương thức `@PostConstruct` bên trong `DynamicWeightEngine` sử dụng cơ chế liên kết dữ liệu động của Micrometer để đăng ký các biến trọng số MCDM (α, β, γ) lên `MeterRegistry` phục vụ cho việc giám sát thời gian thực của Prometheus.
* **Bước 4 — Kéo Topo Mạng và Khởi động Polling:** Gateway thực hiện lệnh gọi HTTP Fetch Registry về Eureka Server để đồng bộ danh sách máy chủ backend cục bộ. Chu kỳ làm mới danh sách này được cấu hình ép cứng về mức 5 giây (`registry-fetch-interval-seconds: 5s`), đồng bộ với thời gian sống của LoadBalancer Cache (TTL: 5s). Ngay khi danh sách máy chủ được nạp, mạch vòng định kỳ của `MetricsPoller` chính thức bước vào trạng thái kích hoạt.

### Luồng Control Plane và Vòng đời Thu thập Metrics (Chu kỳ 1 giây)
Tầng Control Plane hoạt động độc lập dưới dạng một tiến trình chạy ngầm bất đồng bộ. Mỗi giây, `MetricsPoller` thực hiện nghiêm ngặt kịch bản điều khiển 6 bước:
* **Bước 1 — Kiểm tra Overlap:** Sử dụng cờ hiệu nguyên tử `AtomicBoolean isPolling` để đảm bảo chỉ một chu kỳ poll chạy tại một thời điểm. Nếu chu kỳ trước bị treo do nghẽn mạng và chưa hoàn thành, chu kỳ hiện tại sẽ chủ động bị bỏ qua (skip cycle) để bảo vệ tài nguyên luồng của Gateway.
* **Bước 2 — Dọn dẹp dữ liệu:** Lấy danh sách instance từ Eureka và thực thi dọn dẹp các bản ghi dữ liệu (metrics thô, điểm số, trạng thái giao thông) của các instance đã offline khỏi bộ đệm, ngăn ngừa rò rỉ bộ nhớ.
* **Bước 3 — Thẩm định Circuit Breaker:** Kiểm tra trạng thái mạch ngắt của từng instance. Nếu instance đang ở miền `OPEN`, Gateway sẽ bỏ qua lệnh gọi HTTP để tiết kiệm băng thông và áp dụng điểm phạt cực đại (`SCORE = 20.0`) ngay lập tức.
* **Bước 4 — Gọi HTTP Bất đồng bộ (Parallel Polling):** Thực hiện gọi đồng thời các phương thức GET bằng HTTP REST đến endpoint `/api/alb-metrics` của các instance `CLOSED` hoặc `HALF_OPEN`. Quá trình này bị giới hạn ngưỡng timeout cứng là 800ms và thực thi phi khóa qua toán tử `Mono.when()` của Project Reactor.
* **Bước 5 — Đồng hóa Số liệu và Tính toán:**
    * **L_raw** (Delta Latency) → Hàm làm mịn `EWMA smoothing` có hệ số τ thích nghi → **L_ewma**.
    * **L_ewma** + (P5/P95 từ Histogram) → Chuẩn hóa `normalizeLatency()` → **nL ∈ [0,1]**.
    * **Q_raw** + qP99 → Chuẩn hóa Log-Scale `normalizeQueue()` → **nQ ∈ [0,1]**.
    * **CPU_raw** → Chuẩn hóa `normalizeCpu()` → **nC ∈ [0,1]**.
    * Tổng hợp **(nL, nQ, nC)** với bộ trọng số từ ma trận AHP-EWM **(α, β, γ)** → **BaseScore ∈ [0,1]**.
    * Độ trễ chuẩn hóa của instance + Mức nền P50 chuẩn hóa toàn hệ thống → Đưa vào **PID Controller** (tích hợp Leaky Integrator và Low-pass Filter) → **Penalty ∈ [0, λ]**.
    * Lưu trữ kết quả: **FinalScore = BaseScore + Penalty** vào bộ đệm `MetricsCache` (chi phí đọc/ghi O(1)).
* **Bước 6 — Kết thúc Chu kỳ:** Sau khi toàn bộ các luồng HTTP hoàn tất, giải phóng cờ `isPolling` trong khối hàm `doFinally()` để sẵn sàng cho chu kỳ kế tiếp.

### Luồng Data Plane và Định tuyến Thực tế (Xử lý Mili-giây)
Tầng Data Plane được kích hoạt ngay lập tức nhằm đưa ra quyết định định tuyến tối ưu mỗi khi có request đập vào cổng 8080:
* **Bước 1:** Spring Cloud Gateway hứng chặn request và gọi bộ cân bằng tải `AdaptiveLoadBalancer.choose()`.
* **Bước 2 (P2C Algorithm):** Sử dụng cơ chế *Power of Two Choices*, hệ thống trích xuất ngẫu nhiên 2 instance phân biệt từ danh sách cấu hình nhằm khắc phục hiện tượng bầy đàn.
* **Bước 3 (Real-Time Score):** Truy xuất `FinalScore` của 2 instance từ `MetricsCache`. Đọc số lượng request đang xử lý song song tức thời từ `InflightTracker` để tính hình phạt cục bộ.
    `RealTimeScore = FinalScore + Inflight Penalty`
* **Bước 4:** Găm request vào instance có `RealTimeScore` thấp hơn. Kích hoạt sự kiện `InflightLifecycle.onStartRequest()` để tăng biến đếm số luồng đang xử lý tại máy chủ đó lên 1 đơn vị.
* **Bước 5:** Khi luồng xử lý tại môi trường container kết thúc (thành công, lỗi hoặc timeout), sự kiện `InflightLifecycle.onComplete()` được kích hoạt vô điều kiện nhằm giảm biến đếm về trạng thái cân bằng.

### Luồng Quản lý Trạng thái Ngắt mạch (Circuit Breaker Machine)
`InstanceCircuitBreaker` theo dõi lịch sử polling của từng instance theo mô hình máy trạng thái hữu hạn ba miền giá trị:
* **CLOSED (Bình thường):** Gateway định tuyến lưu lượng bình thường. Mỗi lần poll bị timeout hoặc lỗi kết nối, failure count tăng 1. Sau 3 lần lỗi liên tiếp, mạch bị ngắt và chuyển sang `OPEN`.
* **OPEN (Cách ly):** `MetricsPoller` triệt tiêu các lệnh gọi HTTP vô ích, áp dụng penalty tối đa để ngưng toàn bộ traffic định tuyến đến máy chủ này. Trạng thái bị khóa cứng trong 5 giây, sau đó tự động chuyển sang thử nghiệm phục hồi `HALF_OPEN`.
* **HALF_OPEN (Thử nghiệm):** Cho phép một lượng nhỏ traffic/polling thăm dò mạng. Nếu `MetricsPoller` gọi HTTP REST thành công 2 lần liên tiếp, hệ thống xác nhận máy chủ đã khỏe, reset biến đếm và đóng mạch về `CLOSED`. Nếu thất bại, mạch bị dội ngược về `OPEN` và tái khởi động chu kỳ phạt 5 giây.

### Luồng Phối hợp Xử lý Sự cố (Chaos Engineering và Tự Phục hồi)
* **Kích hoạt lỗi:** Khi lệnh kích hoạt lỗi được phát tới `ChaosController` của một node, vòng lặp tính toán căn bậc hai cưỡng bức đẩy CPU lên mức trần và kéo giãn độ trễ.
* **Phản xạ cô lập:** Tại chu kỳ 1 giây tiếp theo, lớp `EwmaSmoother` co hẹp hệ số làm mịn để nhận diện cú sốc trễ, `PIDController` tích lũy sai số dương và đẩy `FinalScore` vọt lên cao. Ở tầng Data Plane, thuật toán P2C lập tức nhận diện sự chênh lệch điểm số và "né" node lỗi, điều hướng toàn bộ 500 RPS bão tải sang 2 node khỏe mạnh còn lại. Hiện tượng sập dây chuyền (Cascading Failure) được ngăn chặn thành công.
* **Tự phục hồi:** Khi sự cố Chaos được tắt đi, độ trễ thô lập tức giảm sút. Cơ chế xả trôi (*Leaky Integrator*) bên trong `PIDController` giải phóng dần điểm phạt. Khi `FinalScore` giảm về lại dải an toàn ban đầu, thuật toán P2C tự động điều tiết lưu lượng phân phối đều đặn trở lại cụm máy chủ một cách mượt mà.

---

## Danh sách Cổng & Chức năng

| Thành phần | Port| Chức năng chính |
| :--- | :---: | :--- |
| **API Gateway** (`api-gateway-alb`) | `8080` | Cổng vào duy nhất (Single Entry Point). Tiếp nhận request từ Client, thực hiện tính toán điểm số và điều hướng lưu lượng bằng thuật toán Adaptive. |
| **Eureka Server** (`eureka-server`) | `8761` | Service Registry / Dashboard. Nơi quản lý tập trung trạng thái đăng ký, hủy đăng ký và phát hiện dịch vụ của các node backend. |
| **Registration Service - Instance 1** | `8081` | Node backend xử lý nghiệp vụ số 1, cung cấp API lấy metric thời gian thực cho Gateway. |
| **Registration Service - Instance 2** | `8082` | Node backend xử lý nghiệp vụ số 2, hoạt động song song để chia sẻ tải cho hệ thống. |
| **Registration Service - Instance 3** | `8083` | Node backend xử lý nghiệp vụ số 3 (thường được chọn để giả lập lỗi/độ trễ qua `ChaosController`). |
| **Prometheus** | `9090` | Hệ thống thu thập, lưu trữ số liệu giám sát (metrics) dưới dạng dữ liệu chuỗi thời gian (Time-series). |
| **Grafana** | `3000` | Giao diện Dashboard trực quan hóa hiệu năng, biểu đồ tải, theo dõi trạng thái CPU, Latency và Inflight Requests. |

---

## Tính năng Cốt lõi

### 1. Thuật toán Thích nghi Thông minh
Đây là thuật toán cân bằng tải mặc định và phức tạp nhất của dự án, kết hợp nhiều mô hình toán học:
* **HTTP REST Metrics Polling:** Thay vì sử dụng các cơ chế hướng sự kiện phức tạp, hệ thống sử dụng cách tiếp cận gọn nhẹ bằng việc pull dữ liệu định kỳ qua API REST `/api/alb-metrics` (lấy CPU, độ trễ p50, hàng đợi).
* **MCDM (AHP-EWM Fusion):** Hệ thống tự động tính toán trọng số động cho 3 tiêu chí: Latency, Queue Length, và CPU. Trọng số EWM (Entropy Weight Method) được kết hợp với AHP để tránh điểm số hội tụ về 0 khi các node có trạng thái quá giống nhau.
* **Bộ điều khiển PID (PID Controller):** Áp dụng phạt (penalty) lên các node có dấu hiệu chậm đi so với mức trung bình của toàn hệ thống (P50 toàn cục). 
* **EWMA Smoother (Adaptive):** Làm mịn các điểm dữ liệu độ trễ thô bằng hàm mũ để loại bỏ hiện tượng nhiễu sóng (spikes) tức thời.
* **Power of Two Choices (P2C):** Chọn ngẫu nhiên 2 node và so sánh điểm số thời gian thực (đã bao gồm Local Inflight) để ra quyết định cuối cùng. Giúp phân tán tải hoàn hảo, triệt tiêu hiện tượng "bầy đàn" (Herd Effect).

### 2. Quản trị Luồng Inflight
Tích hợp `InflightLifecycle` theo dõi chính xác số lượng request đang được một node xử lý cùng lúc (tính bằng mili-giây). Nếu một node đột ngột nhận một "bão tải", điểm số của nó sẽ lập tức bị phạt bằng hàm `log` để nhường traffic cho node khác trước khi chu kỳ thu thập metric tiếp theo diễn ra.

### 3. Circuit Breaker
Bảo vệ hệ thống khỏi các node chết hoặc kẹt mạng liên tục. Nếu một node bị timeout quá 3 lần liên tiếp, Gateway sẽ ngắt mạch (chuyển sang `OPEN`), áp đặt điểm số tồi tệ nhất (`SCORE = 20.0`) để loại hoàn toàn node đó khỏi vòng định tuyến, sau đó tự động thử phục hồi (`HALF_OPEN`) khi hết thời gian phạt.

### 4. Đa dạng chiến lược cân bằng tải
Có thể cấu hình linh hoạt trong `application.yml` (`alb.strategy`):
* `adaptive`: Thuật toán thích nghi thông minh (Mặc định).
* `round-robin`: Định tuyến xoay vòng cơ bản.
* `random`: Định tuyến ngẫu nhiên.
* `least-connections`: Định tuyến vào node có ít kết nối đang xử lý nhất.

---

## Hướng dẫn Cài đặt & Chạy

Dự án đã được thiết lập sẵn sàng triển khai thông qua Docker Compose.

**Bước 1:** Clone repository về máy.
```bash
git clone <your-repository-url>
cd Adaptive-Load-Balancing-
```

**Bước 2:** Khởi chạy toàn bộ hệ thống bằng một câu lệnh:
```bash
docker compose up -d --build
```

**Bước 3:** Kiểm tra trạng thái hệ thống. 
* **Eureka Dashboard:** `http://localhost:8761` (Chờ khoảng 30s-40s để cả 3 node Registration báo trạng thái `UP`).

---

## Hướng dẫn Kiểm thử

Sau khi hệ thống đã `UP`, có thể kiểm thử khả năng cân bằng tải bằng cách gửi request qua Gateway.

**Gửi Request thông thường:**
```bash
curl http://localhost:8080/api/register
```
*Sẽ thấy phản hồi ghi nhận Port của backend đang xử lý request (VD: `Port: 8081`).*

**Kịch bản Kiểm thử với Chaos Engineering:**
Giả sử muốn tạo "nút thắt cổ chai" bằng cách làm cho node 3 (`8083`) bị trễ đột xuất:
```bash
# Kích hoạt sự cố trên node 8083
curl -X POST http://localhost:8083/api/chaos/enable
```
Sau khi kích hoạt, nếu tiếp tục gửi nhiều request vào cổng `8080` của Gateway, sẽ thấy hệ thống Adaptive Load Balancer nhận diện độ trễ của node `8083` bị tăng vọt (thông qua điểm PID Penalty và EWMA Latency). Ngay lập tức, Gateway sẽ điều hướng gần như toàn bộ lưu lượng qua hai node còn khỏe mạnh là `8081` và `8082`.

```bash
# Tắt sự cố để đưa node 8083 về bình thường
curl -X POST http://localhost:8083/api/chaos/disable
```

---

---

## Kiểm thử Tải & Độ Phục hồi với Apache JMeter

Để chứng minh sức mạnh của thuật toán Cân bằng tải Thích nghi, dự án cung cấp một kịch bản kiểm thử kết hợp giữa **Tăng tải đột ngột (Spike Testing)** và **Bơm lỗi tự động (Chaos Engineering)** theo dòng thời gian.

### 1. Tải và Cài đặt JMeter
1. Truy cập trang chủ [Apache JMeter](https://jmeter.apache.org/download_jmeter.cgi) và tải phiên bản mới nhất (Yêu cầu Java 8+).
2. Tải và cài đặt **JMeter Plugins Manager** (file `.jar` bỏ vào thư mục `lib/ext`).
3. Khởi động JMeter, mở Plugins Manager và cài đặt bộ plugin **Custom Thread Groups** (để sử dụng component `jp@gc - Throughput Shaping Timer`).

### 2. Thiết lập Kịch bản Kiểm thử

Bạn tạo một Test Plan gồm 3 Thread Group chạy song song để mô phỏng thực tế:

#### A. Main Thread Group (Tạo bão tải)
Đảm nhiệm việc bắn request liên tục vào Gateway để mô phỏng người dùng.
* **HTTP Request:** Gửi phương thức `GET` đến `http://localhost:8080/api/register`
* **jp@gc - Throughput Shaping Timer:** Thiết lập mô hình tải kéo dài 5 phút (300 giây):
  * `0` -> `200` RPS trong `30s` (Bắt đầu tăng tải).
  * `200` -> `500` RPS trong `60s` (Bão tải đạt đỉnh).
  * `500` -> `500` RPS trong `180s` (Duy trì bão tải).
  * `500` -> `0` RPS trong `30s` (Giảm tải).
* **Constant Timer:** Đặt `Thread Delay: 10ms` để tạo giãn cách nhỏ gọn giữa các thread.

#### B. Thread Group: Chaos - Enable (Kích hoạt sự cố)
Mô phỏng tình huống một node đột ngột bị nghẽn mạng ngay giữa tâm bão.
* **Cấu hình Thread:** `1` Thread, `Startup delay = 90` (giây).
* **HTTP Request:** Gửi phương thức `POST` tới `http://localhost:8083/api/chaos/enable`.
* *Kết quả mong đợi:* Tại giây thứ 90 (đang ở đỉnh 500 RPS), node 8083 sẽ bị trễ. Gateway phải ngay lập tức nhận diện độ trễ tăng vọt (qua thuật toán EWMA và PID) và "né" node này, chuyển toàn bộ lưu lượng sang node 8081 và 8082 mà không làm rơi request của client.

#### C. Thread Group: Chaos - Disable (Tắt sự cố, phục hồi)
Mô phỏng tình huống node đã sửa xong lỗi và sẵn sàng nhận tải lại.
* **Cấu hình Thread:** `1` Thread, `Startup delay = 210` (giây).
* **HTTP Request:** Gửi phương thức `POST` tới `http://localhost:8083/api/chaos/disable`.
* *Kết quả mong đợi:* Tại giây thứ 210 (sau 2 phút bị lỗi), node 8083 khỏe lại. Gateway nhận thấy Latency và Score của node này giảm về mức an toàn nên sẽ tự động điều phối lượng tải lớn quay trở lại node này để chia lửa cho 2 node kia.

### Quan sát Kết quả
Trong lúc JMeter đang bắn tải, hãy mở dashboard của **Grafana (`http://localhost:3000`)**. Ta sẽ thấy cực kỳ rõ rệt trên biểu đồ:
1. Đường Line biểu diễn lưu lượng (RPS) vào node `8083` đột ngột cắm đầu xuống đất tại `T = 90s`.
2. Đồng thời lưu lượng tại `8081` và `8082` vọt lên để gánh phần bị thiếu.
3. Tại `T = 210s`, các đường Line lưu lượng tự động hội tụ và chia đều lại cho cả 3 node một cách mượt mà.

---

## CI/CD & Deploy Tự động
-dùng để thuận tiện trong quá trình thiết kế và kiểm thử
## CÁC FILE NHẬT KÝ LÀM VIỆC
**[Xem chi tiết: Nhật ký chuyển dự án lên Docker Ubuntu Server](nhat-ky-chuyen-len-server.md)**
