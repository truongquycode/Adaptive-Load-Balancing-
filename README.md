# Adaptive Load Balancer for Spring Boot Microservices

## 1. Giới thiệu

Dự án xây dựng hệ thống microservices bằng Spring Boot để mô phỏng, triển khai và kiểm thử các chiến lược cân bằng tải trong môi trường nhiều backend có năng lực xử lý không đồng nhất.

Hệ thống gồm API Gateway, Eureka Server và ba instance backend của `registration-service-alb`. API Gateway hỗ trợ nhiều chiến lược cân bằng tải:

- `adaptive`
- `round-robin`
- `random`
- `least-connections`

Chiến lược `adaptive` sử dụng metrics thời gian gần thực gồm latency, queue, CPU, inflight request và capacity weight để tính routing cost cho từng backend. Sau đó Gateway chọn backend bằng cơ chế P2C kết hợp health score và capacity-normalized load.

---

## 2. Kiến trúc tổng quan

```text
Client / JMeter
      |
      v
API Gateway ALB :8080
      |
      | Route /api/** qua Spring Cloud LoadBalancer
      v
REGISTRATION-SERVICE-ALB
      |
      |-- registration-8081 :8081
      |-- registration-8082 :8082
      |-- registration-8083 :8083
      |
      v
Eureka Server :8761
```

Các service đăng ký vào Eureka. API Gateway lấy danh sách instance backend từ Eureka và chọn một instance cho mỗi request đi qua route `/api/**`.

Ba backend chạy cùng một mã nguồn nhưng khác tài nguyên container:

| Container | Port | CPU | Memory |
|---|---:|---:|---:|
| `registration-8081` | `8081` | `2.0` | `768m` |
| `registration-8082` | `8082` | `1.5` | `512m` |
| `registration-8083` | `8083` | `1.0` | `384m` |

---

## 3. Công nghệ sử dụng

| Nhóm | Công nghệ |
|---|---|
| Ngôn ngữ | Java 21 |
| Build tool | Maven multi-module |
| Framework | Spring Boot 3.2.4 |
| Spring Cloud | Spring Cloud 2023.0.1 |
| Gateway | Spring Cloud Gateway |
| Service Discovery | Netflix Eureka |
| Load Balancing | Spring Cloud LoadBalancer |
| Metrics | Spring Boot Actuator, Micrometer |
| Prometheus export | `micrometer-registry-prometheus` |
| Histogram | HdrHistogram |
| Cache | Caffeine |
| Container | Docker, Docker Compose |
| Monitoring | Prometheus, Grafana, cAdvisor |
| Load testing | Apache JMeter, jp@gc Throughput Shaping Timer |
| CI/CD | GitHub Actions self-hosted runner |

---

## 4. Cấu trúc thư mục

```text
Adaptive-Load-Balancing--main/
├── .github/
│   └── workflows/
│       └── deploy.yml
├── api-gateway-alb/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/truongquycode/apigatewayalb/
│       │   ├── config/
│       │   ├── controller/
│       │   ├── controlplane/
│       │   ├── dataplane/
│       │   ├── math/
│       │   ├── model/
│       │   └── util/
│       └── resources/application.yml
├── eureka-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
├── registration-service-alb/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
├── docs/
│   └── architecture/
├── jmeter/
├── monitoring/
├── scripts_run_jmeter/
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

## 5. Các module/service chính

### `eureka-server`

- Port: `8761`
- Class chính: `EurekaServerApplication`
- Annotation chính: `@EnableEurekaServer`
- Vai trò: service discovery cho Gateway và backend.

### `api-gateway-alb`

- Port: `8080`
- Class chính: `ApiGatewayAlbApplication`
- Annotation chính:
  - `@EnableDiscoveryClient`
  - `@EnableScheduling`
- Vai trò:
  - Nhận request từ client/JMeter.
  - Định tuyến `/api/**` đến `REGISTRATION-SERVICE-ALB`.
  - Chứa các thuật toán cân bằng tải.
  - Poll metrics backend và export metrics Prometheus.

Các class quan trọng:

```text
AdaptiveLoadBalancer
RoutingCostCalculator
MetricsPoller
ScoreCalculator
DynamicWeightEngine
SlidingWindowManager
PIDController
EwmaSmoother
InflightTracker
InflightLifecycle
LeastConnectionsLoadBalancer
MetricAwareLoadBalancer
```

### `registration-service-alb`

- Port mặc định: `${PORT:8081}`
- Service name: `REGISTRATION-SERVICE-ALB`
- Vai trò:
  - Cung cấp endpoint mô phỏng nghiệp vụ.
  - Cung cấp endpoint workload hỗn hợp.
  - Cung cấp chaos endpoint.
  - Cung cấp `/api/alb-metrics` cho Gateway poll.
  - Tự phát hiện CPU quota container để tính `capacityWeight`.

---

## 6. Cơ chế hoạt động chính

### 6.1. Route Gateway

Gateway định tuyến toàn bộ request `/api/**` đến backend service:

```java
.route("backend-route", r -> r.path("/api/**")
        .uri("lb://REGISTRATION-SERVICE-ALB"))
```

### 6.2. Chọn thuật toán cân bằng tải

Cấu hình nằm trong:

```text
api-gateway-alb/src/main/resources/application.yml
```

```yaml
alb:
    strategy: random
```

Các giá trị hợp lệ:

```text
adaptive
round-robin
random
least-connections
```

### 6.3. Pipeline adaptive

```text
Backend /api/alb-metrics
    |
    v
MetricsPoller
    |
    v
ScoreCalculator
    |
    | EWMA latency
    | normalize latency / queue / cpu
    | Dynamic MCDM
    | PID penalty
    v
MetricsCache
    |
    v
RoutingCostCalculator
    |
    | health cost
    | load cost
    | capacity weight
    | stale / overload penalty
    v
AdaptiveLoadBalancer
    |
    v
P2C chọn backend
```

### 6.4. Workload benchmark chính

Endpoint chính:

```text
GET /api/simulate-mixed-call?profile=...
```

Các profile:

| Profile | Tỷ lệ trong JMeter | Đặc điểm |
|---|---:|---|
| `light` | 60% | Request nhẹ, không dùng DB pool |
| `medium` | 25% | Request vừa, có dùng DB pool |
| `slow` | 12% | Request chậm hơn, giữ DB pool lâu hơn |
| `very-slow` | 3% | Request đuôi dài, tiêu tốn CPU/RAM/DB hold cao nhất |

---

## 7. API Endpoint

### Gateway

| Method | Endpoint | Mô tả |
|---|---|---|
| `POST` | `/actuator/alb/reset` | Reset toàn bộ state ALB |
| `GET` | `/actuator/prometheus` | Export metrics Prometheus |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/metrics` | Danh sách Actuator metrics |

### Backend

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/api/simulate-call` | Endpoint mô phỏng cũ |
| `GET` | `/api/simulate-mixed-call?profile=mixed` | Workload hỗn hợp |
| `GET` | `/api/simulate-mixed-call?profile=light` | Request nhẹ |
| `GET` | `/api/simulate-mixed-call?profile=medium` | Request vừa |
| `GET` | `/api/simulate-mixed-call?profile=slow` | Request chậm |
| `GET` | `/api/simulate-mixed-call?profile=very-slow` | Request rất chậm |
| `GET` | `/api/register` | Endpoint tương thích cũ |
| `POST` | `/api/register-user` | Endpoint demo đăng ký người dùng |
| `GET` | `/api/alb-metrics` | Metrics cho Gateway poll |
| `GET` | `/api/chaos/status` | Xem trạng thái chaos |
| `POST` | `/api/chaos/reset` | Tắt toàn bộ chaos |
| `POST` | `/api/chaos/dependency-slowdown/medium` | Bật dependency slowdown mức medium |
| `POST` | `/api/chaos/dependency-slowdown/high` | Bật dependency slowdown mức high |
| `POST` | `/api/chaos/dependency-slowdown/disable` | Tắt dependency slowdown |
| `POST` | `/api/chaos/latency-degradation/medium` | Bật latency degradation mức medium |
| `POST` | `/api/chaos/latency-degradation/high` | Bật latency degradation mức high |
| `POST` | `/api/chaos/latency-degradation/disable` | Tắt latency degradation |

---

## 8. Cách chạy dự án

### 8.1. Build bằng Maven

Tại thư mục gốc:

```bash
mvn clean package -DskipTests
```

### 8.2. Chạy bằng Docker Compose

```bash
docker compose build
docker compose up -d
```

Kiểm tra container:

```bash
docker compose ps
```

Các service sau cần ở trạng thái running:

```text
eureka-server
api-gateway-alb
registration-8081
registration-8082
registration-8083
```

### 8.3. Kiểm tra nhanh

Eureka Dashboard:

```text
http://<server-ip>:8761
```

Gateway:

```bash
curl http://<server-ip>:8080/api/simulate-mixed-call?profile=light
```

Reset ALB state:

```bash
curl -X POST http://<server-ip>:8080/actuator/alb/reset
```

Reset chaos trên backend:

```bash
curl -X POST http://<server-ip>:8081/api/chaos/reset
curl -X POST http://<server-ip>:8082/api/chaos/reset
curl -X POST http://<server-ip>:8083/api/chaos/reset
```

---

## 9. Monitoring

Thư mục:

```text
monitoring/
```

Chạy monitoring stack:

```bash
cd monitoring
docker compose up -d
```

Thành phần:

| Service | Port |
|---|---:|
| Prometheus | `9090` |
| Grafana | `3000` |
| cAdvisor | `8088` |

Prometheus scrape API Gateway:

```yaml
- job_name: "spring-boot"
  metrics_path: "/actuator/prometheus"
  static_configs:
    - targets: ["172.30.35.37:8080"]
```

Prometheus scrape cAdvisor:

```yaml
- job_name: 'cadvisor'
  static_configs:
    - targets: ['cadvisor:8080']
```

Grafana dashboard:

```text
monitoring/dashboard-grafana.json
```

Dashboard title:

```text
ALB — Routing Score & Score Breakdown
```

Các metric quan trọng:

```text
alb_latency_ewma
alb_queue_current
alb_final_score
alb_mcdm_weight
alb_routing_weight
alb_routing_capacity_weight
alb_routing_health_cost
alb_routing_load_raw
alb_routing_score
alb_routing_selected_total
spring_cloud_gateway_requests_seconds_bucket
container_cpu_usage_seconds_total
container_memory_working_set_bytes
```

---

## 10. Benchmark / kiểm thử tải

Thư mục JMeter:

```text
jmeter/
```

Các kịch bản chính:

| File | Mô tả |
|---|---|
| `01_low_baseline_mixed_0300_nochaos_tst.jmx` | Low load 300 RPS, không chaos |
| `02_medium_dependency_slowdown_mixed_0600_tst.jmx` | Medium 600 RPS, dependency slowdown trên backend 8083 |
| `03_high_dependency_slowdown_mixed_0900_staged_tst.jmx` | High staged 900 RPS, dependency slowdown trên backend 8083 |
| `04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst.jmx` | Stress 1200 RPS rồi recovery về 600 RPS |

Các kịch bản khảo sát R_sat:

| File | Mô tả |
|---|---|
| `00_rsat_discovery_mixed_1000plus_600_1600_tst.jmx` | Tìm vùng bão hòa 600 → 1600 RPS |
| `00b_rsat_confirm_mixed_1000plus_1000_1250_tst.jmx` | Xác nhận vùng 1000 → 1250 RPS |

Script chạy benchmark:

```text
scripts_run_jmeter/
```

| Script | Mô tả |
|---|---|
| `0-run_all_benchmark_scenarios.bat` | Chạy toàn bộ kịch bản chính |
| `1-run_low_all_strategies.bat` | Chạy low load cho 4 strategy |
| `2-run_medium_chaos_all_strategies.bat` | Chạy medium degradation cho 4 strategy |
| `3-run_high_all_strategies.bat` | Chạy high degradation cho 4 strategy |
| `4-run_stress-test_all_strategies.bat` | Chạy stress recovery cho 4 strategy |

Mỗi script chạy lần lượt:

```text
round-robin
random
least-connections
adaptive
```

Thiết lập hiện tại trong script:

```text
RUNS_PER_STRATEGY=5
WAIT_BETWEEN_RUNS=180
WAIT_AFTER_PUSH=140
```

---

## 11. CI/CD

Workflow:

```text
.github/workflows/deploy.yml
```

Tên workflow:

```text
CI/CD Auto Deploy to Ubuntu
```

Workflow chạy khi push lên branch:

```text
main
```

Runner:

```yaml
runs-on: self-hosted
```

Quy trình:

1. Checkout source code.
2. Detect changed services.
3. Nếu thay đổi file toàn hệ thống, build/deploy toàn bộ bằng:

```bash
docker compose build
docker compose up -d
```

4. Nếu chỉ thay đổi service cụ thể, build/deploy service đó bằng:

```bash
docker compose build <service>
docker compose up -d --no-deps <service>
```

5. Hiển thị trạng thái container:

```bash
docker compose ps
```

Khi benchmark đổi strategy, script cập nhật:

```text
api-gateway-alb/src/main/resources/application.yml
api-gateway-alb/.alb-strategy-deploy-marker.txt
```

Sau đó commit và push để CI/CD redeploy Gateway.

---

## 12. Ghi chú vận hành

- Dự án hiện dùng `application.yml`, không có `application.properties`.
- Dự án hiện dùng Maven, không có `build.gradle`.
- Backend không kết nối database thật; DB pool trong workload được mô phỏng bằng `Semaphore`.
- `registration-service-alb` loại trừ `DataSourceAutoConfiguration`.
- Endpoint `/actuator/alb/reset` có trong mã nguồn và được dùng để reset benchmark.
- Endpoint `/actuator/alb/strategy` được script `.bat` tham chiếu như endpoint kiểm tra tùy chọn, nhưng chưa được cài đặt trong mã nguồn hiện tại.
- Prometheus hiện scrape trực tiếp API Gateway và cAdvisor; backend metrics cho adaptive được Gateway poll qua `/api/alb-metrics`.
