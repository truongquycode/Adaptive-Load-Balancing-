# Medium Dependency Slowdown Smoke Test — MEASURE-only corrected — 2026-07-02

## 1. Lý do chỉnh lại

Bản tổng hợp trước đã lấy cả setup, teardown, chaos control calls và `DISCARD_*` warmup/ramp-down. Các dòng này không nên được dùng để so sánh thuật toán.

Thư mục này đã được sửa lại theo nguyên tắc:

```text
Chỉ dùng các sampler có label bắt đầu bằng MEASURE_
Loại bỏ setup / teardown / DISCARD_* / chaos control calls
```

Với medium scenario, các pha đo chính gồm:

```text
MEASURE_MEDIUM_0600_RPS_BASELINE_60S_GET_/api/simulate-mixed-call
MEASURE_MEDIUM_0600_RPS_DEPENDENCY_SLOWDOWN_180S_GET_/api/simulate-mixed-call
MEASURE_MEDIUM_0600_RPS_RECOVERY_90S_GET_/api/simulate-mixed-call
```

## 2. Kết quả phase-level từ các dòng MEASURE

File chi tiết:

```text
data/measure-phase-summary.csv
```

### 2.1. Pha dependency slowdown 180s — pha quan trọng nhất

| Thuật toán | Samples | Avg ms | P50 | P90 | P95 | P99 | Max | Throughput |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 107,767 | 341.61 | 95 | 1105.00 | 1322.00 | 2748.90 | 5296 | 487.97/s |
| Least Connections | 107,661 | 353.67 | 77 | 1140.00 | 1403.00 | 2796.97 | 8353 | 507.51/s |
| Random | 107,494 | 373.25 | 97 | 1174.90 | 1455.00 | 2848.99 | 5853 | 522.94/s |
| Round Robin | 107,437 | 384.14 | 115 | 1197.00 | 1475.90 | 2849.98 | 5003 | 526.60/s |

Ở pha dependency slowdown, Adaptive có Avg/P90/P95/P99 tốt hơn cả Round Robin, Random và Least Connections. Đây là pha phản ánh đúng mục tiêu của kịch bản medium chaos.

## 3. Aggregate MEASURE-only

File:

```text
data/aggregate-summary-clean.csv
```

Do hiện tại chỉ có ảnh/dòng HTML report chứ không có raw `.jtl` trong sandbox, các percentile aggregate trong file này được tính theo **xấp xỉ trọng số theo số mẫu của từng phase**. Để có percentile aggregate chính xác tuyệt đối, hãy chạy lại file `summarize_jtl_results.py` đã sửa trên raw `.jtl` ở máy của bạn.

| Thuật toán | Samples | Error % | Throughput approx | Avg ms | P50 approx | P90 approx | P95 approx | P99 approx | Max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 197,339 | 0.0000 | 458.70 | 337.61 | 93.64 | 1093.09 | 1305.10 | 2751.39 | 6347 |
| Least Connections | 197,491 | 0.0000 | 477.34 | 341.01 | 74.27 | 1111.16 | 1345.69 | 2780.78 | 8353 |
| Random | 196,987 | 0.0000 | 482.89 | 357.46 | 94.54 | 1139.05 | 1383.46 | 2815.98 | 10268 |
| Round Robin | 196,910 | 0.0000 | 475.72 | 362.24 | 110.00 | 1146.72 | 1396.87 | 2814.72 | 5003 |

## 4. Nhận xét sau khi sửa cách lấy số liệu

- Không dùng `Total` của JMeter HTML report nếu `Total` còn lẫn `DISCARD_*`, setup, teardown hoặc chaos calls.
- Với medium scenario, nên phân tích mạnh nhất ở pha `MEASURE_MEDIUM_0600_RPS_DEPENDENCY_SLOWDOWN_180S`, vì đây là pha có dependency slowdown.
- Ở pha dependency slowdown, Adaptive thể hiện đúng mục tiêu: giảm Avg/P90/P95/P99 so với các baseline.
- Throughput của các baseline trong pha dependency cao hơn Adaptive một chút, nên khi viết luận văn cần nói rõ rằng Adaptive ưu tiên giảm latency/tail hơn là đẩy throughput cao nhất trong smoke run này.

## 5. Nhận xét từ Grafana

Các ảnh Grafana trong `images/` vẫn giữ nguyên vì chúng phản ánh đúng time range smoke test:

- `01_gateway_latency_all_http_statuses.png`: latency theo từng block chạy strategy.
- `02_gateway_throughput_actual_rps.png`: actual RPS đạt plateau ở giai đoạn đo.
- `03_routing_selection_rate_by_backend.png`: Adaptive không chia đều mù, có giảm traffic tới backend xấu hơn.
- `04_dynamic_mcdm_criterion_weights.png`: Dynamic MCDM thay đổi khi có traffic thật, sau đó quay về AHP prior.
- `05_final_routing_cost_by_backend.png`: backend có cost cao hơn được giảm chọn.
- `06_capacity_normalized_load_ratio_by_backend.png`: Adaptive có xét tải theo capacity.
- `07_ewma_latency_by_backend.png`: 8083 có spike latency, giải thích vì sao Adaptive giảm traffic về node này.

## 6. Cách diễn giải phù hợp

> Sau khi chỉ xét các dòng MEASURE, Adaptive cho kết quả tốt nhất ở pha dependency slowdown 180s về Avg/P90/P95/P99. Điều này phù hợp với mục tiêu của thuật toán: phản ứng với backend degradation dựa trên latency/queue/CPU/capacity thay vì chia đều request. Tuy nhiên đây mới là smoke test 1 run, cần chạy 3–5 runs để xác nhận tính lặp lại.

