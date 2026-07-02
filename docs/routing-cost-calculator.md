# RoutingCostCalculator

## 1. Vai trò

`RoutingCostCalculator` là thành phần hợp nhất health score từ control-plane với tải tức thời của data-plane để tạo `finalCost` cho Adaptive Load Balancer.

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

Nếu `ScoreCalculator` trả lời “backend này đang khỏe hay yếu so với cụm?”, thì `RoutingCostCalculator` trả lời “request hiện tại nên đi tới backend nào để chi phí định tuyến thấp nhất?”.

---

## 2. Đầu vào

| Đầu vào | Nguồn |
|---|---|
| Danh sách `ServiceInstance` | Eureka/Spring LoadBalancer supplier |
| `finalScore`, `ewmaLatency` | `MetricsCache` / `ScoreBreakdown` |
| Inflight hiện tại | `InflightTracker` |
| Capacity weight | `/api/alb-metrics` → `MetricsCache` |
| Staleness | `ScoreBreakdown.updatedAtMs` |
| Routing config | `alb.routing` trong `application.yml` |

---

## 3. Capacity-normalized load

Adaptive không so sánh inflight thô ngang bằng vì backend có CPU quota khác nhau.

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

## 4. Health cost tương đối

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

Sau đó `healthRaw` được min-max normalize trong cụm để tạo `healthCost`.

Giới hạn: nếu toàn cụm cùng chậm, min-max tương đối có thể không phản ánh đầy đủ việc cả hệ thống đang vi phạm SLA. Vì vậy bản hiện tại bổ sung `absoluteLatencyCost`.

---

## 5. Absolute latency cost

`absoluteLatencyCost` là tín hiệu latency tuyệt đối, không phụ thuộc vào việc backend nhanh/chậm tương đối so với nhau.

Công thức:

```text
absoluteLatencyCost = clamp(
    (ewmaLatencyMs - absoluteLatencyTargetMs)
    /
    (absoluteLatencyCriticalMs - absoluteLatencyTargetMs),
    0, 1
)
```

Cấu hình mặc định:

```yaml
alb:
  routing:
    absolute-latency-penalty-weight: 0.12
    absolute-latency-target-ms: 300.0
    absolute-latency-critical-ms: 1500.0
```

Ý nghĩa:

- dưới `300ms`: chưa cộng penalty tuyệt đối;
- từ `300ms` đến `1500ms`: penalty tăng tuyến tính;
- trên `1500ms`: penalty đạt mức tối đa.

Mục tiêu khoa học: khi cả 3 backend đều tăng latency từ mức thấp lên rất cao, hệ thống vẫn nhìn thấy “cụm đang xấu tuyệt đối”, không bị che bởi chuẩn hóa tương đối.

Prometheus metric:

```text
alb_routing_absolute_latency_cost{backend="..."}
```

---

## 6. Stale penalty và hard exclusion

Metrics có thể bị cũ nếu backend không phản hồi `/api/alb-metrics` hoặc Gateway poll lỗi.

```yaml
alb:
  routing:
    stale-soft-ms: 1500
    stale-hard-ms: 5000
    stale-penalty-weight: 0.15
```

- dưới `stale-soft-ms`: không phạt;
- từ soft đến hard: tăng penalty;
- vượt `stale-hard-ms`: hard exclude khỏi eligible list.

Hard exclusion cũng áp dụng khi score quá xấu hoặc inflight vượt hard cap.

---

## 7. Final routing cost

Công thức khái niệm:

```text
finalCost = healthWeight * healthCost
          + loadWeight   * loadCost
          + overloadPenalty
          + capPressurePenalty
          + absoluteHealthPenalty
          + absoluteLatencyPenalty
          + stalePenalty
```

Trong đó:

```text
absoluteLatencyPenalty = absoluteLatencyPenaltyWeight * absoluteLatencyCost
```

`healthWeight` và `loadWeight` được điều chỉnh theo độ phân tán hiện tại của health/load. Nếu tải thấp và spread nhỏ, Adaptive có thể fallback về Round Robin để tránh overreaction.

---

## 8. Probe recovery guard

Probe recovery cho backend bị loại cơ hội quay lại, nhưng nếu probe trong lúc toàn cụm đang căng thì có thể làm xấu p99. Bản hiện tại bổ sung guard:

```yaml
alb:
  routing:
    probe-max-total-inflight-ratio: 0.70
    probe-max-load-raw: 1.10
    probe-max-absolute-latency-cost: 0.80
    probe-max-final-cost: 1.50
```

Probe bị tắt khi cluster ở mode `LOAD_DOMINANT`, `ALL_HARD_EXCLUDED_FALLBACK`, hoặc khi load/latency/finalCost vượt ngưỡng an toàn.

---

## 9. Metrics liên quan

| Metric | Ý nghĩa |
|---|---|
| `alb_routing_score` | final routing cost |
| `alb_routing_health_cost` | health cost đã chuẩn hóa |
| `alb_routing_load_cost` | load cost đã chuẩn hóa |
| `alb_routing_load_raw` | load ratio thô theo capacity |
| `alb_routing_absolute_latency_cost` | cost latency tuyệt đối theo SLA |
| `alb_routing_capacity_weight` | capacity weight |
| `alb_routing_weight` | tỷ trọng health/load |
| `alb_routing_selected_total` | backend được chọn theo reason |

---

## 10. Điểm cần giải thích trong luận văn

- Capacity weight theo CPU quota chỉ là giả định về năng lực tương đối.
- Health/load/absolute latency cost là heuristic thực nghiệm, không phải chứng minh tối ưu toàn cục.
- Low-load fallback về RR là để tránh overreaction.
- Absolute latency cost giúp tránh mất tín hiệu khi toàn cụm cùng chậm.
- Cần ablation `no-capacity`, `no-p2c`, `no-probe`, `no-low-load-rr` và sensitivity analysis để chứng minh từng cơ chế có giá trị.
