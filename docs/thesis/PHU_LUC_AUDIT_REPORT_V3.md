# BÁO CÁO KIỂM TRA VÀ VIẾT LẠI PHỤ LỤC LẦN 3 (AUDIT REPORT V3)

## 1. Xác nhận quá trình đọc và nghiên cứu tài liệu
- **LVTN-TRUONG-VAN-QUY-B2204965.pdf:** Đã đọc TOÀN BỘ.
- **Các chương/mục đã đọc:** Chương 1 (giới thiệu, mục tiêu, phạm vi), Chương 2 (cơ sở lý thuyết, kiến trúc, Tail Latency, MCDM, AHP, EWMA, PID, P2C), Chương 3 (phương pháp thực hiện, thiết kế Control Plane & Data Plane, các luồng thuật toán), Chương 4 (môi trường kiểm thử, kịch bản tải, ablation study), Chương 5 (kết luận).
- **Thư mục source code đã đọc:** 
  - `api-gateway-alb/src/main/java/com/truongquycode/apigatewayalb` (Data plane, Control plane, config)
  - `eureka-server`
  - `registration-service-alb`
  - `monitoring` (Prometheus, Grafana, Docker configs)
- **File cấu hình đã kiểm tra:** `docker-compose.yml`, `api-gateway-alb/src/main/resources/application.yml`, `registration-service-alb/src/main/resources/application.yml`, `monitoring/prometheus.yml`.
- **Các class Java đã kiểm tra:** `AdaptiveLoadBalancer`, `DynamicWeightEngine`, `PIDController`, `RoutingCostCalculator`, `EwmaSmoother`.
- **Kiểm tra Docker/Docker Compose:** Đã xác minh `docker-compose.yml` có chứa các giới hạn CPU (`cpus: "2.0"`, `"1.5"`, `"1.0"`) chính xác như mô tả trong luận văn.
- **Kiểm tra Prometheus/Grafana/cAdvisor:** Đã xác minh cấu hình scrape của Prometheus (`api-gateway-alb`, `registration-service-alb`, `cadvisor`).
- **Kiểm tra Tailscale và Ubuntu Server:** Các thông tin này được lấy từ mô tả của luận văn (máy ảo Guest OS 172.30.35.37, mạng Tailscale 100.x.x.x) vì không có script cài đặt tự động toàn bộ trên Ubuntu. [CÓ THỂ SUY RA / THEO LUẬN VĂN]
- **Kiểm tra JMeter:** Xác minh qua báo cáo trong luận văn (không thấy script jmeter trực tiếp trong repo nhưng luận văn có mô tả quy trình `_benchmark_common.bat`).

## 2. Nguồn gốc thông tin và đối chiếu
- **Lấy trực tiếp từ source code:** Cấu hình `docker-compose.yml`, cấu hình `application.yml` của Gateway và Registration (tomcat threads 500), mã nguồn các thuật toán cốt lõi (P2C, PID, EWM, EWMA), cấu hình Prometheus.
- **Lấy từ luận văn:** Các kịch bản chạy benchmark (Low, Medium, High, Stress), cấu hình tài nguyên vật lý của Host và Guest OS (Ubuntu 26.04, 8 Cores, 11 GiB RAM), dải IP mạng LAN/Tailscale.
- **Chưa thể xác minh tự động từ source code (nhưng hợp lý theo ngữ cảnh):** Việc cài đặt thực tế của Tailscale trên máy ảo Ubuntu (chỉ có IP trong luận văn, không có file script config VPN trong source). Đã đánh dấu [THEO LUẬN VĂN] trong Phụ lục.
- **Mâu thuẫn:** Không phát hiện mâu thuẫn nghiêm trọng. Các thuật toán như AHP, EWM, EWMA, PID, P2C được trình bày trong Chương 2 và 3 đều có file mã nguồn tương ứng thực thi chính xác logic (ví dụ `DynamicWeightEngine` kết hợp AHP tĩnh `[ 0.648, 0.230, 0.122 ]` và EWM động). Tỉ lệ giới hạn CPU trong `docker-compose.yml` (2.0, 1.5, 1.0) khớp hoàn toàn với Bảng 4.4 của luận văn.

## 3. Nội dung Phụ lục mới bổ sung
So với các bản cũ, bản Phụ lục V3 này:
1. Tập trung vào KHẢ NĂNG TÁI LẬP (Reproducibility) bằng cách cung cấp chính xác các file cấu hình cốt lõi đã được kiểm chứng (Gateway `application.yml`, `docker-compose.yml`, `prometheus.yml`).
2. Trích dẫn ĐÚNG MÃ NGUỒN thực tế từ repository (VD: code P2C trong `AdaptiveLoadBalancer.java`, code PID trong `PIDController.java`) thay vì mã giả.
3. Tổ chức lại cấu trúc rõ ràng thành 5 phần (A, B, C, D, E) bám sát yêu cầu từ thiết lập hạ tầng, giám sát, mã nguồn, đến kiểm thử.
4. Đánh dấu minh bạch nguồn gốc thông tin: `[XÁC MINH TỪ SOURCE CODE]`, `[THEO LUẬN VĂN]`.

## 4. Những nội dung cần tác giả kiểm tra thủ công
- Các đoạn script shell/JMeter (ví dụ `1-run_low_all_strategies.bat`) không nằm trong thư mục gốc hoặc có tên khác, tác giả cần điền nội dung script thực tế vào Phụ lục E nếu muốn chi tiết hóa.
- IP của Tailscale và Host/Guest OS có thể thay đổi tùy lần triển khai, tác giả có thể cập nhật lại địa chỉ tĩnh nếu cần.
