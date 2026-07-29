# BÁO CÁO PHÂN TÍCH ABLATION STUDY (Medium Load - 600 RPS)
*Ngày phân tích: 24/07/2026*

## 1. MỤC TIÊU
Phân tích độc lập tác động của từng thành phần (component) trong thuật toán Adaptive Load Balancing thông qua kỹ thuật loại bỏ (Ablation). Bộ số liệu được đối chiếu trực tiếp với `adaptive_full` (Baseline 0%).

## 2. PHÂN TÍCH TỪNG BIẾN THỂ

### 2.1 Adaptive Fixed Weights
- **Tác động:** Giữ nguyên trọng số AHP, không dùng Entropy Weight Method (EWM) để học trọng số động.
- **Latency:** Avg giảm 3.80%, P95 giảm 1.17%, P99 giảm 0.88%. 
- **Error & Goodput:** Error Rate 0.00%. Goodput giảm 2.01% (544.6 so với 555.8 RPS).
- **Kết luận (Low Impact):** Việc cố định trọng số bất ngờ mang lại độ trễ vi mô thấp hơn một chút ở mức tải Medium. Giải thích hợp lý là EWM tốn chi phí CPU tính toán tại Gateway, tạo ra "overhead" nhỏ. Tuy nhiên, Fixed Weights khiến thông lượng tổng (Goodput) bị hao hụt ~11 RPS do không linh hoạt chuyển hướng kịp thời khi CPU bão hòa ngầm.

### 2.2 Adaptive No Capacity
- **Tác động:** Không dùng sức mạnh thiết kế (vCPU/RAM limit) làm trọng số phân bổ, coi mọi node ngang hàng.
- **Latency & Goodput:** Avg tăng 7.15%, P95 tăng 5.74%. Goodput giảm 1.94%.
- **Kết luận (Low Impact):** Ở mức tải 600 RPS, các node yếu vẫn chưa kiệt sức hoàn toàn, nên việc chia đều (thay vì chia theo tỉ lệ cấu hình) chỉ gây ra sự chậm trễ nhẹ (P95 tăng ~77ms) mà không gây sập lỗi mạng.

### 2.3 Adaptive No EWMA Latency
- **Tác động:** Bỏ bộ lọc trung bình trượt hàm mũ EWMA đối với số liệu độ trễ từ backend, sử dụng Raw Latency để chấm điểm MCDM.
- **Latency & Error:** Avg tăng 13.54%, P95 tăng 15.02%. Error Rate xuất hiện (0.13%). Stability bị phá vỡ (Độ lệch chuẩn Std tăng **+304.9%**).
- **Kết luận (Moderately Important):** Bộ lọc EWMA là màng chắn cốt lõi chống lại nhiễu vi mô (Jitter/Garbage Collection). Khi tắt EWMA, thuật toán bị "giật cục", định tuyến liên tục chuyển đổi khiến hiệu năng hệ thống dao động cực mạnh.

### 2.4 Adaptive No Score EMA
- **Tác động:** Bỏ bộ lọc hàm mũ làm mượt tổng điểm MCDM Routing Cost.
- **Latency & Stability:** Avg tăng 7.44%, P95 tăng 11.61%. Độ lệch chuẩn Std tăng **+493.3%**.
- **Kết luận (Moderately Important):** Tương tự EWMA Latency, Score EMA đóng vai trò kìm hãm sự thay đổi điểm số. Loại bỏ nó khiến Data Plane chao đảo, chứng minh thiết kế "Smoothing" ở nhiều tầng là điều kiện bắt buộc.

### 2.5 Adaptive No Low Load RR
- **Tác động:** Không ép thuật toán dùng Round Robin ở mức tải cực thấp (warmup), mà dùng MCDM ngay từ đầu.
- **Latency:** P95 tăng kỷ lục **23.38%** (vọt lên 1660ms). 
- **Kết luận (Moderately Important):** Điểm số MCDM vô giá trị khi chưa có Request thực tế. Nếu không có cơ chế Fallback sang Round Robin lúc rảnh rỗi, hiệu ứng bầy đàn lập tức xảy ra do mọi request dồn vào node vừa khởi động đầu tiên.

### 2.6 Adaptive No P2C (Power of Two Choices)
- **Tác động:** Data Plane duyệt O(N) tìm node có điểm tốt nhất tuyệt đối để định tuyến.
- **Error Rate:** Tăng vọt lên **1.96%** (Mức LỖI CAO NHẤT trong toàn bộ các cấu hình Ablation!).
- **Kết luận (Highly Important for Resilience):** Thuật toán O(N) tìm Min Cost là con dao hai lưỡi. Dữ liệu thực nghiệm chứng minh nó sinh ra "Thundering Herd": hàng trăm request lao vào node tốt nhất cùng một lúc mili-giây, bóp nghẹt node đó và gây ra lỗi 503/Timeout cục bộ. P2C đã chứng minh giá trị tuyệt đối trong việc phân tán rủi ro.

### 2.7 Adaptive No Probe
- **Tác động:** Bỏ cơ chế "Thăm dò phục hồi" (Probe) đối với các node bị phạt cách ly.
- **Latency:** P95 tăng **26.32%**, P99 tăng **16.13%**, Stability hỏng (**+898%**).
- **Kết luận (Highly Important for Tail Latency):** Khi một node bị chậm và bị đưa vào danh sách đen, nếu không có request "chim mồi" (Probe) để kiểm tra nó phục hồi chưa, nó sẽ bị cô lập vĩnh viễn hoặc hồi phục ồ ạt. Hậu quả là các node còn lại phải "gánh" toàn bộ tải (làm P95 tăng 354ms).

### 2.8 Adaptive No PID
- **Tác động:** Bỏ bộ điều khiển phản hồi PID (Không có Penalty Cost khi độ trễ tăng nhanh).
- **Latency & Stability:** P99 tăng mạnh nhất **+17.79%** (đạt 3306ms), Avg tăng 15.97%. Độ lệch chuẩn Std tăng khổng lồ **+1015%**.
- **Kết luận (Most Critical Component):** PID là trái tim của cơ chế chống "Hàng xóm ồn ào". Khi bỏ hàm phạt PID, MCDM đơn thuần phản ứng quá chậm trước sự cố. Yêu cầu vẫn bị nhồi vào node đang suy thoái ngầm, khiến Tail Latency (P99) bùng nổ vô kiểm soát. 
