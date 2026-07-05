# Low Load 300 RPS

## Mục tiêu kịch bản

Đánh giá độ ổn định và overhead của các thuật toán khi hệ thống còn dư tài nguyên, không có fault injection.

- **Mức tải mục tiêu:** 300 RPS
- **Nguồn số liệu chính:** `data/jtl-summary.csv`
- **Phạm vi tính toán:** chỉ các sampler có nhãn `MEASURE_`
- **Số lần chạy kỳ vọng:** 3 run cho mỗi thuật toán
- **Thứ tự chạy xác định từ Grafana:** Round Robin → Random → Least Connections → Adaptive


## Kết quả trung bình

| Thuật toán | Run | Avg (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Error (%) | Actual RPS |
|---|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 3 | 385.64 | 1116.67 | 1326.00 | 2790.21 | 0.0000 | 266.31 |
| Least Connections | 3 | 391.45 | 1133.33 | 1340.67 | 2797.18 | 0.0000 | 260.00 |
| Random | 3 | 381.46 | 1113.33 | 1325.03 | 2778.68 | 0.0000 | 265.75 |
| Round Robin | 3 | 387.08 | 1119.67 | 1329.00 | 2778.29 | 0.0000 | 265.73 |

## So sánh Adaptive với baseline

| So sánh Adaptive với | Avg giảm | P95 giảm | P99 giảm | Error giảm | Throughput thay đổi |
|---|---:|---:|---:|---:|---:|
| Least Connections | 1.48% | 1.09% | 0.25% | N/A | 2.43% |
| Random | -1.10% | -0.07% | -0.41% | N/A | 0.21% |
| Round Robin | 0.37% | 0.23% | -0.43% | N/A | 0.22% |

## Đối chiếu với Grafana

- **Error rate bằng 0%** trong toàn bộ khoảng chạy, cho thấy hệ thống ổn định ở mức tải thấp.
- **Throughput giữ sát 300 RPS** ở từng lần chạy; các đoạn rơi về 0 là thời gian nghỉ giữa các run.
- Routing Selection Rate by Decision Reason cho thấy ba baseline chạy trước, sau đó Adaptive sử dụng chủ yếu NORMAL_P2C và HEALTH_DOMINANT.
- MCDM weights có dao động trong lúc Adaptive chạy nhưng quay về AHP mặc định ở cuối khoảng đo.

## Kết luận chính

**Ở Low Load, Adaptive hoạt động ổn định và không phát sinh lỗi, nhưng chưa tạo lợi thế rõ rệt vì hệ thống chưa có suy giảm cục bộ.**

## Dữ liệu và hình ảnh đi kèm

- `data/jtl-summary.csv` — kết quả tổng hợp từ raw JTL.
- `data/aggregate-summary-clean.csv` — bảng tổng hợp gọn theo thuật toán.
- `data/strategy-mean-std.csv` — mean/std của các chỉ số chính.
- `data/adaptive-comparison-vs-baselines.csv` — mức cải thiện của Adaptive so với các baseline.
- `notes/run-order.md` — thứ tự chạy xác định từ panel Decision Reason.
- `notes/quick-summary.txt` — tóm tắt ngắn.

### Ảnh Grafana

- `images/01_low_gateway_latency_all_statuses.png` — Gateway Latency Percentiles — All HTTP Statuses
- `images/02_low_gateway_throughput_actual_rps.png` — Gateway Throughput — Actual RPS
- `images/03_low_gateway_error_rate.png` — Gateway Error Rate — Non-2xx Requests
- `images/04_low_routing_selection_by_backend.png` — Routing Selection Rate by Backend
- `images/05_low_routing_selection_by_reason.png` — Routing Selection Rate by Decision Reason
- `images/06_low_mcdm_weights.png` — Dynamic MCDM criterion weights
- `images/07_low_final_routing_cost.png` — Final Routing Cost by Backend
