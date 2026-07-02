# Phân tích smoke test medium dependency slowdown sau tuning thuật toán

**Ngày tổng hợp:** 2026-07-02  
**Kịch bản:** `02_medium_dependency_slowdown_mixed_0600_tst`  
**Mục đích:** kiểm tra nhanh sau khi tinh chỉnh thuật toán Adaptive Load Balancer, trước khi chạy benchmark chính thức nhiều lần.  
**Lưu ý:** đây là **1 run/strategy**, dùng để xác minh hệ thống và quan sát xu hướng. Chưa dùng để kết luận cuối cùng trong luận văn.

## 1. Nội dung thư mục

```text
medium_dependency_slowdown_smoke_analysis_2026-07-02/
├── README.md
├── data/
│   ├── jtl-summary.csv
│   ├── aggregate-summary-clean.csv
│   └── adaptive-improvement-vs-baselines.csv
├── images/
│   ├── 01_gateway_latency_all_http_statuses.png
│   ├── 02_gateway_throughput_actual_rps.png
│   ├── 03_routing_selection_rate_by_backend.png
│   ├── 04_dynamic_mcdm_criterion_weights.png
│   ├── 05_final_routing_cost_by_backend.png
│   ├── 06_capacity_normalized_load_ratio_by_backend.png
│   └── 07_ewma_latency_by_backend.png
└── notes/
    └── image-index.json
```

## 2. Kết quả JMeter tổng hợp

| Strategy | Samples | Errors | Error % | Throughput (req/s) | Avg (ms) | P50 | P90 | P95 | P99 | Max |
|---|---|---|---|---|---|---|---|---|---|---|
| Adaptive | 242,134 | 1 | 0.0004 | 493.81 | 338.90 | 92 | 1079 | 1289 | 2739 | 6347 |
| Least Connections | 242,314 | 0 | 0.0000 | 496.22 | 338.28 | 73 | 1088 | 1302 | 2764 | 8353 |
| Random | 241,843 | 1 | 0.0004 | 494.63 | 353.38 | 93 | 1106 | 1326 | 2779 | 10268 |
| Round Robin | 241,717 | 1 | 0.0004 | 491.59 | 358.38 | 101 | 1111 | 1332 | 2784 | 6358 |

## 3. Adaptive cải thiện so với baseline

Công thức: `(baseline - adaptive) / baseline * 100%`. Giá trị dương nghĩa là Adaptive thấp hơn baseline ở chỉ số đó.

| Baseline | Avg giảm | P90 giảm | P95 giảm | P99 giảm | Max giảm |
|---|---|---|---|---|---|
| Round Robin | 5.44% | 2.88% | 3.23% | 1.62% | 0.17% |
| Random | 4.10% | 2.44% | 2.79% | 1.44% | 38.19% |
| Least Connections | -0.18% | 0.83% | 1.00% | 0.90% | 24.02% |

## 4. Nhận xét từ JMeter

- Cả 4 thuật toán đều giữ throughput gần nhau, khoảng **491–496 req/s**, nên phép so sánh latency không bị lệch lớn do thuật toán này nhận tải ít hơn thuật toán khác.
- Error gần như bằng 0. Adaptive có 1 lỗi trên hơn 242 nghìn mẫu, tương đương khoảng **0.0004%**, chưa có dấu hiệu lỗi hệ thống.
- Adaptive có Avg thấp hơn Round Robin và Random, đồng thời P90/P95/P99 thấp hơn cả Round Robin, Random và Least Connections. Tuy nhiên mức chênh lệch P95/P99 còn nhỏ, nên cần chạy 3–5 runs để xác nhận.
- Least Connections có Avg gần Adaptive và P50 tốt hơn Adaptive, nhưng Adaptive có tail latency và Max tốt hơn. Đây là dấu hiệu Adaptive có xu hướng kiểm soát request chậm tốt hơn, nhưng chưa thể kết luận mạnh từ 1 run.

## 5. Nhận xét từ Grafana

### 5.1. Gateway latency all-status

File ảnh: `images/01_gateway_latency_all_http_statuses.png`

- Các giai đoạn chạy chiến lược xuất hiện thành từng cụm rõ ràng. P90/P95/P99 ổn định theo plateau và tăng mạnh ở cuối mỗi run do pha ramp-down/teardown hoặc chuyển pha.
- Panel này dùng **all HTTP statuses**, nên phù hợp hơn panel chỉ lọc HTTP 200. Đây là bằng chứng tốt để tránh phản biện “chỉ chọn request thành công”.

### 5.2. Throughput thực tế

File ảnh: `images/02_gateway_throughput_actual_rps.png`

- Actual RPS trong mỗi run đạt plateau khoảng **600 req/s** trong pha chính, khớp mục tiêu medium scenario.
- Các đoạn tụt về gần 0 là khoảng nghỉ/chuyển strategy giữa các lần chạy, không phải lỗi thuật toán.

### 5.3. Routing selection rate by backend

File ảnh: `images/03_routing_selection_rate_by_backend.png`

- Các baseline như Random/Round Robin có xu hướng phân phối gần đều khoảng 200 req/s cho mỗi backend khi tổng tải khoảng 600 req/s.
- Ở cửa sổ Adaptive, traffic không chia đều tuyệt đối. Backend `8083` có lúc bị giảm mạnh, trong khi `8081` và `8082` nhận nhiều hơn. Đây là dấu hiệu Adaptive phản ứng với health/load khác nhau thay vì chia mù.
- Mẫu này phù hợp với mục tiêu thuật toán: không chỉ cân bằng số request, mà cân bằng theo chi phí định tuyến dựa trên latency/queue/CPU/capacity.

### 5.4. Dynamic MCDM criterion weights

File ảnh: `images/04_dynamic_mcdm_criterion_weights.png`

- Trong thời gian Adaptive chạy, trọng số latency vẫn chiếm ưu thế lớn, khoảng 50–60% ở nhiều thời điểm.
- CPU weight tăng lên khoảng 25–36% trong một số đoạn, cho thấy DynamicWeightEngine có phản ứng với khác biệt tài nguyên/CPU runtime.
- Queue weight thấp hơn trong đoạn này. Điều này không sai nếu queue không phải tiêu chí phân biệt mạnh nhất ở thời điểm đó.
- Sau khi hết traffic, trọng số có xu hướng trở về AHP prior, phù hợp với cơ chế real-traffic gate đã sửa trước đó.

### 5.5. Final routing cost by backend

File ảnh: `images/05_final_routing_cost_by_backend.png`

- Trong cửa sổ Adaptive, cost của các backend dao động theo metrics runtime.
- `8083` nhiều thời điểm có cost cao hơn, tương ứng với việc Adaptive giảm traffic tới backend này ở routing selection.
- Có một số điểm tụt sâu/spike ngắn, nhưng không kéo dài. Cần kiểm tra thêm ở 3–5 runs để đánh giá oscillation.

### 5.6. Capacity-normalized load ratio

File ảnh: `images/06_capacity_normalized_load_ratio_by_backend.png`

- Load ratio cho thấy Adaptive không chỉ nhìn số request tuyệt đối, mà có xét tương quan với capacity.
- `8083` có nhiều đoạn load ratio thấp hơn rõ rệt so với `8081/8082`, phù hợp với việc backend yếu hoặc có latency xấu được giảm tải.
- Một vài đoạn load ratio vượt 1.2–1.3 cho `8081/8082` thể hiện các backend này đang nhận nhiều tải hơn expected share để bù cho node kém hơn. Đây là hành vi hợp lý nếu latency/error vẫn ổn.

### 5.7. EWMA latency by backend

File ảnh: `images/07_ewma_latency_by_backend.png`

- `8083` có nhiều spike EWMA latency cao, có lúc vượt 2 giây. Điều này giải thích vì sao Adaptive giảm traffic về `8083` trong cửa sổ Adaptive.
- `8081` và `8082` nhìn chung ổn định hơn, dù có một số spike ngắn gần cuối.
- Panel này là bằng chứng quan trọng cho luận điểm: Adaptive không giảm traffic ngẫu nhiên, mà phản ứng theo latency đã làm mượt.

## 6. Kết luận tạm thời

Smoke test này cho thấy bản tuning mới **chạy ổn định** ở kịch bản medium dependency slowdown:

- Throughput giữ tương đương baseline.
- Error gần như bằng 0.
- Adaptive cải thiện nhẹ Avg/P90/P95/P99 so với Round Robin và Random.
- Adaptive có tail latency nhỉnh hơn Least Connections, đặc biệt Max thấp hơn.
- Grafana cho thấy Adaptive có phản ứng hợp lý: `8083` xuất hiện spike latency và được giảm traffic trong một số giai đoạn.

Tuy nhiên, do đây chỉ là **1 run**, kết luận nên ghi là:

> Bản tuning có xu hướng cải thiện tail latency và phản ứng đúng với backend có latency cao, nhưng cần chạy lặp lại 3–5 lần để xác nhận tính ổn định thống kê.

Không nên ghi là:

> Adaptive vượt trội hoàn toàn.

## 7. Việc cần làm tiếp theo

1. Chạy medium scenario đủ 3–5 runs/strategy.
2. Chạy `5-run_adaptive_ablation_medium.bat` để kiểm tra đóng góp của PID, EWM, capacity, P2C, probe.
3. Chụp thêm panel `Gateway Error Rate — Non-2xx Requests`, vì bộ ảnh hiện tại chưa có error-rate panel.
4. Với benchmark chính thức, lưu riêng time range từng strategy để tránh ảnh Grafana bị lẫn nhiều run trong một hình.
