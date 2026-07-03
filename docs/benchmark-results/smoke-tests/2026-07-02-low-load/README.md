# Thử nghiệm tải thấp không tiêm lỗi — 2026-07-02

## 1. Mục tiêu kịch bản

Kịch bản này đánh giá hành vi của các thuật toán khi hệ thống chạy ở mức tải thấp, không có backend bị tiêm lỗi. Dữ liệu thống kê chỉ sử dụng các sampler `MEASURE_`, không tính setup, teardown, ramp-up, ramp-down và end-guard.

| Thuộc tính | Giá trị |
|---|---|
| Kịch bản | `01_low_baseline_mixed_0300_nochaos_tst` |
| Loại tải | Low load, mixed workload, no chaos |
| Endpoint | `GET /api/simulate-mixed-call` |
| Số lần chạy | 1 run/thuật toán |
| Pha thống kê | `MEASURE_LOW_0300_RPS_BASELINE_STEADY_240s` |

## 2. Kết quả trọng tâm

**Round Robin cho kết quả tốt nhất trong kịch bản low load không chaos.** Đây là kết quả hợp lý vì khi các backend ổn định và không có suy giảm cục bộ, cơ chế chia đều đơn giản có thể hoạt động hiệu quả hơn thuật toán thích nghi phức tạp.

**Adaptive không vượt Round Robin ở low load, nhưng vẫn giữ throughput tương đương và error rate rất thấp.** Vì vậy, kịch bản này không dùng để chứng minh Adaptive vượt trội, mà dùng để kiểm tra Adaptive có ổn định trong điều kiện tải nhẹ hay không.

| Thuật toán | Samples | Error % | Throughput (req/s) | Avg (ms) | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 71,026 | 0.0084 | 274.16 | 527.53 | 297.00 | 1386.90 | 2449.95 | 4415.85 | 10074.00 |
| Least Connections | 71,174 | 0.0014 | 273.81 | 628.62 | 325.00 | 1722.00 | 2460.00 | 3514.96 | 13366.00 |
| Random | 71,071 | 0.0084 | 269.64 | 716.92 | 435.00 | 1935.90 | 2539.95 | 4027.97 | 8641.00 |
| **Round Robin** | **71,797** | **0.0000** | **274.81** | **385.25** | **163.00** | **1130.00** | **1335.95** | **2781.99** | **3739.00** |

## 3. Nhận xét chính

**Throughput của các thuật toán gần tương đương**, dao động khoảng 269–275 req/s. Điều này cho thấy tải được duy trì ổn định và việc so sánh latency có ý nghĩa trong phạm vi smoke test.

**Round Robin có Avg, P90, P95 và P99 thấp nhất.** Trong môi trường không chaos, không có backend yếu rõ rệt, việc chia đều request giúp Round Robin đạt lợi thế.

**Adaptive không gây lỗi bất thường.** Error rate của Adaptive là 0.0084%, rất thấp so với tổng số mẫu. Tuy nhiên, Adaptive có tail latency cao hơn Round Robin trong run này, nên không nên kết luận Adaptive tốt hơn ở low load.

**Dynamic MCDM quay về AHP prior sau khi traffic kết thúc**, với trọng số xấp xỉ latency 64.8%, queue 23.0%, CPU 12.0%. Điều này xác nhận cơ chế không tiếp tục học từ nhiễu nền khi không còn đủ traffic thực.

## 4. Bằng chứng Grafana

| Hình | Nội dung chính |
|---|---|
| `images/01_gateway_latency_successful_requests_only.png` | P90/P95/P99 có đuôi dài; Round Robin thấp hơn các thuật toán còn lại trong run này. |
| `images/02_gateway_throughput_actual_rps.png` | Throughput đạt plateau ổn định trong từng block chạy. |
| `images/03_routing_selection_rate_by_backend.png` | Các thuật toán nền phân phối gần đều; Adaptive có điều chỉnh theo cost/capacity nhưng chưa tạo lợi thế latency ở low load. |
| `images/04_dynamic_mcdm_criterion_weights.png` | Trọng số động trở về AHP prior khi kết thúc traffic. |
| `images/05_final_routing_cost_by_backend.png` | Routing cost dao động trong vùng chấp nhận được, không có backend bị loại bỏ kéo dài. |

## 5. Kết luận

**Ở tải thấp không tiêm lỗi, Round Robin là thuật toán tốt nhất trong run này.** Adaptive chạy ổn định nhưng chưa có lợi thế về latency, điều này phù hợp với giả thuyết rằng cơ chế thích nghi chỉ phát huy rõ hơn khi backend có trạng thái không đồng nhất, xuất hiện dependency slowdown hoặc tải cao hơn.

Bộ kết quả này là smoke test 1 run. Khi dùng cho luận văn, chỉ nên diễn giải là bằng chứng kiểm tra ổn định ban đầu, chưa phải kết luận thống kê cuối cùng.
