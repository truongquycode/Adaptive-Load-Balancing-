# Low Load Smoke Test — MEASURE-only corrected — 2026-07-02

## 1. Lý do chỉnh lại

Bản tổng hợp trước đã lấy cả `Total` trong JTL, tức là còn lẫn các sampler setup, teardown và `DISCARD_*` như ramp-up/ramp-down. Như vậy số liệu latency bị sai cho mục tiêu benchmark.

Thư mục này đã được sửa lại để chỉ dùng sampler đo chính:

```text
MEASURE_LOW_0300_RPS_BASELINE_STEADY_240s_GET_/api/simulate-mixed-call
```

Vì low load chỉ có một pha `MEASURE`, các số liệu trong bảng dưới đây được lấy trực tiếp từ dòng `MEASURE_LOW_0300_RPS_BASELINE_STEADY_240s` của JMeter HTML report.

## 2. Kết quả JMeter sau khi lọc MEASURE

| Thuật toán | Samples | Error % | Throughput (req/s) | Avg ms | P50 | P90 | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 71,026 | 0.0084 | 274.16 | 527.53 | 297 | 1386.90 | 2449.95 | 4415.85 | 10074 |
| Least Connections | 71,174 | 0.0014 | 273.81 | 628.62 | 325 | 1722.00 | 2460.00 | 3514.96 | 13366 |
| Random | 71,071 | 0.0084 | 269.64 | 716.92 | 435 | 1935.90 | 2539.95 | 4027.97 | 8641 |
| Round Robin | 71,797 | 0.0000 | 274.81 | 385.25 | 163 | 1130.00 | 1335.95 | 2781.99 | 3739 |

## 3. Adaptive so với baseline sau khi sửa

Giá trị dương nghĩa là Adaptive thấp hơn baseline ở latency. Giá trị âm nghĩa là Adaptive kém hơn baseline.

| Baseline | Avg | P50 | P90 | P95 | P99 | Max | Throughput |
|---|---:|---:|---:|---:|---:|---:|---:|
| Round Robin | -36.93% | -82.21% | -22.73% | -83.39% | -58.73% | -169.43% | -0.24% |
| Random | 26.42% | 31.72% | 28.36% | 3.54% | -9.63% | -16.58% | 1.68% |
| Least Connections | 16.08% | 8.62% | 19.46% | 0.41% | -25.63% | 24.63% | 0.13% |

## 4. Nhận xét đúng sau khi chỉ lấy MEASURE

- Round Robin là thuật toán tốt nhất trong run low load này về Avg, P90, P95, P99 và Max.
- Adaptive tốt hơn Random ở Avg/P50/P90/P95, nhưng P99 và Max vẫn kém Random.
- Adaptive tốt hơn Least Connections ở Avg/P50/P90/P95 và Max, nhưng P99 lại kém.
- Throughput của Adaptive gần tương đương Round Robin và Least Connections, nên khác biệt latency không phải do Adaptive nhận tải thấp hơn.
- Đây là kết quả hợp lý về mặt diễn giải: ở low load không có chaos, thuật toán đơn giản như Round Robin có thể hoạt động rất tốt; Adaptive không nhất thiết phải thắng ở kịch bản nhẹ.

## 5. Nhận xét từ Grafana

Các ảnh Grafana được giữ lại trong thư mục `images/`:

- `01_gateway_latency_successful_requests_only.png`: p90/p95/p99 có đuôi dài do workload mixed, nhưng các block chạy ổn định.
- `02_gateway_throughput_actual_rps.png`: throughput đạt plateau gần 300 req/s trong từng block.
- `03_routing_selection_rate_by_backend.png`: một số đoạn có phân phối lệch về backend mạnh hơn, phù hợp với capacity-aware routing.
- `04_dynamic_mcdm_criterion_weights.png`: MCDM thay đổi khi có traffic thật và quay về AHP prior sau khi hết tải.
- `05_final_routing_cost_by_backend.png`: routing cost dao động trong biên độ chấp nhận được; không có dấu hiệu crash.

## 6. Kết luận tạm thời

Sau khi sửa cách tổng hợp số liệu, kết luận low load cần viết thận trọng hơn:

> Ở low load không có chaos, Adaptive giữ throughput tương đương baseline và hệ thống chạy ổn định, nhưng chưa tạo lợi thế latency so với Round Robin. Điều này cho thấy Adaptive phù hợp hơn để đánh giá trong các kịch bản có degradation, tải cao hoặc backend không đồng nhất rõ rệt, còn ở tải nhẹ Round Robin vẫn là baseline rất mạnh.

Đây vẫn chỉ là **1 run smoke test**, chưa phải kết luận chính thức cho luận văn.
