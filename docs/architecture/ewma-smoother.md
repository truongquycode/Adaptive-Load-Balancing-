# EwmaSmoother — Luồng hoạt động

## File này làm gì

`EwmaSmoother` nhận vào một giá trị latency thô (ms) đo được từ một server instance,
và trả về một giá trị latency đã được làm mượt. Kết quả đó được `ScoreCalculator` dùng
để tính điểm cho instance, từ đó `AdaptiveLoadBalancer` quyết định nên route request đến đâu.

Vấn đề cần giải quyết: latency đo được mỗi 200ms có thể bị nhiễu. Một request đơn lẻ
bị chậm vì lý do bất thường (GC pause, hệ điều hành, network jitter) không nên làm điểm của
server bị tăng vọt ngay lập tức. Nhưng nếu server thực sự đang bị quá tải, hệ thống phải
phát hiện ra trong vòng vài trăm millisecond, không phải vài giây.

Thuật toán được dùng là AEWMA (Adaptive Exponentially Weighted Moving Average). So với
EWMA thông thường, điểm khác biệt duy nhất là hằng số làm mượt `tau` không cố định mà tự
điều chỉnh tùy theo mức độ biến động của latency vừa đo được.

---

## Vị trí trong hệ thống

```
MetricsPoller           ScoreCalculator           AdaptiveLoadBalancer
     |                        |                            |
     |  rawLatency (ms)       |                            |
     |----------------------> |                            |
     |                        |  smooth(instanceId, raw)   |
     |                        |------------------------->  |
     |                        |  EwmaSmoother              |
     |                        |  <-- ewmaLatency (ms)      |
     |                        |                            |
     |                        |  normalise -> MCDM score   |
     |                        |--------------------------> |
     |                        |                            |  route request
```

`MetricsPoller` gọi `ScoreCalculator.calculateScore()` mỗi 200ms cho mỗi instance.
Bên trong `calculateScore()`, dòng đầu tiên xử lý latency là gọi `EwmaSmoother.smooth()`.

---

## Trạng thái lưu trữ

`EwmaSmoother` duy trì một Caffeine Cache với key là `instanceId` và value là `EwmaState`.

`EwmaState` gồm hai trường:
- `value`: giá trị EWMA hiện tại (ms). Đây là "ký ức" của thuật toán, dùng làm lịch sử
  cho lần tính tiếp theo.
- `lastTimestamp`: thời điểm (epoch ms) lần tính EWMA trước đó. Dùng để biết đã bao lâu
  kể từ lần đo trước.

Cache tự xóa một entry sau 5 phút không được truy cập. Điều này xử lý trường hợp một
instance bị down và không còn được poll nữa, bộ nhớ sẽ tự được giải phóng mà không cần
thêm logic cleanup.

---

## Luồng xử lý khi `smooth()` được gọi

### Bước 1: Kiểm tra lần đầu tiên

Nếu instance này chưa từng được tính EWMA (không có entry trong cache), thuật toán khởi
tạo `EwmaState` với giá trị ban đầu là `fallbackP50`, tức là giá trị P50 latency lịch sử
của instance đó lấy từ HDR Histogram.

Lý do không dùng giá trị 0 để khởi tạo: nếu bắt đầu từ 0, EWMA sẽ mất vài chu kỳ poll
mới "leo" lên được giá trị thực. Trong thời gian đó, điểm của instance sẽ bị tính sai
(quá thấp, tức quá tốt) khiến hệ thống route quá nhiều traffic vào một instance chưa
được đo chính xác.

Sau khi khởi tạo, hàm trả về ngay `fallbackP50` và kết thúc.

---

### Bước 2: Tính khoảng thời gian giữa hai lần đo (dt)

```
dt = now - lastTimestamp
dt = clamp(dt, min=1ms, max=dtCap)
dtCap = 3 * tauMax
```

`dt` là khoảng thời gian tính bằng millisecond kể từ lần gọi `smooth()` trước đó cho
instance này.

Giới hạn dưới 1ms: tránh trường hợp `dt = 0` dẫn đến `ratio = 0`, làm cho `theta = 0`,
khiến EWMA không bao giờ cập nhật dù có latency mới.

Giới hạn trên `dtCap = 3 * tauMax`: nếu instance bị offline rồi quay lại sau vài phút,
`dt` sẽ rất lớn, dẫn đến `theta` xấp xỉ 1.0, tức là EWMA sẽ bị reset hoàn toàn về
giá trị raw mới nhất và bỏ qua toàn bộ lịch sử. Bằng cách giới hạn `dt` tối đa ở
`3 * tauMax` (ví dụ 6 giây với `tauMax = 2000ms`), hệ thống vẫn dịch chuyển EWMA đáng
kể về phía raw mới nhưng không reset hoàn toàn.

---

### Bước 3: Tính deviation (mức độ lệch của raw so với EWMA hiện tại)

```
deviation = |rawLatency - ewmaPrev| / ewmaPrev
```

`deviation` đo xem giá trị raw vừa đo lệch bao nhiêu phần trăm so với giá trị EWMA
đang lưu.

Ví dụ cụ thể:
- EWMA hiện tại là 100ms, raw vừa đo là 160ms: `deviation = 60 / 100 = 0.60` (lệch 60%).
- EWMA hiện tại là 100ms, raw vừa đo là 105ms: `deviation = 5 / 100 = 0.05` (lệch 5%).

`deviation` là đầu vào trực tiếp để quyết định `tau` sẽ là bao nhiêu ở bước tiếp theo.

---

### Bước 4: Tính tau thích nghi (adaptiveTau)

```
kd          = k * deviation
adaptiveTau = tauMin + (tauMax - tauMin) * e^(-kd)
```

Đây là phần làm cho EWMA "thích nghi" so với EWMA thông thường.

`tau` điều khiển tốc độ phản ứng của EWMA:
- `tau` lớn: EWMA di chuyển chậm, kết quả mượt, ít bị ảnh hưởng bởi nhiễu.
- `tau` nhỏ: EWMA di chuyển nhanh, phản ứng tức thì với thay đổi.

Hàm `e^(-kd)` đưa `tau` về gần `tauMin` khi `deviation` lớn, và giữ `tau` gần `tauMax`
khi `deviation` nhỏ:

```
deviation = 0    =>  e^0 = 1     =>  tau = tauMax   (ổn định, lọc nhiễu mạnh)
deviation = 0.33 =>  kd ≈ 1.0   =>  e^-1 ≈ 0.37    =>  tau giảm còn ~37% tauRange + tauMin
deviation = 1.0  =>  kd = 3.0   =>  e^-3 ≈ 0.05    =>  tau gần tauMin
deviation >= 2.0 =>  kd >= 6.0  =>  e^-6 ≈ 0        =>  tau = tauMin (shortcut, bỏ qua exp)
```

Với cấu hình mặc định `tauMin=200ms`, `tauMax=2000ms`, `k=3.0`:
- Instance ổn định: `tau` ≈ 2000ms, thay đổi rất chậm.
- Instance spike 33%: `tau` giảm về khoảng 860ms, phản ứng nhanh hơn gần gấp đôi.
- Instance spike mạnh >= 67%: `tau` về gần 200ms, phản ứng gần như tức thì.

---

### Bước 5: Tính theta

```
ratio = dt / adaptiveTau
theta = 1 - e^(-ratio)
```

`theta` là hệ số quyết định bao nhiêu phần trăm của giá trị raw mới sẽ được đưa vào
kết quả EWMA. `theta` nằm trong khoảng (0, 1].

`theta` phụ thuộc vào cả thời gian (`dt`) lẫn `tau`:
- `dt = 200ms`, `tau = 2000ms`: `ratio = 0.1`, `theta ≈ 0.095`. EWMA chỉ dịch chuyển
  9.5% về phía raw mới, 90.5% giữ nguyên từ lịch sử.
- `dt = 200ms`, `tau = 200ms`: `ratio = 1.0`, `theta ≈ 0.632`. EWMA dịch chuyển 63.2%
  về phía raw mới.

Shortcut khi `ratio >= 10`: `e^-10 ≈ 0`, nên `theta = 1.0`, tức `smoothed = rawLatency`.
EWMA bị "reset" về raw. Điều này xảy ra khi instance offline lâu rồi quay lại và `dt`
đã bị giới hạn bởi `dtCap` nhưng vẫn đủ lớn để `ratio >= 10`.

---

### Bước 6: Tính giá trị EWMA mới

```
smoothed = theta * rawLatency + (1 - theta) * ewmaPrev
```

Đây là công thức EWMA tiêu chuẩn. `theta` đóng vai trò như `alpha` trong EWMA thông thường,
nhưng khác ở chỗ `theta` được tính lại mỗi lần gọi dựa trên `dt` và `adaptiveTau`.

Hai giá trị được cập nhật vào state sau khi tính xong:
- `state.value = smoothed`: lưu kết quả làm "lịch sử" cho lần gọi tiếp theo.
- `state.lastTimestamp = now`: cập nhật mốc thời gian để tính `dt` lần sau.

Hàm trả về `smoothed`.

---

## Các trường hợp biên được xử lý

**Instance offline rồi quay lại:** `dt` bị giới hạn bởi `dtCap = 3 * tauMax`, ngăn
EWMA bị reset hoàn toàn về raw.

**EWMA hiện tại gần 0:** Mẫu số khi tính `deviation` được giới hạn tối thiểu là 1.0
thay vì `state.value`, tránh chia cho 0.

**Spike cực lớn:** Khi `kd >= 6.0`, bỏ qua phép tính `Math.exp(-kd)` và gán thẳng
`tau = tauMin`. Đây là tối ưu vì `e^-6 ≈ 0.0025` thực tế không khác gì 0.

**Cold start:** Dùng `fallbackP50` thay vì 0 để EWMA có giá trị khởi tạo thực tế ngay
từ đầu.

---

## Reset

`resetAllStates()` xóa toàn bộ cache. Được `AdminController` gọi qua endpoint
`POST /actuator/alb/reset` trước mỗi lần chạy benchmark.

Sau khi reset, lần gọi `smooth()` tiếp theo cho bất kỳ instance nào sẽ rơi vào nhánh
cold start và khởi tạo lại từ `fallbackP50`.

---

## Tóm tắt luồng trong một lần gọi

```
smooth(instanceId, rawLatency, tauMin, tauMax, k, fallbackP50)
    |
    +-- Lần đầu tiên?
    |       Có  -->  Khởi tạo state = (fallbackP50, now), trả về fallbackP50
    |       Không -->
    |
    +-- Tính dt = clamp(now - lastTimestamp, 1ms, 3*tauMax)
    |
    +-- Tính deviation = |raw - ewmaPrev| / ewmaPrev
    |
    +-- Tính adaptiveTau = tauMin + (tauMax - tauMin) * e^(-k * deviation)
    |       deviation thấp  -->  adaptiveTau gần tauMax  (mượt)
    |       deviation cao   -->  adaptiveTau gần tauMin  (nhạy)
    |
    +-- Tính theta = 1 - e^(-dt / adaptiveTau)
    |       theta nhỏ  -->  giữ nhiều lịch sử
    |       theta lớn  -->  nặng vào raw mới
    |
    +-- smoothed = theta * raw + (1 - theta) * ewmaPrev
    |
    +-- Lưu state: value = smoothed, lastTimestamp = now
    |
    +-- Trả về smoothed
```