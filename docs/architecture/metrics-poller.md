# MetricsPoller

## 1. Vai trò

`MetricsPoller` là thành phần control-plane chịu trách nhiệm thu thập metrics từ các backend `REGISTRATION-SERVICE-ALB`, xử lý metrics thô và cập nhật score cho Adaptive Load Balancer.

Nó chạy định kỳ, không chạy theo từng request của client.

---

## 2. Tần suất poll

Mã nguồn dùng annotation:

```java
@Scheduled(fixedRateString = "${alb.polling.interval:200}")
```

Cấu hình hiện tại:

```yaml
alb:
    polling:
        interval: 200
```

Nghĩa là Gateway cố gắng poll backend mỗi `200ms`.

Để tránh các chu kỳ poll chồng lên nhau, `MetricsPoller` dùng `AtomicBoolean isPolling`. Nếu chu kỳ trước chưa hoàn tất, chu kỳ mới sẽ bị bỏ qua.

---

## 3. Danh sách backend

Mỗi lần poll, Gateway lấy danh sách instance từ Eureka:

```java
discoveryClient.getInstances("REGISTRATION-SERVICE-ALB")
```

Nếu danh sách instance thay đổi, poller xóa dữ liệu cũ của instance không còn active:

- raw metrics,
- score,
- capacity weight,
- latency values,
- queue values,
- smoothed scores,
- failure counters.

---

## 4. Endpoint được poll

Với mỗi backend, poller gọi:

```text
GET /api/alb-metrics
```

URL được tạo từ `ServiceInstance.getUri()`:

```java
String url = instance.getUri().toString() + "/api/alb-metrics";
```

Timeout hiện tại:

```java
METRICS_POLL_TIMEOUT_MS = 800
```

Nếu backend không trả lời trong 800ms hoặc có lỗi network, poller chuyển sang nhánh penalty.

---

## 5. Dữ liệu `/api/alb-metrics`

Backend trả về các field:

```json
{
  "cpu": 0.0,
  "rawCpu": 0.0,
  "count": 0.0,
  "totalTime": 0.0,
  "queue": 0.0,
  "capacityWeight": 1.0
}
```

Ý nghĩa:

| Field | Ý nghĩa |
|---|---|
| `cpu` | CPU usage đã chuẩn hóa theo CPU quota container |
| `rawCpu` | `process.cpu.usage` thô từ Micrometer |
| `count` | Tổng số request nghiệp vụ đã hoàn thành |
| `totalTime` | Tổng thời gian xử lý request, đơn vị giây |
| `queue` | Số request đang xử lý tại backend |
| `capacityWeight` | Số core CPU tương đối phát hiện từ cgroup/JVM |

Backend loại trừ các endpoint control khỏi thống kê latency:

- `/api/alb-metrics`
- `/actuator/**`
- `/api/chaos/**`

---

## 6. Tính delta latency

Micrometer Timer trả về counter tích lũy, không trả latency tức thời. Vì vậy poller tính latency theo chênh lệch giữa hai lần poll:

```text
deltaCount = currentCount - previousCount
deltaTotal = currentTotalTime - previousTotalTime
latencyMs = (deltaTotal / deltaCount) * 1000
```

Nếu `deltaCount > 0`, đây là latency trung bình của các request hoàn thành trong cửa sổ poll vừa qua.

Nếu counter bị reset do container/JVM restart, poller tái thiết lập baseline.

Nếu không có request mới hoàn thành, poller không kéo latency về 0. Thay vào đó, nó kéo nhẹ latency về baseline idle:

```text
currentLatency = previousLatency + 0.20 * (idleTarget - previousLatency)
```

Baseline mặc định khi chưa có dữ liệu là:

```text
IDLE_LATENCY_BASELINE_MS = 65.0
```

Latency được clamp vào khoảng:

```text
[1ms, 3000ms]
```

---

## 7. Xử lý queue

Poller ưu tiên giá trị `queue` do backend báo cáo từ gauge:

```text
http.server.requests.inflight
```

Nếu backend không cung cấp queue hợp lệ, Gateway fallback về `InflightTracker`:

```java
double realQueue = reportedQueue >= 0 ? reportedQueue : inflightTracker.getInflight(instanceId);
```

Trong mã nguồn hiện tại, backend có `RegistrationServiceMetricsFilter` để đăng ký gauge inflight và loại trừ endpoint control.

---

## 8. Capacity weight

Poller đọc:

```java
double capacityWeight = node.path("capacityWeight").asDouble(1.0);
metricsCache.putCapacityWeight(instanceId, capacityWeight);
```

`capacityWeight` được dùng bởi `RoutingCostCalculator` để tính phân bổ tải theo năng lực container:

```text
capacityShare = capacityWeight / sumCapacity
expectedInflight = max(minExpectedInflight, totalInflight * capacityShare)
```

Nhờ đó backend 2 CPU được kỳ vọng xử lý nhiều request hơn backend 1 CPU.

---

## 9. Pipeline xử lý metrics

Sau khi parse dữ liệu, poller tạo:

```java
InstanceMetrics metrics = new InstanceMetrics(instanceId, latency, realQueue, cpu);
```

Sau đó:

```text
1. Lưu raw metrics vào MetricsCache.
2. Ghi latency và queue vào SlidingWindowManager.
3. Gọi ScoreCalculator.calculateScore().
4. Áp EMA bất đối xứng lên finalScore.
5. Lưu ScoreBreakdown đã smooth vào MetricsCache.
6. Cập nhật backing maps cho Prometheus Gauge.
7. Đăng ký gauge nếu instance chưa từng được đăng ký.
```

---

## 10. EMA bất đối xứng cho finalScore

Sau khi `ScoreCalculator` trả về `finalScore`, poller làm mượt thêm lần nữa bằng EMA.

Cấu hình hard-code trong mã nguồn:

```text
EMA_ALPHA_SPIKE = 0.60
EMA_ALPHA_RISE = 0.35
EMA_ALPHA_RECOVER = 0.25
EMA_SPIKE_THRESHOLD = 0.30
```

Quy tắc:

| Trường hợp | Alpha | Ý nghĩa |
|---|---:|---|
| Score tăng mạnh | `0.60` | Phản ứng nhanh khi backend xấu đi |
| Score tăng nhẹ | `0.35` | Phản ứng vừa phải |
| Score giảm | `0.25` | Phục hồi chậm để tránh flapping |

Công thức:

```text
smoothedScore = previous + alpha * (rawScore - previous)
```

---

## 11. Xử lý poll failure

Nếu gọi `/api/alb-metrics` thất bại, poller tăng số lần lỗi liên tiếp:

```text
failures = consecutiveFailures + 1
```

Penalty score:

```text
rawPenaltyScore = min(10.0, failures * 2.5)
```

Sau đó vẫn áp EMA finalScore và tạo một `ScoreBreakdown` giả với trạng thái xấu:

```text
normLatency = 1
normQueue = 1
normCpu = 1
finalScore = smoothedPenaltyScore
```

Mục đích là để Adaptive tránh route vào backend không còn cung cấp metrics.

---

## 12. Metrics Prometheus do poller đăng ký

Theo từng backend:

```text
alb_latency_ewma
alb_queue_current
alb_final_score
alb_routing_score
alb_routing_health_cost
alb_routing_load_cost
alb_routing_load_raw
alb_routing_capacity_weight
```

Trong đó:

- `alb_latency_ewma`: latency đã làm mượt bằng EWMA.
- `alb_queue_current`: số request đang xử lý.
- `alb_final_score`: health score sau EMA.
- `alb_routing_score`: final routing cost.
- `alb_routing_health_cost`: health cost đã chuẩn hóa trong cụm.
- `alb_routing_load_cost`: load cost đã chuẩn hóa trong cụm.
- `alb_routing_load_raw`: tỷ lệ `inflight / expectedInflight`.
- `alb_routing_capacity_weight`: năng lực tương đối của backend.

---

## 13. Reset

Khi gọi:

```text
POST /actuator/alb/reset
```

`MetricsPoller.resetAllStates()` sẽ xóa:

- `trafficStates`
- `consecutiveFailures`
- `smoothedScores`
- `latencyValues`
- `queueValues`
- `scoreValues`

Đồng thời gọi:

```java
routingCostCalculator.reset();
```

Các Prometheus gauge đã đăng ký không bị hủy, nhưng backing map được làm sạch.
