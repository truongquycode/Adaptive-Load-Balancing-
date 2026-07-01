# Adaptive Load Balancer

## 1. Vai trò

`AdaptiveLoadBalancer` là thành phần data-plane chọn backend cho từng request đi qua API Gateway khi cấu hình:

```yaml
alb:
  strategy: adaptive
```

Route Gateway tương ứng:

```java
.route("backend-route", r -> r.path("/api/**")
        .uri("lb://REGISTRATION-SERVICE-ALB"))
```

Class này không tự poll metrics. Nó đọc kết quả đã được control-plane cập nhật trong `MetricsCache` và kết hợp với inflight realtime để chọn instance.

---

## 2. Phân tách control-plane và data-plane

```text
Control-plane
    MetricsPoller
    SlidingWindowManager
    ScoreCalculator
    DynamicWeightEngine
    PIDController
    EwmaSmoother
    MetricsCache

Data-plane
    AdaptiveLoadBalancer
    RoutingCostCalculator
    InflightTracker
    InflightLifecycle
```

Cách tách này giúp request path không phải tự gọi HTTP đến backend để lấy metrics. Gateway chỉ đọc cache và counter cục bộ.

---

## 3. Luồng chọn backend

```text
AdaptiveLoadBalancer.choose()
    |
    v
Lấy danh sách ServiceInstance từ supplier/Eureka
    |
    v
RoutingCostCalculator.calculate(instances)
    |
    v
RoutingContext
    |-- costs
    |-- eligibleCosts
    |-- healthWeight
    |-- loadWeight
    |-- mode
    v
Chọn backend theo thứ tự ưu tiên:
    1. Không có instance -> EmptyResponse
    2. Một instance -> chọn thẳng
    3. Warmup -> Round Robin
    4. Low-load stable -> Round Robin
    5. Probe recovery -> chọn instance bị phạt có kiểm soát
    6. Normal -> P2C trên routing cost
    7. Fallback -> least cost
```

Mỗi lần chọn backend, thuật toán ghi counter `alb.routing.selected` với label `backend`, `port`, `strategy`, `reason`.

---

## 4. Warmup Round Robin

Khi backend mới xuất hiện hoặc Gateway vừa reset, metrics chưa đủ ổn định. Trong khoảng:

```yaml
alb:
  routing:
    warmup-ms: 5000
```

Adaptive dùng Round Robin và ghi reason `WARMUP_RR`. Cơ chế này tránh phạt sai một instance chỉ vì chưa có đủ metrics sau restart.

---

## 5. Low-load fallback

Ở tải thấp, chênh lệch latency/queue/CPU nhỏ có thể chỉ là nhiễu. Nếu data-plane phản ứng quá mạnh thì Adaptive có thể tự tạo dao động không cần thiết.

Các ngưỡng chính:

```yaml
alb:
  routing:
    low-load-inflight: 20
    low-load-health-spread: 0.12
    low-load-load-spread: 0.25
```

Nếu tổng inflight thấp và health/load spread nhỏ, Adaptive dùng Round Robin và ghi reason `LOW_LOAD_RR`. Vì vậy trong low load, Adaptive có thể cho kết quả gần Round Robin; đây là hành vi cố ý để ổn định.

Ablation `no-low-load-rr` dùng để kiểm chứng cơ chế này.

---

## 6. Probe recovery

Nếu một backend từng bị phạt do latency/queue/CPU xấu, nó cần cơ hội nhận một lượng request nhỏ để chứng minh đã hồi phục. Cơ chế probe dùng:

```yaml
alb:
  routing:
    probe-interval-ms: 3000
    probe-probability: 0.005
```

Probe không phải chia tải đều. Nó chỉ là lưu lượng kiểm tra hồi phục có xác suất thấp. Ablation `no-probe` dùng để xem recovery có bị chậm hoặc backend bị bỏ đói không.

---

## 7. P2C selection

Khi không ở warmup/low-load/probe, Adaptive dùng Power of Two Choices:

```text
randomly pick 2 eligible backends
choose the one with lower final routing cost
```

P2C giảm nguy cơ herd effect so với chọn min toàn cục trong mọi request, đồng thời rẻ hơn về chi phí tính toán. Ablation `no-p2c` thay bằng chọn backend có cost thấp nhất toàn cục.

---

## 8. Hard exclusion

`RoutingCostCalculator` có thể loại một backend khỏi danh sách eligible nếu:

| Reason | Ý nghĩa |
|---|---|
| `NO_METRICS` | Chưa có score trong cache |
| `STALE` | Metrics quá cũ |
| `UNHEALTHY_SCORE` | Score vượt ngưỡng unhealthy |
| `HARD_INFLIGHT_CAP` | Inflight vượt hard cap theo capacity |

Nếu tất cả backend đều bị loại, Adaptive phải fallback để không làm Gateway mất route. Trường hợp này cần quan sát trong Grafana qua routing reason và error rate.

---

## 9. Vì sao gọi là adaptive

Adaptive là adaptive ở mức thực nghiệm vì:

- backend metrics được cập nhật liên tục;
- health score thay đổi theo latency/queue/CPU;
- MCDM weights có thể thay đổi khi có traffic thật;
- routing cost kết hợp health và load realtime;
- capacity weight làm instance mạnh/yếu nhận tải khác nhau;
- stale penalty xử lý metrics lỗi/chậm;
- probe recovery cho phép instance hồi phục quay lại.

Không nên mô tả thuật toán này là tối ưu toàn cục hoặc có chứng minh ổn định như một bộ điều khiển lý thuyết đầy đủ.

---

## 10. Quan hệ với benchmark

Để benchmark không gắn nhãn sai strategy, script phải xác minh:

```http
GET /actuator/alb/strategy
```

Trước mỗi run cần reset:

```http
POST /actuator/alb/reset
POST /api/chaos/reset trên 8081, 8082, 8083
```

Khi báo cáo Adaptive, nên đọc cùng lúc:

- `alb_routing_selected_total` theo backend/reason;
- `alb_routing_score`;
- `alb_final_score`;
- `alb_routing_weight`;
- Gateway latency all-status;
- Gateway error rate;
- actual throughput.
