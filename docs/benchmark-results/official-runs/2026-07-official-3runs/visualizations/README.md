# Benchmark Visualization

## 1. Mục đích
Bộ tài liệu và mã nguồn này được thiết kế nhằm xây dựng pipeline phân tích tự động, có tính lặp lại (reproducible) để trực quan hóa dữ liệu benchmark của đồ án Adaptive Load Balancer. Việc sử dụng pipeline giúp đảm bảo tính minh bạch, khách quan, và trung thực của số liệu trước khi đưa vào báo cáo/luận văn tốt nghiệp, đồng thời tạo ra các hình ảnh chất lượng cao.

## 2. Nguồn dữ liệu
- **Thư mục nguồn gốc:** `D:\eclipse-workspace\adaptive-load-balancer-parent\docs\benchmark-results\official-runs\2026-07-official-3runs`
- **Các kịch bản (Scenarios):** `01-low-load`, `02-medium-dependency-slowdown`, `03-high-dependency-slowdown`.
- **File dữ liệu chính:** `jtl-summary.csv` nằm trong thư mục `data/` của mỗi kịch bản. Đây là tệp tin chứa summary metrics của toàn bộ các lần chạy (runs).
- **Trạng thái file RAW:** Không sử dụng các tệp JTL gốc (raw request logs do kích thước quá lớn). Toàn bộ xử lý thống kê được xây dựng dựa trên file đã aggregate `jtl-summary.csv`.
- **Lưu ý:** Pipeline python cố tình bỏ qua tệp `aggregate-summary-clean.csv` có sẵn để tiến hành trích xuất lại dữ liệu trực tiếp nhằm đảm bảo không có sai lệch.

## 3. Phương pháp xử lý dữ liệu
Pipeline thực hiện tính toán lại toàn bộ metrics bằng Python Pandas theo các quy tắc sau:
- **Mean Avg Latency:** Arithmetic Mean của các giá trị Average Latency thu được từ 3 runs độc lập.
- **Mean Median:** Arithmetic Mean của P50/Median thu được từ 3 runs.
- **Mean P95/P99:** Arithmetic Mean của P95/P99 thu được từ 3 runs. Lưu ý, đây KHÔNG PHẢI là "Pooled P95" gộp chung toàn bộ requests.
- **Pooled Error Rate:** Được tính bằng `(Tổng lỗi của 3 runs / Tổng số requests của 3 runs) * 100`. Phương pháp này mang lại độ chính xác thống kê cao hơn so với trung bình cộng (Mean Error Rate) khi số request không đồng đều.
- **Mean Throughput:** Arithmetic Mean của Throughput từ 3 runs.
- **Goodput:** Khả năng xử lý thành công thực tế, tính bằng `(Tổng Samples - Tổng Errors) / Total Duration` cho mỗi run, sau đó lấy Mean Goodput.
- **Standard Deviation (Std Dev) & CV (Coefficient of Variation):** Được tính để đánh giá mức độ phân tán của độ trễ trung bình qua 3 lần chạy.

## 4. Danh sách biểu đồ
Bộ biểu đồ được sinh tự động tại thư mục `charts/`.
### Figure 01: Average Latency by Load
- **Tên file:** `fig_01_avg_latency_by_load` (.png/.svg)
- **Loại biểu đồ:** Grouped Bar Chart
- **Metrics:** Mean Avg Latency (ms) theo từng mức tải (Low, Medium, High).
- **Ý nghĩa:** Trực quan hóa độ trễ phản hồi trung bình của hệ thống khi mức tải thay đổi.
- **Cách diễn giải:** Cột càng thấp, thuật toán đó càng có tốc độ xử lý nhanh. Dễ dàng thấy sự chênh lệch lớn giữa các chiến lược ở mức tải High.

### Figure 02: P95 Latency
- **Tên file:** `fig_02_p95_latency` (.png/.svg)
- **Loại biểu đồ:** Grouped Bar Chart
- **Metrics:** Mean P95 Latency (ms) qua 3 runs.
- **Ý nghĩa:** Thể hiện "tail latency" - độ trễ của 5% lượng requests tồi tệ nhất.
- **Giới hạn:** Do không có dữ liệu jtl thô, con số này biểu thị trị số trung bình của 3 phân vị (không phải phân vị gốc của nhóm gộp).

### Figure 03: P99 Latency
- **Tên file:** `fig_03_p99_latency` (.png/.svg)
- **Loại biểu đồ:** Grouped Bar Chart
- **Metrics:** Mean P99 Latency (ms) qua 3 runs.
- **Ý nghĩa:** Thể hiện "extreme tail latency", độ trễ của 1% requests chậm nhất.

### Figure 04: Error Rate by Load
- **Tên file:** `fig_04_error_rate_by_load` (.png/.svg)
- **Loại biểu đồ:** Line Chart (Symlog Scale)
- **Metrics:** Pooled Error Rate (%)
- **Ý nghĩa:** Biểu diễn khả năng duy trì độ tin cậy và ngăn ngừa hiện tượng từ chối dịch vụ khi tải tăng cường.
- **Cách diễn giải:** Sử dụng thang Logarit (Symlog) do sự khác biệt giữa các hệ số lỗi rất lớn (Ví dụ: 0.03% vs 26%).

### Figure 05: Throughput vs Goodput
- **Tên file:** `fig_05_throughput_vs_goodput` (.png/.svg)
- **Loại biểu đồ:** Grouped Bar Chart
- **Metrics:** Mean Throughput và Mean Goodput ở riêng kịch bản High Load 900 RPS.
- **Ý nghĩa:** Khẳng định rằng "Xử lý nhiều request hơn không đồng nghĩa với phục vụ thành công nhiều hơn".
- **Cách diễn giải:** Khoảng cách giữa 2 cột càng nhỏ tức là tỉ lệ request hỏng càng ít.

### Figure 06: Adaptive Improvement
- **Tên file:** `fig_06_adaptive_improvement` (.png/.svg)
- **Loại biểu đồ:** Horizontal Bar Chart
- **Metrics:** Phần trăm cải thiện của Adaptive so với Random và Round Robin tại High Load.
- **Công thức:** `(Baseline - Adaptive) / Baseline * 100` (Đối với Latency & Error).
- **Ý nghĩa:** Đúc kết lại sức mạnh của Adaptive bằng chỉ số %. 

### Figure 07: Stability Mean ± Standard Deviation
- **Tên file:** `fig_07_stability_mean_std` (.png/.svg)
- **Loại biểu đồ:** Error Bar Chart
- **Metrics:** Mean Avg Latency đi kèm thanh sai số chuẩn (Standard Deviation) và hệ số phân tán (CV).
- **Ý nghĩa:** Đo lường sự đồng nhất và tin cậy của thuật toán qua các lần thử nghiệm ngẫu nhiên. Thanh error bar càng nhỏ, thuật toán càng ổn định.

## 5. Lưu ý thống kê bắt buộc
- **Không phải Pooled Percentile:** Mean P95, Mean P99 và Mean Median không phải là Percentile trên mẫu tổng (gộp chung toàn bộ 3 run).
- **Phương pháp tính Goodput:** Tính bằng công thức cơ bản và trực quan từ Duration được log của từng scenario.
- **Mẫu thống kê:** Số lượng mẫu N=3 chỉ mang tính chất thể hiện (illustrative) xu hướng của sự phân tán, không đủ lớn để làm cơ sở cho các phép kiểm định t-test hoặc ANOVA. 

## 6. Đề xuất sử dụng trong luận văn
- **Figure 01, 04:** Lý tưởng để chèn vào phần đánh giá tổng quan, cho phép hội đồng giám khảo dễ dàng nắm bắt bức tranh toàn cảnh sức chịu tải của hệ thống.
- **Figure 06:** Nên chèn vào chương "Đánh giá Hiệu năng Adaptive Load Balancer", vì biểu đồ này thu hút sự chú ý vào các % thành tựu đạt được.
- **Figure 05:** Cực kỳ quan trọng để bảo vệ quan điểm thuật toán tĩnh như Round Robin có thể "nhỉnh hơn" ở TPS nhưng hoàn toàn thua thiệt ở lượng request thành công.
- **Figure 07:** Phù hợp với đoạn bình luận về tính bền bỉ và ổn định của kiến trúc tự sửa chữa.

## 7. Hạn chế của phân tích
- Dữ liệu ở kịch bản Low Load chưa cho thấy được ưu thế của hệ thống phức tạp, có lúc overhead của quá trình tính toán MCDM làm độ trễ cao hơn Random đôi chút. Điều này là hoàn toàn bình thường theo học thuật và đã được giữ nguyên để bảo đảm tính liêm chính.
