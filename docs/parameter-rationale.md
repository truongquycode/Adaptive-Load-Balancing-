# Parameter Rationale

## 1. Mục đích

Tài liệu này ghi rõ ý nghĩa và giới hạn của các tham số chính trong Adaptive Load Balancer. Mục tiêu là tránh trình bày các hằng số như chân lý tuyệt đối.

Các tham số hiện tại là **tham số thiết kế/thực nghiệm**, cần được kiểm chứng bằng benchmark, ablation và sensitivity analysis.

---

## 2. Strategy và ablation

```yaml
alb:
  strategy: ${ALB_STRATEGY:adaptive}
  ablation:
    variant: ${ALB_ABLATION_VARIANT:full}
```

| Tham số | Lý do |
|---|---|
| `adaptive` mặc định | Phù hợp mục tiêu nghiên cứu chính |
| `full` mặc định | Benchmark chính dùng thuật toán đầy đủ, ablation chạy riêng |

---

## 3. Polling

```yaml
alb:
  polling:
    interval: 200
    metrics-timeout-ms: 800
    score-ema-alpha-spike: 0.60
    score-ema-alpha-rise: 0.35
    score-ema-alpha-recover: 0.25
    score-ema-spike-threshold: 0.30
    idle-latency-baseline-ms: 65.0
    idle-decay-alpha: 0.20
```

| Tham số | Lý do |
|---|---|
| `interval=200` | Đủ nhanh để Gateway nhận biến động backend gần thời gian thực, nhưng không poll quá dày như per-request |
| `metrics-timeout-ms=800` | Tránh poll treo làm backlog control-plane |
| `score-ema-alpha-*` | Score xấu đi phản ứng nhanh hơn score hồi phục để giảm flapping |
| `idle-*` | Chỉ dùng làm baseline/cache khi không có traffic thật; không đưa vào histogram/PID/MCDM |

Rủi ro:

- quá nhỏ -> tăng overhead control-plane;
- quá lớn -> phản ứng chậm với chaos;
- vẫn có thể bỏ lỡ spike rất ngắn.

---

## 4. EWMA

```yaml
alb:
  ewma:
    tau-min: 200.0
    tau-max: 2000.0
    k: 3.0
```

| Tham số | Ý nghĩa |
|---|---|
| `tau-min` | phản ứng nhanh nhất khi latency lệch mạnh |
| `tau-max` | làm mượt mạnh khi latency ổn định |
| `k` | độ nhạy với deviation |

Giới hạn: cần ablation `no-ewma-latency` và sensitivity analysis để chứng minh giá trị cụ thể.

---

## 5. PID-inspired penalty

```yaml
alb:
  pid:
    kp: 1.0
    ki: 0.08
    kd: 0.04
    tau-d: 2.0
    min-i: -0.8
    max-i: 2.5
    lambda: 0.8
    kappa: 1.2
```

| Nhóm | Ý nghĩa |
|---|---|
| `kp`, `ki`, `kd` | mức phản ứng theo P/I/D |
| `tau-d` | lọc đạo hàm để giảm nhiễu |
| `min-i`, `max-i` | anti-windup |
| `lambda` | giới hạn mức penalty |
| `kappa` | định hình độ tăng penalty |

Cách diễn giải: đây là PID-inspired latency penalty, không phải PID controller đầy đủ. Cần ablation `no-pid`.

---

## 6. Dynamic MCDM weights

```yaml
alb:
  weights:
    update-interval: 5000
    min-completed-requests: 20
    min-actual-rps: 5.0
    reset-to-ahp-when-idle: true
    blend-factor: 0.70
    ema-alpha-min: 0.08
    ema-alpha-max: 0.22
    stable-queue-threshold: 0.08
    stable-cpu-threshold: 0.08
    stable-latency-spread: 0.12
    alpha-min: 0.15
    alpha-max: 0.70
    beta-min: 0.08
    beta-max: 0.45
    gamma-min: 0.08
    gamma-max: 0.35
```

| Tham số | Ý nghĩa |
|---|---|
| `update-interval=5000` | cập nhật trọng số mỗi 5 giây, không chạy theo từng request |
| `min-completed-requests=20` | tránh học từ quá ít request |
| `min-actual-rps=5.0` | tránh học từ idle/noise |
| `reset-to-ahp-when-idle=true` | khi idle quay về AHP prior |
| `blend-factor=0.70` | EWM là tín hiệu runtime, AHP là neo lý thuyết |
| `ema-alpha-min/max` | làm mượt trọng số động để tránh dao động |
| `stable-*` | giữ AHP khi có traffic thật nhưng cụm vẫn quá ổn định |
| `alpha/beta/gamma min/max` | không cho một tiêu chí triệt tiêu hoàn toàn các tiêu chí khác |

AHP prior:

```text
latency = 0.648
queue   = 0.230
cpu     = 0.122
```

Bộ trọng số này được tạo từ ma trận AHP:

```text
A = | 1    3    5 |
    | 1/3  1    2 |
    | 1/5  1/2  1 |
```

Theo thứ tự tiêu chí `latency`, `queue`, `cpu`, ma trận này thể hiện ba phán đoán:

- latency quan trọng hơn queue ở mức 3;
- latency quan trọng hơn CPU ở mức 5;
- queue quan trọng hơn CPU ở mức 2.

Chuẩn hóa cột và lấy trung bình hàng cho ra `W = [0.648, 0.230, 0.122]`. Consistency Ratio xấp xỉ `0.003 < 0.1`, nên ma trận đủ nhất quán để dùng làm prior. Chi tiết nằm trong `docs/architecture/ahp-default-weight-rationale.md`.

Giới hạn: AHP prior chỉ hợp thức hóa bộ trọng số nền theo một quy trình ra quyết định đa tiêu chí. Nó không chứng minh đây là bộ trọng số tối ưu tuyệt đối; hiệu quả thực tế vẫn cần benchmark, ablation và sensitivity analysis.

---

## 7. Routing thresholds

```yaml
alb:
  routing:
    warmup-ms: 5000
    min-expected-inflight: 3.0
    low-load-inflight: 20
    low-load-health-spread: 0.12
    low-load-load-spread: 0.25
    min-health-weight: 0.25
    max-health-weight: 0.75
    stale-penalty-weight: 0.15
    stale-soft-ms: 1500
    stale-hard-ms: 5000
    unhealthy-score-cutoff: 2.0
    hard-inflight-cap: 220
    probe-interval-ms: 3000
    probe-probability: 0.005
    min-routing-norm-range: 0.12
    routing-weight-ema-alpha: 0.18
    dominant-threshold: 0.70
    overload-penalty-weight: 0.30
    cap-pressure-penalty-weight: 0.20
    absolute-health-penalty-weight: 0.12
    absolute-latency-penalty-weight: 0.12
    absolute-latency-target-ms: 300.0
    absolute-latency-critical-ms: 1500.0
    probe-max-total-inflight-ratio: 0.70
    probe-max-load-raw: 1.10
    probe-max-absolute-latency-cost: 0.80
    probe-max-final-cost: 1.50
```

| Tham số | Ý nghĩa |
|---|---|
| `warmup-ms` | tránh tin vào metrics khi instance mới xuất hiện |
| `min-expected-inflight` | tránh chia cho expected load quá nhỏ |
| `low-load-*` | tránh overreaction ở tải thấp |
| `min/max-health-weight` | giới hạn vai trò health trong final cost |
| `stale-*` | phạt hoặc loại metrics cũ |
| `unhealthy-score-cutoff` | loại backend có score quá xấu |
| `hard-inflight-cap` | chặn backend nhận quá nhiều inflight |
| `probe-*` | cho backend hồi phục cơ hội nhận traffic nhỏ |
| `absolute-latency-*` | thêm tín hiệu latency tuyệt đối khi toàn cụm cùng chậm |
| `overload/cap-pressure/absolute-health` | penalty tuyệt đối bổ sung cho min-max tương đối |
| `probe-max-*` | chặn probe khi cluster đang căng để không làm xấu p99 |

Lưu ý: giá trị trong `application.yml` là nguồn chính. Nếu code default trong `AlbProperties` khác YAML, khi chạy thực tế Spring sẽ bind theo YAML.

---

## 8. Capacity weight

Capacity weight được backend lấy từ CPU quota container. Trong Docker Compose hiện tại:

```text
8081 = 2.0 CPU
8082 = 1.5 CPU
8083 = 1.0 CPU
```

Ý nghĩa:

- instance mạnh hơn được kỳ vọng nhận nhiều inflight hơn;
- Least Connections thông thường không biết capacity khác nhau;
- Adaptive dùng capacity-normalized load để tránh đối xử ngang bằng giữa instance mạnh/yếu.

Giới hạn:

- CPU quota không phản ánh DB/network/memory bottleneck;
- nếu dependency chậm, health cost phải thắng capacity;
- cần ablation `no-capacity`.

---

## 9. Sensitivity analysis nên làm

Nên thử thay đổi có kiểm soát:

| Nhóm | Tham số |
|---|---|
| Low-load guard | `low-load-inflight`, `low-load-health-spread` |
| Stale | `stale-soft-ms`, `stale-hard-ms` |
| Probe | `probe-probability`, `probe-interval-ms` |
| PID | `kp`, `ki`, `kd`, `lambda` |
| EWMA | `tau-min`, `tau-max`, `k` |
| Capacity/load | `min-expected-inflight`, `hard-inflight-cap` |
| MCDM | `min-completed-requests`, `min-actual-rps`, `blend-factor`, bounds alpha/beta/gamma |
| Absolute latency | `absolute-latency-target-ms`, `absolute-latency-critical-ms`, `absolute-latency-penalty-weight` |
| Probe guard | `probe-max-load-raw`, `probe-max-absolute-latency-cost`, `probe-max-final-cost` |

Mục tiêu không phải tìm số đẹp nhất, mà chứng minh kết quả không quá phụ thuộc một bộ tham số duy nhất.

---

## 10. Cách viết trong luận văn

Nên viết:

```text
Các tham số được chọn dựa trên mục tiêu ổn định định tuyến, tránh phản ứng quá mức ở tải thấp, phát hiện degradation đủ nhanh và phù hợp với môi trường Docker Compose 3 backend. Tác động của một số tham số được kiểm chứng qua ablation/sensitivity analysis.
```

Không nên viết:

```text
Các tham số này là tối ưu.
```
