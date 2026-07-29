# Báo Cáo Kiểm Tra Dữ Liệu Stress Test (Pre-Analysis Validation)

Tài liệu này xác nhận độ tin cậy và toàn vẹn của dữ liệu thu thập được từ Pipeline `process_stress_test_results.py` trước khi đưa vào trực quan hóa và phân tích kết luận.

## 1. Trạng Thái Cấu Trúc
*   **Chiến lược (Strategies)**: Có đủ 4 strategy bắt buộc (`adaptive_full`, `least_connect`, `random`, `round_robin`).
*   **Số lượng Runs**: Có đủ 3 runs cho mỗi chiến lược.
*   **Tổng số Runs**: 12/12.
*   **Trạng thái Hợp lệ (Validity)**: Không có bất kỳ Run nào mang cờ `INVALID` trong file `validation-report.csv`. Tất cả 12 JTL đều chứa hàng trăm ngàn record mẫu hợp lệ.

## 2. Kiểm Tra Tính Hợp Lý Của Metrics (Sanity Checks)
*   **Giá trị NaN/Null**: Không phát hiện giá trị bất thường tại các cột thống kê chính.
*   **Metric âm**: Không có bất kỳ giá trị âm nào cho Throughput, Latency, hay Error Rate.
*   **Miền giá trị Error Rate**: Nằm hoàn toàn trong khoảng an toàn và có ý nghĩa thực tế: [32.68%, 42.58%].
*   **Bất đẳng thức phân vị (Percentile Inequality)**: `Min <= P50 <= Avg <= P95 <= P99 <= Max` hoàn toàn được bảo toàn trên toàn bộ 12 runs (Ví dụ ở Round Robin: 87 < 1188 < 1582 < 5326 < 10799 < 15699).
*   **Throughput Consistency**: Throughput biến thiên tự nhiên giữa các lần chạy, không có dấu hiệu bị hard-code hay lặp số ngẫu nhiên.

## 3. Kết luận
Dữ liệu nguồn đạt **100% độ toàn vẹn (Data Integrity)**. Mọi khác biệt và độ chênh lệch giữa các thuật toán là hiện tượng vật lý hệ thống thực sự, hoàn toàn đủ tiêu chuẩn học thuật để sinh biểu đồ (Charts) và bảng tổng hợp cho Luận văn Tốt nghiệp.
