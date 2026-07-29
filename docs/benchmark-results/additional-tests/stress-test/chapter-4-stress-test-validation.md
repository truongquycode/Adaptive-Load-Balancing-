# Xác nhận Số liệu Kịch bản Stress Test (Validation Record)

## 1. Nguồn Dữ Liệu
Bộ dữ liệu được sử dụng để phân tích mục 4.3.4 là **Final Dataset** (phiên bản đóng băng ngày 22/07/2026), lưu trữ tại thư mục:
`benchmark-raw-results/additional-tests/stress-test-final`

## 2. Tính Hợp Lệ Của Dữ Liệu (Validation Status)
- **Tổng số lần chạy (Runs):** 12/12 lần chạy hợp lệ.
- **Cấu trúc:** 4 chiến lược (Adaptive, Least Connections, Random, Round Robin) × 3 lần chạy mỗi chiến lược.
- **Tính toàn vẹn:** Mọi báo cáo `.jtl` đều ở trạng thái `VALID`, không chứa giá trị `NaN`, `Inf` hay bị hỏng chuỗi dữ liệu. Các thông số nguyên tắc (P50 < P95 < P99) được bảo toàn tuyệt đối.

## 3. Các Số Đo Lường Chính (Metrics Audited)
- **Average Latency (ms):** Giá trị trung bình của độ trễ, đại diện cho xu hướng chung.
- **P50 / P95 / P99 (ms):** Các phân vị độ trễ.
- **Error Rate (%):** Được tính bằng tỷ lệ số mẫu lỗi trên tổng số mẫu hợp lệ của cả 3 lần chạy (Pooled Error Rate).
- **Throughput (RPS):** Thông lượng tổng thể đo được.
- **Goodput (RPS):** Thông lượng hữu ích. 

## 4. Ghi Chú Về Công Thức Goodput
Trong bộ dữ liệu này, **Goodput** là giá trị dẫn xuất (Estimated / Derived Metric), được tính bằng công thức:
`Goodput = Throughput × (1 − Error Rate)`
Việc tính toán này phản ánh chính xác lượng yêu cầu được xử lý thành công trên thực tế trong điều kiện tập mẫu lớn (> 400.000 mẫu mỗi run). Số liệu trong bảng tổng hợp khớp hoàn toàn với biểu đồ Visualizations.

## 5. Giới Hạn & Điểm Thận Trọng
- Các phân tích về cơ chế nội bộ của thuật toán (như việc chấm điểm hay cập nhật trọng số) được suy luận gián tiếp từ dữ liệu hộp đen (End-to-End metrics). Các kết luận này được diễn đạt dưới dạng "gợi ý" hoặc "phù hợp với giả thuyết" thay vì khẳng định tuyệt đối về trạng thái bộ nhớ bên trong.
- Biểu đồ Tail Latency (P99) sử dụng thang đo Log (Log-Scale) để biểu diễn được sự chênh lệch lớn giữa P50 (~300ms) và P99 (~15.000ms). Cần lưu ý điều này khi đọc trực quan.
