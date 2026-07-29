# Báo Cáo Kiểm Toán Độ Chính Xác Dữ Liệu Benchmark (Data Audit Report)

**1. Ngày thực hiện audit:** 21/07/2026
**2. Đường dẫn dữ liệu nguồn:** `D:\eclipse-workspace\adaptive-load-balancer-parent\docs\benchmark-results\official-runs\2026-07-official-3runs`
**3. Tổng số file đã kiểm kê:** Hơn 40 file bao gồm CSV, JSON, Markdown, Text, và PNG trong các thư mục data, notes, images.
**4. Danh sách scenario:**
- `01-low-load` (300 RPS)
- `02-medium-dependency-slowdown` (600 RPS)
- `03-high-dependency-slowdown` (900 RPS)
**5. Số Run của từng strategy:** 
Tất cả các chiến lược (Adaptive, Least Connections, Random, Round Robin) tại tất cả các kịch bản đều có **ĐÚNG 3 RUN**. Mặc dù file `quick-summary.txt` của kịch bản 03 báo cáo Round Robin chỉ có 2 run, nhưng dữ liệu thực tế tại `jtl-summary.csv` chứa đầy đủ 3 run hợp lệ.
**6. Số request tổng cộng:** Khoảng hơn 4.2 triệu request trong toàn bộ bộ dữ liệu.
**7. Các file dữ liệu được sử dụng:** 
- `*/data/jtl-summary.csv` (Nguồn gốc raw duy nhất để trích xuất số liệu Run riêng lẻ).
**8. Các file bị bỏ qua và lý do:**
- `aggregate-summary-clean.csv` ở kịch bản 03 bị bỏ qua vì thiếu dữ liệu Least Connections và Round Robin chỉ tính 2 run (dẫn đến sai lệch dữ liệu nếu sử dụng).
- Các file hình ảnh Grafana (`.png`) được dùng làm tài liệu tham khảo ngữ cảnh, nhưng bị bỏ qua trong tính toán định lượng do không thể đọc chính xác giá trị số từ ảnh.

---

### 9. Kết quả tính toán lại (Ví dụ High Load - 900 RPS)
- **Adaptive:** Tổng samples = 884,147; Tổng errors = 277; **Pooled Error Rate = 0.0313%**. Mean Throughput = 824.35; Pooled Goodput = 824.01.
- **Round Robin:** Tổng samples = 855,265; Tổng errors = 224,708; **Pooled Error Rate = 26.2735%**. Mean Throughput = 834.20; Pooled Goodput = 614.99.
- **Random:** Tổng samples = 849,786; Tổng errors = 178,439; **Pooled Error Rate = 20.9981%**. Mean Throughput = 849.77; Pooled Goodput = 671.26.

### 10. Đối chiếu với summary
- Dòng AGGREGATE trong `jtl-summary.csv` cho Adaptive High Load ghi Error Rate là `0.0315998%`. Giá trị tính lại (Pooled Error Rate) là `0.0313296%`.
- AGGREGATE cho Round Robin High Load ghi Error Rate là `26.1308%`. Giá trị tính lại (Pooled) là `26.2735%`.
- **Kết luận đối chiếu:** Các bảng CSV cũ tính Aggregate Error Rate bằng **trung bình cộng của 3 Error Rates** (Mean Error Rate) thay vì Pooled Error Rate. Cả hai phương pháp đều có thể tính được, tuy nhiên Pooled Error Rate chính xác hơn khi mẫu khác nhau. File Python mới đã dùng Pooled Error Rate. Các metric Average, Median, P95, P99 hoàn toàn MATCH 100%.

### 11. Các mismatch
- **MISMATCH 1:** `aggregate-summary-clean.csv` của kịch bản 03 bị lỗi mất 1 strategy (Least Connections) và thiếu 1 run của Round Robin. Nguyên nhân do lỗi trong công cụ filter cũ. Data pipeline mới đã xử lý triệt để mismatch này bằng cách quét thẳng từ `jtl-summary.csv`.
- **MISMATCH 2:** Mean Error Rate (cũ) vs Pooled Error Rate (mới). Sự chênh lệch cực nhỏ (ví dụ 26.13% vs 26.27%).

### 12. Các dữ liệu thiếu
- Không có file JTL thô (chứa từng dòng request riêng lẻ). Do đó không thể tính Pooled P95 / P99. Pipeline buộc phải tính **Mean of P95/P99** (đã ghi chú rõ trong bộ biểu đồ).

### 13. Các giả định
- Giả định rằng `duration_s` trong bảng là chính xác và trùng khớp để tính Goodput (Goodput = Successful Samples / Duration).

### 14. Các giới hạn
- Đánh giá Stability (Variance/CV) được thực hiện dựa trên n=3 (3 lần chạy). Mặc dù có tính định hướng tốt, n=3 là cỡ mẫu quá nhỏ để chạy các phép kiểm định thống kê chuyên sâu. Điều này nên được trình bày như một biểu hiện quan sát, không phải là chân lý thống kê tuyệt đối.

---

### KẾT LUẬN CUỐI CÙNG

**DATA VERIFIED WITH WARNINGS**

**Lý do:** Toàn bộ dữ liệu raw hợp lệ, không bị làm giả, và có thể tái lập (reproducible). Tuy nhiên, có cảnh báo nhỏ liên quan đến file summary gộp cũ bị mất dữ liệu và cách tính Mean Error Rate ở các báo cáo trước đó. Cả 2 warning này đã được Data Pipeline Python mới khắc phục hoàn toàn để xuất ra số liệu tinh khiết, minh bạch cho luận văn. Mọi biểu đồ mới sinh ra hoàn toàn đủ tiêu chuẩn sử dụng.
