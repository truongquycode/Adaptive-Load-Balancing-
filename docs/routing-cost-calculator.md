# RoutingCostCalculator

## 1. Vai trò

`RoutingCostCalculator` là thành phần kết hợp health score từ control-plane với tải tức thời của data-plane để tạo final routing cost cho Adaptive Load Balancer.

Nó nằm giữa:

```text
ScoreCalculator / MetricsCache
    |
    v
RoutingCostCalculator
    |
    v
AdaptiveLoadBalancer
```

Nếu `ScoreCalculator` trả lời câu hỏi “backend này đang khỏe hay yếu?”, thì `RoutingCostCalculator` trả lời câu hỏi “trong request hiện tại, backend nào có chi phí định tuyến thấp nhất?”.

---

## 2. Đầu vào

| Đầu vào | Nguồn |
|---|---|
| Danh sách `ServiceInstance` | Eureka/Spring LoadBalancer supplier |
| `finalScore` | `MetricsCache` |
| Inflight hiện tại | `InflightTracker` |
| Capacity weight | `MetricsCache` từ `/api/alb-metrics` |
| Staleness | timestamp trong `ScoreBreakdown` |
| Routing config | `alb.routing` trong `application.yml` |

---

## 3. Capacity-normalized load

Adaptive không so sánh inflight thô một cách ngang bằng vì backend có CPU quota khác nhau.

Công thức ý tưởng:

```text
capacityShare = capacityWeight / sumCapacity
expectedInflight = max(minExpectedInflight, totalInflight * capacityShare)
loadRaw = inflight / expectedInflight
```

Ý nghĩa:

- `loadRaw = 1.0`: backend đang nhận đúng phần tải theo capacity.
- `loadRaw > 1.0`: backend đang nhận nhiều hơn phần hợp lý.
- `loadRaw < 1.0`: backend còn tương đối rảnh so với capacity.

Ablation `no-capacity` xem mọi backend có capacity = 1.0 để kiểm chứng vai trò capacity weight.

---

## 4. Health cost

Health raw lấy từ `ScoreBreakdown.finalScore()`:

```text
healthRaw = finalScore
```

Giá trị này đã gồm:

- EWMA latency;
- normalized latency;
- normalized queue;
- normalized CPU;
- Dynamic MCDM base score;
- PID-inspired penalty;
- score EMA ở `MetricsPoller`.

Health càng thấp càng tốt.

---

## 5. Stale penalty

Metrics có thể bị cũ nếu backend không phản hồi `/api/alb-metrics` hoặc Gateway poll lỗi.

Cấu hình:

```yaml
alb:
  routing:
    stale-soft-ms: 1500
    stale-hard-ms: 5000
    stale-penalty-weight: 0.15
```

Ý nghĩa:

- dưới `stale-soft-ms`: không phạt;
- từ soft đến hard: tăng penalty;
- vượt `stale-hard-ms`: có thể hard exclude khỏi eligible list.

Stale penalty giúp tránh tiếp tục gửi traffic vào backend có dữ liệu quá cũ.

---

## 6. Hard inflight cap

Cấu hình:

```yaml
alb:
  routing:
    hard-inflight-cap: 220
```

Hard cap được điều chỉnh theo capacity. Instance mạnh có cap cao hơn, instance yếu có cap thấp hơn.

Mục tiêu là tránh một backend nhận quá nhiều request đồng thời dù score tạm thời còn tốt.

---

## 7. Health/load dynamic weight

Routing cost không phải luôn ưu tiên health hoặc luôn ưu tiên load. Nó dùng hai thành phần:

```text
finalCost = healthWeight * healthCost
          + loadWeight   * loadCost
          + stalePenalty
```

`healthWeight` và `loadWeight` được điều chỉnh theo độ phân tán hiện tại:

- nếu health khác biệt rõ, tăng vai trò health;
- nếu load/inflight khác biệt rõ, tăng vai trò load;
- nếu low-load ổn định, Adaptive có thể fallback về RR.

Prometheus metric:

```text
alb_routing_weight{component="health"}
alb_routing_weight{component="load"}
```

---

## 8. Low-load stability

Low load là vùng dễ bị nhiễu. Nếu chỉ vài request đang chạy, một chênh lệch latency nhỏ có thể làm score khác biệt giả.

Cấu hình:

```yaml
alb:
  routing:
    low-load-inflight: 20
    low-load-health-spread: 0.12
    low-load-load-spread: 0.25
```

Nếu tải thấp và spread nhỏ, mode chuyển sang low-load stable để `AdaptiveLoadBalancer` dùng Round Robin. Đây là hành vi có chủ đích, không phải lỗi.

---

## 9. Metrics liên quan

| Metric | Ý nghĩa |
|---|---|
| `alb_routing_score` | final routing cost |
| `alb_routing_health_cost` | health cost đã chuẩn hóa |
| `alb_routing_load_cost` | load cost đã chuẩn hóa |
| `alb_routing_load_raw` | load ratio thô theo capacity |
| `alb_routing_capacity_weight` | capacity weight |
| `alb_routing_weight` | tỷ trọng health/load |
| `alb_routing_selected_total` | backend được chọn theo reason |

---

## 10. Điểm cần giải thích trong luận văn

- Capacity weight theo CPU quota chỉ là giả định về năng lực tương đối, không phản ánh mọi bottleneck.
- Health cost và load cost là heuristic thực nghiệm.
- Low-load fallback về RR là để tránh overreaction, nên Adaptive có thể giống RR khi tải thấp.
- Stale penalty là cần thiết vì metrics polling có thể lỗi hoặc trễ.
- Cần ablation `no-capacity`, `no-p2c`, `no-probe`, `no-low-load-rr` để chứng minh từng cơ chế có giá trị.
