# Adaptive Load Balancer

Hệ thống API Gateway cân bằng tải thích nghi cho kiến trúc microservices. Gateway liên tục đo lường sức khỏe thực tế của từng instance backend qua ba tiêu chí (latency, queue length, CPU usage), tổng hợp thành điểm số theo thuật toán MCDM kết hợp PID Controller, và đưa ra quyết định routing trong thời gian thực.

Mục tiêu: chứng minh rằng một gateway thích nghi có thể phát hiện instance degradation trong vòng 200–400ms và tránh route traffic đến đó, thay vì tiếp tục đổ tải vào một node đang có vấn đề như Round-Robin hay Least Connections.

---

## Kiến trúc hệ thống

```
Client
  |
  v
API Gateway (8080)          Control Plane: MetricsPoller -> ScoreCalculator -> MetricsCache
  |                         Data Plane:    AdaptiveLoadBalancer <- MetricsCache
  +-- Eureka Server (8761)
  |
  +-- registration-8081  (2.0 CPU / 768 MB)
  +-- registration-8082  (1.5 CPU / 512 MB)
  +-- registration-8083  (1.0 CPU / 384 MB)
```

Gateway vận hành hai plane độc lập:

- **Control Plane**: MetricsPoller thu thập metrics mỗi 200ms, ScoreCalculator tính điểm, kết quả lưu vào MetricsCache.
- **Data Plane**: AdaptiveLoadBalancer đọc điểm từ cache, kết hợp inflight count tức thời, chọn instance có routing score thấp nhất.

---

## Modules

| Module | Mô tả |
|--------|-------|
| `eureka-server` | Eureka Server — đăng ký và khám phá service instance |
| `api-gateway-alb` | API Gateway — routing, scoring, load balancing |
| `registration-service-alb` | Backend service — xử lý nghiệp vụ và phơi metrics |

---

## Yêu cầu hệ thống

- Java 21
- Maven 3.9+
- Docker & Docker Compose

---

## Khởi chạy

### Với Docker Compose

```bash
docker compose build
docker compose up -d
```

Thứ tự khởi động được kiểm soát qua health check: Eureka phải UP trước khi các service khác start.

### Thủ công (phát triển)

```bash
# Khởi động lần lượt
mvn clean package -DskipTests

java -jar eureka-server/target/*.jar
java -jar registration-service-alb/target/*.jar --server.port=8081
java -jar registration-service-alb/target/*.jar --server.port=8082
java -jar registration-service-alb/target/*.jar --server.port=8083
java -jar api-gateway-alb/target/*.jar
```

---

## Cấu hình

Tất cả tham số thuật toán được đặt trong `api-gateway-alb/src/main/resources/application.yml`.

### Chiến lược load balancing

```yaml
alb:
  strategy: adaptive   # adaptive | round-robin | random | least-connections
```

### AEWMA (Adaptive EWMA)

```yaml
alb:
  ewma:
    tau-min: 200.0    # ms — tau tối thiểu khi latency spike (phản ứng nhanh)
    tau-max: 2000.0   # ms — tau tối đa khi ổn định (lọc nhiễu)
    k: 3.0            # hệ số nhạy với deviation
```

### PID Controller

```yaml
alb:
  pid:
    kp: 1.0       # hệ số P
    ki: 0.08      # hệ số I
    kd: 0.04      # hệ số D
    tau-d: 2.0    # hằng số thời gian low-pass filter cho D (giây)
    min-i: -0.8   # giới hạn dưới integral
    max-i: 2.5    # giới hạn trên integral
    lambda: 0.8   # biên độ tối đa của penalty
    kappa: 1.2    # độ dốc hàm tanh
```

### Polling và weight update

```yaml
alb:
  polling:
    interval: 200       # ms — tần suất thu thập metrics
  weights:
    update-interval: 5000  # ms — tần suất tính lại trọng số MCDM
```

---

## Thuật toán cân bằng tải thích nghi

### Pipeline tính điểm (mỗi 200ms)

```
rawLatency
   -> AEWMA smooth          (tau tự điều chỉnh theo deviation)
   -> normalize [0,1]       (Min-Max theo P5/P95 toàn hệ thống)
queueLength -> log-scale normalize
cpuUsage    -> clamp normalize
   -> MCDM baseScore        (alpha*nL + beta*nQ + gamma*nCpu)
   -> PID penalty           (max 0.8, setpoint = P75 hệ thống)
   -> EMA bất đối xứng      (spike: alpha=0.60, recover: alpha=0.25)
   -> MetricsCache
```

### MCDM với trọng số động (AHP + EWM)

Trọng số alpha (latency), beta (queue), gamma (CPU) được cập nhật mỗi 5 giây:

```
fusion[j] = 0.80 * EWM_weight[j] + 0.20 * AHP_weight[j]
final[j]  = EMA(fusion[j], alpha=0.08)   clamped to [min, max]
```

Giá trị AHP mặc định: alpha=0.648, beta=0.230, gamma=0.122.

Giới hạn trọng số:

| Tiêu chí | Giới hạn dưới | Giới hạn trên |
|----------|--------------|--------------|
| alpha (latency) | 0.15 | 0.70 |
| beta (queue) | 0.08 | 0.55 |
| gamma (cpu) | 0.08 | 0.35 |

### Routing score (mỗi request)

```
share[i]     = 1 / sqrt(rawMcdm[i])
routingScore = rawMcdm + relPenalty + absPenalty

relPenalty   = 0.010 * (inflight_i - inflight_min)
absPenalty   = 0.60  * ((inflight_i / expected_inflight_i) - 1)^1.3
```

Instance có inflight >= 200 bị loại hoàn toàn. Instance mới (<5 giây sau khi join Eureka) sử dụng Round-Robin trong giai đoạn warmup.

---

## API Reference

### Gateway — Business Endpoints

| Method | Path | Mô tả |
|--------|------|-------|
| GET | `/api/register` | Xử lý đăng ký đơn giản |
| POST | `/api/register-user` | Xử lý đăng ký nặng (CPU + memory + I/O) |
| GET | `/api/simulate-call` | Mô phỏng inter-service call |

### Gateway — Admin

| Method | Path | Mô tả |
|--------|------|-------|
| POST | `/actuator/alb/reset` | Reset toàn bộ state ALB trước benchmark |

Reset xóa: EWMA state, PID integral, HDR Histogram, MetricsCache, EMA score, inflight counter, warmup timer.

### Registration Service — Chaos Control

Gọi trực tiếp đến instance cần inject chaos (port 8081/8082/8083).

| Method | Path | Mô tả |
|--------|------|-------|
| POST | `/api/chaos/async-io/enable` | Bật I/O bottleneck: mỗi request sleep 600–1000ms |
| POST | `/api/chaos/async-io/disable` | Tắt I/O bottleneck |
| POST | `/api/chaos/cpu-spike/enable` | Bật background burner threads, đốt 100% CPU |
| POST | `/api/chaos/cpu-spike/disable` | Tắt CPU spike |
| POST | `/api/chaos/hidden/enable` | Bật hidden degradation (CPU cao, latency gần bình thường) |
| POST | `/api/chaos/hidden/disable` | Tắt hidden degradation |
| POST | `/api/chaos/enable` | Bật original chaos: CPU burn + sleep 100–300ms |
| POST | `/api/chaos/disable` | Tắt original chaos |
| POST | `/api/chaos/reset` | Tắt tất cả chaos, dừng background threads |

### Registration Service — Metrics

| Method | Path | Mô tả |
|--------|------|-------|
| GET | `/api/alb-metrics` | Trả về `{ cpu, count, totalTime, queue }` cho gateway thu thập |

---

## Chaos Testing

Bốn kịch bản được thiết kế để kiểm tra từng loại tín hiệu mà thuật toán cần phát hiện:

| Kịch bản | Tín hiệu | Kỳ vọng với Adaptive |
|----------|----------|----------------------|
| Async I/O | Latency tăng, CPU không tăng | EWMA + PID phát hiện trong 200–400ms |
| CPU Spike | CPU tăng, latency tăng theo | MCDM gamma tăng, traffic giảm về node chaos |
| Hidden Degradation | CPU cao, latency gần bình thường | MCDM đọc CPU signal trước khi latency tăng |
| Original Chaos | Latency + CPU tăng đồng thời | Tất cả signal kích hoạt cùng lúc |

Quy trình benchmark:

```bash
# 1. Reset state trước khi chạy
curl -X POST http://localhost:8080/actuator/alb/reset

# 2. Bắt đầu JMeter test plan

# 3. Inject chaos vào instance 8083
curl -X POST http://localhost:8083/api/chaos/async-io/enable

# 4. Sau khoảng thời gian định sẵn, tắt chaos
curl -X POST http://localhost:8083/api/chaos/async-io/disable

# 5. Thu thập kết quả từ JMeter và Grafana
```

---

## Monitoring

Tất cả instance phơi Prometheus endpoint tại `/actuator/prometheus`.

### Metrics của ALB

| Metric | Mô tả |
|--------|-------|
| `alb.latency.ewma{backend}` | EWMA latency (ms) của từng instance |
| `alb.queue.current{backend}` | Số request đang xử lý |
| `alb.final.score{backend}` | Score sau EMA (thấp = tốt) |
| `alb.routing.score{backend}` | Score đã tính thêm inflight penalty |
| `alb.routing.selected{backend,port}` | Counter số lần instance được chọn |
| `alb.mcdm.weight{criterion}` | Trọng số alpha/beta/gamma hiện tại |

### Metrics của Registration Service

| Metric | Mô tả |
|--------|-------|
| `process.cpu.usage` | CPU usage của JVM process |
| `http.server.requests.inflight` | Số request đang xử lý song song |
| `http.server.requests` | Timer tổng hợp (count + totalTime) |

---

## CI/CD

GitHub Actions chạy trên self-hosted runner. Mỗi push lên nhánh `main`:

```
push to main
  -> docker compose build
  -> docker compose up -d
```

`cancel-in-progress: true` đảm bảo chỉ có một deployment chạy tại một thời điểm.

---

## Cấu trúc dự án

```
adaptive-load-balancer-parent/
├── eureka-server/
│   └── src/main/
│       ├── java/...EurekaServerApplication.java
│       └── resources/application.yml
├── api-gateway-alb/
│   └── src/main/java/.../
│       ├── config/          AlbProperties, GatewayRoutingConfig, LoadBalancerConfiguration
│       ├── controlplane/    MetricsPoller, ScoreCalculator, DynamicWeightEngine, SlidingWindowManager
│       ├── dataplane/       AdaptiveLoadBalancer, LeastConnectionsLoadBalancer,
│       │                    InflightTracker, InflightLifecycle, PIDController
│       ├── math/            EwmaSmoother, NormalizationFunctions
│       ├── model/           ScoreBreakdown, InstanceMetrics, PidState, PidConfig, PercentileSnapshot
│       └── util/            MetricsCache
├── registration-service-alb/
│   └── src/main/java/.../
│       ├── controller/      RegistrationController, SimulateController,
│       │                    ChaosController, AlbMetricsController
│       └── metrics/         RegistrationServiceMetricsFilter
├── docs/
│   └── architecture/        overview.md, adaptive-load-balancer.md, score-calculator.md,
│                            ewma-smoother.md, pid-controller.md, sliding-window-manager.md,
│                            dynamic-weight-engine.md, metrics-poller.md
├── docker-compose.yml
└── .github/workflows/deploy.yml
```

---

## Tài nguyên tài nguyên phân bổ theo container

| Container | CPU | RAM | Vai trò |
|-----------|-----|-----|---------|
| eureka-server | 0.5 | 256 MB | Service registry |
| registration-8081 | 2.0 | 768 MB | Instance mạnh |
| registration-8082 | 1.5 | 512 MB | Instance trung bình |
| registration-8083 | 1.0 | 384 MB | Instance yếu (mục tiêu chaos) |
| api-gateway-alb | 2.0 | 1 GB | Gateway + control plane |

---

## Công nghệ sử dụng

| Thành phần | Công nghệ |
|-----------|-----------|
| Runtime | Java 21 |
| Framework | Spring Boot 3.2.4, Spring Cloud 2023.0.1 |
| Gateway | Spring Cloud Gateway |
| Service Discovery | Netflix Eureka |
| HTTP Client | WebClient (Project Reactor) |
| Histogram | HdrHistogram 2.1.12 |
| Cache | Caffeine |
| Metrics | Micrometer, Prometheus |
| Container | Docker, Docker Compose |
| CI/CD | GitHub Actions (self-hosted runner) |