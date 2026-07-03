# Xác định ngưỡng chịu tải R_sat

## 1. Mục tiêu

Kịch bản này dùng để xác định **ngưỡng R_sat (RPS saturation)** của hệ thống trước khi chia các mức tải chính cho benchmark. R_sat được hiểu là **mức RPS cao nhất mà hệ thống còn duy trì ổn định**, trước khi throughput không còn tăng tương ứng và tail latency/error rate bắt đầu tăng rõ rệt.

Kết quả của bước này không dùng để so sánh thuật toán cân bằng tải, mà dùng để **xác định cơ sở chia tải Low, Medium, High và Stress**.

## 2. File kiểm thử liên quan

| File JMeter | Vai trò |
|---|---|
| `jmeter/00_rsat_discovery_mixed_1000plus_600_1600_tst.jmx` | Tăng tải theo nhiều mức RPS để tìm vùng bắt đầu bão hòa. |
| `jmeter/00b_rsat_confirm_mixed_1000plus_1000_1250_tst.jmx` | Kiểm tra lại vùng gần ngưỡng bằng các mức RPS hẹp hơn. |

Dữ liệu thống kê chỉ lấy từ các sampler có nhãn `MEASURE_`. Các giai đoạn ramp-up, stabilize, end-guard, ramp-down và các thao tác setup/teardown không được dùng để kết luận.

## 3. Cách đánh giá ổn định

Một mức tải được xem là còn ổn định khi:

- **Actual RPS bám sát Target RPS**;
- **Error rate gần 0%**;
- **P95/P99 chưa tăng đột biến**;
- **queue/inflight không bị tích lũy kéo dài**;
- hệ thống không xuất hiện timeout hoặc backlog làm ảnh hưởng sang mức tải kế tiếp.

Ngược lại, một mức tải được xem là vượt ngưỡng khi throughput không còn tăng tương ứng với target, trong khi error rate, P95/P99 hoặc queue/inflight tăng rõ rệt.

## 4. Kết quả trọng tâm

**Hệ thống duy trì ổn định đến khoảng 1100 RPS.** Ở các mức tải dưới và bằng 1100 RPS, hệ thống vẫn còn giữ được throughput gần mục tiêu và chưa xuất hiện suy giảm nghiêm trọng.

**Tại 1200 RPS, hệ thống bắt đầu vượt vùng vận hành ổn định.** Tail latency và tỷ lệ lỗi tăng rõ hơn, cho thấy hệ thống đã đi vào vùng bão hòa.

Vì vậy, trong phạm vi cấu hình kiểm thử của dự án, ngưỡng chịu tải thực nghiệm được xác định là:

> **R_sat = 1100 RPS**

## 5. Chia mức tải từ R_sat

Từ R_sat = 1100 RPS, các mức tải chính được chọn như sau:

| Mức tải | RPS sử dụng | Ý nghĩa |
|---|---:|---|
| Low | 300 RPS | Tải thấp, hệ thống còn dư tài nguyên, dùng để kiểm tra overhead và độ ổn định cơ bản. |
| Medium | 600 RPS | Tải trung bình, đủ tạo khác biệt khi có dependency slowdown nhưng chưa đẩy hệ thống đến vùng bão hòa. |
| High | 900 RPS | Tải cao, gần ngưỡng R_sat, dùng để làm rõ khác biệt giữa các thuật toán khi có suy giảm cục bộ. |
| Stress | 1200 RPS | Vượt ngưỡng ổn định, dùng để quan sát graceful degradation và khả năng phục hồi. |

## 6. Kết luận

**R_sat = 1100 RPS là mốc nền để thiết kế toàn bộ benchmark chính.** Cách chia tải này giúp các kịch bản không bị quá nhẹ đến mức thuật toán nào cũng giống nhau, đồng thời không quá nặng đến mức toàn hệ thống sập và mất ý nghĩa so sánh.

Khi sử dụng kết quả này trong luận văn, cần hiểu rằng R_sat là **ngưỡng thực nghiệm của cấu hình hiện tại** gồm Gateway, Eureka, ba backend Docker container, mixed workload và môi trường máy chủ cụ thể. Nếu thay đổi phần cứng, Docker quota, workload hoặc network, R_sat cần được đo lại.
