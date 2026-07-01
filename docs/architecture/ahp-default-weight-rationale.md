# Cơ sở chọn AHP prior mặc định cho DynamicWeightEngine

Tài liệu này giải thích cơ sở chọn bộ trọng số mặc định trong `DynamicWeightEngine`:

```text
latency = 0.648
queue   = 0.230
cpu     = 0.122
```

Bộ trọng số này không phải là kết quả học máy và cũng không phải trọng số tối ưu tuyệt đối. Đây là **AHP prior**: một bộ trọng số nền được thiết lập bằng Analytic Hierarchy Process (AHP), sau đó được `DynamicWeightEngine` dùng làm điểm neo khi hệ thống idle, khi chưa đủ traffic thật hoặc khi chạy ablation `fixed-weights`.

---

## 1. Vì sao dùng AHP prior trong dự án

Trong bài toán cân bằng tải thích nghi, Gateway phải đánh giá trạng thái backend dựa trên nhiều tiêu chí cùng lúc. Ba tiêu chí trong dự án là:

| Tiêu chí | Biến trong code | Ý nghĩa |
|---|---|---|
| Latency | `normLatency` | Chi phí độ trễ sau EWMA và chuẩn hóa |
| Queue | `normQueue` | Chi phí hàng đợi/inflight hiện tại |
| CPU | `normCpu` | Chi phí áp lực tài nguyên CPU |

AHP phù hợp ở đây vì nó cho phép xác định mức quan trọng tương đối giữa các tiêu chí bằng so sánh cặp, thay vì gán trực tiếp các số 0.6, 0.3, 0.1 theo cảm tính. Trong AHP, bài toán được tách thành mục tiêu, tiêu chí và phương án; trọng số tiêu chí được tạo từ ma trận so sánh cặp và có thể kiểm tra tính nhất quán bằng Consistency Ratio (CR).

Trong dự án này, AHP chỉ được dùng cho **criteria weights**. Phần lựa chọn backend cuối cùng vẫn do `RoutingCostCalculator` và `AdaptiveLoadBalancer` thực hiện dựa trên health cost, load cost, stale penalty, capacity và P2C.

---

## 2. Cấu trúc AHP dùng cho dự án

```text
Goal
  Chọn backend có chi phí định tuyến thấp nhất

Criteria
  C1: Latency
  C2: Queue / Inflight
  C3: CPU usage

Alternatives
  registration-8081
  registration-8082
  registration-8083
```

Ba tiêu chí trên đều là **cost criteria**: giá trị càng cao thì backend càng kém phù hợp để nhận thêm request. Vì vậy, trọng số AHP không biểu diễn “điểm tốt”, mà biểu diễn mức độ quan trọng của từng loại chi phí trong health score.

---

## 3. Cơ sở xếp thứ tự ưu tiên Latency > Queue > CPU

### 3.1. Latency quan trọng nhất

Latency phản ánh trực tiếp trải nghiệm request và SLA của hệ thống. Trong microservices, latency là chỉ số “hộp đen” vì nó hấp thụ nhiều nguyên nhân bên trong backend như CPU, queue, I/O, dependency slowdown, GC hoặc nghẽn thread pool.

Vì mục tiêu của đề tài là cân bằng tải dựa trên độ trễ thời gian thực, latency được đặt là tiêu chí có trọng số cao nhất.

### 3.2. Queue đứng thứ hai

Queue/inflight là tín hiệu sớm của quá tải. Khi số request đang xử lý hoặc đang chờ tăng, backend có nguy cơ tăng latency trong các chu kỳ tiếp theo. Queue đặc biệt hữu ích vì nó còn phân biệt được mức quá tải khi CPU đã gần bão hòa.

Tuy nhiên, queue là tín hiệu nội bộ của backend, chưa phản ánh trực tiếp kết quả người dùng nhận được. Vì vậy, queue thấp hơn latency nhưng vẫn cao hơn CPU.

### 3.3. CPU đứng thứ ba

CPU usage phản ánh áp lực tài nguyên, nhưng không đủ để đánh giá backend một mình. Một backend có thể CPU cao nhưng request vẫn nhanh nếu workload chủ yếu CPU-bound ngắn; ngược lại CPU không quá cao nhưng latency vẫn xấu nếu bị dependency slowdown, I/O wait, lock hoặc queue dài.

Vì vậy, CPU được dùng như tiêu chí phụ trợ để phát hiện áp lực tài nguyên, không phải tiêu chí quyết định chính.

---

## 4. Ma trận so sánh cặp AHP

Thứ tự tiêu chí:

```text
C1 = Latency
C2 = Queue
C3 = CPU
```

Ma trận so sánh cặp được chọn:

```text
A = | 1    3    5 |
    | 1/3  1    2 |
    | 1/5  1/2  1 |
```

Diễn giải:

| So sánh | Giá trị | Lý do |
|---|---:|---|
| Latency so với Queue | 3 | Latency quan trọng hơn vừa phải vì phản ánh trực tiếp QoS/SLA, còn queue là tín hiệu nguyên nhân. |
| Latency so với CPU | 5 | Latency quan trọng hơn rõ rệt vì CPU có thể bão hòa hoặc gây hiểu nhầm khi bottleneck nằm ở I/O, dependency hoặc queue. |
| Queue so với CPU | 2 | Queue quan trọng hơn CPU nhưng chỉ ở mức thỏa hiệp, vì CPU vẫn hữu ích để nhận biết áp lực tài nguyên. Giá trị 2 cũng giúp ma trận nhất quán với hai so sánh trước. |

Ma trận là reciprocal matrix:

```text
a_ij = 1 / a_ji
```

---

## 5. Tính trọng số bằng chuẩn hóa cột và trung bình hàng

### 5.1. Tổng từng cột

```text
Column latency = 1 + 1/3 + 1/5 = 1.533
Column queue   = 3 + 1 + 1/2   = 4.500
Column cpu     = 5 + 2 + 1     = 8.000
```

### 5.2. Ma trận chuẩn hóa xấp xỉ

```text
A_norm ≈ | 0.652  0.667  0.625 |
         | 0.217  0.222  0.250 |
         | 0.130  0.111  0.125 |
```

### 5.3. Trọng số ưu tiên

Lấy trung bình từng hàng:

```text
Latency = (0.652 + 0.667 + 0.625) / 3 = 0.648
Queue   = (0.217 + 0.222 + 0.250) / 3 = 0.230
CPU     = (0.130 + 0.111 + 0.125) / 3 = 0.122
```

Do đó:

```text
W = [0.648, 0.230, 0.122]
```

Đây chính là bộ trọng số đang hard-code trong `DynamicWeightEngine.java`:

```java
private static final double[] AHP_WEIGHTS = { 0.648, 0.230, 0.122 };
```

---

## 6. Kiểm tra Consistency Ratio

AHP cần kiểm tra các so sánh cặp có nhất quán hay không. Với vector trọng số:

```text
W = [0.648, 0.230, 0.122]
```

Nhân ma trận gốc với W:

```text
A * W ≈ [1.948, 0.690, 0.367]
```

Chia từng phần tử cho trọng số tương ứng:

```text
1.948 / 0.648 ≈ 3.007
0.690 / 0.230 ≈ 3.003
0.367 / 0.122 ≈ 3.001
```

Suy ra:

```text
lambda_max ≈ 3.004
CI = (lambda_max - n) / (n - 1)
CI ≈ (3.004 - 3) / 2 ≈ 0.002
```

Với ma trận 3 tiêu chí, nếu dùng `RI = 0.58`:

```text
CR = CI / RI ≈ 0.002 / 0.58 ≈ 0.003
```

Kết quả:

```text
CR << 0.1
```

Do đó, ma trận so sánh cặp có tính nhất quán tốt. Nói cách khác, bộ trọng số `0.648 / 0.230 / 0.122` không chỉ là cảm tính mà có thể giải thích được bằng quy trình AHP.

---

## 7. Cách DynamicWeightEngine dùng AHP prior

Trong code, AHP prior được dùng ở ba tình huống chính:

| Tình huống | Hành vi |
|---|---|
| Gateway vừa khởi động hoặc reset | dùng AHP prior làm trọng số ban đầu |
| Không có đủ traffic thật | reset hoặc giữ AHP prior để tránh học từ nhiễu idle |
| `ALB_ABLATION_VARIANT=fixed-weights` | tắt EWM, chỉ dùng AHP prior |

Khi có đủ traffic thật, `DynamicWeightEngine` có thể tính EWM từ `normLatency`, `normQueue`, `normCpu`, sau đó blend với AHP prior:

```text
target = 0.70 * EWM + 0.30 * AHP
```

Vì vậy, AHP prior đóng vai trò **điểm neo ổn định**, còn EWM đóng vai trò **tín hiệu thích nghi theo dữ liệu runtime**.

---

## 8. Cách trình bày trong luận văn

Nên viết:

```text
Bộ trọng số mặc định của DynamicWeightEngine được thiết lập bằng AHP dựa trên ba tiêu chí latency, queue và CPU. Ma trận so sánh cặp được chọn là [[1,3,5],[1/3,1,2],[1/5,1/2,1]], từ đó thu được vector trọng số [0.648, 0.230, 0.122]. Consistency Ratio nhỏ hơn 0.1 nên ma trận có tính nhất quán chấp nhận được. Trong hệ thống, bộ trọng số này được dùng như AHP prior; khi có đủ traffic thật, EWM có thể điều chỉnh trọng số quanh prior này để thích nghi với trạng thái runtime.
```

Không nên viết:

```text
AHP chứng minh đây là bộ trọng số tối ưu tuyệt đối cho mọi hệ thống microservices.
```

AHP chỉ chứng minh rằng bộ trọng số có cơ sở ra quyết định đa tiêu chí và nhất quán theo ma trận phán đoán đã chọn. Để chứng minh hiệu quả thực tế, vẫn cần benchmark, ablation và sensitivity analysis.

---

## 9. Giới hạn

- Ma trận AHP dựa trên lập luận kỹ thuật và đặc thù mục tiêu của đề tài: ưu tiên latency thời gian thực.
- Với hệ thống khác, ví dụ CPU-bound nặng hoặc batch processing, thứ tự ưu tiên có thể thay đổi.
- CPU quota, queue và latency trong Docker là môi trường thực nghiệm, không đại diện hoàn toàn cho production.
- EWM chỉ có 3 backend nên cần real-traffic gate, clamp và EMA để tránh dao động.


---

## 10. Nguồn tham khảo nội bộ/tài liệu kèm theo

- `Note_AHP.pdf`: mô tả quy trình AHP gồm tạo ma trận so sánh cặp, chuẩn hóa cột, lấy trung bình hàng để tạo vector trọng số, và kiểm tra Consistency Ratio bằng `CI / RI < 0.1`.
- `Paper_ConstructionEngineeringandManagement.pdf`: minh họa việc dùng AHP cho quyết định đa tiêu chí, trong đó AHP giúp kết hợp nhiều tiêu chí, dùng phán đoán chuyên gia và kiểm tra tính nhất quán của ma trận.
- `load-balancing.pdf`: nhấn mạnh trong cân bằng tải động/adaptive, thuật toán cần thông tin trạng thái hệ thống như hàng đợi, utilization, response time/waiting time và phải cân nhắc overhead của việc thu thập thông tin.
- `Báo cáo Nghiên cứu.docx`: cung cấp lập luận chi tiết hơn về việc ưu tiên latency, queue và CPU trong bối cảnh microservices Spring Boot.
