# Trực Quan Hóa Kết Quả Benchmark - Stress Test

Tài liệu này giải thích ý nghĩa, phương pháp tính toán và hạn chế của 7 biểu đồ phân tích hiệu năng phục vụ trực tiếp cho Luận văn Tốt nghiệp (Chương 4).

> [!WARNING]
> **Data Integrity Warning:** Tất cả biểu đồ và số liệu được sinh ra tự động 100% từ thư mục `benchmark-raw-results` thông qua script Python `generate_stress_test_visualizations.py`. KHÔNG một con số nào bị chỉnh sửa thủ công.

---

## 1. Average Latency Comparison (Chart 01)
*   **Mục đích**: So sánh độ trễ trung bình của 4 thuật toán trong điều kiện siêu tải (Overload).
*   **Nguồn dữ liệu**: Cột `avg_latency` từ `02_stress_test_aggregate.csv`.
*   **Cách đọc biểu đồ**: Cột càng thấp thì hệ thống phản hồi càng nhanh.
*   **Ý nghĩa đối với Adaptive**: Biểu đồ cho thấy Adaptive có độ trễ cao nhất. Điều này xác nhận hành vi "chủ động xếp hàng" (queueing) của thuật toán: thay vì trả về lỗi timeout lập tức (như thuật toán tĩnh), Adaptive giữ request lại trong hàng đợi để chờ tài nguyên backend rảnh rỗi.
*   **Hạn chế**: Chỉ nhìn vào Average Latency sẽ lầm tưởng Adaptive kém nhất. Cần phải đối chiếu đồng thời với Error Rate.

## 2. Tail Latency Comparison (Chart 02)
*   **Mục đích**: Phân tích độ trễ ở các phân vị 50% (P50), 95% (P95), và 99% (P99).
*   **Nguồn dữ liệu**: Các cột `p50`, `p95`, `p99` từ file aggregate.
*   **Phương pháp tính**: Là trung bình cộng phân vị của 3 runs độc lập (Mean of Run-level Percentiles).
*   **Cách đọc biểu đồ**: **[LOGARITHMIC SCALE]** Trục Y sử dụng thang đo Logarit cơ số 10. Khoảng cách giữa 1.000ms và 10.000ms trên biểu đồ chỉ bằng khoảng cách từ 100ms lên 1.000ms. Giá trị nhãn trên cột là giá trị thực tế (không biến đổi).
*   **Ý nghĩa**: P99 của Adaptive lên tới gần 15.000ms. Thể hiện giới hạn cực đại (worst-case scenario) mà request phải chờ đợi trước khi timeout.

## 3. Error Rate Comparison (Chart 03)
*   **Mục đích**: So sánh tỷ lệ ném ngoại lệ / lỗi (Connection Refused, Timeout) của các thuật toán.
*   **Nguồn dữ liệu**: Cột `error_rate_pooled`.
*   **Phương pháp tính**: Tính theo kiểu Pooled: `Total Errors của 3 run / Total Samples của 3 run * 100`.
*   **Ý nghĩa đối với Adaptive**: Adaptive có **Error Rate thấp nhất**. Mặc dù bị chậm (ở Chart 01), nhưng Adaptive cứu sống được lượng lớn request không bị rớt mạng. Đây là minh chứng sắc bén nhất cho luận điểm "Sự hi sinh tốc độ để lấy sự sống còn" (Survival over Speed) ở trạng thái sụp đổ.

## 4. Throughput & Goodput Comparison (Chart 04)
*   **Mục đích**: Đánh giá năng lực xử lý Request Per Second (RPS).
*   **Metric sử dụng**: 
    *   `Throughput`: Tổng số RPS bơm vào.
    *   `Goodput`: Số lượng RPS thành công (Success RPS). Tính bằng `Throughput * (100 - Error Rate)`.
*   **Ý nghĩa đối với Adaptive**: Round Robin có Throughput (Total) rất cao nhưng chủ yếu là "xử lý lỗi rất nhanh". Cần quan sát cột Goodput để thấy hiệu năng thực thụ.

## 5. Performance Stability (Chart 05)
*   **Mục đích**: Đánh giá độ ổn định giữa các lần chạy.
*   **Dạng biểu đồ**: Bar Chart (thể hiện Mean) + Error Bar (thể hiện Standard Deviation).
*   **Cách đọc biểu đồ**: Vạch đen (Error bar) càng ngắn, thuật toán chạy càng ổn định qua nhiều lượt.

## 6. Run-to-Run Latency Variability (Chart 06)
*   **Mục đích**: Thể hiện sự phân tán Latency cụ thể của từng lần chạy.
*   **Dữ liệu**: Box plot rải 12 điểm dữ liệu từ file `01_stress_test_run_level.csv`.
*   **Hạn chế khi diễn giải**: Do kích thước mẫu (Sample size) chỉ là N=3 cho mỗi thuật toán, biểu đồ Box plot ở đây mang tính chất trực quan hóa vị trí điểm hơn là phân phối thống kê chuẩn (Distribution).

## 7. Relative Performance Difference (Chart 07)
*   **Mục đích**: Trực quan hóa % chênh lệch của Adaptive Load Balancer so với các thuật toán Baseline.
*   **Phương pháp tính**: `(Baseline - Adaptive) / Baseline * 100`.
    *   `Màu Xanh (+)`: Adaptive TỐT HƠN baseline (Ví dụ: Error rate thấp hơn).
    *   `Màu Đỏ (-)`: Adaptive KÉM HƠN baseline (Ví dụ: Latency cao hơn, bị âm).
*   **Ý nghĩa**: Một biểu đồ 2 chiều phơi bày sòng phẳng các khuyết điểm (màu đỏ) và ưu điểm (màu xanh) của thuật toán đề xuất, đảm bảo tính trung thực khoa học của Luận văn.

---
### 📌 Tái tạo Biểu đồ
Chạy lệnh sau tại thư mục `scripts/`:
```bash
python generate_stress_test_visualizations.py
```
