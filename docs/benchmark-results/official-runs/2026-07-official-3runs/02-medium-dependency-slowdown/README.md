# Medium Load 600 RPS — Dependency Slowdown

## Mục tiêu kịch bản

Đánh giá khả năng thích nghi khi một backend bị suy giảm cục bộ ở mức tải vừa.

- **Mức tải mục tiêu:** 600 RPS
- **Nguồn số liệu chính:** `data/jtl-summary.csv`
- **Phạm vi tính toán:** chỉ các sampler có nhãn `MEASURE_`
- **Số lần chạy kỳ vọng:** 3 run cho mỗi thuật toán
- **Thứ tự chạy xác định từ Grafana:** Adaptive → Least Connections → Random → Round Robin


## Kết quả trung bình

| Thuật toán | Run | Avg (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Error (%) | Actual RPS |
|---|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 3 | 392.92 | 1131.33 | 1346.00 | 2806.67 | 0.0000 | 555.77 |
| Least Connections | 3 | 411.12 | 1175.00 | 1394.67 | 2870.28 | 0.0002 | 576.11 |
| Random | 3 | 502.24 | 1330.00 | 1836.67 | 3189.00 | 0.0070 | 548.45 |
| Round Robin | 3 | 995.55 | 3297.67 | 4646.78 | 7465.00 | 0.7879 | 545.86 |

## So sánh Adaptive với baseline

| So sánh Adaptive với | Avg giảm | P95 giảm | P99 giảm | Error giảm | Throughput thay đổi |
|---|---:|---:|---:|---:|---:|
| Least Connections | 4.43% | 3.49% | 2.22% | 100.00% | -3.53% |
| Random | 21.77% | 26.72% | 11.99% | 100.00% | 1.34% |
| Round Robin | 60.53% | 71.03% | 62.40% | 100.00% | 1.82% |

## Đối chiếu với Grafana

- **Throughput phần lớn giữ quanh 600 RPS**, riêng một đoạn cuối có dao động khi hệ thống xuất hiện backlog.
- Error rate nhìn chung rất thấp, nhưng có spike ngắn ở cuối khoảng chạy; khi đối chiếu Decision Reason, spike này nằm ở phần baseline cuối.
- Queue Depth cho thấy backend 8083 có lúc tích lũy inflight cao, giải thích hiện tượng tail latency tăng ở một số đoạn.
- Trong đoạn Adaptive, Decision Reason chủ yếu là NORMAL_P2C kết hợp HEALTH_DOMINANT; Absolute Latency Cost có spike ngắn nhưng không kéo dài.

## Kết luận chính

**Ở Medium Load, Adaptive là thuật toán tốt nhất về Average, P90, P95, P99 và giữ error rate bằng 0%.**

## Dữ liệu và hình ảnh đi kèm

- `data/jtl-summary.csv` — kết quả tổng hợp từ raw JTL.
- `data/aggregate-summary-clean.csv` — bảng tổng hợp gọn theo thuật toán.
- `data/strategy-mean-std.csv` — mean/std của các chỉ số chính.
- `data/adaptive-comparison-vs-baselines.csv` — mức cải thiện của Adaptive so với các baseline.
- `notes/run-order.md` — thứ tự chạy xác định từ panel Decision Reason.
- `notes/quick-summary.txt` — tóm tắt ngắn.

### Ảnh Grafana

- `images/01_medium_gateway_latency_all_statuses.png` — Gateway Latency Percentiles — All HTTP Statuses
- `images/02_medium_gateway_throughput_actual_rps.png` — Gateway Throughput — Actual RPS
- `images/03_medium_gateway_error_rate.png` — Gateway Error Rate — Non-2xx Requests
- `images/04_medium_routing_selection_by_backend.png` — Routing Selection Rate by Backend
- `images/05_medium_routing_selection_by_reason.png` — Routing Selection Rate by Decision Reason
- `images/06_medium_queue_depth.png` — Queue Depth (inflight requests) theo instance
- `images/07_medium_ewma_latency_by_backend.png` — EWMA latency by backend
- `images/08_medium_final_routing_cost.png` — Final Routing Cost by Backend
- `images/09_medium_absolute_latency_cost.png` — Absolute Latency Cost by Backend
- `images/10_medium_capacity_normalized_load_ratio.png` — Capacity-normalized Load Ratio by Backend
- `images/11_medium_mcdm_weights.png` — Dynamic MCDM criterion weights
