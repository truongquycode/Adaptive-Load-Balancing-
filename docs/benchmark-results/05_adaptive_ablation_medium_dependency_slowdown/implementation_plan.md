# Kế hoạch triển khai: Pipeline xử lý dữ liệu Adaptive Ablation

## Mục tiêu
Tự động hóa toàn bộ quy trình xử lý, trực quan hóa và lập báo cáo học thuật cho 24 runs (8 kịch bản Ablation) từ thư mục `05_adaptive_ablation_medium_dependency_slowdown`, đối chiếu với `adaptive_full` từ kịch bản Medium (02).

## Phương pháp tiếp cận

### 1. Khai thác dữ liệu Baseline (`adaptive_full`)
Vì thư mục Ablation chỉ chứa 8 kịch bản cắt bỏ, `adaptive_full` (làm mốc 0%) sẽ được trích xuất tự động từ file `jtl-summary.csv` và `aggregate-summary-clean.csv` của kịch bản **02_medium_chaos_dependency_slowdown** (đã chạy trước đó). Điều này đảm bảo tính nhất quán (cùng workload 600 RPS, cùng Chaos).

### 2. Viết Parser Script (`process_adaptive_ablation_results.py`)
- **Đầu vào**: Thư mục `benchmark-raw-results/05_adaptive_ablation...` và thư mục `official-runs/.../02-medium.../data`.
- **Thực thi**:
  - Quét 8 thư mục cấu hình, parse 24 tệp JTL (tuân thủ nguyên tắc `MEASURE_` filter, tính Latency, Throughput, Error Rate).
  - Ghép chung 3 runs của `adaptive_full` từ CSV cũ vào.
  - Tính toán Goodput = `Throughput * (1 - Error Rate)`.
- **Đầu ra**: Xuất các file CSV: `run_level_results.csv`, `aggregate_results.csv`, `percentiles.csv`, `throughput.csv` tại `docs/benchmark-results/05_adaptive_ablation_medium_dependency_slowdown/data/`.

### 3. Viết Visualizer Script (`generate_ablation_visualizations.py`)
- Đọc các tệp CSV vừa sinh.
- Vẽ 9 biểu đồ (PNG & SVG, 300 DPI) bằng Seaborn:
  - Fig 01-03: Avg Latency, P95, P99.
  - Fig 04-06: Throughput, Error Rate, Goodput.
  - Fig 07-08: Stability (Error bars) và Latency Distribution (Boxplot).
  - Fig 09: Relative Difference (% thay đổi so với `adaptive_full` = 0).

### 4. Tự động Tổng hợp và Sinh Báo Cáo
- Tôi (AI Agent) sẽ đọc kết quả từ `aggregate_results.csv` sau khi Parser chạy xong.
- Dựa trên số liệu, tôi sẽ trực tiếp sinh 3 file Markdown:
  - `ANALYSIS_SUMMARY.md`: Trả lời 7 câu hỏi về tác động của từng module bị cắt.
  - `THESIS_ANALYSIS.md`: Xếp hạng tầm quan trọng của các module (PID, EWMA, P2C, v.v.).
  - `README.md`: Báo cáo tổng thể Dataset.

## Yêu cầu người dùng (User Review Required)
> [!IMPORTANT]
> 1. Baseline `adaptive_full` được tôi lấy từ bài chạy **02_medium_chaos_dependency_slowdown**, điều này có phù hợp với giả định của bạn không? (Vì Ablation được tiêm Chaos mức Medium).
> 2. Các file phân tích Markdown (như `THESIS_ANALYSIS.md`) sẽ do tôi (AI) phân tích tự động dựa trên CSV sau khi chạy Python và viết bằng Markdown (chứ không phải lập trình Python in ra Markdown) để đảm bảo văn phong luận văn. Bạn đồng ý với quy trình này chứ?

Hãy duyệt (Proceed) để tôi bắt đầu lập trình Python và chạy Pipeline ngay lập tức.
