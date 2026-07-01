# Threats to Validity

## 1. Mục đích

Tài liệu này liệt kê các giới hạn khi diễn giải kết quả benchmark của dự án. Đây là phần nên có trong luận văn để tránh bị phản biện rằng kết quả được trình bày quá rộng so với phạm vi thực nghiệm.

---

## 2. Internal validity

### 2.1. State giữa các run

Rủi ro:

- EWMA/PID/window/cache còn state từ run trước;
- chaos chưa reset hết;
- backend còn request chậm chưa hoàn thành.

Giảm thiểu:

- script gọi `POST /actuator/alb/reset`;
- script gọi `POST /api/chaos/reset` trên 8081/8082/8083;
- có `WAIT_AFTER_RESET`;
- mỗi run có metadata.

### 2.2. Gắn nhãn sai strategy

Rủi ro:

- tên folder kết quả là adaptive nhưng server đang chạy strategy khác;
- CI/CD deploy chưa xong.

Giảm thiểu:

- script strict check `GET /actuator/alb/strategy`;
- nếu không khớp thì dừng benchmark.

### 2.3. Thứ tự chạy gây bias

Rủi ro:

- strategy chạy sau có cache/JVM/server nóng hơn;
- CPU nhiệt hoặc network thay đổi theo thời gian.

Giảm thiểu:

- script randomize thứ tự strategy;
- chạy nhiều lần;
- ghi metadata thứ tự thực tế.

---

## 3. Construct validity

### 3.1. Latency metric

Rủi ro:

- `MetricsPoller` dùng delta-average latency, không phải p95/p99 raw per backend;
- `SlidingWindowManager` xây percentile từ sample polling.

Giảm thiểu:

- báo cáo Gateway latency từ JMeter/Prometheus;
- không nói backend percentile là per-request percentile tuyệt đối;
- đọc kèm p95/p99 từ JMeter.

### 3.2. CPU metric

Rủi ro:

- `process.cpu.usage` có thể khác semantics khi chạy trong container;
- CPU normalized by capacity không phản ánh DB/network/memory bottleneck.

Giảm thiểu:

- dùng cAdvisor để đối chiếu CPU/memory;
- dùng capacity ablation;
- ghi rõ capacity weight là giả định theo CPU quota.

### 3.3. Synthetic workload

Rủi ro:

- không có database thật;
- dependency slowdown là sleep/DB hold mô phỏng;
- request không đại diện đầy đủ cho ứng dụng production.

Giảm thiểu:

- gọi rõ đây là synthetic workload service;
- không kết luận vượt quá phạm vi mô phỏng;
- nếu có thời gian, thêm real DB/external service trong future work.

---

## 4. External validity

Kết quả hiện chỉ áp dụng chắc chắn cho môi trường kiểm thử:

- Spring Boot Gateway + Eureka;
- 3 backend;
- Docker Compose;
- CPU quota 2.0/1.5/1.0;
- workload mixed 60/25/12/3;
- chaos dependency/latency mô phỏng;
- Prometheus scrape interval 5s;
- JMeter client và server cụ thể.

Không nên kết luận rằng Adaptive sẽ tốt hơn mọi thuật toán trong mọi hệ thống microservices.

---

## 5. Conclusion validity

Rủi ro:

- chỉ lấy một run đẹp nhất;
- không tính std/CI;
- không tách warmup/baseline/chaos/recovery;
- chỉ nhìn latency 200 mà bỏ qua error rate;
- actual RPS thấp hơn target RPS.

Giảm thiểu:

- chạy ít nhất 5 run mỗi strategy;
- dùng `summarize_jtl_results.py`;
- báo cáo mean/median/std/CI nếu có;
- tách phase khi phân tích;
- đọc kèm error rate và throughput;
- lưu Grafana snapshot.

---

## 6. Network validity

Nếu client JMeter chạy trên Windows và server Ubuntu qua LAN/VPN/Tailscale, latency mạng có thể ảnh hưởng kết quả.

Cần ghi trong luận văn:

- vị trí client/server;
- có dùng Tailscale hay không;
- RTT baseline nếu đo được;
- thời điểm chạy benchmark;
- máy client có đủ tài nguyên để phát RPS mục tiêu không.

---

## 7. Monitoring validity

Prometheus scrape interval 5s có thể bỏ qua spike ngắn. Grafana panel phụ thuộc PromQL và filter label.

Nguyên tắc:

- không chỉ dùng một panel để kết luận;
- dùng JMeter JTL làm nguồn chính cho latency tổng thể;
- dùng Grafana để giải thích cơ chế routing và tài nguyên;
- kiểm tra all-status latency và error rate.

---

## 8. Algorithm validity

Adaptive hiện là hybrid heuristic model. Các thành phần như AHP/EWM/PID/EWMA có cơ sở kỹ thuật nhưng chưa phải chứng minh tối ưu.

Cần làm:

- ablation study;
- sensitivity analysis;
- ghi rõ các tham số là thực nghiệm;
- không tuyên bố tối ưu toàn cục.

---

## 9. Cách viết kết luận an toàn

Nên viết:

```text
Trong môi trường thử nghiệm gồm 3 backend Spring Boot chạy Docker Compose với workload hỗn hợp và chaos mô phỏng, Adaptive cho thấy khả năng điều chỉnh phân phối request theo metrics runtime. Kết luận này có giá trị trong phạm vi cấu hình và kịch bản đã kiểm thử.
```

Không nên viết:

```text
Adaptive luôn tốt hơn các thuật toán còn lại trong mọi hệ thống microservices.
```
