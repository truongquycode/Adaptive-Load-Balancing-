# MetricsPoller

## 1. Vai trò

`MetricsPoller` là control-plane component trong API Gateway. Nó định kỳ lấy metrics từ các backend thông qua endpoint:

```http
GET /api/alb-metrics
```

Tần suất poll:

```yaml
alb:
  polling:
    interval: 200
```

Metrics được dùng để cập nhật:

- latency EWMA;
- queue/inflight;
- CPU normalized;
- capacity weight;
- health score;
- Prometheus gauges phục vụ Grafana;
- traffic activity gate cho Dynamic MCDM.

---

## 2. Dữ liệu backend trả về

`AlbMetricsController` trả về map gồm:

| Field | Ý nghĩa |
|---|---|
| `cpu` | CPU usage đã chuẩn hóa theo capacity |
| `rawCpu` | CPU process từ Micrometer |
| `count` | Tổng số request nghiệp vụ đã hoàn thành |
| `totalTime` | Tổng thời gian xử lý request, đơn vị giây |
| `queue` | `http.server.requests.inflight` |
| `capacityWeight` | CPU quota phát hiện từ cgroup/container |

Control endpoint như `/api/alb-metrics`, `/actuator`, `/api/chaos` không được tính vào request nghiệp vụ.

---

## 3. Delta latency

Micrometer timer là counter tích lũy. `MetricsPoller` không dùng trực tiếp tổng `totalTime`, mà tính delta giữa hai lần poll:

```text
deltaCount = currentCount - previousCount
deltaTotal = currentTotalTime - previousTotalTime
latencyMs  = (deltaTotal / deltaCount) * 1000
```

Chỉ khi `deltaCount > 0` mới xem là có request nghiệp vụ thật hoàn thành trong cửa sổ poll.

Nếu counter reset do backend restart, poller re-baseline và không tính đó là traffic thật.

---

## 4. Idle latency và real traffic gate

Khi không có request mới:

```text
deltaCount = 0
```

Poller vẫn giữ một latency idle để cache/routing không bị rỗng dữ liệu. Tuy nhiên idle sample **không được ghi nhận là traffic thật** cho Dynamic MCDM.

Điểm quan trọng:

```text
Idle metrics vẫn có thể phục vụ routing/cache.
DynamicWeightEngine chỉ học trọng số khi có đủ request nghiệp vụ thật.
```

Poller gọi:

```java
metricsCache.recordCompletedRequestsForMcdm(latencySample.completedRequests())
```

Trong đó `completedRequests` chỉ lớn hơn 0 khi `deltaCount > 0`.

---

## 5. Score pipeline

Sau khi nhận metrics, Poller thực hiện:

```text
InstanceMetrics(instanceId, latency, queue, cpu)
    |
    v
metricsCache.putMetrics()
windowManager.addMetrics()
scoreCalculator.calculateScore()
applyScoreEma()
metricsCache.putScore()
update Prometheus backing maps
registerPrometheusGauges()
```

Final score sau EMA được dùng bởi `RoutingCostCalculator`.

Ablation `no-score-ema` tắt bước làm mượt finalScore.

---

## 6. Score EMA

Poller áp EMA bất đối xứng lên final score:

- score xấu đi nhanh -> tăng nhanh để bảo vệ người dùng;
- score tốt lại -> giảm chậm để tránh flapping;
- spike lớn -> alpha cao hơn.

Đây là heuristic thực nghiệm, không phải chứng minh tối ưu. Khi viết luận văn cần giải thích là cơ chế giảm dao động routing.

---

## 7. Prometheus metrics do Poller đăng ký

| Metric | Ý nghĩa |
|---|---|
| `alb_latency_ewma` | EWMA latency theo backend |
| `alb_queue_current` | queue/inflight theo backend |
| `alb_final_score` | final health score sau EMA |
| `alb_routing_score` | final routing cost từ `RoutingCostCalculator` |
| `alb_routing_health_cost` | health cost đã chuẩn hóa |
| `alb_routing_load_cost` | load cost đã chuẩn hóa |
| `alb_routing_load_raw` | capacity-normalized load ratio thô |
| `alb_routing_capacity_weight` | capacity weight theo backend |

Tên Micrometer dạng dotted sẽ hiển thị trong Prometheus dạng underscore, ví dụ `alb.final.score` thành `alb_final_score`.

---

## 8. Rủi ro và giới hạn

- Latency từ Poller là delta-average giữa hai lần poll, không phải p95/p99 raw per request.
- Poll interval 200 ms có thể bỏ lỡ spike rất ngắn.
- Nếu backend không phản hồi `/api/alb-metrics`, metric của backend đó có thể thiếu hoặc stale.
- CPU từ `process.cpu.usage` phụ thuộc semantics của Micrometer/JVM/container.
- Poller chạy nền ngay cả khi không có JMeter; vì vậy Dynamic MCDM phải có real-traffic gate để không học từ idle noise.

---

## 9. Cách kiểm tra khi thiếu backend trên Grafana

Nếu panel `alb_final_score` thiếu 8083, kiểm tra:

```bash
curl -m 3 http://172.30.35.37:8083/actuator/health
curl -m 3 http://172.30.35.37:8083/api/alb-metrics
curl -s http://172.30.35.37:8761/eureka/apps/REGISTRATION-SERVICE-ALB
curl -s http://172.30.35.37:8080/actuator/prometheus | grep alb_final_score
```

Nếu Eureka có 8083 nhưng `/api/alb-metrics` treo, lỗi nằm ở backend 8083 chứ không phải dashboard.
