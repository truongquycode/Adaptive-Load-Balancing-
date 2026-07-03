# Thử nghiệm tải trung bình với dependency slowdown — 2026-07-02

## 1. Mục tiêu kịch bản

Kịch bản này đánh giá khả năng cân bằng tải khi hệ thống chịu tải trung bình và một backend gặp hiện tượng dependency slowdown cục bộ. Dữ liệu thống kê chỉ sử dụng các sampler `MEASURE_`, không tính setup, teardown, ramp-up, ramp-down, end-guard và thao tác bật/tắt chaos.

| Thuộc tính | Giá trị |
|---|---|
| Kịch bản | `02_medium_dependency_slowdown_mixed_0600_tst` |
| Loại tải | Medium load, mixed workload, localized dependency slowdown |
| Endpoint | `GET /api/simulate-mixed-call` |
| Số lần chạy | 1 run/thuật toán |
| Pha thống kê | baseline, dependency slowdown, recovery |

## 2. Kết quả trọng tâm

**Trong pha dependency slowdown, Adaptive có Avg, P90, P95 và P99 thấp nhất trong bốn thuật toán.** Đây là kết quả quan trọng nhất của kịch bản medium, vì pha slowdown phản ánh đúng tình huống backend bị suy giảm cục bộ.

**Adaptive giảm P95 khoảng 10.43% so với Round Robin, 9.14% so với Random và 5.77% so với Least Connections trong pha slowdown.** Điều này cho thấy việc dùng latency, queue, CPU, capacity và routing cost giúp Adaptive phản ứng tốt hơn các thuật toán nền khi backend không đồng nhất về trạng thái runtime.

## 3. Kết quả pha dependency slowdown

| Thuật toán | Samples | Error % | Throughput (req/s) | Avg (ms) | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **Adaptive** | **107,767** | **0.0000** | 487.97 | **341.61** | 95 | **1105.00** | **1322.00** | **2748.90** | 5296 |
| Least Connections | 107,661 | 0.0000 | 507.51 | 353.67 | **77** | 1140.00 | 1403.00 | 2796.97 | 8353 |
| Random | 107,494 | 0.0000 | 522.94 | 373.25 | 97 | 1174.90 | 1455.00 | 2848.99 | 5853 |
| Round Robin | 107,437 | 0.0000 | **526.60** | 384.14 | 115 | 1197.00 | 1475.90 | 2849.98 | **5003** |

## 4. Mức cải thiện của Adaptive trong pha slowdown

| So sánh | Avg | P90 | P95 | P99 |
|---|---:|---:|---:|---:|
| So với Least Connections | **giảm 3.41%** | **giảm 3.07%** | **giảm 5.77%** | **giảm 1.72%** |
| So với Random | **giảm 8.48%** | **giảm 5.95%** | **giảm 9.14%** | **giảm 3.51%** |
| So với Round Robin | **giảm 11.07%** | **giảm 7.69%** | **giảm 10.43%** | **giảm 3.55%** |

## 5. Kết quả tổng hợp các pha MEASURE

| Thuật toán | Samples | Error % | Throughput (req/s) | Avg (ms) | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **Adaptive** | 197,339 | 0.0000 | 458.70 | **337.61** | 93.64 | **1093.09** | **1305.10** | **2751.39** | 6347 |
| Least Connections | 197,491 | 0.0000 | 477.34 | 341.01 | **74.27** | 1111.16 | 1345.69 | 2780.78 | 8353 |
| Random | 196,987 | 0.0000 | **482.89** | 357.46 | 94.54 | 1139.05 | 1383.46 | 2815.98 | 10268 |
| Round Robin | 196,910 | 0.0000 | 475.72 | 362.24 | 110.00 | 1146.72 | 1396.87 | 2814.72 | **5003** |

## 6. Nhận xét chính

**Adaptive không đạt throughput cao nhất, nhưng đạt latency tốt nhất ở pha slowdown.** Đây là đánh đổi phù hợp với mục tiêu của thuật toán: giảm tác động của backend chậm lên latency tổng thể thay vì chỉ tối đa hóa số request/giây.

**Least Connections có P50 thấp nhất nhưng thua Adaptive ở Avg, P90, P95 và P99.** Điều này cho thấy chỉ nhìn số kết nối/inflight chưa đủ để nhận biết backend bị chậm do dependency.

**Error rate bằng 0% trong các pha MEASURE.** Vì vậy, mức cải thiện latency của Adaptive không đến từ việc tăng lỗi hay bỏ request.

## 7. Bằng chứng Grafana

| Hình | Nội dung chính |
|---|---|
| `images/01_gateway_latency_all_http_statuses.png` | P99 cao hơn rõ rệt P50, xác nhận workload có tail latency dài. |
| `images/02_gateway_throughput_actual_rps.png` | Throughput tạo plateau ổn định trong từng block chạy. |
| `images/03_routing_selection_rate_by_backend.png` | Adaptive không chia đều tuyệt đối; traffic được điều chỉnh theo trạng thái backend. |
| `images/04_dynamic_mcdm_criterion_weights.png` | Latency weight chiếm ưu thế; trọng số trở về AHP prior sau khi traffic kết thúc. |
| `images/05_final_routing_cost_by_backend.png` | Backend có cost cao hơn thường nhận ít traffic hơn. |
| `images/06_capacity_normalized_load_ratio_by_backend.png` | Tải được phân tích theo capacity tương đối của từng backend. |
| `images/07_ewma_latency_by_backend.png` | `8083` có nhiều spike EWMA latency, giải thích vì sao Adaptive giảm traffic về backend này. |

## 8. Kết luận

**Ở kịch bản medium dependency slowdown, Adaptive là thuật toán có kết quả latency tốt nhất trong pha slowdown.** Kết quả này phù hợp với mục tiêu của cơ chế cân bằng tải thích nghi: sử dụng metrics runtime để giảm ảnh hưởng của backend bị suy giảm cục bộ.

Bộ kết quả hiện tại là smoke test 1 run. Có thể dùng làm bằng chứng sơ bộ, nhưng kết luận chính thức trong luận văn cần dựa trên nhiều lần chạy và phân tích thống kê.
