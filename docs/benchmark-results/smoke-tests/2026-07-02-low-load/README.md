# Thử nghiệm tải thấp không tiêm lỗi — 2026-07-02

## 1. Thông tin kịch bản

Bộ kết quả này ghi nhận quá trình thử nghiệm sơ bộ cho kịch bản tải thấp không tiêm lỗi trong hệ thống cân bằng tải thích nghi cho microservices Spring Boot.

| Thuộc tính | Giá trị |
|---|---|
| Kịch bản JMeter | `01_low_baseline_mixed_0300_nochaos_tst` |
| Loại kịch bản | Low load, mixed workload, no chaos |
| Endpoint chính | `GET /api/simulate-mixed-call` |
| Mục tiêu tải | khoảng 300 request/giây trong pha đo ổn định |
| Số lần chạy | 1 lần cho mỗi thuật toán |
| Phạm vi thống kê | chỉ tính các sampler có nhãn bắt đầu bằng `MEASURE_` |
| Thuật toán so sánh | Adaptive, Least Connections, Random, Round Robin |

Các sampler thuộc nhóm thiết lập, dọn dẹp, ramp-up, ramp-down và end-guard không được đưa vào bảng thống kê chính. Cách lọc này giúp kết quả phản ánh đúng pha đo ổn định của kịch bản thay vì bị ảnh hưởng bởi các thao tác phụ trợ.

## 2. Mục tiêu đánh giá

Kịch bản tải thấp được dùng để kiểm tra hành vi của các thuật toán trong điều kiện hệ thống chưa chịu áp lực cao và không có backend bị suy giảm cục bộ. Trong bối cảnh này, mục tiêu không phải chứng minh Adaptive luôn vượt trội, mà là đánh giá các điểm sau:

- hệ thống duy trì throughput ổn định trong điều kiện tải thấp;
- error rate không xuất hiện bất thường;
- Adaptive không tạo dao động định tuyến quá mức khi chênh lệch giữa các backend nhỏ;
- cơ chế trọng số động MCDM không tiếp tục cập nhật khi không có đủ traffic thực;
- các thuật toán nền có thể hoạt động tốt trong môi trường ổn định, làm cơ sở so sánh với các kịch bản có suy giảm backend.

## 3. Kết quả JMeter ở pha đo ổn định

| Thuật toán | Samples | Errors | Error % | Throughput (req/s) | Avg (ms) | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 71,026 | 6 | 0.0084 | 274.16 | 527.53 | 297.00 | 1386.90 | 2449.95 | 4415.85 | 10074.00 |
| Least Connections | 71,174 | 1 | 0.0014 | 273.81 | 628.62 | 325.00 | 1722.00 | 2460.00 | 3514.96 | 13366.00 |
| Random | 71,071 | 6 | 0.0084 | 269.64 | 716.92 | 435.00 | 1935.90 | 2539.95 | 4027.97 | 8641.00 |
| Round Robin | 71,797 | 0 | 0.0000 | 274.81 | 385.25 | 163.00 | 1130.00 | 1335.95 | 2781.99 | 3739.00 |

Dữ liệu liên quan được lưu trong:

```text
data/jtl-summary.csv
data/aggregate-summary-clean.csv
data/adaptive-improvement-vs-baselines.csv
```

## 4. Phân tích kết quả

### 4.1. Throughput và error rate

Các thuật toán đạt throughput tương đối gần nhau, trong khoảng khoảng `269–275 req/s`. Chênh lệch throughput không lớn, do đó việc so sánh latency giữa các thuật toán có thể được xem là tương đối công bằng trong phạm vi thử nghiệm sơ bộ này.

Error rate của toàn bộ các thuật toán ở mức rất thấp. Round Robin không ghi nhận lỗi trong pha đo chính. Adaptive và Random có 6 lỗi trên hơn 71 nghìn mẫu, tương ứng khoảng `0.0084%`. Mức lỗi này nhỏ và chưa cho thấy dấu hiệu mất ổn định hệ thống, nhưng cần tiếp tục theo dõi trong các lần chạy lặp lại.

### 4.2. Latency trung bình và tail latency

Round Robin cho kết quả tốt nhất trong run này, với `Avg = 385.25 ms`, `P95 = 1335.95 ms` và `P99 = 2781.99 ms`. Adaptive đứng sau Round Robin về hầu hết các chỉ số latency, nhưng vẫn tốt hơn Random ở Avg, P50, P90 và P95.

Kết quả này phù hợp với đặc điểm của kịch bản tải thấp không tiêm lỗi. Khi các backend tương đối ổn định và không có suy giảm cục bộ, thuật toán đơn giản như Round Robin có thể đạt hiệu quả tốt do phân phối đều, ít chi phí điều chỉnh và ít rủi ro phản ứng theo nhiễu nhỏ. Adaptive trong trường hợp này không tạo lợi thế rõ rệt vì không có sự khác biệt đủ lớn giữa các backend để khai thác.

### 4.3. Vai trò của kịch bản tải thấp

Kịch bản tải thấp có giá trị như một phép kiểm tra ổn định nền. Nó cho thấy thuật toán thích nghi không nhất thiết phải thắng ở mọi điều kiện. Trong luận văn, kết quả này nên được dùng để làm rõ phạm vi hiệu quả của Adaptive: cơ chế thích nghi có ý nghĩa rõ hơn khi hệ thống có tải biến động, backend có độ trễ khác biệt hoặc xuất hiện dependency slowdown.

## 5. Phân tích biểu đồ Grafana

### 5.1. Gateway Latency Percentiles — Successful Requests Only

File ảnh:

```text
images/01_gateway_latency_successful_requests_only.png
```

Biểu đồ thể hiện các đường P50, P90, P95 và P99 theo từng block chạy thuật toán. P50 duy trì ở mức thấp hơn đáng kể so với P90/P95/P99, cho thấy workload có phân phối không đồng đều: phần lớn request xử lý nhanh, nhưng vẫn tồn tại nhóm request chậm tạo ra phần đuôi latency.

Ở cuối một số block có spike ngắn, phù hợp với thời điểm kết thúc pha đo hoặc chuyển trạng thái giữa các run. Khi phân tích chính thức, nên ưu tiên số liệu trong pha `MEASURE` đã được lọc thay vì lấy toàn bộ thời gian trên biểu đồ.

### 5.2. Gateway Throughput — Actual RPS

File ảnh:

```text
images/02_gateway_throughput_actual_rps.png
```

Throughput đạt plateau ổn định quanh mục tiêu tải thấp trong từng block chạy. Các đoạn giảm về gần 0 tương ứng với khoảng nghỉ giữa các thuật toán, không phản ánh lỗi xử lý request của hệ thống.

### 5.3. Routing Selection Rate by Backend

File ảnh:

```text
images/03_routing_selection_rate_by_backend.png
```

Các thuật toán nền có xu hướng phân phối gần đều hơn giữa ba backend. Ở đoạn Adaptive, phân phối có xu hướng ưu tiên `8081` và giảm tỷ lệ chọn `8083` tại một số thời điểm. Điều này phù hợp với cơ chế định tuyến có xét capacity và routing cost, tuy nhiên trong kịch bản tải thấp không chaos, sự lệch phân phối này chưa mang lại cải thiện latency so với Round Robin.

### 5.4. Dynamic MCDM criterion weights

File ảnh:

```text
images/04_dynamic_mcdm_criterion_weights.png
```

Trọng số MCDM thay đổi trong thời gian có traffic và quay về bộ trọng số AHP prior ở cuối run:

```text
latency ≈ 64.8%
queue   ≈ 23.0%
cpu     ≈ 12.0%
```

Điều này cho thấy cơ chế cập nhật trọng số động chỉ duy trì khi có đủ tín hiệu thực từ request. Khi traffic kết thúc, trọng số trở lại prior thay vì tiếp tục biến động theo nhiễu nền.

### 5.5. Final Routing Cost by Backend

File ảnh:

```text
images/05_final_routing_cost_by_backend.png
```

Routing cost giữa các backend dao động trong vùng chấp nhận được và không xuất hiện trạng thái một backend bị loại bỏ kéo dài. Trong kịch bản không chaos, các dao động cost nên được hiểu là phản ứng theo biến thiên runtime ngắn hạn, không phải bằng chứng về lỗi cục bộ của backend.

## 6. Kết luận

Trong thử nghiệm tải thấp không tiêm lỗi, Round Robin đạt kết quả latency tốt nhất ở run hiện tại. Adaptive duy trì throughput tương đương các thuật toán nền và error rate rất thấp, nhưng chưa thể hiện lợi thế rõ rệt về latency. Kết quả này phù hợp với giả thuyết rằng trong môi trường ổn định, không có backend suy giảm và tải chưa đủ cao, thuật toán phân phối đơn giản có thể hoạt động hiệu quả hơn hoặc tương đương cơ chế thích nghi.

Kết quả của kịch bản này không làm giảm giá trị của Adaptive, mà giúp xác định rõ phạm vi tác dụng của nó. Adaptive cần được đánh giá trọng tâm ở các kịch bản có dependency slowdown, high load hoặc stress/recovery, nơi trạng thái backend thay đổi rõ hơn và routing dựa trên metrics có nhiều cơ hội phát huy tác dụng.

## 7. Giới hạn của bộ kết quả

Bộ kết quả này chỉ gồm một lần chạy cho mỗi thuật toán. Các nhận xét trong thư mục này chỉ nên được xem là kết quả kiểm tra sơ bộ. Để sử dụng làm kết luận chính thức trong luận văn, cần chạy lặp lại nhiều lần, tổng hợp trung bình, độ lệch chuẩn và khoảng tin cậy cho các chỉ số chính.
