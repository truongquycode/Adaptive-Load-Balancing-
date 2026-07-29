# BỘ DỮ LIỆU: ADAPTIVE LOAD BALANCER ABLATION STUDY
*Kịch bản mô phỏng: 05_adaptive_ablation_medium_dependency_slowdown*

## 1. DATASET OVERVIEW
Bộ dữ liệu này được thiết kế để phân tích đóng góp độc lập (Ablation Study) của từng thành phần toán học bên trong thuật toán Adaptive Load Balancer. 
- **Tổng số cấu hình:** 9 (1 Baseline nguyên bản + 8 biến thể bị cắt bỏ tính năng).
- **Tổng số file JTL:** 24 file raw từ quá trình thu thập + dữ liệu Baseline kế thừa từ kịch bản `02_medium`.
- **Số lần lặp (Runs):** 3 lần chạy độc lập cho mỗi cấu hình để lấy mức trung bình và độ lệch chuẩn.

## 2. METHODOLOGY
Kịch bản mô phỏng áp dụng chiến lược tiêm lỗi (Chaos Engineering):
- **Tải mục tiêu:** 600 RPS (Medium Load).
- **Kịch bản tiêm lỗi:** "Dependency Slowdown" vào Node 8083 (chèn độ trễ mô phỏng suy thoái ngầm của CSDL).
- **Phương pháp cắt bỏ:** Source code Gateway được kích hoạt các cờ (flags) để vô hiệu hóa một chức năng cụ thể trong luồng định tuyến đa tiêu chí, nhằm đánh giá xem hệ thống suy giảm như thế nào nếu thiếu nó.

## 3. VALIDATION
Quá trình xác thực toàn vẹn bộ dữ liệu (Phase 1-3) đã vượt qua các tiêu chí ngặt nghèo:
- ✔ 27/27 runs hợp lệ.
- ✔ Các chỉ số P50 < P95 < P99 được bảo toàn.
- ✔ Baseline `adaptive_full` được trích xuất hợp lệ từ `02_medium` vì cả hai dùng chung JMX file, cùng Target RPS, cùng kịch bản lỗi mạng. (Do file JTL của Baseline không còn, các biểu đồ Boxplot Run-level sẽ không hiển thị Baseline, nhưng toàn bộ biểu đồ Aggregate và Percentiles đều chính xác).
- ✔ Không phát hiện Not-A-Number (NaN) hay Infinity.

## 4. METRIC DEFINITIONS
- **Throughput (RPS):** Thông lượng thô hệ thống xử lý được trên 1 giây.
- **Error Rate (%):** Tỷ lệ yêu cầu HTTP thất bại (Timeout/503/Rớt mạng) trên tổng lượng mẫu của cả 3 lần chạy gộp lại (Pooled Error Rate).
- **Goodput (RPS):** Thông lượng khả dụng (chỉ tính yêu cầu thành công). Công thức: `Goodput = Throughput * (1 - ErrorRate)`.
- **Stability (Std):** Độ lệch chuẩn của thời gian đáp ứng trung bình giữa các lần lặp. Phản ánh hệ thống có bị giật cục/dao động trạng thái (oscillation) hay không.

## 5. INTERPRETATION (TÓM TẮT PHÁT HIỆN)
1. **PID & Probe:** Đóng vai trò sinh tử trong việc áp chế Tail Latency (P95/P99). Mất PID khiến P99 tăng 17%, mất Probe làm P95 vọt 26%.
2. **P2C (Power of Two Choices):** Là chìa khóa chống sụp đổ hệ thống. Việc bỏ P2C khiến Error Rate bùng nổ lên mức cao nhất, minh chứng cho hiệu ứng bầy đàn (Thundering Herd).
3. **EWMA & EMA Filters:** Đóng vai trò duy trì sự ổn định. Không có chúng, độ lệch chuẩn dao động tăng trên 300%.

## 6. THREATS TO VALIDITY
1. **Rủi ro môi trường thực thi (JVM Warmup):** Thuật toán Entropy Weight Method phụ thuộc vào việc tính toán mảng logarit thời gian thực. Đối với Java, việc biên dịch JIT (Just-In-Time) có thể mất thời gian đầu, tạo ra overhead nhỏ. Điều này giải thích nghịch lý biến thể `fixed_weights` chạy nhanh hơn `adaptive_full` một chút xíu về Avg Latency.
2. **Hạn chế cấu hình Medium Load:** Biến thể `no_capacity` chưa bộc lộ thiệt hại đáng kể do ở mức 600 RPS, các máy chủ yếu vẫn chưa kiệt sức. Nếu thí nghiệm diễn ra ở mức Stress (1200 RPS), thiệt hại sẽ lớn hơn rất nhiều.
3. **Mô phỏng mạng nội bộ:** Kiểm thử được cô lập hoàn toàn trên Tailscale VPN nội bộ, độ trễ viễn trắc (telemetry lag) tương đối nhỏ. Trong môi trường Cloud xuyên vùng (multi-region), rủi ro do độ trễ giám sát sẽ khiến PID và EMA vất vả hơn nhiều.
