# Algorithm Tuning Log

Tài liệu này ghi lại các thay đổi thuật toán sau khi benchmark/docs đã được ổn định. Mỗi thay đổi phải có mục tiêu rõ ràng và có thể kiểm chứng bằng benchmark/ablation.

## Patch 2026-07-02 — Scientific cleanup for Adaptive routing

### 1. Real latency histogram gate

**Giả thuyết:** idle polling không nên làm lệch histogram latency, PID setpoint và system percentile.

**File sửa:**

- `MetricsPoller.java`
- `ScoreBreakdown.java`

**Thay đổi:**

- `LatencySample` có thêm `realLatencySample`.
- Chỉ gọi `SlidingWindowManager.addMetrics()` khi `deltaCount > 0`.
- Khi idle thật, chỉ refresh timestamp score cũ; không cập nhật EWMA/score từ latency giả.
- Khi có inflight nhưng chưa có request hoàn thành, dùng EWMA latency gần nhất và cập nhật queue/CPU, không add histogram.

**Metric/kỳ vọng kiểm chứng:**

- Khi chưa chạy JMeter, `alb_mcdm_update_mode = 0`.
- Dynamic MCDM giữ AHP prior.
- System latency percentile không bị kéo bởi idle baseline.

### 2. Absolute latency cost

**Giả thuyết:** khi toàn cụm cùng chậm, min-max tương đối không đủ; cần thêm tín hiệu latency tuyệt đối theo SLA.

**File sửa:**

- `RoutingCostCalculator.java`
- `RoutingCost.java`
- `MetricsPoller.java`
- `AlbProperties.java`
- `application.yml`

**Thay đổi:**

```text
absoluteLatencyCost = clamp((ewmaLatencyMs - targetMs) / (criticalMs - targetMs), 0, 1)
finalCost += absoluteLatencyPenaltyWeight * absoluteLatencyCost
```

Default:

```yaml
absolute-latency-target-ms: 300.0
absolute-latency-critical-ms: 1500.0
absolute-latency-penalty-weight: 0.12
```

**Metric mới:**

```text
alb_routing_absolute_latency_cost
```

### 3. Config hóa hằng số tuning

**Giả thuyết:** các hằng số quyết định cần được cấu hình để sensitivity analysis có thể lặp lại, thay vì hard-code trong Java.

**File sửa:**

- `AlbProperties.java`
- `application.yml`
- `DynamicWeightEngine.java`
- `MetricsPoller.java`
- `RoutingCostCalculator.java`

**Thay đổi:** đưa các nhóm sau ra config:

- score EMA alpha;
- metrics timeout;
- MCDM blend/EMA/bounds;
- routing norm range;
- health/load dominant threshold;
- overload/cap/absolute penalties;
- probe guard thresholds.

### 4. Metric reason consistency for baseline algorithms

**Giả thuyết:** dashboard phải so sánh công bằng giữa Adaptive và baseline strategies.

**File sửa:**

- `MetricAwareLoadBalancer.java`
- `LeastConnectionsLoadBalancer.java`
- `LoadBalancerBeanConfig.java`

**Thay đổi:** mọi strategy đều emit cùng schema:

```text
alb_routing_selected_total{backend, port, reason}
```

Reason mới:

- `BASELINE_ROUND_ROBIN`
- `BASELINE_RANDOM`
- `BASELINE_LEAST_CONNECTIONS`

### 5. P2C exact two-candidate selection

**Giả thuyết:** P2C phải luôn so sánh hai ứng viên khác nhau.

**File sửa:**

- `AdaptiveLoadBalancer.java`

**Thay đổi:** chọn hai index khác nhau bằng công thức thay vì random retry.

### 6. Probe recovery guard

**Giả thuyết:** probe recovery hữu ích khi backend có thể hồi phục, nhưng có thể làm xấu p99 nếu cụm đang stress.

**File sửa:**

- `AdaptiveLoadBalancer.java`
- `AlbProperties.java`
- `application.yml`

**Thay đổi:** tắt probe khi:

- mode là `LOAD_DOMINANT` hoặc `ALL_HARD_EXCLUDED_FALLBACK`;
- max load raw vượt ngưỡng;
- absolute latency cost vượt ngưỡng;
- total inflight vượt tỷ lệ an toàn;
- candidate probe có final cost/absolute latency cost quá cao.

## Kế hoạch kiểm chứng

Chạy tối thiểu:

1. Low no chaos — 3 đến 5 runs.
2. Medium dependency slowdown — 3 đến 5 runs.
3. High dependency slowdown — 3 đến 5 runs.
4. Stress/recovery — 3 đến 5 runs.
5. Adaptive ablation medium — full/no-pid/fixed-weights/no-capacity/no-p2c/no-probe.

Các chỉ số cần so sánh trước/sau:

- average, p90, p95, p99;
- actual RPS;
- error rate;
- routing distribution;
- `alb_routing_absolute_latency_cost`;
- `alb_routing_selected_total` theo reason;
- CPU/memory container.

Nếu p99 hoặc error rate xấu hơn rõ rệt ở high/stress, rollback từng patch theo thứ tự: probe guard config → absolute latency weight → score EMA config.
