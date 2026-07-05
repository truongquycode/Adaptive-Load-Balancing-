# Official Benchmark Results — 3 Runs

Thư mục này lưu kết quả benchmark chính thức phục vụ phân tích trong luận văn. Bộ dữ liệu hiện có gồm ba mức tải: **Low 300 RPS**, **Medium 600 RPS** và **High 900 RPS**. Stress test chưa được đưa vào bộ kết quả này.

## Nguyên tắc tổng hợp

- Kết quả được tổng hợp từ `jtl-summary.csv` của từng kịch bản.
- Chỉ các sampler có nhãn `MEASURE_` được dùng để tính số liệu.
- Các giai đoạn setup, teardown, ramp-up, ramp-down và guard không được dùng để kết luận hiệu năng.
- Thứ tự chạy không giả định cố định; thứ tự trong từng mức tải được xác định từ panel Grafana `Routing Selection Rate by Decision Reason`.

## Tóm tắt Adaptive theo từng mức tải

| Mức tải | Thứ tự chạy từ Grafana | Adaptive Avg | Adaptive P95 | Adaptive P99 | Adaptive Error | Adaptive RPS |
|---|---|---:|---:|---:|---:|---:|
| Low Load 300 RPS | Round Robin → Random → Least Connections → Adaptive | 385.64 | 1326.00 | 2790.21 | 0.0000% | 266.31 |
| Medium Load 600 RPS — Dependency Slowdown | Adaptive → Least Connections → Random → Round Robin | 392.92 | 1346.00 | 2806.67 | 0.0000% | 555.77 |
| High Load 900 RPS — Dependency Slowdown | Random → Adaptive → Round Robin | 662.96 | 2237.65 | 3803.56 | 0.0316% | 824.35 |

## Kết luận tổng hợp

- **Low Load:** Adaptive ổn định, error bằng 0%, nhưng không tạo lợi thế rõ rệt vì hệ thống chưa bị suy giảm cục bộ.
- **Medium Load:** Adaptive cho kết quả tốt nhất về Average, P90, P95, P99 và giữ error rate bằng 0%; đây là mức tải thể hiện rõ lợi ích thích nghi trong điều kiện vừa phải.
- **High Load:** Adaptive giảm mạnh error rate và tail latency so với Random/Round Robin. Tuy nhiên, dữ liệu high chưa đủ Least Connections và Round Robin mới có 2 run, nên chưa dùng để kết luận đầy đủ cho cả 4 thuật toán.

## Cấu trúc thư mục

```text
docs/benchmark-results/official-runs/2026-07-official-3runs/
├── README.md
├── official-3runs-overview.csv
├── 01-low-load/
├── 02-medium-dependency-slowdown/
└── 03-high-dependency-slowdown/
```

Mỗi thư mục con gồm:

```text
README.md
data/
  jtl-summary.csv
  aggregate-summary-clean.csv
  strategy-mean-std.csv
  adaptive-comparison-vs-baselines.csv
images/
notes/
  image-index.json
  run-order.md
  quick-summary.txt
```

## Hướng sử dụng trong luận văn

Khi viết kết quả, nên dùng bảng `aggregate-summary-clean.csv` để lấy số liệu chính, dùng `adaptive-comparison-vs-baselines.csv` để trình bày mức cải thiện của Adaptive và dùng ảnh trong `images/` để giải thích nguyên nhân hệ thống ở từng mức tải.
