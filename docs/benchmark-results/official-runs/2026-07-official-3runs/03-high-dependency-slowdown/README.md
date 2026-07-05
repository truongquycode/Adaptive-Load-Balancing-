# High Load 900 RPS — Dependency Slowdown

## Mục tiêu kịch bản

Đánh giá khả năng kiểm soát tail latency và error rate khi hệ thống chạy gần ngưỡng chịu tải.

- **Mức tải mục tiêu:** 900 RPS
- **Nguồn số liệu chính:** `data/jtl-summary.csv`
- **Phạm vi tính toán:** chỉ các sampler có nhãn `MEASURE_`
- **Số lần chạy kỳ vọng:** 3 run cho mỗi thuật toán
- **Thứ tự chạy xác định từ Grafana:** Random → Adaptive → Round Robin

**Lưu ý dữ liệu:** Least Connections: 0 run; Round Robin: 2/3 run.

## Kết quả trung bình

| Thuật toán | Run | Avg (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Error (%) | Actual RPS |
|---|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 3 | 662.96 | 1479.33 | 2237.65 | 3803.56 | 0.0316 | 824.35 |
| Random | 3 | 1922.66 | 3378.90 | 12909.67 | 15266.87 | 21.0013 | 849.77 |
| Round Robin | 2* | 1691.37 | 3392.00 | 7386.00 | 15295.50 | 22.5329 | 822.80 |

## So sánh Adaptive với baseline

| So sánh Adaptive với | Avg giảm | P95 giảm | P99 giảm | Error giảm | Throughput thay đổi |
|---|---:|---:|---:|---:|---:|
| Random | 65.52% | 82.67% | 75.09% | 99.85% | -2.99% |
| Round Robin | 60.80% | 69.70% | 75.13% | 99.86% | 0.19% |

## Đối chiếu với Grafana

- **High Load tạo khác biệt rõ nhất giữa các thuật toán**, vì error rate và tail latency tăng mạnh ở các baseline không thích nghi.
- Decision Reason cho thấy dữ liệu high hiện có gồm Random, Adaptive và Round Robin; không thấy Least Connections trong ảnh và CSV.
- Trong đoạn Adaptive, routing chuyển đổi giữa NORMAL_P2C, HEALTH_DOMINANT và một số lần ALL_HARD_EXCLUDED_FALLBACK, cho thấy hệ thống có thời điểm bị áp lực mạnh.
- Queue Depth, EWMA latency và Absolute Latency Cost cho thấy backend 8083 thường là điểm bất ổn; đây là nguyên nhân chính làm tăng tail latency ở high load.
- CPU panel cho thấy registration-8083 có thời điểm chạm gần 100% quota, phù hợp với hiện tượng queue và latency tăng.

## Kết luận chính

**Ở High Load, Adaptive giảm error rate và tail latency rất mạnh so với Random/Round Robin, nhưng bộ dữ liệu high chưa đủ Least Connections và Round Robin mới có 2 run.**

## Dữ liệu và hình ảnh đi kèm

- `data/jtl-summary.csv` — kết quả tổng hợp từ raw JTL.
- `data/aggregate-summary-clean.csv` — bảng tổng hợp gọn theo thuật toán.
- `data/strategy-mean-std.csv` — mean/std của các chỉ số chính.
- `data/adaptive-comparison-vs-baselines.csv` — mức cải thiện của Adaptive so với các baseline.
- `notes/run-order.md` — thứ tự chạy xác định từ panel Decision Reason.
- `notes/quick-summary.txt` — tóm tắt ngắn.

### Ảnh Grafana

- `images/01_high_gateway_latency_all_statuses.png` — Gateway Latency Percentiles — All HTTP Statuses
- `images/02_high_gateway_latency_successful_only.png` — Gateway Latency Percentiles — Successful Requests Only
- `images/03_high_gateway_throughput_actual_rps.png` — Gateway Throughput — Actual RPS
- `images/04_high_gateway_error_rate.png` — Gateway Error Rate — Non-2xx Requests
- `images/05_high_routing_selection_by_backend.png` — Routing Selection Rate by Backend
- `images/06_high_routing_selection_by_reason.png` — Routing Selection Rate by Decision Reason
- `images/07_high_ewma_latency_by_backend.png` — EWMA latency by backend
- `images/08_high_queue_depth.png` — Queue Depth (inflight requests) theo instance
- `images/09_high_final_routing_cost.png` — Final Routing Cost by Backend
- `images/10_high_absolute_latency_cost.png` — Absolute Latency Cost by Backend
- `images/11_high_capacity_normalized_load_ratio.png` — Capacity-normalized Load Ratio by Backend
- `images/12_high_cpu_usage_normalized.png` — CPU usage normalized by container quota
- `images/13_high_memory_usage.png` — Memory Usage per Container (% of Limit)
