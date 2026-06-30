# Adaptive Load Balancer

## 1. Vai trò

`AdaptiveLoadBalancer` là thành phần data-plane dùng để chọn backend cho từng request đi qua API Gateway. Class này được đăng ký thông qua Spring Cloud LoadBalancer cho service name:

```text
REGISTRATION-SERVICE-ALB
```

Route tương ứng trong Gateway:

```java
.route("backend-route", r -> r.path("/api/**")
        .uri("lb://REGISTRATION-SERVICE-ALB"))
```

Khi `alb.strategy=adaptive`, Gateway dùng `AdaptiveLoadBalancer` thay vì Round Robin, Random hoặc Least Connections.

---

## 2. Nguyên tắc thiết kế

Adaptive Load Balancer trong mã nguồn hiện tại không tự poll metrics và không tự tính health score trực tiếp trong mỗi request. Hệ thống được tách thành hai phần:

```text
Control-plane
    MetricsPoller
    ScoreCalculator
    DynamicWeightEngine
    SlidingWindowManager
    PIDController
    EwmaSmoother
    MetricsCache

Data-plane
    AdaptiveLoadBalancer
    RoutingCostCalculator
    InflightTracker
    InflightLifecycle
```

Control-plane chạy định kỳ để tính trạng thái backend. Data-plane dùng trạng thái đã có sẵn trong cache và số inflight tức thời để chọn backend nhanh nhất có thể.

---

## 3. Luồng xử lý khi có request

```text
Gateway nhận request /api/**
    |
    v
AdaptiveLoadBalancer.choose()
    |
    v
Lấy danh sách instance từ ServiceInstanceListSupplier
    |
    v
RoutingCostCalculator.calculate(instances)
    |
    v
Tạo RoutingContext:
    - all costs
    - eligible costs
    - healthWeight
    - loadWeight
    - mode
    |
    v
AdaptiveLoadBalancer chọn instance:
    - Single instance
    - Warmup Round Robin
    - Low-load Round Robin
    - Probe recovery
    - P2C
    - Least-cost fallback
```

Nếu không có instance nào từ Eureka, Gateway nhận `EmptyResponse`.

Nếu chỉ có một instance, hệ thống chọn thẳng instance đó và ghi reason `SINGLE_INSTANCE`.

---

## 4. Warmup Round Robin

Adaptive dùng biến `firstSeenMs` để ghi nhận thời điểm mỗi instance xuất hiện lần đầu trong danh sách Eureka.

Cấu hình warmup:

```yaml
alb:
  routing:
    warmup-ms: 5000
```

Nếu tất cả instance đều mới xuất hiện và chưa vượt qua `warmup-ms`, Adaptive chưa tin vào metrics. Lúc này thuật toán dùng Round Robin và ghi reason:

```text
WARMUP_RR
```

Cơ chế này giúp tránh việc một instance bị đánh giá sai chỉ vì chưa kịp có đủ dữ liệu poll.

---

## 5. RoutingCostCalculator

`RoutingCostCalculator` là nơi tính chi phí định tuyến cuối cùng cho từng backend. Đây là phần quan trọng nhất của Adaptive Load Balancer hiện tại.

Đầu vào:

- Danh sách `ServiceInstance` từ Eureka.
- `finalScore` của từng backend từ `MetricsCache`.
- Số inflight hiện tại từ `InflightTracker`.
- `capacityWeight` của từng backend từ `MetricsCache`.
- Cấu hình trong `alb.routing`.

Đầu ra:

- `RoutingContext`
- Danh sách `RoutingCost`
- `healthWeight`
- `loadWeight`
- `mode`

---

## 6. Health raw

Với mỗi backend, thuật toán lấy `ScoreBreakdown` trong `MetricsCache`.

```java
double healthRaw = bd != null ? Math.max(0.0, bd.finalScore()) : DEFAULT_HEALTH_SCORE;
```

Nếu chưa có metrics, hệ thống dùng giá trị mặc định:

```text
DEFAULT_HEALTH_SCORE = 0.50
```

`healthRaw` càng thấp thì backend càng tốt. Giá trị này đã bao gồm:

- EWMA latency
- normalized latency
- normalized queue
- normalized CPU
- Dynamic MCDM base score
- PID penalty
- EMA smoothing ở tầng final score

---

## 7. Capacity-normalized load

Adaptive không so sánh inflight thô giữa các backend một cách ngang bằng, vì các container có CPU quota khác nhau.

Công thức trong `RoutingCostCalculator`:

```text
capacityShare = capacityWeight / sumCapacity
expectedInflight = max(minExpectedInflight, totalInflight * capacityShare)
loadRaw = inflight / expectedInflight
```

Cấu hình liên quan:

```yaml
alb:
  routing:
    min-expected-inflight: 3.0
```

Ý nghĩa:

- Backend có CPU quota cao hơn được kỳ vọng xử lý nhiều inflight hơn.
- `loadRaw = 1.0` nghĩa là backend đang nhận đúng phần tải hợp lý theo năng lực.
- `loadRaw > 1.0` nghĩa là backend đang nhận nhiều hơn mức hợp lý.
- `loadRaw < 1.0` nghĩa là backend còn tương đối rảnh so với năng lực.

---

## 8. Hard exclusion

Một backend có thể bị loại khỏi danh sách `eligible` nếu rơi vào một trong các trạng thái sau:

| Reason | Điều kiện |
|---|---|
| `NO_METRICS` | Chưa có `ScoreBreakdown` trong cache |
| `STALE` | Score quá cũ, vượt `stale-hard-ms` |
| `UNHEALTHY_SCORE` | `healthRaw >= unhealthy-score-cutoff` |
| `HARD_INFLIGHT_CAP` | Inflight vượt ngưỡng hard cap theo capacity |

Cấu hình liên quan:

```yaml
alb:
  routing:
    stale-soft-ms: 1500
    stale-hard-ms: 5000
    unhealthy-score-cutoff: 2.0
    hard-inflight-cap: 220
```

Hard cap được điều chỉnh theo năng lực backend:

```text
factor = capacityWeight / avgCapacity
factor = clamp(factor, 0.70, 1.50)
hardCap = max(40, round(hardInflightCap * factor))
```

Vì vậy instance 8081 có thể chịu hard cap cao hơn 8083.

---

## 9. Chuẩn hóa health và load trong cụm

Sau khi có `healthRaw` và `loadRaw`, thuật toán chuẩn hóa tương đối trong cụm:

```text
healthCost = normalize(healthRaw, minHealth, maxHealth)
loadCost   = normalize(loadRaw, minLoad, maxLoad)
```

Nếu khoảng chênh lệch quá nhỏ, hàm `normalize()` trả về `0.5` để tránh phóng đại nhiễu:

```text
MIN_ROUTING_NORM_RANGE = 0.12
```

Ý nghĩa:

- `0.0` là tốt nhất trong cụm hiện tại.
- `1.0` là xấu nhất trong cụm hiện tại.
- `0.5` là trung lập khi khác biệt không đủ rõ.

---

## 10. Trọng số health/load động

Adaptive tính mức phân tán tương đối của health và load:

```text
healthSpread = relativeSpread(healthRaw[])
loadSpread   = relativeSpread(loadRaw[])
```

Sau đó tính trọng số mục tiêu:

```text
targetHealthWeight = healthSpread / (healthSpread + loadSpread + EPS)
```

Giá trị này được clamp trong khoảng cấu hình:

```yaml
alb:
  routing:
    min-health-weight: 0.25
    max-health-weight: 0.75
```

Sau đó áp EMA:

```text
healthWeight = previous + 0.18 * (target - previous)
loadWeight = 1.0 - healthWeight
```

Ý nghĩa:

- Khi khác biệt health rõ hơn khác biệt load, thuật toán ưu tiên health.
- Khi khác biệt load rõ hơn, thuật toán ưu tiên inflight/capacity.
- EMA giúp trọng số không nhảy đột ngột giữa hai lần định tuyến.

---

## 11. Final routing cost

Chi phí định tuyến cuối cùng được tính theo công thức:

```text
finalCost =
    healthWeight * healthCost
  + loadWeight   * loadCost
  + overloadPenalty
  + capPressurePenalty
  + absoluteHealthPenalty
  + stalePenalty
```

Trong đó:

```text
overloadPenalty = 0.30 * clamp((loadRaw - 0.95) / 0.45, 0, 1)
capPressurePenalty = 0.20 * clamp((inflight / hardCap - 0.70) / 0.30, 0, 1)
absoluteHealthPenalty = 0.12 * clamp((healthRaw - 0.75) / 0.75, 0, 1)
```

`finalCost` càng thấp thì backend càng có khả năng được chọn.

Điểm quan trọng của bản hiện tại là ngoài min-max relative cost, thuật toán còn có penalty tuyệt đối. Điều này giúp Adaptive không mất tín hiệu khi cả ba backend cùng bị quá tải.

---

## 12. Low-load Round Robin

Nếu tải thấp và các backend không khác biệt rõ, Adaptive chuyển sang Round Robin để tránh tự tạo dao động.

Điều kiện:

```text
totalInflight <= lowLoadInflight
healthSpread <= lowLoadHealthSpread
loadSpread <= lowLoadLoadSpread
```

Cấu hình:

```yaml
alb:
  routing:
    low-load-inflight: 20
    low-load-health-spread: 0.12
    low-load-load-spread: 0.25
```

Khi nhánh này được kích hoạt, reason ghi nhận là:

```text
LOW_LOAD_RR
```

---

## 13. P2C selection

Khi không ở warmup hoặc low-load, Adaptive chọn backend bằng Power of Two Choices.

Luồng chọn:

```text
Lấy danh sách candidates = eligible nếu có, ngược lại dùng all
    |
    v
Random chọn hai RoutingCost a và b
    |
    v
So sánh bằng RoutingCostCalculator.better(a, b)
    |
    v
Chọn backend có finalCost thấp hơn
```

Nếu `finalCost` bằng nhau:

1. Chọn backend có `loadCostRaw` thấp hơn.
2. Nếu vẫn bằng nhau, chọn backend có `capacityWeight` cao hơn.

P2C giúp giảm chi phí tính toán so với việc luôn chọn min toàn cục, đồng thời vẫn tránh phân phối ngẫu nhiên mù.

---

## 14. Probe recovery

Adaptive có cơ chế probe nhẹ để kiểm tra backend đang bị loại có thể hồi phục hay chưa.

Cấu hình:

```yaml
alb:
  routing:
    probe-interval-ms: 3000
    probe-probability: 0.005
```

Probe chỉ áp dụng cho backend bị loại vì health/stale/no-metrics, không áp dụng cho backend bị `HARD_INFLIGHT_CAP`.

Adaptive không probe khi mode là:

```text
LOAD_DOMINANT
ALL_HARD_EXCLUDED_FALLBACK
```

Mục đích là tránh gửi thêm request thử nghiệm vào node đang quá tải trong giai đoạn stress.

---

## 15. Các mode quyết định

`RoutingContext.mode()` có thể là:

| Mode | Ý nghĩa |
|---|---|
| `LOW_LOAD_RR` | Tải thấp, các node gần giống nhau, dùng Round Robin |
| `ALL_HARD_EXCLUDED_FALLBACK` | Tất cả backend bị hard-excluded, phải fallback |
| `HEALTH_DOMINANT` | Health weight chiếm ưu thế |
| `LOAD_DOMINANT` | Load weight chiếm ưu thế |
| `NORMAL_P2C` | Chọn bằng P2C bình thường |

Ngoài mode từ `RoutingContext`, metric `alb.routing.selected` còn có reason:

| Reason | Khi nào xuất hiện |
|---|---|
| `SINGLE_INSTANCE` | Chỉ có một backend |
| `WARMUP_RR` | Tất cả backend đang warmup |
| `LOW_LOAD_RR` | Low-load guard được kích hoạt |
| `PROBE_RECOVERY` | Request được dùng để probe backend bị loại |
| `HEALTH_DOMINANT` | Chọn bằng P2C trong mode health-dominant |
| `LOAD_DOMINANT` | Chọn bằng P2C trong mode load-dominant |
| `NORMAL_P2C` | Chọn bằng P2C bình thường |
| `ALL_HARD_EXCLUDED_FALLBACK` | Fallback khi tất cả backend bị loại |

---

## 16. Metrics Prometheus

Adaptive ghi counter:

```text
alb_routing_selected_total
```

Tag chính:

- `backend`
- `port`
- `reason`

Dashboard dùng PromQL:

```promql
sum by (backend) (
  rate(alb_routing_selected_total[10s])
)
```

và:

```promql
sum by (reason) (
  rate(alb_routing_selected_total[10s])
)
```

Các metric routing cost được đăng ký trong `MetricsPoller` và `RoutingCostCalculator`:

```text
alb_routing_score
alb_routing_health_cost
alb_routing_load_cost
alb_routing_load_raw
alb_routing_capacity_weight
alb_routing_weight
```

---

## 17. Reset state

Endpoint reset:

```text
POST /actuator/alb/reset
```

Endpoint này gọi:

- `InflightTracker.resetAll()`
- `AdaptiveLoadBalancer.resetStaticState()`
- `InflightLifecycle.resetActiveRequests()`
- `PIDController.resetAllStates()`
- `EwmaSmoother.resetAllStates()`
- `SlidingWindowManager.resetAll()`
- `MetricsPoller.resetAllStates()`
- `DynamicWeightEngine.resetWeights()`
- `MetricsCache.clearAll()`

Reset state là bước bắt buộc trước benchmark để kết quả không bị ảnh hưởng bởi lần chạy trước.
