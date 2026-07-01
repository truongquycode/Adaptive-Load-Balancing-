# Tổng quan kiến trúc hệ thống

## 1. Mục đích

Tài liệu này mô tả kiến trúc thực tế của dự án Adaptive Load Balancer cho Spring Boot microservices. Nội dung bám theo mã nguồn hiện tại, không mô tả như một hệ thống production hoàn chỉnh.

Hệ thống phục vụ luận văn/nghiên cứu về cân bằng tải thích nghi. Mục tiêu là quan sát cách các thuật toán định tuyến phản ứng khi backend có năng lực khác nhau, workload hỗn hợp và chaos cục bộ.

---

## 2. Thành phần chính

| Thành phần | Thư mục | Port | Vai trò |
|---|---|---:|---|
| Eureka Server | `eureka-server` | 8761 | Service Discovery |
| API Gateway ALB | `api-gateway-alb` | 8080 | Gateway + Load Balancer |
| Registration backend 1 | `registration-service-alb` | 8081 | Backend mạnh nhất |
| Registration backend 2 | `registration-service-alb` | 8082 | Backend trung bình |
| Registration backend 3 | `registration-service-alb` | 8083 | Backend yếu nhất / chaos target |
| Prometheus | `monitoring` | 9090 | Scrape metrics |
| Grafana | `monitoring` | 3000 | Dashboard |
| cAdvisor | `monitoring` | 8088 | Container CPU/memory |
| JMeter | `jmeter`, `scripts_run_jmeter` | - | Sinh tải benchmark |

---

## 3. Luồng request chính

```text
Client / JMeter
    |
    | GET /api/**
    v
API Gateway :8080
    |
    | Spring Cloud Gateway route: backend-route
    | URI: lb://REGISTRATION-SERVICE-ALB
    v
Spring Cloud LoadBalancer
    |
    | Strategy theo alb.strategy:
    | - adaptive
    | - round-robin
    | - random
    | - least-connections
    v
Eureka Discovery Client
    |
    | REGISTRATION-SERVICE-ALB instances
    v
registration-8081 / registration-8082 / registration-8083
```

Gateway là nơi duy nhất quyết định backend nào nhận request. Backend chỉ xử lý request và export metrics.

---

## 4. Luồng control-plane của Adaptive

```text
MetricsPoller
    |
    | Poll /api/alb-metrics từ từng backend
    v
MetricsCache + SlidingWindowManager
    |
    v
ScoreCalculator
    |
    | EWMA latency
    | normalized latency / queue / CPU
    | Dynamic MCDM base score
    | PID-inspired penalty
    v
final health score
    |
    v
RoutingCostCalculator
    |
    | health cost
    | capacity-normalized load cost
    | stale penalty
    v
AdaptiveLoadBalancer
    |
    | warmup RR / low-load RR / probe / P2C
    v
selected backend
```

Control-plane chạy định kỳ để cập nhật metrics và score. Data-plane dùng cache sẵn có để chọn backend nhanh trong từng request.

---

## 5. Discovery và instance identity

Các backend đăng ký Eureka với service name:

```text
REGISTRATION-SERVICE-ALB
```

Mỗi backend có `instance-id` theo port:

```yaml
eureka:
  instance:
    instance-id: ${spring.application.name}:${PORT:8081}
```

Gateway cũng có instance-id cố định:

```yaml
eureka:
  instance:
    instance-id: ${spring.application.name}:${server.port}
```

Mục đích là tránh tạo nhiều instance `DOWN` trong Eureka khi Gateway restart/deploy nhiều lần.

---

## 6. Các strategy được hỗ trợ

| Strategy | Class/nguồn | Cách chọn |
|---|---|---|
| `adaptive` | `AdaptiveLoadBalancer` | Chọn theo routing cost, P2C, probe, warmup/low-load fallback |
| `round-robin` | Spring `RoundRobinLoadBalancer` | Chia tuần tự |
| `random` | Spring `RandomLoadBalancer` | Chọn ngẫu nhiên |
| `least-connections` | `LeastConnectionsLoadBalancer` | Chọn backend có inflight thấp nhất |

Strategy được xác minh bằng:

```http
GET /actuator/alb/strategy
```

Reset trạng thái benchmark bằng:

```http
POST /actuator/alb/reset
```

---

## 7. Backend simulation

`registration-service-alb` cung cấp:

| Endpoint | Mục đích |
|---|---|
| `GET /api/register` | API đơn giản để kiểm tra routing |
| `POST /api/register-user` | Mô phỏng đăng ký user |
| `GET /api/simulate-call` | Endpoint legacy/baseline |
| `GET /api/simulate-mixed-call` | Endpoint benchmark chính |
| `GET /api/alb-metrics` | Metrics để Gateway poll |
| `POST /api/chaos/**` | Bật/tắt chaos |

`/api/simulate-mixed-call` có profile `light`, `medium`, `slow`, `very-slow`, `mixed`. Tỷ lệ `mixed` là 60/25/12/3.

---

## 8. Monitoring

Prometheus scrape:

- Gateway actuator;
- backend actuator trực tiếp;
- cAdvisor.

Dashboard chính là:

```text
monitoring/dashboard-grafana.json
```

Các panel đã tách riêng:

- latency chỉ request thành công;
- latency all-status;
- error rate;
- actual throughput;
- routing selection rate;
- routing score/cost;
- MCDM weights và update mode;
- CPU/memory container.

Khi phân tích kết quả, không được chỉ nhìn latency của HTTP 200. Cần đọc kèm error rate và throughput thực tế.

---

## 9. Giới hạn kiến trúc

- Backend chỉ là service mô phỏng.
- Không có database thật hoặc external dependency thật.
- Chỉ có 3 backend nên một số kỹ thuật thống kê như EWM cần diễn giải thận trọng.
- Docker CPU quota là capacity giả định, không đại diện đầy đủ cho mọi bottleneck.
- Gateway chỉ phụ thuộc healthcheck Eureka trong Docker Compose; benchmark script phải kiểm tra đủ backend trước khi chạy.

---

## 10. Tài liệu liên quan

| File | Nội dung |
|---|---|
| `adaptive-load-balancer.md` | Data-plane Adaptive |
| `../routing-cost-calculator.md` | Routing cost cuối cùng |
| `metrics-poller.md` | Poll metrics và real-traffic gate |
| `score-calculator.md` | Health score |
| `dynamic-weight-engine.md` | Dynamic MCDM |
| `ahp-default-weight-rationale.md` | Cơ sở chọn AHP prior mặc định |
| `pid-controller.md` | PID-inspired penalty |
| `ewma-smoother.md` | Adaptive EWMA |
| `sliding-window-manager.md` | Percentile windows |
| `../benchmark-methodology.md` | Quy trình benchmark |
| `../threats-to-validity.md` | Giới hạn khi diễn giải |
