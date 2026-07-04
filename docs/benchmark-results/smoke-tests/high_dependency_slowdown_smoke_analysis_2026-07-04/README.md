# High Load Smoke Test — 900 RPS, Dependency Slowdown

## Mục tiêu

Kịch bản này kiểm tra hệ thống ở mức **High Load 900 RPS** khi một backend bị suy giảm cục bộ. Kết quả chỉ dùng cho smoke test sau tuning, chưa thay thế kết quả benchmark chính thức nhiều lần chạy.

## Phạm vi dữ liệu

- Chỉ sử dụng các sampler có nhãn **MEASURE_**.
- Thứ tự chạy: **Adaptive → Least Connections → Random → Round Robin**.
- Các giai đoạn đo gồm baseline, dependency slowdown và recovery.

## Kết quả tổng hợp

| Thuật toán | Samples | Errors | Error (%) | Throughput (req/s) | Avg (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Max (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Adaptive | 180252 | 5450 | 3.02 | 546.20 | 3992.73 | 8714.0 | 17491.0 | 30001.0 | 43318 |
| Least Connections | 219163 | 4178 | 1.91 | 663.41 | 3157.77 | 6720.0 | 11537.8 | 30001.0 | 39783 |
| Random | 182903 | 31142 | 17.03 | 554.27 | 3943.91 | 11441.0 | 15648.0 | 22882.0 | 37987 |
| Round Robin | 209122 | 38774 | 18.54 | 624.74 | 3241.66 | 9860.0 | 15099.0 | 17983.6 | 34993 |

## Trọng tâm kết quả

- **Random và Round Robin xuất hiện lỗi rất cao** trong high load, lần lượt khoảng **17.03%** và **18.54%**.
- **Adaptive giảm mạnh error so với Random/Round Robin**, còn khoảng **3.02%**, nhưng vẫn chưa ổn định tuyệt đối ở run này.
- **Least Connections có kết quả tổng hợp tốt nhất trong smoke test này** về Avg, P90, P95 và error rate.
- **Adaptive chưa thắng Least Connections ở high load aggregate**, vì tail latency của Adaptive còn cao, đặc biệt P95 đạt **17491 ms** và P99 chạm **30001 ms**.
- **Kết quả này cho thấy high load cần phân tích kỹ hơn trước khi chạy stress test chính thức**, vì hệ thống đã bắt đầu có timeout và lỗi ở nhiều thuật toán.

## Nhận xét từ Grafana

- **Gateway latency tăng mạnh ở các đoạn sau**, P99 có thời điểm vượt vùng 10–15 giây.
- **Error rate tăng rõ trong các run Random và Round Robin**, phù hợp với bảng JMeter.
- **Throughput thực tế dao động mạnh**, cho thấy hệ thống không còn giữ tải ổn định như medium load.
- **Routing Selection Rate by Backend** cho thấy Adaptive phân phối không đều theo trạng thái backend, trong khi các thuật toán nền có xu hướng dễ dồn request vào backend đang chậm hơn.
- **Routing Selection Rate by Decision Reason** cho thấy Adaptive chủ yếu dùng NORMAL_P2C và HEALTH_DOMINANT; LOAD_DOMINANT rất ít, WARMUP_RR chỉ xuất hiện ngắn.
- **EWMA latency của 8083 có nhiều spike**, chứng tỏ backend này có các thời điểm phản hồi chậm rõ rệt.

## Kết luận

**High load smoke test chưa đủ ổn để kết luận Adaptive đã tối ưu.** Adaptive kiểm soát lỗi tốt hơn Random và Round Robin, nhưng **Least Connections đang tốt hơn Adaptive trong kết quả aggregate của run này**. Trước khi đưa high load vào kết quả chính thức, cần chạy thêm 3–5 lần và kiểm tra riêng từng phase MEASURE, đặc biệt là giai đoạn dependency slowdown.

## File dữ liệu và hình ảnh

- `data/jtl-summary.csv`: dữ liệu tổng hợp từ JMeter, đã lọc nhãn MEASURE.
- `data/aggregate-summary-clean.csv`: bảng tổng hợp gọn theo thuật toán.
- `data/adaptive-comparison-vs-baselines.csv`: tỷ lệ chênh lệch của Adaptive so với các thuật toán nền.
- `images/`: ảnh Grafana dùng để đối chiếu latency, error, throughput và routing behavior.
