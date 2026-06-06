# AdaptiveLoadBalancer — Luồng hoạt động

## Vai trò trong hệ thống

`AdaptiveLoadBalancer` là thành phần quyết định mỗi HTTP request đến từ client sẽ được chuyển đến instance backend nào (8081, 8082, hoặc 8083).

Nó **không tự thu thập metrics** và **không tự tính điểm**. Toàn bộ công việc phân tích nặng (đo latency, tính CPU, chạy MCDM và PID) đã được `MetricsPoller` và `ScoreCalculator` thực hiện trước đó, mỗi 200ms một lần. Kết quả được lưu vào `MetricsCache`.

Khi một request đến, `AdaptiveLoadBalancer` chỉ cần **đọc điểm từ cache** và kết hợp với thông tin inflight tức thời để chọn ra instance phù hợp nhất. Thiết kế này giúp quyết định routing xảy ra cực nhanh, không block request.

```
MetricsPoller (mỗi 200ms)
    --> ScoreCalculator
        --> MetricsCache (lưu finalScore theo instanceId)

HTTP request đến Gateway
    --> AdaptiveLoadBalancer.choose()
        --> đọc score từ MetricsCache
        --> đọc inflight từ InflightTracker
        --> chọn instance
        --> route request
```

---

## Luồng chính: choose()

Khi Spring Cloud Gateway nhận một request và cần biết phải gửi đến đâu, nó gọi `choose()`.

Hàm này lấy danh sách các instance đang UP từ Eureka qua `ServiceInstanceListSupplier`, sau đó đẩy danh sách đó vào `selectBestInstance()` để chọn instance. Kết quả được bọc trong `DefaultResponse` và trả về cho Gateway.

Nếu Eureka chưa sẵn sàng hoặc không có instance nào, hàm trả về `EmptyResponse` để Gateway tự xử lý lỗi.

---

## Luồng chi tiết: selectBestInstance()

Đây là nơi toàn bộ logic chọn instance diễn ra. Hàm nhận vào danh sách instance và trả về 1 instance được chọn.

### Bước 0 — Xử lý các trường hợp đặc biệt

Nếu danh sách rỗng, trả về `EmptyResponse`.

Nếu chỉ có đúng 1 instance, chọn ngay, không cần tính toán gì thêm.

### Bước 1 — Thu thập thông tin của từng instance

Với mỗi instance, hàm đọc 3 thông tin:

**firstSeenMs** — thời điểm instance này lần đầu xuất hiện trong danh sách Eureka. Dùng để xác định instance có đang trong giai đoạn warmup không. Giá trị này chỉ được ghi lần đầu, không bao giờ bị ghi đè.

**rawMcdm** — điểm tổng hợp của instance, đọc từ `MetricsCache`. Điểm này do `ScoreCalculator` tính dựa trên latency, queue length, và CPU, sau đó được làm mượt qua hai lớp EMA trước khi lưu vào cache. Giá trị thấp nghĩa là instance đang hoạt động tốt. Nếu cache chưa có dữ liệu (instance mới, chưa kịp poll), dùng giá trị mặc định là 0.35 — mức trung lập, không ưu tiên cũng không né tránh.

**inflight** — số request đang được instance này xử lý tại thời điểm hiện tại, đọc từ `InflightTracker`. Đây là thông tin real-time, khác với score trong cache vốn phản ánh trạng thái trung bình trong khoảng 200ms vừa qua.

Đồng thời trong bước này, hàm tìm ra `minCurrentInflight` — số inflight nhỏ nhất trong tất cả instance. Giá trị này dùng ở bước tính penalty sau.

### Bước 2 — Kiểm tra warmup toàn hệ thống

Một instance được coi là đang warmup nếu nó xuất hiện trong Eureka chưa đủ 5 giây. Trong thời gian đó, `MetricsCache` chưa có đủ dữ liệu đáng tin cậy để MCDM cho ra điểm chính xác.

Nếu **tất cả** instance đều đang trong warmup (thường xảy ra khi hệ thống vừa khởi động), hàm chuyển sang Round-Robin đơn giản: dùng một bộ đếm tăng dần, lấy modulo số instance, chọn lần lượt.

Nếu chỉ có **một số** instance trong warmup (còn có instance đã ổn định), instance đang warmup được gán `share = 1.0` — mức trung bình — và tiếp tục tham gia quá trình chọn bình thường cùng các instance khác.

### Bước 3 — Tính capacity weight (share)

`share[i]` đại diện cho tỉ lệ traffic mà instance i xứng đáng nhận, dựa trên điểm MCDM.

Công thức: `share[i] = 1 / sqrt(rawMcdm)`

Dùng căn bậc hai thay vì nghịch đảo thẳng (`1/score`) là có chủ đích: hàm căn bậc hai làm mềm sự chênh lệch. Khi một instance tốt hơn nhiều so với các instance còn lại, nó không nhận được quá nhiều traffic — điều này giúp hệ thống tránh dồn hết tải vào một điểm duy nhất.

Ví dụ với 3 instance:
| Instance | rawMcdm | share (trước normalize) |
|----------|---------|--------------------------|
| 8081     | 0.10    | 3.16                     |
| 8082     | 0.50    | 1.41                     |
| 8083     | 1.00    | 1.00                     |

Sau khi normalize (chia cho tổng 5.57): 8081 nhận ~57%, 8082 nhận ~25%, 8083 nhận ~18%.

**Share floor** — để instance tệ nhất không bao giờ bị "chết đói", hàm áp một ngưỡng tối thiểu:

```
capFloor = maxShare / 3.0
```

Nếu share của một instance thấp hơn ngưỡng này, nó được nâng lên bằng ngưỡng. Mục đích là để `MetricsPoller` vẫn có thể tiếp tục đo được dữ liệu thực tế từ instance đó — nếu không có traffic, không có metrics, không có metrics thì điểm không được cập nhật.

### Bước 4 — Tính routing score và chọn instance

Với mỗi instance còn lại (không bị hard-cap), hàm tính `routingScore`:

```
routingScore = rawMcdm + relPenalty + absPenalty
```

Instance có `routingScore` thấp nhất được chọn.

**Hard cap** — Instance có inflight >= 200 bị loại hoàn toàn, không tham gia tính điểm. Đây là biện pháp bảo vệ cuối: dù điểm MCDM tốt đến đâu, một instance đang xử lý 200 request song song cũng không nên nhận thêm request.

---

## Cơ chế hai loại penalty inflight

MCDM score phản ánh trạng thái của instance trong khoảng thời gian vừa qua (trung bình qua EWMA và EMA). Nhưng giữa hai lần poll, inflight có thể thay đổi nhanh — đặc biệt khi traffic tăng đột biến. Hai loại penalty bổ sung bù đắp cho khoảng mù này.

### Penalty tương đối (relPenalty)

```
relPenalty = 0.010 * (inflight_node - inflight_min)
```

Câu hỏi: "Instance này đang bận hơn instance nhàn nhất bao nhiêu request?"

Mỗi request dư thêm so với instance nhàn nhất cộng thêm 0.010 vào score. Penalty này nhỏ, chỉ tạo ra sự ưu tiên nhẹ cho instance ít bận hơn khi các điểm MCDM gần bằng nhau.

### Penalty tuyệt đối (absPenalty)

```
expected = totalInflight * share[i]
excessRatio = (inflight / expected) - 1.0
absPenalty = 0.6 * excessRatio^1.3   (chỉ khi excessRatio > 0)
```

Câu hỏi: "Instance này đang nhận nhiều hơn phần traffic công bằng của nó bao nhiêu?"

`expected` là số inflight mà instance này nên đang xử lý nếu traffic được phân bổ đúng theo `share`. Nếu thực tế vượt quá expected, penalty tăng theo hàm mũ 1.3 — không tuyến tính. Điều này có nghĩa là vượt 50% bị phạt nặng hơn đúng tỉ lệ, vượt 100% bị phạt nặng hơn nhiều. Mục đích là ngăn việc một instance tiếp tục nhận request khi đã rõ ràng đang quá tải so với kỳ vọng.

---

## Fallback khi tất cả instance đều bị hard-cap

Nếu tất cả instance đều có inflight >= 200, vòng lặp kết thúc mà không chọn được instance nào (`best == null`).

Trong trường hợp này, hàm chọn instance có inflight thấp nhất trong số các instance bị hard-cap (`leastLoadFb`). Nếu kể cả fallback này cũng không có, hàm lấy instance đầu tiên trong danh sách Eureka. Một cảnh báo được ghi vào log.

Đây là biện pháp tránh trả về lỗi cho client trong trường hợp toàn hệ thống bão tải.

---

## State tĩnh và vòng đời

Ba biến sau đây là `static` — tồn tại ở cấp độ class, không phải instance của class:

`firstSeenMs` lưu thời điểm lần đầu thấy mỗi instanceId. Không bao giờ bị ghi đè sau khi đã được ghi.

`rrCounter` bộ đếm Round-Robin dùng trong warmup.

`counterCache` lưu các Prometheus Counter object để tái sử dụng, tránh tra cứu registry mỗi request.

Khi `AdminController` nhận POST `/actuator/alb/reset`, nó gọi `resetStaticState()` để xóa cả ba. Sau reset, tất cả instance vào lại warmup 5 giây, counter bắt đầu từ 0, Counter object được tạo lại. Đây là cách làm sạch state trước mỗi lần benchmark.

---

## Ghi metric Prometheus

Sau khi chọn được instance, hàm `emitMetric()` tăng counter `alb.routing.selected` gắn tag `backend` và `port`.

Counter này cho phép Grafana vẽ biểu đồ tỉ lệ phân bổ traffic theo thời gian — qua đó kiểm chứng xem thuật toán có đang ưu tiên đúng instance hay không.

`counterCache` tránh gọi `Metrics.counter()` mỗi request. Lần đầu gặp instanceId mới, Counter được tạo và lưu vào map. Từ lần thứ hai trở đi, chỉ cần lấy từ map và gọi `.increment()`.