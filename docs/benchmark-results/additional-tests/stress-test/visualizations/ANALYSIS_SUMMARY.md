# Báo Cáo Phân Tích Kịch Bản Siêu Tải (Stress Test - 1200 RPS)
*Dữ liệu dựa trên bộ Stress Test Final Dataset (Rebuilt 2026-07-22)*

## 1. Mục Tiêu Kiểm Thử
Kịch bản 1200 RPS nhằm đẩy hệ thống vượt qua giới hạn chịu đựng lý thuyết, kết hợp với Chaos Engineering (giả lập backend chậm ngẫu nhiên). Mục tiêu là đánh giá chiến lược **Graceful Degradation** của thuật toán Adaptive so với các Baseline (Least Connections, Random, Round Robin).

## 2. Validation & Data Integrity
Dữ liệu được thu thập trên 12 runs (3 runs x 4 strategies). Tổng số requests được ghi nhận là ~1.7 triệu mẫu.
Mọi chỉ số đều tuân thủ nguyên tắc `P50 < P95 < P99` và độ lệch chuẩn nằm trong mức kiểm soát. 

## 3. Đánh Giá Các Chỉ Số Cốt Lõi

### 3.1. Tỷ Lệ Lỗi (Error Rate) và Tính Sống Còn
- **Round Robin / Random**: Thất bại nặng nề với **43.27%** và **27.71%** lỗi.
- **Least Connections**: Cố gắng sống sót với tỷ lệ lỗi **0.33%**.
- **Adaptive Load Balancer**: Dẫn đầu tuyệt đối với tỷ lệ lỗi chỉ **0.06%**.

### 3.2. Độ Trễ (Latency)
- **Average Latency**: Adaptive ghi nhận mức trung bình **530ms**, nhanh hơn 44% so với Least Connect (949ms) và vượt trội so với Random/Round Robin (2255 - 3695ms).
- **Tail Latency (P99)**: Mặc dù hệ thống bị quá tải, P99 của Adaptive được kìm hãm ở mức **3.89s**. Trong khi đó, Least Connect đẩy P99 lên tới **10.1s**. 
- *Kết luận*: Adaptive đã khéo léo né các backend bị đóng băng, tránh việc request bị treo vô thời hạn (nguyên nhân gây ra mức P99 10s của Least Connect).

### 3.3. Thông lượng Thành công (Goodput)
- Adaptive tiếp nhận và trả về thành công **763.0 RPS**.
- Least Connect theo sát ở mức **732.1 RPS**.
- Round Robin và Random bị tụt dốc thảm hại (chỉ còn **422 - 440 RPS**) do 30-40% số yêu cầu trả về lỗi.

## 4. Kiểm tra giả thuyết Graceful Degradation
Giả thuyết: *"Adaptive Load Balancing chủ động điều tiết việc phân phối tải để giảm tỷ lệ lỗi trong điều kiện quá tải, chấp nhận một phần chi phí về latency"*.
Thực tế: Dữ liệu **cực kỳ ủng hộ** giả thuyết này, thậm chí còn tích cực hơn:
- Adaptive chấp nhận việc độ trễ P99 tăng lên 3.8s (đó là "chi phí" của Graceful Degradation trong lúc Chaos kích hoạt).
- Đổi lại, hệ thống không hề bị treo cứng như Round Robin (lỗi 43%).
- Và quan trọng nhất: Adaptive vượt qua được cái bẫy "Hold and Wait" của Least Connect (nơi request bị giữ lại 10s ở backend lỗi).

## 5. Tính Ổn Định
Độ lệch chuẩn (Std Dev) giữa các lần chạy (Run-to-run variability):
- Adaptive: **~67ms** (Cực kỳ ổn định).
- Least Connect: **~481ms** (Nhiễu rất cao do nhạy cảm với thời điểm Chaos).
Thuật toán Adaptive chứng minh khả năng kháng nhiễu ưu việt nhờ cơ chế PID tự cân bằng.
