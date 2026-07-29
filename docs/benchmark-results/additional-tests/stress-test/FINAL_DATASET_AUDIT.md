# Final Dataset Audit Report
*Tiến hành bởi Đánh giá viên Độc lập (AI)*

## 1. Dataset Identity
- **Đường dẫn**: `D:\eclipse-workspace\adaptive-load-balancer-parent\benchmark-raw-results\additional-tests\stress-test-final`
- **Thời gian Audit**: 2026-07-22

## 2. Dataset Structure
Cấu trúc cây thư mục phản ánh chính xác 4 thuật toán, mỗi thuật toán có đúng 3 runs chứa các tệp: `.jtl`, `-metadata.txt`, và thư mục HTML report. Tổng số run là 12, không có duplicate hoặc run bị thiếu.
- **DATASET STRUCTURE: PASS**

## 3. Raw Data Validation
File báo cáo `validation-report.csv` khẳng định 12/12 tệp JTL đều đọc được, số lượng dòng dữ liệu hợp lệ (`MEASURE_`) dao động từ 418,125 đến 574,037 dòng.
- **RAW DATA VALIDATION: PASS**

## 4. Parser Methodology Audit
Script `process_stress_test_results.py` tuân thủ nghiêm ngặt phương pháp đo lường khoa học:
- **Lọc Request**: Chỉ đọc các row bắt đầu bằng `MEASURE_`. Bỏ qua Setup/Discard hoàn toàn.
- **Thời gian (Duration)**: Tính bằng hiệu số `(Max(timestamp) - Min(timestamp)) / 1000`. Cực kỳ chuẩn xác.
- **Thông lượng (Throughput)**: = `Tổng request hợp lệ / Duration`.
- **Tỷ lệ lỗi (Error Rate)**: Tính trực tiếp bằng `Tổng số lỗi / Tổng request hợp lệ`.
- **PARSER METHODOLOGY: PASS**

## 5. 12-Run Cross-Validation
- Adaptive: 3/3 Runs
- Least Connect: 3/3 Runs
- Random: 3/3 Runs
- Round Robin: 3/3 Runs
- **12-RUN CROSS-VALIDATION: PASS**

## 6. Aggregate Metric Audit
Tất cả các Aggregate metric (`mean` của Throughput, Latency, Percentiles) đều được tính trung bình chuẩn (Arithmetic mean) của 3 giá trị cấp run.
- **AGGREGATE METRIC AUDIT: PASS**

## 7. Error Rate Audit
Dữ liệu Parser cung cấp hai cột: `error_rate_mean` và `error_rate_pooled`.
- Script Visualization sử dụng `error_rate_pooled` cho biểu đồ (Chart 03). Điều này là hoàn toàn chính xác trong khoa học dữ liệu (Pooled error đại diện cho tổng lỗi / tổng request của toàn bộ tập mẫu 3 runs, triệt tiêu nhiễu tốt hơn mean của từng % error).
- **ERROR RATE AUDIT: PASS**

## 8. Goodput Audit
Goodput không được xuất trực tiếp từ Parser mà được tính toán phái sinh (Derived) trong khâu Visualization bằng công thức: `Throughput * (1 - Error Rate)`.
Về mặt toán học, phép nhân này sẽ cho ra chính xác số "Successful Requests Per Second", tương đương với Direct Measure.
- Định danh trong báo cáo: **DERIVED METRIC**.
- **GOODPUT AUDIT: PASS**

## 9. CSV Integrity Audit
Các tệp 01 đến 05 sinh ra không chứa giá trị `NaN`, `Inf` hay số âm. Các nguyên tắc thống kê (P50 < P95 < P99) được bảo toàn 100%. Mức độ cải thiện (Improvement) được tính toán trung thực, các giá trị kém hơn được hiển thị số âm (Regression).
- **CSV INTEGRITY AUDIT: PASS**

## 10. Visualization Integrity Audit
Toàn bộ 7 biểu đồ (PNG/SVG) ánh xạ chính xác cột trục, Legend và giá trị của bộ dữ liệu CSV. Scale log được dùng phù hợp ở Chart 02 (Tail Latency).
- **VISUALIZATION INTEGRITY: PASS**

## 11. Scientific Interpretation Audit
Các kết luận về thứ hạng của Adaptive (Tốt nhất về Error Rate, Goodput, Avg Latency, P99 và Độ ổn định) là các **FACT** (Sự thật có thể chứng minh từ dữ liệu), không phải phỏng đoán.
- **INTERPRETATION AUDIT: PASS**

## 12. Graceful Degradation Evidence
**Is Consistent With (Ủng hộ giả thuyết)**:
Dữ liệu chỉ ra rằng dưới điều kiện Chaos 1200 RPS, các Baseline tĩnh bị tê liệt (27%-43% lỗi), Baseline động (Least Connect) bị thắt cổ chai P99 (10.1 giây). Trong khi đó, Adaptive chấp nhận hy sinh một phần Latency (P99 = 3.8s) để duy trì tỷ lệ sống sót ở 99.94% (lỗi 0.06%). Bằng chứng này trực tiếp ủng hộ giả thuyết Graceful Degradation.

## 13. Known Limitations
Chưa có metric theo dõi trực tiếp việc "Node bị kích hoạt Chaos có trọng số (Weight) là bao nhiêu" trong suốt vòng đời test. Chúng ta chỉ đo lường kết quả cuối (End-to-End Metrics). Việc chứng minh động lực học (Internal Dynamics) chỉ nằm ở mức độ "Suggests" thay vì "Definitively Proves".

## 14. Final Audit Verdict
**VERDICT: PASS**
The final dataset is completely clean, mathematically consistent, and suitable for thesis analysis and Chapter 4 reporting. No re-runs or data alterations are required.
