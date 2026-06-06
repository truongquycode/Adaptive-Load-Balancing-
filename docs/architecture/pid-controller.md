# PIDController — Luồng hoạt động

## Mục đích

PIDController tính một giá trị penalty cho từng instance backend. Penalty này được cộng vào baseScore (do DynamicWeightEngine tính) để ra finalScore trong ScoreBreakdown. Instance nào có finalScore cao hơn thì xác suất được AdaptiveLoadBalancer chọn thấp hơn.

Nói ngắn gọn: nếu một instance đang trả lời chậm hơn mức trung bình của toàn hệ thống, PID sẽ tích lũy penalty để hướng traffic sang instance khác. Khi instance hồi phục, penalty giảm dần và traffic được phân bổ lại.

---

## Đầu vào và đầu ra

Hàm duy nhất được gọi từ bên ngoài là `calculatePenalty()`. Nó nhận bốn tham số:

- `instanceId`: định danh của instance, dùng làm key để tra cứu trạng thái PID tương ứng.
- `rawLat`: giá trị latency đã chuẩn hóa về khoảng [0, 1], được tính bởi ScoreCalculator từ EWMA latency thực tế và dải p5-p95 toàn hệ thống.
- `setpoint`: ngưỡng latency "chấp nhận được", cụ thể là P75 toàn hệ thống sau khi chuẩn hóa. Khi instance vượt ngưỡng này thì PID mới bắt đầu phạt.
- `cfg`: bộ hệ số PID đọc từ application.yml, bao gồm kp, ki, kd, tauD, minI, maxI, lambda, kappa.

Hàm trả về một giá trị penalty trong khoảng [0, lambda]. Với cấu hình mặc định lambda=0.8, penalty tối đa là 0.8. Penalty bằng 0 khi instance nhanh hơn hoặc bằng setpoint.

---

## Trạng thái nội bộ — PidState

Mỗi instance có một đối tượng PidState riêng, được lưu trong Caffeine Cache với TTL 5 phút (tự xóa khi instance down lâu không được poll). PidState lưu sáu trường:

- `lastTimestamp`: thời điểm (millisecond) lần tính penalty trước, dùng để tính delta thời gian.
- `lastRawLat`: giá trị rawLat của lần trước, dùng làm baseline để tính tốc độ thay đổi latency cho thành phần D.
- `integral`: giá trị tích phân I tích lũy theo thời gian, phản ánh lịch sử chậm của instance.
- `lastFilteredD`: giá trị thành phần D sau khi qua bộ lọc, dùng để tiếp tục filter ở lần tiếp theo.
- `lastOutput`: kết quả u (P + I + D) chưa squash của lần trước, dùng để kiểm tra trạng thái bão hòa.
- `lastError`: trường này tồn tại trong model nhưng không được dùng trực tiếp trong phiên bản hiện tại.

---

## Luồng tính penalty — từng bước

```
calculatePenalty(instanceId, rawLat, setpoint, cfg)
        |
        |-- tra cứu PidState từ Caffeine Cache
        |        |-- nếu chưa có: khởi tạo mới, lastTimestamp = now - 200ms
        |
        |-- tính actualDt (giây) = (now - lastTimestamp) / 1000
        |        clamp vào [0.001, 5.0]
        |
        |-- error = rawLat - setpoint
        |        dương: instance chậm hơn P75 → cần phạt
        |        âm:    instance nhanh hơn P75 → penalty sẽ về 0
        |
        |-- P = kp × error
        |
        |-- I: tích lũy error × dt
        |        kiểm tra Conditional Anti-Windup trước khi tích lũy
        |        nếu |error| < 0.1: áp decay 0.97^dt để integral giảm dần
        |        clamp integral vào [minI, maxI]
        |        I = ki × integral
        |
        |-- D: tốc độ thay đổi latency
        |        rawD = (rawLat - lastRawLat) / actualDt
        |        filteredD = low-pass filter(rawD, prevFilteredD, tauD, actualDt)
        |        D = kd × filteredD
        |
        |-- u = P + I + D
        |
        |-- lưu state mới (lastRawLat, lastFilteredD, lastOutput, lastTimestamp)
        |
        |-- penalty = lambda × tanh(kappa × max(0, u))
        |
        return penalty ∈ [0, lambda]
```

---

## Chi tiết từng thành phần

### Thành phần P — Phản ứng tức thì

```
P = kp × error
```

P phản ứng ngay lập tức với mức độ lệch so với setpoint. Khi instance đột ngột chậm hơn P75 hệ thống, P tăng ngay trong cùng poll cycle 200ms. Khi instance nhanh trở lại, P giảm ngay.

P phù hợp với việc phát hiện sự cố cấp tính nhưng không có "bộ nhớ" — nếu instance chậm kinh niên nhưng không quá tệ, P không đủ để phân biệt.

### Thành phần I — Tích lũy lịch sử

```
integral = integral + (error × actualDt)
I = ki × integral
```

I tích lũy error theo thời gian. Nếu instance liên tục chậm hơn P75 trong nhiều chu kỳ poll liên tiếp, integral tăng dần và I làm tăng penalty đáng kể. Ngược lại, nếu instance hồi phục, error âm và integral giảm dần.

Có hai cơ chế bảo vệ cho I:

**Conditional Anti-Windup:** Trước mỗi lần tích lũy, kiểm tra hai điều kiện đồng thời:
- Output lần trước đã đạt biên bão hòa (|lastOutput| >= 2.0).
- Error hiện tại cùng chiều với output (đang đẩy sâu hơn vào vùng bão hòa).

Nếu cả hai điều kiện đều đúng thì dừng tích lũy I. Cơ chế này tránh tình trạng integral "chạy ngầm" khi PID đã đạt biên và không còn khả năng điều chỉnh thêm.

**Decay khi error nhỏ:** Khi |error| < 0.1, tức là instance đang hoạt động gần với setpoint, integral được nhân với `0.97^actualDt` mỗi giây. Điều này giúp integral tự giảm về 0 chậm nhưng chắc, tránh tình trạng instance đã phục hồi nhưng vẫn bị phạt do tích lũy từ quá khứ.

Sau cùng, integral được clamp vào [minI, maxI] = [-0.8, 2.5] để giới hạn ảnh hưởng tối đa.

### Thành phần D — Tốc độ thay đổi

```
rawD = (rawLat - lastRawLat) / actualDt
filteredD = (1 - e^(-dt/tauD)) × rawD + e^(-dt/tauD) × prevFilteredD
D = kd × filteredD
```

D đo tốc độ thay đổi của latency. Khi latency đang tăng nhanh, D dương và làm tăng penalty sớm hơn trước khi I kịp tích lũy. Khi latency đang giảm nhanh, D âm và hãm bớt penalty ngay cả khi I vẫn còn cao.

Bộ lọc thông thấp (low-pass filter) bậc 1 với hằng số thời gian tauD=2 giây được áp vào rawD trước khi nhân với kd. Bộ lọc này loại bỏ nhiễu cao tần — một spike latency đơn lẻ trong một poll cycle sẽ không gây ra D lớn đột biến. Chỉ khi latency tăng liên tục qua nhiều chu kỳ thì filteredD mới tăng đáng kể.

### Bước squash cuối

```
u = P + I + D

penalty = lambda × tanh(kappa × max(0, u))
```

`max(0, u)`: loại bỏ trường hợp instance nhanh hơn setpoint (u âm). PID không "thưởng" instance bằng penalty âm — việc đó thuộc về baseScore từ MCDM.

`tanh(kappa × u)`: ép output vào khoảng [0, 1) theo hàm sigmoid. Khi u lớn, penalty không tăng vô hạn mà bão hòa tiệm cận 1.0. Kappa kiểm soát độ dốc: kappa lớn hơn thì penalty bão hòa sớm hơn với cùng giá trị u.

`lambda × ...`: scale kết quả về [0, lambda] = [0, 0.8]. Tổng hợp với baseScore ∈ [0, 1], finalScore tối đa khoảng 1.8.

---

## Ví dụ kịch bản thực tế

**Kịch bản:** Instance 8083 bật CPU spike. Latency tăng từ 60ms lên 800ms. Setpoint P75 toàn hệ thống = 80ms. Sau chuẩn hóa, rawLat của 8083 tăng từ 0.2 lên 0.9.

Các chu kỳ poll đầu tiên (error = 0.9 - setpoint):
- P phản ứng ngay: penalty xuất hiện từ chu kỳ đầu.
- I bắt đầu tích lũy: sau 5-10 chu kỳ (1-2 giây), integral đủ lớn để I đóng góp đáng kể.
- D dương khi latency đang tăng: tăng thêm penalty trong giai đoạn transient.
- Tổng penalty tiệm cận 0.8 sau vài giây.

Khi tắt CPU spike, latency về 60ms:
- error âm: P về 0 ngay.
- rawD âm: D âm, hãm penalty nhanh hơn.
- I giảm dần: error âm làm integral giảm từ từ, decay thêm khi |error| < 0.1.
- Penalty giảm về 0 sau 5-15 giây tùy mức tích lũy của integral.

---

## Mối quan hệ với các component khác

| Component | Chiều | Mô tả |
|---|---|---|
| ScoreCalculator | Gọi PID | Gọi `calculatePenalty()` mỗi khi tính score cho một instance. Truyền vào rawLat (đã qua EWMA) và setpoint (P75 từ SlidingWindowManager). |
| SlidingWindowManager | Gián tiếp qua ScoreCalculator | Cung cấp P75 toàn hệ thống làm setpoint. P75 thay đổi mỗi khi histogram flip, kéo theo setpoint thay đổi và ảnh hưởng đến error. |
| MetricsPoller | Kích hoạt gián tiếp | Poll metrics mỗi 200ms, gọi ScoreCalculator, từ đó gọi PID. Tần suất poll = tần suất PID cập nhật state. |
| AdminController | Gọi `resetAllStates()` | Xóa toàn bộ PID state khi nhận POST /actuator/alb/reset. Sau reset, lần gọi tiếp theo sẽ khởi tạo PidState mới từ đầu (integral = 0, không còn ký ức về lần benchmark trước). |
| AlbProperties / PidConfig | Đọc cấu hình | PID đọc kp, ki, kd, tauD, minI, maxI, lambda, kappa từ application.yml thông qua PidConfig. Các giá trị này ảnh hưởng trực tiếp đến tốc độ phản ứng và biên độ penalty. |

---

## Lưu ý khi điều chỉnh tham số

**kp cao hơn:** Phản ứng P mạnh hơn ngay khi instance bắt đầu chậm. Dễ gây dao động nếu latency không ổn định.

**ki thấp hơn:** Integral tích lũy chậm hơn, tránh phạt quá nặng khi instance chỉ chậm tạm thời. Đổi lại, mất nhiều thời gian hơn để nhận ra instance chậm mãn tính.

**kd cao hơn:** D phản ứng mạnh với sự thay đổi latency. Kết hợp với tauD lớn (filter mượt), có thể phát hiện sớm xu hướng xấu. Nếu tauD nhỏ, D dễ bị nhiễu.

**lambda thấp hơn:** Penalty tối đa thấp hơn, PID ảnh hưởng ít hơn đến routing decision. Phù hợp khi muốn MCDM baseScore chiếm ưu thế.

**kappa cao hơn:** Penalty bão hòa nhanh hơn — instance vừa vượt setpoint một chút đã nhận penalty gần tối đa. Phù hợp khi muốn phản ứng quyết liệt nhưng dễ gây flapping.