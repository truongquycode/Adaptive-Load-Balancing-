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