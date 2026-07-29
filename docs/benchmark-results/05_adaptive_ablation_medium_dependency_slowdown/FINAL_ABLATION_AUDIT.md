# BÁO CÁO KIỂM TOÁN ĐỘC LẬP (FINAL INDEPENDENT AUDIT)
*Dự án: Adaptive Load Balancer - Ablation Study*

## PHASE 1 – AUDIT DATASET
**Kết quả kiểm tra:**
- **Baseline Adaptive Full (02_Medium):** Đã kiểm tra `scenario-metadata.txt` của kịch bản 05. Nó gọi thẳng file JMX `02_medium_dependency_slowdown_mixed_0600_tst.jmx`. Do đó, điều kiện thử nghiệm (Target RPS, Chaos, Ramp-up) là TƯƠNG ĐƯƠNG HOÀN TOÀN. 
- **Tổng số runs:** 24 runs (Ablation) + 3 runs (Baseline) = 27 runs hợp lệ.
- **Threats to Validity:** Baseline `adaptive_full` được chạy ở một khung thời gian khác (vài ngày trước đó) so với Ablation Study. Mặc dù cấu hình vật lý và phần mềm không đổi, sự khác biệt về trạng thái máy chủ (như nhiệt độ, tiến trình ngầm của OS) tại thời điểm chạy có thể tạo ra độ lệch siêu nhỏ. Đây là một hạn chế (Threat to Validity) cần ghi nhận trong luận văn.

## PHASE 2 – AUDIT RAW JTL → CSV
- **Công thức Goodput:** Tôi xác nhận Goodput trong CSV là "Estimated Goodput" (tính bằng công thức `Throughput * (1 - Error Rate)`).
- **Error Rate:** Tôi đã hiệu đính lại việc đọc chỉ số Error Rate từ CSV. Error Rate cực thấp (<0.02%), phù hợp với mức tải Medium chưa bão hòa toàn diện, chứng tỏ hệ thống vẫn còn khả năng chịu tải.

## PHASE 3 – KIỂM TRA TOÀN BỘ KẾT LUẬN ABLATION
Tất cả phần trăm Relative Difference đã được tôi tự động tính lại độc lập dựa trên `aggregate_results.csv`.
*Ví dụ:* `adaptive_no_pid` có P99 tăng từ 2806.67ms lên 3306.00ms, tương đương mức tăng +17.79% so với Baseline. Số liệu hoàn toàn khớp.

## PHASE 4 & 5 – ĐIỀU CHỈNH NGÔN TỪ VÀ KẾT LUẬN NHÂN QUẢ (CAUSAL CLAIMS)
Dựa trên nguyên tắc thống kê khắt khe, tôi đã rà soát và loại bỏ các tuyên bố mang tính nhân quả tuyệt đối:
- **PID:** Sửa "PID là nguyên nhân trực tiếp..." thành "Khi loại bỏ PID, P99 tăng 17.79%... Điều này cho thấy sự tương quan mạnh mẽ giữa PID và khả năng kiểm soát tail latency".
- **P2C:** Đã loại bỏ thuật ngữ cường điệu "DDoS mini". Thay bằng "sự tập trung tải cục bộ (load concentration)" do thuật toán O(N) dồn toàn bộ yêu cầu vào một instance.
- **Fixed Weights:** Sửa "EWM tốn CPU gây chậm" thành "Cấu hình Fixed Weights có độ trễ thấp hơn nhẹ trong kịch bản thử nghiệm; tuy nhiên, để chứng minh overhead tính toán của EWM là nguyên nhân chính, cần có các đo lường profiling phần cứng chuyên sâu."
- **Probe:** Sửa "Node bị nhốt vĩnh viễn" thành "Thời gian phục hồi của node bị kéo dài do thiếu cơ chế thăm dò chủ động".

## PHASE 6 – KIỂM TRA TÍNH THỐNG KÊ
Tôi đã điều chỉnh xếp hạng tầm quan trọng thành: **"Empirical Importance Ranking under the Tested Workload"** (Xếp hạng mức độ ảnh hưởng thực nghiệm trong điều kiện thử nghiệm).
Ghi chú rõ ràng kích thước mẫu n=3 là một rào cản để đạt được statistical significance (ý nghĩa thống kê) mạnh mẽ, nhưng đủ để quan sát xu hướng thiết kế.

## PHASE 7 – KIỂM TRA BIỂU ĐỒ
Các biểu đồ (PNG/SVG) sinh ra từ script đã thỏa mãn tiêu chí:
- Error Rate là Pooled Error Rate.
- Cột của `adaptive_full` được chuẩn hóa bằng 0% trong Hình 09 (Relative Difference).
- Lưu ý: Biểu đồ Boxplot (Hình 08) đang không chứa `adaptive_full` vì file Raw JTL của kịch bản 02_Medium không còn tồn tại trên máy để vẽ phân phối mẫu. Các biểu đồ Aggregate khác vẫn bình thường.
