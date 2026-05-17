# ⚖️ Adaptive Load Balancing cho Microservices

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://java.com/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.4-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring_Cloud-2023.0.1-6DB33F?style=for-the-badge&logo=spring&logoColor=white)](https://spring.io/projects/spring-cloud)
[![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)](https://prometheus.io/)
[![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=github-actions&logoColor=white)](https://github.com/features/actions)

Một hệ thống API Gateway tích hợp cơ chế Cân bằng tải Thích nghi (Adaptive Load Balancer) tiên tiến dành cho kiến trúc Microservices. Dự án không chỉ dừng lại ở các thuật toán định tuyến cơ bản mà còn áp dụng thu thập số liệu thời gian thực (thông qua HTTP REST), phân tích đa tiêu chí (MCDM), bộ điều khiển PID và làm mịn chuỗi thời gian (EWMA) để tối ưu hóa lưu lượng mạng và tránh quá tải hệ thống.

---

## 🏗 Kiến trúc Hệ thống (Architecture)

Hệ thống được chia thành 3 thành phần chính:

1. **Eureka Server (`eureka-server`):** Đóng vai trò Service Registry, quản lý việc đăng ký và khám phá dịch vụ của toàn bộ các node trong cụm.
2. **API Gateway (`api-gateway-alb`):** Trái tim của hệ thống. Nơi nhận mọi request từ client và thực thi logic định tuyến. Nó liên tục thăm dò (poll) trạng thái của các backend để ra quyết định điều hướng thông minh nhất.
3. **Registration Service (`registration-service-alb`):** Các backend instance dùng để xử lý request thực tế. Đặc biệt, các backend này được tích hợp sẵn một `ChaosController` cho phép kích hoạt các lỗi hoặc độ trễ giả lập (chaos engineering) để kiểm thử độ nhạy bén của Gateway.

---

## 🌟 Tính năng Cốt lõi (Core Features)

### 1. Thuật toán Thích nghi Thông minh (Adaptive Strategy)
Đây là thuật toán cân bằng tải mặc định và phức tạp nhất của dự án, kết hợp nhiều mô hình toán học:
* **HTTP REST Metrics Polling:** Thay vì sử dụng các cơ chế hướng sự kiện phức tạp, hệ thống sử dụng cách tiếp cận gọn nhẹ bằng việc pull dữ liệu định kỳ qua API REST `/api/alb-metrics` (lấy CPU, độ trễ p50, hàng đợi).
* **MCDM (AHP-EWM Fusion):** Hệ thống tự động tính toán trọng số động cho 3 tiêu chí: Latency, Queue Length, và CPU. Trọng số EWM (Entropy Weight Method) được kết hợp với AHP để tránh điểm số hội tụ về 0 khi các node có trạng thái quá giống nhau.
* **Bộ điều khiển PID (PID Controller):** Áp dụng phạt (penalty) lên các node có dấu hiệu chậm đi so với mức trung bình của toàn hệ thống (P50 toàn cục). 
* **EWMA Smoother (Adaptive):** Làm mịn các điểm dữ liệu độ trễ thô bằng hàm mũ để loại bỏ hiện tượng nhiễu sóng (spikes) tức thời.
* **Power of Two Choices (P2C):** Chọn ngẫu nhiên 2 node và so sánh điểm số thời gian thực (đã bao gồm Local Inflight) để ra quyết định cuối cùng. Giúp phân tán tải hoàn hảo, triệt tiêu hiện tượng "bầy đàn" (Herd Effect).

### 2. Quản trị Luồng Inflight (Inflight Tracking)
Tích hợp `InflightLifecycle` theo dõi chính xác số lượng request đang được một node xử lý cùng lúc (tính bằng mili-giây). Nếu một node đột ngột nhận một "bão tải", điểm số của nó sẽ lập tức bị phạt bằng hàm `log` để nhường traffic cho node khác trước khi chu kỳ thu thập metric tiếp theo diễn ra.

### 3. Circuit Breaker (Ngắt mạch Tự động)
Bảo vệ hệ thống khỏi các node chết hoặc kẹt mạng liên tục. Nếu một node bị timeout quá 3 lần liên tiếp, Gateway sẽ ngắt mạch (chuyển sang `OPEN`), áp đặt điểm số tồi tệ nhất (`SCORE = 20.0`) để loại hoàn toàn node đó khỏi vòng định tuyến, sau đó tự động thử phục hồi (`HALF_OPEN`) khi hết thời gian phạt.

### 4. Đa dạng chiến lược cân bằng tải
Bạn có thể cấu hình linh hoạt trong `application.yml` (`alb.strategy`):
* `adaptive`: Thuật toán thích nghi thông minh (Mặc định).
* `round-robin`: Định tuyến xoay vòng cơ bản.
* `random`: Định tuyến ngẫu nhiên.
* `least-connections`: Định tuyến vào node có ít kết nối đang xử lý nhất.

---

## 🚀 Hướng dẫn Cài đặt & Chạy (Getting Started)

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

## 🧪 Hướng dẫn Kiểm thử (Usage & Testing)

Sau khi hệ thống đã `UP`, bạn có thể kiểm thử khả năng cân bằng tải bằng cách gửi request qua Gateway.

**Gửi Request thông thường:**
```bash
curl http://localhost:8080/api/register
```
*Bạn sẽ thấy phản hồi ghi nhận Port của backend đang xử lý request (VD: `Port: 8081`).*

**Kịch bản Kiểm thử với Chaos Engineering:**
Giả sử bạn muốn tạo "nút thắt cổ chai" bằng cách làm cho node 3 (`8083`) bị trễ đột xuất:
```bash
# Kích hoạt sự cố trên node 8083
curl -X POST http://localhost:8083/api/chaos/enable
```
Sau khi kích hoạt, nếu bạn tiếp tục gửi nhiều request vào cổng `8080` của Gateway, bạn sẽ thấy hệ thống Adaptive Load Balancer nhận diện độ trễ của node `8083` bị tăng vọt (thông qua điểm PID Penalty và EWMA Latency). Ngay lập tức, Gateway sẽ điều hướng gần như toàn bộ lưu lượng qua hai node còn khỏe mạnh là `8081` và `8082`.

```bash
# Tắt sự cố để đưa node 8083 về bình thường
curl -X POST http://localhost:8083/api/chaos/disable
```

---

## ⚙️ Cấu hình Tùy chỉnh (Configuration)
Dự án cho phép tinh chỉnh rất sâu vào các thông số toán học tại file `application.yml` của `api-gateway-alb`:

```yaml
alb:
  strategy: adaptive       # Chọn thuật toán định tuyến
  polling:
    interval: 1000         # Tần suất lấy metric từ backend (ms)
  ewma:
    tau-min: 500.0         # Mức làm mịn thấp khi hệ thống có bão (ms)
    tau-max: 5000.0        # Mức làm mịn cao khi hệ thống ổn định (ms)
    k: 2.0                 # Hệ số độ nhạy (càng cao càng nhạy bén với thay đổi)
  pid:
    kp: 1.5                # Trọng số P (Lỗi hiện tại)
    ki: 0.15               # Trọng số I (Lỗi tích lũy)
    kd: 0.05               # Trọng số D (Tốc độ thay đổi lỗi)
```

---

## 🔄 CI/CD & Deploy Tự động
Dự án tích hợp sẵn một luồng GitHub Actions (`.github/workflows/deploy.yml`) để hỗ trợ Continuous Deployment. Mỗi khi có code đẩy lên nhánh `main`, action sẽ tự động checkout code và chạy lệnh `docker compose build && docker compose up -d` trên môi trường self-hosted (ví dụ: máy chủ Ubuntu cá nhân).