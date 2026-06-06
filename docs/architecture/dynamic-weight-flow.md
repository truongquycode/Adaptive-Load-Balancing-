# DynamicWeightEngine — Luồng hoạt động

## Mục đích

Hệ thống đánh giá chất lượng của mỗi instance backend dựa trên 3 tiêu chí: latency, queue length và CPU usage. Mỗi tiêu chí có một trọng số, và tổng 3 trọng số luôn bằng 1.0. Công thức tính điểm nền của một instance là:

```
baseScore = alpha * normLatency + beta * normQueue + gamma * normCpu
```

Vấn đề với trọng số cố định là trong điều kiện bình thường, latency là tín hiệu phân biệt tốt nhất. Nhưng khi CPU bão tải, CPU lại là tín hiệu đáng tin hơn. `DynamicWeightEngine` giải quyết bài toán này bằng cách tự động điều chỉnh 3 trọng số mỗi 5 giây dựa trên dữ liệu thực tế.

---

## Các thành phần cốt lõi

### AHP_WEIGHTS

Bộ trọng số tĩnh được tính toán trước bởi chuyên gia theo phương pháp Analytic Hierarchy Process.

| Tiêu chí | Trọng số | Lý do |
|----------|----------|-------|
| latency  | 0.648    | Người dùng cảm nhận trực tiếp qua thời gian chờ |
| queue    | 0.230    | Tín hiệu sớm của overload, tăng trước khi latency tăng |
| cpu      | 0.122    | Quan trọng nhưng ít tin cậy nhất vì CPU cao không luôn đồng nghĩa latency cao |

Bộ này đóng vai trò neo, kéo kết quả cuối về gần kinh nghiệm thực tế, tránh thuật toán đi quá xa khi dữ liệu nhiễu.

### EWM (Entropy Weight Method)

Phương pháp tính trọng số động. Nguyên lý: tiêu chí nào có sự chênh lệch lớn giữa các instance thì mang nhiều thông tin phân biệt hơn, và sẽ được gán trọng số cao hơn.

Ví dụ: nếu 3 instance có CPU lần lượt là 10%, 50%, 90% thì CPU đang phân biệt rõ ràng ai tốt ai xấu, trọng số gamma sẽ tăng lên. Ngược lại nếu cả 3 instance đều có CPU gần bằng nhau thì CPU không phân biệt được gì, trọng số gamma sẽ giảm.

### McdmWeights

Record bất biến lưu bộ ba trọng số hiện tại. Khi cần cập nhật, engine tạo ra một record mới thay vì sửa từng field. Cách này đảm bảo thread đọc weights luôn thấy một snapshot nhất quán, không bao giờ thấy alpha mới nhưng beta cũ.

---

## Luồng chạy chính

```
computeMCDMWeights() — chạy mỗi 5 giây
        |
        |-- [n < 2 instance] --------------> return, không làm gì
        |
        |-- [idle: queue < 2 && cpu < 6%] -> đóng băng AHP defaults, return
        |
        v
buildNormalizedMatrix()
        |
        |   raw metrics -> chuẩn hoá về [0, 1]
        |   instance tốt nhất = 1.0, tệ hơn < 1.0
        |
        v
calculateEntropyWeights()
        |
        |   variance cao -> entropy thấp -> trọng số cao
        |   variance thấp -> entropy cao -> trọng số thấp
        |
        v
blendAndApplyFinalWeights()
        |
        |-- fusion  = 80% EWM + 20% AHP
        |-- EMA     = 8% * fusion + 92% * current
        |-- clamp   upper bounds: gamma <= 0.35, alpha <= 0.70, beta <= 0.55
        |-- clamp   lower bounds: gamma >= 0.08, beta >= 0.08, alpha >= 0.15
        |
        v
   gán this.weights = McdmWeights mới
```

---

## Chi tiết từng bước

### Bước 0 — Kiểm tra điều kiện

`computeMCDMWeights()` được gọi mỗi 5 giây. Việc đầu tiên là kiểm tra xem có đủ dữ liệu để tính không: cần ít nhất 2 instance, vì Shannon Entropy không có nghĩa với 1 instance duy nhất.

Tiếp theo kiểm tra trạng thái idle. Nếu queue trung bình dưới 2 request và CPU trung bình dưới 6%, hệ thống đang nhàn rỗi. Khi nhàn rỗi, tất cả các instance đều hoạt động tốt như nhau nên các metric gần bằng nhau, EWM sẽ trả về trọng số đều nhau, không hữu ích. Engine đóng băng trọng số tại AHP defaults và bỏ qua các bước tiếp theo.

### Bước 1 — Chuẩn hoá ma trận

Lấy raw metrics của tất cả instance từ MetricsCache, tạo ra ma trận `n instances x 3 tiêu chí`. Sau đó chuẩn hoá theo công thức:

```
data[i][j] = (minVal[j] + MU[j]) / (raw[i][j] + MU[j])
```

Kết quả: instance có giá trị tốt nhất trên tiêu chí j sẽ nhận điểm 1.0, các instance tệ hơn nhận điểm thấp hơn. Giá trị MU được cộng vào cả tử và mẫu để tránh chia cho 0 và để làm giảm sự dao động khi metric gần bằng 0.

Ví dụ thực tế với latency (MU = 5):

| Instance | Raw (ms) | Tính toán        | Kết quả |
|----------|----------|------------------|---------|
| 8081     | 30ms     | (30+5) / (30+5)  | 1.000   |
| 8082     | 50ms     | (30+5) / (50+5)  | 0.636   |
| 8083     | 200ms    | (30+5) / (200+5) | 0.171   |

### Bước 2 — Tính trọng số Entropy

Với mỗi tiêu chí j, thực hiện theo thứ tự:

**1. Chuẩn hoá thành phân phối xác suất**

```
p_ij = data[i][j] / sum(data[col_j])
```

`p_ij` là tỉ trọng của instance i trong tiêu chí j.

**2. Tính Shannon Entropy**

```
E_j = -k * sum(p_ij * ln(p_ij))    với k = 1 / ln(n)
```

`k = 1/ln(n)` để chuẩn hoá entropy về khoảng [0, 1].

**3. Tính trọng số thô**

```
ewm[j] = max(0, 1 - E_j)
```

- Entropy cao có nghĩa là các instance gần bằng nhau trên tiêu chí này, tiêu chí này ít phân biệt, trọng số sẽ thấp.
- Entropy thấp có nghĩa là các instance rất khác nhau, tiêu chí này phân biệt tốt, trọng số sẽ cao.

**4. Chuẩn hoá**

Chia mỗi `ewm[j]` cho tổng để 3 trọng số cộng lại bằng 1.0. Nếu tổng bằng 0 thì fallback về 1/3 cho mỗi tiêu chí.

### Bước 3 — Pha trộn, làm mượt và giới hạn

**Pha trộn EWM và AHP**

```
fusion[j] = 0.80 * ewm[j] + 0.20 * AHP_WEIGHTS[j]
```

80% phụ thuộc vào dữ liệu thực, 20% neo vào kinh nghiệm chuyên gia. Re-normalize để tổng = 1.0.

**EMA smoothing**

```
newAlpha = 0.08 * fusionAlpha + 0.92 * currentAlpha
```

Hệ số 0.08 rất nhỏ có nghĩa là mỗi chu kỳ 5 giây chỉ dịch chuyển 8% về phía giá trị mới. Tác dụng: một spike CPU ngắn hạn trong 1-2 chu kỳ sẽ không gây ra thay đổi trọng số đột ngột, tránh hệ thống phản ứng thái quá với nhiễu tạm thời.

**Clamp bounds**

Sau EMA, áp dụng giới hạn cứng. Phần vượt quá giới hạn trên được tái phân bổ sang tiêu chí khác theo tỉ lệ định sẵn. Phần thiếu so với giới hạn dưới được lấy bù từ tiêu chí khác.

| Loại          | gamma        | alpha        | beta         |
|---------------|--------------|--------------|--------------|
| Giới hạn trên | <= 0.35      | <= 0.70      | <= 0.55      |
| Giới hạn dưới | >= 0.08      | >= 0.15      | >= 0.08      |

Lý do có giới hạn: nếu không có giới hạn, trong kịch bản CPU bão tải, EWM có thể đẩy gamma lên rất cao và làm mất tác dụng của latency signal. Giới hạn đảm bảo mọi tiêu chí đều có tiếng nói tối thiểu trong mọi tình huống.

**Gán kết quả**

Tạo `McdmWeights` record mới và gán vào volatile field `this.weights`. Từ thời điểm này, mọi thread gọi `computeBaseScore()` sẽ dùng bộ trọng số mới.

---

## Luồng đọc — computeBaseScore

Trong khi `computeMCDMWeights()` chạy nền mỗi 5 giây, hàm `computeBaseScore()` được gọi liên tục bởi ScoreCalculator mỗi khi có request cần chọn instance. Hàm này đọc `this.weights` một lần duy nhất vào biến local để lấy snapshot nhất quán, rồi tính:

```
baseScore = alpha * normLatency + beta * normQueue + gamma * normCpu
```

Kết quả được ScoreCalculator cộng thêm PID penalty để ra finalScore, rồi AdaptiveLoadBalancer dùng finalScore để quyết định route request đến instance nào.

---

## Mối quan hệ với các component khác

| Component        | Chiều         | Mô tả |
|------------------|---------------|-------|
| MetricsCache     | DWE đọc       | Cung cấp raw metrics đầu vào. MetricsPoller đã poll định kỳ và ghi vào đây trước. |
| ScoreCalculator  | Gọi DWE       | Gọi `computeBaseScore()` mỗi khi cần tính điểm cho một instance. |
| AdminController  | Gọi DWE       | Gọi `resetWeights()` khi POST `/actuator/alb/reset`, đưa trọng số về AHP defaults. |
| MeterRegistry    | DWE đăng ký   | Nhận 3 Prometheus Gauge để theo dõi alpha, beta, gamma theo thời gian thực trên Grafana. |

---

## Ví dụ kịch bản thực tế

**Kịch bản:** instance 8083 bật CPU spike, 2 instance còn lại bình thường.

Sau vài chu kỳ poll, MetricsCache sẽ có dữ liệu CPU gần như sau:

| Instance | CPU    |
|----------|--------|
| 8081     | 15%    |
| 8082     | 18%    |
| 8083     | 90%    |

Ba giá trị này rất khác nhau, entropy của cột CPU thấp, EWM đẩy trọng số gamma lên. Sau khi pha trộn với AHP và làm mượt qua EMA, gamma tăng dần từ 0.122 về phía giới hạn trên 0.35.

ScoreCalculator tính baseScore với gamma cao hơn, instance 8083 nhận điểm cao hơn (xấu hơn), AdaptiveLoadBalancer giảm traffic đến 8083.

Khi tắt CPU spike, 3 instance có CPU gần bằng nhau trở lại, entropy cột CPU tăng, EWM giảm trọng số gamma, hệ thống dần trở về AHP defaults sau vài chu kỳ EMA.