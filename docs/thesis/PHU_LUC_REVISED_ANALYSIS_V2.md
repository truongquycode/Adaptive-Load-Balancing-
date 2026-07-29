# BÁO CÁO PHÂN TÍCH RÚT GỌN PHỤ LỤC LUẬN VĂN (V2)

## 1. Phương pháp chắt lọc thông tin

Quá trình chỉnh sửa Phụ lục (V2) được thực hiện với nguyên tắc "Ngắn gọn, Thực dụng, Có tính tái lập", nhằm đảm bảo Phụ lục không trở thành nơi chứa toàn bộ mã nguồn (code dump) hay lặp lại các lý thuyết đã phân tích.

### 1.1. Những phần ĐÃ LOẠI BỎ vì trùng lặp (Chương 2, 3, 4)
- **Lý thuyết Thuật toán**: Các định nghĩa, khái niệm và sơ đồ hoạt động của PID, EWMA, AHP, EWM, MCDM, P2C (đã có ở Chương 2, Chương 3). Phụ lục V2 tuyệt đối không giải thích lại lý thuyết.
- **Kiến trúc luồng dữ liệu**: Sơ đồ Gateway gọi Eureka và lấy metrics (đã có ở Chương 3).
- **Kịch bản và Kết quả Benchmark**: Không kể lại ý nghĩa của các mức tải Low, Medium, High, Stress hay đưa biểu đồ, bảng danh sách kịch bản vào Phụ lục (vì toàn bộ đã có ở Chương 4).
- **Cấu hình phần cứng Backend**: Bảng phân bổ CPU và RAM cho từng container Docker đã bị loại bỏ vì cũng đã được trình bày chi tiết ở phần thiết lập thực nghiệm của Chương 4.

### 1.2. Những phần ĐƯỢC GIỮ LẠI (Giá trị tái lập hệ thống)
- Cấu hình môi trường (Java, Maven, Docker) và phần cứng ảo hóa cấp phát cho mỗi container.
- Các hằng số thuật toán thực tế (PID, EWMA, MCDM, Routing) định nghĩa hiệu năng của Gateway (`application.yml`).
- Cơ chế kích hoạt Chaos Engineering qua các API Endpoints.
- Hệ thống tập lệnh và quy trình tự động hóa thực nghiệm (rất quan trọng để hội đồng đánh giá tính khoa học và khách quan của luận văn).

---

## 2. Chi tiết các tệp được trích xuất và rút gọn

### 2.1. Cấu hình (`.yml`, Docker)
- **Trích xuất từ**: `application.yml`, `docker-compose.yml`, `prometheus.yml`.
- **Cách rút gọn**:
  - Không chèn nguyên bản `application.yml` (>180 dòng). Chỉ chọn lọc 8 tham số cốt lõi nhất (như `tau-min`, `kp`, `blend-factor`, `hard-inflight-cap`) và trình bày bằng **Bảng tóm tắt**.
  - Bỏ hẳn bảng mô tả cấu hình CPU/RAM Docker do đã trình bày chi tiết ở Chương 4, chỉ giữ lại lệnh khởi chạy hệ thống `docker compose up -d --build`.

### 2.2. Mã nguồn Java
- **Trích xuất từ**: `PIDController.java`, `AdaptiveLoadBalancer.java` (P2C), `ScoreCalculator.java`, `RoutingCostCalculator.java`, `DynamicWeightEngine.java`, `MetricsPoller.java`.
- **Cách rút gọn**:
  - Không dán toàn bộ Class hay các hàm phụ trợ (logging, getters/setters, null checks, exception handling).
  - Chỉ trích duy nhất từ 5 đến 15 dòng code cho mỗi thuật toán, tập trung vào công thức toán học cốt lõi. Ví dụ: Phần tính Deadband và Anti-windup của PID; Hàm tính Entropy cho EWM.

### 2.3. Cấu hình Benchmark (JMeter & Scripts)
- **Trích xuất từ**: Các file `.jmx`, `_benchmark_common.bat`, `summarize_jtl_results.py`.
- **Cách rút gọn**:
  - Thay vì copy toàn bộ script `.bat` hay Python, Phụ lục V2 dùng gạch đầu dòng liệt kê logic làm việc chính (Deploy -> Verify -> Reset State -> Execute).
  - Lược bỏ hoàn toàn bảng danh sách kịch bản JMeter (do đã có ở Chương 4), chỉ giữ lại quy ước nhãn dữ liệu (`MEASURE_` / `DISCARD_`).

---

## 3. Các thành phần cần lưu ý và kiểm tra thêm

### 3.1. Các phần còn cần kiểm tra thủ công (Manual Review)
- **Mức độ chi tiết mã nguồn**: Cần tham khảo ý kiến Giảng viên hướng dẫn xem với 10 dòng code snippet mỗi thuật toán đã đủ minh chứng cho khối lượng cài đặt của đề tài chưa. Nếu GVHD yêu cầu, có thể bổ sung thêm link tới Github repository thay vì dán thêm code vào luận văn.
- **Tailscale VPN**: Phần mềm Tailscale được ghi nhận trong thiết lập. Sinh viên cần xác minh xem khi bảo vệ luận văn (nếu báo cáo offline tại trường) có sử dụng Tailscale không, để quyết định giữ hay bỏ mục A.5.

### 3.2. Những phần chưa thể xác minh từ source code
- **Cấu hình mạng hạ tầng vật lý của Github Actions Runner**: Chỉ có file YAML của workflow trong mã nguồn, nhưng thông tin cấu hình máy runner thực tế chạy ở đâu (máy tính cá nhân hay server trường) chưa thể xác định chỉ qua source code.
- **Chi tiết giao diện Dashboard Grafana**: File cấu hình `dashboard-grafana.json` có sẵn, nhưng giao diện hiển thị thực tế (màu sắc, panel) phụ thuộc vào quá trình thiết lập khi deploy. Do đó phụ lục chỉ liệt kê các panel chính được đo lường thay vì dán file JSON.
