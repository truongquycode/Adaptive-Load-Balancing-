# Thử nghiệm tải trung bình với dependency slowdown — 2026-07-02

## 1. Thông tin kịch bản

Bộ kết quả này ghi nhận quá trình thử nghiệm sơ bộ cho kịch bản tải trung bình có tiêm lỗi dependency slowdown cục bộ. Kịch bản được dùng để quan sát khả năng phản ứng của các thuật toán cân bằng tải khi một backend xuất hiện dấu hiệu xử lý chậm hơn so với các backend còn lại.

| Thuộc tính | Giá trị |
|---|---|
| Kịch bản JMeter | `02_medium_dependency_slowdown_mixed_0600_tst` |
| Loại kịch bản | Medium load, mixed workload, localized dependency slowdown |
| Endpoint chính | `GET /api/simulate-mixed-call` |
| Pha đo chính | baseline, dependency slowdown, recovery |
| Số lần chạy | 1 lần cho mỗi thuật toán |
| Phạm vi thống kê | chỉ tính các sampler có nhãn bắt đầu bằng `MEASURE_` |
| Thuật toán so sánh | Adaptive, Least Connections, Random, Round Robin |

Các nhãn thiết lập, dọn dẹp, ramp-up, ramp-down, end-guard và thao tác bật/tắt chaos không được đưa vào thống kê chính. Phạm vi này giúp bảng kết quả phản ánh đúng các pha đo nghiệp vụ của JMeter.

## 2. Mục tiêu đánh giá

Kịch bản medium dependency slowdown được dùng để kiểm tra khả năng của thuật toán trong điều kiện tải đủ lớn và có backend bị suy giảm cục bộ. Các mục tiêu đánh giá gồm:

- xác định thuật toán có giữ được throughput gần mục tiêu tải hay không;
- kiểm tra error rate trong quá trình dependency slowdown;
- so sánh latency trung bình và tail latency giữa Adaptive và các thuật toán nền;
- quan sát việc Adaptive điều chỉnh phân phối request dựa trên latency, queue, CPU, capacity và routing cost;
- đánh giá xu hướng phục hồi sau khi pha slowdown kết thúc.

## 3. Kết quả tổng hợp theo các pha đo

Bảng dưới đây là kết quả tổng hợp từ các nhãn `MEASURE_` của JMeter. Các giá trị aggregate trong thư mục này được dùng để quan sát xu hướng tổng quát; khi phân tích chính thức nhiều lần chạy, nên tổng hợp trực tiếp từ raw `.jtl` bằng cùng quy tắc lọc `MEASURE_`.

| Thuật toán | Samples | Errors | Error % | Throughput (req/s) | Avg (ms) | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 197,339 | 0 | 0.0000 | 458.70 | 337.61 | 93.64 | 1093.09 | 1305.10 | 2751.39 | 6347 |
| Least Connections | 197,491 | 0 | 0.0000 | 477.34 | 341.01 | 74.27 | 1111.16 | 1345.69 | 2780.78 | 8353 |
| Random | 196,987 | 0 | 0.0000 | 482.89 | 357.46 | 94.54 | 1139.05 | 1383.46 | 2815.98 | 10268 |
| Round Robin | 196,910 | 0 | 0.0000 | 475.72 | 362.24 | 110.00 | 1146.72 | 1396.87 | 2814.72 | 5003 |

Dữ liệu liên quan được lưu trong:

```text
data/jtl-summary.csv
data/measure-phase-summary.csv
data/aggregate-summary-clean.csv
data/adaptive-improvement-vs-baselines.csv
```

## 4. Kết quả trong pha dependency slowdown

Pha dependency slowdown là pha quan trọng nhất của kịch bản này vì nó phản ánh trực tiếp khả năng xử lý backend bị suy giảm.

| Thuật toán | Samples | Errors | Error % | Throughput (req/s) | Avg (ms) | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 107,767 | 0 | 0.0000 | 487.97 | 341.61 | 95 | 1105.00 | 1322.00 | 2748.90 | 5296 |
| Least Connections | 107,661 | 0 | 0.0000 | 507.51 | 353.67 | 77 | 1140.00 | 1403.00 | 2796.97 | 8353 |
| Random | 107,494 | 0 | 0.0000 | 522.94 | 373.25 | 97 | 1174.90 | 1455.00 | 2848.99 | 5853 |
| Round Robin | 107,437 | 0 | 0.0000 | 526.60 | 384.14 | 115 | 1197.00 | 1475.90 | 2849.98 | 5003 |

Trong pha slowdown, Adaptive đạt latency trung bình và các phân vị P90, P95, P99 thấp nhất trong bốn thuật toán. Điều này cho thấy cơ chế định tuyến dựa trên metrics runtime có xu hướng giảm tác động của backend xử lý chậm lên latency tổng thể.

## 5. Phân tích kết quả

### 5.1. Throughput và error rate

Tất cả thuật toán đều có error rate bằng `0%` trong các pha `MEASURE_`. Throughput giữa các thuật toán có khác biệt nhất định, trong đó Random và Round Robin đạt throughput cao hơn trong pha dependency slowdown, còn Adaptive thấp hơn một phần. Do đó, khi diễn giải kết quả cần xem xét đồng thời latency và throughput, tránh kết luận chỉ dựa trên một chỉ số.

Mặc dù throughput của Adaptive không cao nhất, latency trung bình và tail latency của Adaptive thấp hơn trong pha slowdown. Điều này phù hợp với mục tiêu của Adaptive: ưu tiên giảm chi phí định tuyến và kiểm soát request chậm thay vì chỉ tối đa hóa số request/giây.

### 5.2. Latency trong pha dependency slowdown

So với các thuật toán nền trong pha dependency slowdown, Adaptive có các mức giảm sau:

| So sánh | Avg | P90 | P95 | P99 |
|---|---:|---:|---:|---:|
| So với Least Connections | giảm khoảng 3.41% | giảm khoảng 3.07% | giảm khoảng 5.77% | giảm khoảng 1.72% |
| So với Random | giảm khoảng 8.48% | giảm khoảng 5.95% | giảm khoảng 9.14% | giảm khoảng 3.51% |
| So với Round Robin | giảm khoảng 11.07% | giảm khoảng 7.69% | giảm khoảng 10.43% | giảm khoảng 3.55% |

Mức cải thiện ở P95 rõ hơn P99. P99 vẫn còn chênh lệch nhỏ do workload có nhóm request rất chậm và phụ thuộc vào thời điểm request rơi vào backend đang bị slowdown. Đây là đặc điểm bình thường của tail latency trong hệ thống có workload mixed.

### 5.3. So sánh với Least Connections

Least Connections có P50 thấp hơn Adaptive, cho thấy ở các request thông thường LC vẫn hoạt động tốt. Tuy nhiên Adaptive có Avg, P90, P95, P99 và Max tốt hơn LC trong pha dependency slowdown. Điều này cho thấy chỉ số số kết nối/inflight không đủ để nhận biết đầy đủ backend đang chậm do dependency, trong khi Adaptive có thêm tín hiệu latency, queue, CPU và capacity.

## 6. Phân tích biểu đồ Grafana

### 6.1. Gateway Latency Percentiles — All HTTP Statuses

File ảnh:

```text
images/01_gateway_latency_all_http_statuses.png
```

Biểu đồ cho thấy P90/P95/P99 duy trì dạng plateau theo từng block chạy. P99 cao hơn rõ rệt so với P50, phản ánh workload mixed có phần đuôi latency dài. Việc sử dụng all HTTP statuses giúp biểu đồ phù hợp hơn cho phân tích luận văn vì không loại bỏ các request lỗi khỏi quan sát latency.

### 6.2. Gateway Throughput — Actual RPS

File ảnh:

```text
images/02_gateway_throughput_actual_rps.png
```

Throughput có dạng plateau rõ ràng trong từng block, thể hiện JMeter duy trì tải tương đối ổn định trong pha chính. Các đoạn giảm về 0 là khoảng nghỉ khi chuyển thuật toán và không được xem là suy giảm hệ thống.

### 6.3. Routing Selection Rate by Backend

File ảnh:

```text
images/03_routing_selection_rate_by_backend.png
```

Ở các thuật toán nền, phân phối request có xu hướng gần đều hoặc chỉ dao động theo cơ chế chọn đơn giản. Trong cửa sổ Adaptive, traffic không chia đều tuyệt đối; backend `8083` có thời điểm được giảm tải, trong khi `8081` và `8082` nhận tỷ lệ cao hơn. Đây là dấu hiệu thuật toán phản ứng theo trạng thái runtime thay vì phân phối mù.

### 6.4. Dynamic MCDM criterion weights

File ảnh:

```text
images/04_dynamic_mcdm_criterion_weights.png
```

Trọng số latency chiếm ưu thế trong phần lớn thời gian, phù hợp với mục tiêu giảm thời gian phản hồi. CPU weight có lúc tăng đáng kể, cho thấy DynamicWeightEngine phản ứng khi tiêu chí CPU có khả năng phân biệt backend. Sau khi traffic kết thúc, các trọng số trở về AHP prior, phản ánh cơ chế cập nhật trọng số có điều kiện theo traffic thực.

### 6.5. Final Routing Cost by Backend

File ảnh:

```text
images/05_final_routing_cost_by_backend.png
```

Routing cost thay đổi theo backend và theo thời gian, cho thấy Adaptive không dùng một trọng số cố định cho toàn bộ quá trình. Backend có cost cao hơn thường nhận ít traffic hơn trong biểu đồ phân phối request. Đây là bằng chứng phù hợp với pipeline định tuyến: metrics runtime được chuyển thành score, sau đó thành routing cost để ra quyết định.

### 6.6. Capacity-normalized Load Ratio by Backend

File ảnh:

```text
images/06_capacity_normalized_load_ratio_by_backend.png
```

Biểu đồ cho thấy tải được diễn giải theo capacity tương đối của từng backend. Trong một số giai đoạn, `8081` và `8082` nhận tỷ lệ tải cao hơn, trong khi `8083` thấp hơn. Hành vi này phù hợp với giả thuyết backend có capacity khác nhau và Adaptive ưu tiên tránh dồn request vào instance có cost cao.

### 6.7. EWMA latency by backend

File ảnh:

```text
images/07_ewma_latency_by_backend.png
```

EWMA latency của `8083` có nhiều spike cao hơn so với `8081` và `8082`. Đây là bằng chứng quan trọng cho thấy việc giảm traffic đến `8083` không phải ngẫu nhiên mà liên quan đến tín hiệu latency đã làm mượt. `EWMA` giúp tránh phản ứng quá mạnh với một sample đơn lẻ, đồng thời vẫn ghi nhận xu hướng backend xử lý chậm.

## 7. Kết luận

Trong kịch bản tải trung bình có dependency slowdown, Adaptive thể hiện xu hướng tốt nhất về latency trung bình và tail latency trong pha slowdown. Thuật toán không đạt throughput cao nhất, nhưng giữ error rate bằng `0%` và giảm P90/P95/P99 so với các thuật toán nền. Kết quả Grafana cũng cho thấy quyết định định tuyến có liên hệ với routing cost, capacity-normalized load và EWMA latency của backend.

Kết quả này ủng hộ giả thuyết rằng Adaptive phù hợp hơn các thuật toán đơn giản trong bối cảnh backend có trạng thái không đồng nhất hoặc bị suy giảm cục bộ. Tuy nhiên, do bộ kết quả hiện tại chỉ gồm một lần chạy cho mỗi thuật toán, kết luận chính thức cần dựa trên nhiều lần chạy và phân tích thống kê.

## 8. Giới hạn của bộ kết quả

Bộ kết quả này là thử nghiệm sơ bộ, chưa phải benchmark chính thức nhiều lần. Các số liệu có thể chịu ảnh hưởng của thứ tự chạy, trạng thái tài nguyên máy chủ, network latency và biến động runtime của JVM. Để sử dụng trong phần kết quả luận văn, cần chạy lặp lại nhiều lần, tổng hợp trung bình, độ lệch chuẩn và khoảng tin cậy cho các chỉ số chính.
