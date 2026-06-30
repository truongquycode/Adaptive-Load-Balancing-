# DynamicWeightEngine

## 1. Vai trò

`DynamicWeightEngine` tính trọng số động cho ba tiêu chí trong mô hình MCDM:

```text
latency
queue
cpu
```

Score nền của backend được tính theo công thức:

```text
baseScore = alpha * normLatency
          + beta  * normQueue
          + gamma * normCpu
```

Trong đó:

- `alpha` là trọng số latency.
- `beta` là trọng số queue.
- `gamma` là trọng số CPU.
- Tổng ba trọng số luôn được chuẩn hóa về `1.0`.

`DynamicWeightEngine` không trực tiếp chọn backend. Nó chỉ cung cấp trọng số cho `ScoreCalculator`.

---

## 2. Vị trí trong pipeline

```text
MetricsPoller
    |
    v
ScoreCalculator
    |
    | lấy normLatency, normQueue, normCpu
    | lấy alpha, beta, gamma từ DynamicWeightEngine
    v
baseScore
    |
    v
PID penalty
    |
    v
finalScore
```

`DynamicWeightEngine` đọc dữ liệu đã chuẩn hóa từ `ScoreBreakdown` trong `MetricsCache`, không đọc raw latency/queue/cpu trực tiếp.

---

## 3. Tần suất cập nhật

Trọng số MCDM được cập nhật theo lịch:

```java
@Scheduled(fixedRateString = "${alb.weights.update-interval:5000}")
```

Cấu hình trong `application.yml`:

```yaml
alb:
  weights:
    update-interval: 5000
```

Nghĩa là cứ 5 giây hệ thống tính lại bộ trọng số một lần.

---

## 4. AHP prior

Mã nguồn hiện tại dùng bộ trọng số nền từ AHP:

```java
private static final double[] AHP_WEIGHTS = { 0.648, 0.230, 0.122 };
```

| Tiêu chí | Ký hiệu | Trọng số AHP |
|---|---:|---:|
| Latency | `alpha` | `0.648` |
| Queue | `beta` | `0.230` |
| CPU | `gamma` | `0.122` |

Ý nghĩa:

- Latency được ưu tiên cao nhất vì phản ánh trực tiếp trải nghiệm người dùng.
- Queue là tín hiệu sớm của quá tải.
- CPU có trọng số thấp hơn nhưng vẫn cần thiết để phát hiện suy giảm tài nguyên hoặc noisy neighbor.

AHP prior đóng vai trò neo ổn định, tránh để trọng số động dao động quá mạnh theo dữ liệu nhiễu.

---

## 5. Điều kiện giữ AHP khi hệ thống ổn định

Nếu cụm backend đang ổn định, engine không áp EWM mà quay về AHP prior.

Điều kiện trong mã nguồn:

```text
avgQueue < 0.08
avgCpu < 0.08
maxNormLatency - minNormLatency < 0.12
```

Khi thỏa điều kiện này:

```java
this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
```

Mục đích:

- Tránh phóng đại nhiễu rất nhỏ ở low load.
- Giữ trọng số dễ giải thích khi các backend gần như giống nhau.
- Không làm adaptive tự tạo dao động trong điều kiện bình thường.

---

## 6. Entropy Weight Method

Khi hệ thống có khác biệt đủ rõ, engine dùng Entropy Weight Method để tính trọng số theo độ phân tán dữ liệu.

Dữ liệu đầu vào là ma trận `badness`:

```text
data[i][0] = normLatency của backend i
data[i][1] = normQueue của backend i
data[i][2] = normCpu của backend i
```

Các giá trị đều nằm trong `[0, 1]` và càng lớn nghĩa là càng xấu.

Nguyên tắc của EWM:

- Tiêu chí nào phân biệt backend rõ hơn thì nhận trọng số cao hơn.
- Tiêu chí nào gần như giống nhau giữa các backend thì nhận trọng số thấp hơn.

Ví dụ:

- Nếu latency của 3 backend rất khác nhau nhưng CPU gần như bằng nhau, trọng số latency sẽ tăng.
- Nếu CPU của một backend tăng mạnh trong khi latency chưa tăng nhiều, trọng số CPU có thể tăng để phản ánh suy giảm tài nguyên.

---

## 7. Công thức entropy

Với mỗi tiêu chí `j`, engine tính xác suất tương đối:

```text
p_ij = data_ij / sum(data_j)
```

Entropy:

```text
entropy_j = -k * sum(p_ij * ln(p_ij))
```

Trong đó:

```text
k = 1 / ln(n)
```

`n` là số backend có score.

Độ đa dạng thông tin:

```text
diversity_j = 1 - entropy_j
```

Trọng số EWM:

```text
ewm_j = diversity_j / sum(diversity)
```

Nếu tổng diversity quá nhỏ, engine quay về AHP prior.

---

## 8. Kết hợp EWM và AHP

Mã nguồn không dùng EWM trực tiếp. EWM được trộn với AHP:

```java
private static final double BLEND_FACTOR = 0.70;
```

Công thức:

```text
targetWeight = 0.70 * ewmWeight + 0.30 * ahpWeight
```

Sau đó các trọng số được chuẩn hóa lại để tổng bằng `1.0`.

Cách này giúp trọng số vừa phản ứng theo dữ liệu thực tế, vừa không rời khỏi cấu trúc ưu tiên ban đầu của bài toán.

---

## 9. EMA cho trọng số

Sau khi có target weight, engine không cập nhật ngay lập tức mà áp EMA.

Mã nguồn hiện tại:

```text
WEIGHT_EMA_ALPHA_MIN = 0.08
WEIGHT_EMA_ALPHA_MAX = 0.22
```

EMA alpha được tính theo mức thay đổi giữa trọng số mục tiêu và trọng số hiện tại:

```text
delta = average(|target - current|)
emaAlpha = 0.08 + (0.22 - 0.08) * clamp(delta * 3.0, 0, 1)
```

Nếu target thay đổi ít, cập nhật chậm. Nếu target thay đổi nhiều, cập nhật nhanh hơn.

---

## 10. Giới hạn mềm cho trọng số

Trước khi lưu trọng số mới, engine clamp từng tiêu chí:

```text
gamma ∈ [0.08, 0.35]
beta  ∈ [0.08, 0.45]
alpha ∈ [0.15, 0.70]
```

Sau đó chuẩn hóa lại tổng bằng `1.0`.

Mục đích:

- Không cho một tiêu chí bị triệt tiêu hoàn toàn.
- Không cho một tiêu chí thống trị tuyệt đối.
- Giữ công thức MCDM ổn định và dễ giải thích.

---

## 11. Metric Prometheus

`DynamicWeightEngine` export ba gauge:

```text
alb_mcdm_weight{criterion="latency"}
alb_mcdm_weight{criterion="queue"}
alb_mcdm_weight{criterion="cpu"}
```

Dashboard dùng các PromQL:

```promql
alb_mcdm_weight{criterion="latency"}
```

```promql
alb_mcdm_weight{criterion="queue"}
```

```promql
alb_mcdm_weight{criterion="cpu"}
```

Và dùng biểu thức kiểm tra tổng:

```promql
alb_mcdm_weight{criterion="latency"}
+ alb_mcdm_weight{criterion="queue"}
+ alb_mcdm_weight{criterion="cpu"}
```

Kết quả kỳ vọng của tổng là `1.0`.

---

## 12. Reset

Khi gọi:

```text
POST /actuator/alb/reset
```

`AdminController` gọi:

```java
weightEngine.resetWeights();
```

Sau reset, trọng số quay lại AHP prior:

```text
alpha = 0.648
beta  = 0.230
gamma = 0.122
```
