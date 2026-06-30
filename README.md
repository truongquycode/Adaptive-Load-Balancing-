# Adaptive Load Balancer for Spring Boot Microservices

## 1. Giới thiệu

Dự án này xây dựng môi trường mô phỏng và kiểm thử cơ chế cân bằng tải cho hệ thống microservices sử dụng Spring Boot. Hệ thống gồm API Gateway, Eureka Service Discovery và nhiều instance backend của `registration-service-alb`.

Mục tiêu của dự án là đánh giá và so sánh các chiến lược cân bằng tải trong điều kiện các backend có năng lực xử lý không đồng nhất hoặc xuất hiện suy giảm hiệu năng cục bộ. Các chiến lược được hỗ trợ trong mã nguồn gồm:

- `adaptive`
- `round-robin`
- `random`
- `least-connections`

Hệ thống thu thập metrics từ backend, tính toán điểm trạng thái của từng instance, theo dõi số request đang xử lý và sử dụng các thông tin này để định tuyến request khi chạy chiến lược `adaptive`.

Các công nghệ chính của dự án gồm Spring Boot, Spring Cloud Gateway, Spring Cloud LoadBalancer, Netflix Eureka, Micrometer, Prometheus, Docker Compose và JMeter.

## 2. Kiến trúc tổng quan

Kiến trúc hệ thống gồm các thành phần chính sau:

```text
Client / JMeter
      |
      v
API Gateway ALB :8080
      |
      |  Route /api/** qua Spring Cloud LoadBalancer
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

Các thành phần chính:

- **Eureka Server**
  - Module: `eureka-server`
  - Port: `8761`
  - Vai trò: cung cấp Service Discovery, cho phép các service đăng ký và cho Gateway lấy danh sách instance backend.

- **API Gateway ALB**
  - Module: `api-gateway-alb`
  - Port: `8080`
  - Vai trò: nhận request từ client và định tuyến các request `/api/**` đến service `REGISTRATION-SERVICE-ALB`.
  - Chiến lược cân bằng tải được cấu hình thông qua `alb.strategy`.

- **Registration Service ALB**
  - Module: `registration-service-alb`
  - Port mặc định: `${PORT:8081}`
  - Khi chạy bằng Docker Compose có 3 instance:
    - `registration-8081`: port `8081`
    - `registration-8082`: port `8082`
    - `registration-8083`: port `8083`
  - Vai trò: xử lý API mô phỏng nghiệp vụ, mô phỏng tải, chaos và cung cấp metrics cho Gateway.

- **Adaptive Load Balancer**
  - Nằm trong module `api-gateway-alb`
  - Dựa trên các class chính:
    - `AdaptiveLoadBalancer`
    - `RoutingCostCalculator`
    - `MetricsPoller`
    - `ScoreCalculator`
    - `DynamicWeightEngine`
    - `SlidingWindowManager`
    - `PIDController`
    - `EwmaSmoother`
    - `InflightTracker`

- **Monitoring**
  - Thư mục: `monitoring`
  - Cung cấp Docker Compose riêng cho:
    - Prometheus: `9090`
    - Grafana: `3000`
    - cAdvisor: `8088`
  - Prometheus thu thập metrics từ API Gateway thông qua endpoint `/actuator/prometheus` và thu thập metrics tài nguyên container thông qua cAdvisor.
  - Grafana sử dụng dashboard `dashboard-grafana.json` với tiêu đề `ALB — Routing Score & Score Breakdown`.
  - Dashboard tập trung theo dõi pipeline đánh giá và định tuyến của Adaptive Load Balancer, gồm MCDM weights, health score, routing weights, routing cost, phân phối request, latency percentile, CPU usage và memory usage của các container chính.

- **Benchmark**
  - Thư mục: `jmeter`
  - Thư mục script chạy benchmark: `scripts_run_jmeter`
  - Bao gồm các kịch bản low load, medium degradation, high degradation, stress recovery và R_sat discovery/confirm.

## 3. Công nghệ sử dụng

Các công nghệ và thư viện chính được khai báo trong `pom.xml`, mã nguồn và các file cấu hình:

| Nhóm | Công nghệ / thư viện |
|---|---|
| Ngôn ngữ | Java 21 |
| Build tool | Maven multi-module |
| Framework chính | Spring Boot 3.2.4 |
| Spring Cloud | Spring Cloud 2023.0.1 |
| API Gateway | Spring Cloud Gateway |
| Service Discovery | Spring Cloud Netflix Eureka |
| Load Balancer | Spring Cloud LoadBalancer |
| Backend REST service | Spring Boot Starter Web |
| Reactive HTTP client | WebClient |
| Metrics | Spring Boot Actuator, Micrometer |
| Prometheus export | `micrometer-registry-prometheus` |
| Histogram | HdrHistogram 2.1.12 |
| Cache | Caffeine |
| Container | Docker, Docker Compose |
| Monitoring ngoài app | Prometheus, Grafana, cAdvisor |
| Benchmark | Apache JMeter, jp@gc Throughput Shaping Timer |
| CI/CD | GitHub Actions self-hosted runner |

Dự án sử dụng Maven và các file `application.yml`; không sử dụng `build.gradle` hoặc `application.properties`.

## 4. Cấu trúc thư mục

Cấu trúc chính của dự án:

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
│       │   ├── ApiGatewayAlbApplication.java
│       │   ├── config/
│       │   │   ├── AlbProperties.java
│       │   │   ├── GatewayRoutingConfig.java
│       │   │   ├── LoadBalancerBeanConfig.java
│       │   │   └── LoadBalancerConfiguration.java
│       │   ├── controller/
│       │   │   └── AdminController.java
│       │   ├── controlplane/
│       │   │   ├── DynamicWeightEngine.java
│       │   │   ├── MetricsPoller.java
│       │   │   └── SlidingWindowManager.java
│       │   ├── dataplane/
│       │   │   ├── AdaptiveLoadBalancer.java
│       │   │   ├── InflightLifecycle.java
│       │   │   ├── InflightTracker.java
│       │   │   ├── LeastConnectionsLoadBalancer.java
│       │   │   ├── MetricAwareLoadBalancer.java
│       │   │   ├── PIDController.java
│       │   │   ├── RoutingCostCalculator.java
│       │   │   └── ScoreCalculator.java
│       │   ├── math/
│       │   │   ├── EwmaSmoother.java
│       │   │   └── NormalizationFunctions.java
│       │   ├── model/
│       │   └── util/
│       │       └── MetricsCache.java
│       └── resources/
│           └── application.yml
├── eureka-server/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/truongquycode/eurekaserver/
│       │   └── EurekaServerApplication.java
│       └── resources/
│           └── application.yml
├── registration-service-alb/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/truongquycode/registrationservicealb/
│       │   ├── RegistrationServiceAlbApplication.java
│       │   ├── controller/
│       │   │   ├── AlbMetricsController.java
│       │   │   ├── ChaosController.java
│       │   │   ├── RegistrationController.java
│       │   │   └── SimulateController.java
│       │   └── metrics/
│       │       ├── ContainerResourceDetector.java
│       │       └── RegistrationServiceMetricsFilter.java
│       └── resources/
│           └── application.yml
├── jmeter/
│   ├── 00_rsat_discovery_mixed_1000plus_600_1600_tst.jmx
│   ├── 00b_rsat_confirm_mixed_1000plus_1000_1250_tst.jmx
│   ├── 01_low_baseline_mixed_0300_nochaos_tst.jmx
│   ├── 02_medium_dependency_slowdown_mixed_0600_tst.jmx
│   ├── 03_high_dependency_slowdown_mixed_0900_tst.jmx
│   ├── 03_high_dependency_slowdown_mixed_0900_staged_tst.jmx
│   ├── 04_stress_recovery_mixed_1200_to_0600_nochaos_tst.jmx
│   └── 04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst.jmx
├── monitoring/
│   ├── docker-compose.yml
│   ├── prometheus.yml
│   └── dashboard-grafana.json
├── scripts_run_jmeter/
│   ├── 0-run_all_benchmark_scenarios.bat
│   ├── 1-run_low_all_strategies.bat
│   ├── 2-run_medium_chaos_all_strategies.bat
│   ├── 3-run_high_all_strategies.bat
│   └── 4-run_stress-test_all_strategies.bat
├── docker-compose.yml
├── pom.xml
└── README.md
```

## 5. Các module/service chính

### 5.1. `eureka-server`

- **Vai trò:** Service Discovery.
- **Port:** `8761`.
- **Class chính:** `EurekaServerApplication`.
- **Annotation chính:** `@EnableEurekaServer`.
- **Cấu hình chính:** `eureka-server/src/main/resources/application.yml`.

Cấu hình quan trọng:

```yaml
server:
  port: 8761

eureka:
  instance:
    hostname: ${EUREKA_HOSTNAME:localhost}
  client:
    register-with-eureka: false
    fetch-registry: false
```

Service này không tự đăng ký vào Eureka và không fetch registry vì nó đóng vai trò server.

### 5.2. `api-gateway-alb`

- **Vai trò:** API Gateway, nơi nhận request từ client và định tuyến đến backend.
- **Port:** `8080`.
- **Class chính:** `ApiGatewayAlbApplication`.
- **Annotation chính:**
  - `@SpringBootApplication`
  - `@EnableDiscoveryClient`
  - `@EnableScheduling`
- **Route chính:** `/api/**` → `lb://REGISTRATION-SERVICE-ALB`.

Class quan trọng:

| Class | Vai trò |
|---|---|
| `GatewayRoutingConfig` | Khai báo route `/api/**` đến `REGISTRATION-SERVICE-ALB` |
| `LoadBalancerConfiguration` | Gắn cấu hình load balancer cho service `REGISTRATION-SERVICE-ALB` |
| `LoadBalancerBeanConfig` | Chọn bean load balancer theo `alb.strategy` |
| `AdaptiveLoadBalancer` | Thuật toán adaptive, chọn backend theo routing cost và P2C |
| `RoutingCostCalculator` | Tính chi phí định tuyến cuối cùng dựa trên health score, inflight, capacity và stale penalty |
| `MetricsPoller` | Định kỳ gọi `/api/alb-metrics` của từng backend |
| `ScoreCalculator` | Tính final score từ latency, queue, CPU, MCDM và PID |
| `DynamicWeightEngine` | Tính trọng số MCDM động bằng EWM kết hợp AHP |
| `SlidingWindowManager` | Lưu histogram latency/queue để lấy percentile |
| `PIDController` | Tính điểm bất lợi khi instance chậm hơn ngưỡng hệ thống |
| `EwmaSmoother` | Làm mượt latency bằng adaptive EWMA |
| `InflightTracker` | Theo dõi số request đang xử lý trên từng instance |
| `InflightLifecycle` | Tăng/giảm inflight theo vòng đời request của LoadBalancer |
| `AdminController` | Cung cấp endpoint reset state ALB |

Chiến lược cân bằng tải được chọn trong:

```text
api-gateway-alb/src/main/resources/application.yml
```

Giá trị đang được cấu hình trong `application.yml`:

```yaml
alb:
  strategy: random
```

Các giá trị cấu hình được hỗ trợ:

```text
adaptive
round-robin
random
least-connections
```

Lưu ý: `AlbProperties.java` đặt mặc định là `adaptive`, nhưng `application.yml` đang ghi đè bằng giá trị `random`.

### 5.3. `registration-service-alb`

- **Vai trò:** Backend service nhận request từ Gateway.
- **Service name:** `REGISTRATION-SERVICE-ALB`.
- **Port mặc định:** `${PORT:8081}`.
- **Khi chạy Docker Compose:** tạo 3 instance ở các port `8081`, `8082`, `8083`.
- **Class chính:** `RegistrationServiceAlbApplication`.
- **Annotation chính:**
  - `@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})`
  - `@EnableDiscoveryClient`

Controller chính:

| Controller | Vai trò |
|---|---|
| `RegistrationController` | API đăng ký demo và API tương thích cũ `/api/register` |
| `SimulateController` | API mô phỏng request nhẹ/vừa/chậm/rất chậm |
| `ChaosController` | API bật/tắt các chế độ chaos |
| `AlbMetricsController` | API `/api/alb-metrics` cho Gateway thu thập metrics |

Class metrics:

| Class | Vai trò |
|---|---|
| `RegistrationServiceMetricsFilter` | Đếm số request nghiệp vụ đang xử lý song song bằng gauge `http.server.requests.inflight` |
| `ContainerResourceDetector` | Đọc CPU quota từ cgroup v1/v2 để xác định `capacityWeight` |

## 6. Cơ chế hoạt động chính

### 6.1. Luồng request chính

Luồng xử lý request nghiệp vụ:

```text
Client / JMeter
    |
    v
API Gateway :8080
    |
    | route /api/**
    v
Spring Cloud LoadBalancer
    |
    | chọn instance theo alb.strategy
    v
registration-8081 / registration-8082 / registration-8083
    |
    v
Controller trong registration-service-alb
```

Tất cả request có path `/api/**` đi vào Gateway sẽ được route đến service `REGISTRATION-SERVICE-ALB` thông qua URI:

```text
lb://REGISTRATION-SERVICE-ALB
```

Danh sách instance backend được lấy từ Eureka.

### 6.2. Các chiến lược cân bằng tải

Trong `LoadBalancerBeanConfig`, hệ thống chọn thuật toán dựa trên cấu hình `alb.strategy`:

| Giá trị `alb.strategy` | Thuật toán được dùng |
|---|---|
| `adaptive` | `AdaptiveLoadBalancer` |
| `round-robin` | `RoundRobinLoadBalancer` được bọc bởi `MetricAwareLoadBalancer` |
| `random` | `RandomLoadBalancer` được bọc bởi `MetricAwareLoadBalancer` |
| `least-connections` | `LeastConnectionsLoadBalancer` |

`MetricAwareLoadBalancer` dùng để tăng counter `alb.routing.selected` khi instance được chọn.

`LeastConnectionsLoadBalancer` chọn instance có số inflight thấp nhất theo `InflightTracker`. Nếu nhiều instance có cùng số inflight thấp nhất thì chọn ngẫu nhiên trong nhóm đó.

### 6.3. Cơ chế thu thập metrics

Gateway có `MetricsPoller` chạy định kỳ theo cấu hình:

```yaml
alb:
  polling:
    interval: 200
```

Mỗi chu kỳ, `MetricsPoller`:

1. Lấy danh sách instance `REGISTRATION-SERVICE-ALB` từ Eureka.
2. Gọi endpoint `/api/alb-metrics` trên từng instance.
3. Đọc các chỉ số:
   - `cpu`
   - `rawCpu`
   - `count`
   - `totalTime`
   - `queue`
   - `capacityWeight`
4. Tính latency trung bình theo delta của `count` và `totalTime`.
5. Cập nhật histogram trong `SlidingWindowManager`.
6. Gọi `ScoreCalculator` để tính score.
7. Làm mượt score bằng EMA bất đối xứng.
8. Lưu kết quả vào `MetricsCache`.
9. Đăng ký/cập nhật các Prometheus Gauge liên quan đến ALB.

Nếu poll metrics thất bại, `MetricsPoller` tăng điểm bất lợi cho instance đó để Adaptive giảm khả năng định tuyến đến instance không lấy được metrics.

### 6.4. Pipeline tính điểm trạng thái backend

Trong `ScoreCalculator`, điểm trạng thái được tính theo luồng sau:

```text
Raw latency
    |
    v
Adaptive EWMA latency
    |
    v
Normalize latency theo system P5/P95
    |
    +-- Normalize queue bằng log-scale
    |
    +-- Normalize CPU về [0, 1]
    |
    v
MCDM base score = alpha * latency + beta * queue + gamma * cpu
    |
    v
PID penalty
    |
    v
finalScore = baseScore + pidPenalty
```

Các thành phần chính:

- **Latency:** được làm mượt bằng `EwmaSmoother`.
- **Queue:** dùng số request đang xử lý hoặc đang chờ trên backend.
- **CPU:** được chuẩn hóa theo CPU capacity/quota của container.
- **MCDM weights:** do `DynamicWeightEngine` cập nhật.
- **PID penalty:** tăng khi instance chậm hơn setpoint dựa trên percentile của toàn hệ thống.
- **Score càng thấp thể hiện instance càng phù hợp để nhận request.**

### 6.5. MCDM weights

`DynamicWeightEngine` cập nhật trọng số theo lịch:

```yaml
alb:
  weights:
    update-interval: 5000
```

Theo phần cài đặt hiện tại:

- AHP prior:

```text
latency = 0.648
queue   = 0.230
cpu     = 0.122
```

- Khi cụm ổn định, hệ thống quay về AHP prior để tránh khuếch đại nhiễu nhỏ.
- Khi có đủ dữ liệu, trọng số mới được tính bằng EWM rồi blend với AHP:

```text
target[j] = 0.70 * EWM[j] + 0.30 * AHP[j]
```

Sau đó trọng số được làm mượt bằng EMA và clamp:

| Tiêu chí | Min | Max |
|---|---:|---:|
| Latency alpha | 0.15 | 0.70 |
| Queue beta | 0.08 | 0.45 |
| CPU gamma | 0.08 | 0.35 |

### 6.6. Cơ chế định tuyến của Adaptive

Khi `alb.strategy=adaptive`, `AdaptiveLoadBalancer` sử dụng `RoutingCostCalculator`.

Luồng định tuyến:

```text
Request đến Gateway
    |
    v
Lấy danh sách instance từ Eureka
    |
    v
RoutingCostCalculator tính cost cho từng instance
    |
    v
Nếu warmup hoặc low-load ổn định -> Round Robin
    |
    v
Nếu không -> chọn ứng viên bằng P2C
    |
    v
Route request đến instance có finalCost tốt hơn
```

Các yếu tố trong routing cost:

- `healthRaw`: final score từ control-plane.
- `loadRaw`: inflight thực tế chia cho expected inflight theo capacity.
- `capacityWeight`: năng lực tương đối của instance, lấy từ CPU quota container.
- `stalePenalty`: điểm bất lợi khi metrics cũ.
- `overloadPenalty`: điểm bất lợi khi loadRaw cao.
- `capPressurePenalty`: điểm bất lợi khi inflight tiến gần hard cap.
- `absoluteHealthPenalty`: điểm bất lợi khi health score tuyệt đối cao.
- `hardExcluded`: loại instance khỏi nhóm ứng viên trong một số trường hợp như không có metrics, metrics quá cũ, score quá xấu hoặc inflight vượt ngưỡng.

Adaptive có các mode quyết định như:

- `WARMUP_RR`
- `LOW_LOAD_RR`
- `NORMAL_P2C`
- `HEALTH_DOMINANT`
- `LOAD_DOMINANT`
- `ALL_HARD_EXCLUDED_FALLBACK`
- `PROBE_RECOVERY`

### 6.7. Cơ chế mô phỏng tải trong backend

`SimulateController` có hai endpoint mô phỏng chính:

- `/api/simulate-call`
- `/api/simulate-mixed-call`

`/api/simulate-call` là endpoint mô phỏng cũ hơn, có baseline, heavy request và async I/O.

`/api/simulate-mixed-call` mô phỏng workload thực tế hơn, gồm nhiều loại request:

| Profile | Ý nghĩa | Đặc điểm chính |
|---|---|---|
| `light` | Request nhẹ | Ít CPU/RAM, không dùng DB pool |
| `medium` | Request vừa | Có CPU/RAM, dùng DB pool ngắn |
| `slow` | Request chậm | CPU/RAM cao hơn, giữ DB pool lâu hơn |
| `very-slow` | Request rất chậm | Đuôi dài, CPU/RAM và DB hold cao nhất |
| `mixed` | Trộn tự động | 60% light, 25% medium, 12% slow, 3% very-slow |

Dự án không kết nối database thật; DB pool được mô phỏng bằng `Semaphore` trong `SimulateController`.

## 7. API Endpoint

### 7.1. Gateway route

| Method | Path | Chức năng | Module |
|---|---|---|---|
| Bất kỳ method phù hợp với request | `/api/**` | Route request đến `lb://REGISTRATION-SERVICE-ALB` | `api-gateway-alb` |

Route này được khai báo trong `GatewayRoutingConfig`.

### 7.2. Gateway admin endpoint

| Method | Path | Chức năng | Module |
|---|---|---|---|
| POST | `/actuator/alb/reset` | Reset toàn bộ state ALB trước khi benchmark | `api-gateway-alb` |

Endpoint `/actuator/alb/reset` reset các trạng thái:

- `InflightTracker`
- `AdaptiveLoadBalancer`
- `InflightLifecycle`
- `PIDController`
- `EwmaSmoother`
- `SlidingWindowManager`
- `MetricsPoller`
- `DynamicWeightEngine`
- `MetricsCache`

### 7.3. Registration service - nghiệp vụ và mô phỏng tải

Các endpoint này có thể gọi trực tiếp vào backend hoặc thông qua Gateway nếu path bắt đầu bằng `/api/**`.

| Method | Path | Chức năng | Module |
|---|---|---|---|
| GET | `/api/register` | Endpoint đăng ký demo dạng cũ, trả text gồm port, request id, mode và elapsed time | `registration-service-alb` |
| POST | `/api/register-user` | Endpoint demo đăng ký người dùng, nhận JSON body và mô phỏng CPU, allocation/GC, I/O | `registration-service-alb` |
| GET | `/api/simulate-call` | Mô phỏng inter-service call baseline/heavy/async I/O | `registration-service-alb` |
| GET | `/api/simulate-mixed-call?profile=light` | Mô phỏng request nhẹ | `registration-service-alb` |
| GET | `/api/simulate-mixed-call?profile=medium` | Mô phỏng request vừa | `registration-service-alb` |
| GET | `/api/simulate-mixed-call?profile=slow` | Mô phỏng request chậm | `registration-service-alb` |
| GET | `/api/simulate-mixed-call?profile=very-slow` | Mô phỏng request rất chậm | `registration-service-alb` |
| GET | `/api/simulate-mixed-call?profile=mixed` | Mô phỏng workload trộn 60/25/12/3 | `registration-service-alb` |

Nếu không truyền `profile`, `/api/simulate-mixed-call` dùng mặc định `mixed`.

### 7.4. Registration service - metrics cho ALB

| Method | Path | Chức năng | Module |
|---|---|---|---|
| GET | `/api/alb-metrics` | Trả metrics để Gateway tính score và routing cost | `registration-service-alb` |

Response của `/api/alb-metrics` gồm các trường:

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

| Trường | Ý nghĩa |
|---|---|
| `cpu` | CPU đã chuẩn hóa theo capacity/quota |
| `rawCpu` | CPU thô từ Micrometer |
| `count` | Tổng số request được ghi nhận bởi `http.server.requests` |
| `totalTime` | Tổng thời gian xử lý request, đơn vị giây |
| `queue` | Số request nghiệp vụ đang xử lý song song |
| `capacityWeight` | Số core CPU được phát hiện từ cgroup hoặc fallback từ JVM |

### 7.5. Registration service - chaos endpoint

Các endpoint chaos nằm trong `ChaosController`.

| Method | Path | Chức năng | Module |
|---|---|---|---|
| POST | `/api/chaos/heavy/enable` | Bật heavy request mode cho endpoint tương thích cũ | `registration-service-alb` |
| POST | `/api/chaos/enable` | Alias của `/api/chaos/heavy/enable` | `registration-service-alb` |
| POST | `/api/chaos/heavy/disable` | Tắt heavy request mode | `registration-service-alb` |
| POST | `/api/chaos/disable` | Alias của `/api/chaos/heavy/disable` | `registration-service-alb` |
| POST | `/api/chaos/cpu-spike/enable` | Bật background CPU burner threads | `registration-service-alb` |
| POST | `/api/chaos/cpu-spike/disable` | Tắt CPU spike | `registration-service-alb` |
| POST | `/api/chaos/async-io/enable` | Bật async I/O degradation cho `/api/simulate-call` | `registration-service-alb` |
| POST | `/api/chaos/async-io/disable` | Tắt async I/O degradation | `registration-service-alb` |
| POST | `/api/chaos/hidden/enable` | Bật hidden CPU degradation | `registration-service-alb` |
| POST | `/api/chaos/hidden/disable` | Tắt hidden CPU degradation | `registration-service-alb` |
| POST | `/api/chaos/latency-degradation/enable?minMs=250&maxMs=500&probability=50` | Bật latency degradation tùy chỉnh | `registration-service-alb` |
| POST | `/api/chaos/latency-degradation/medium` | Bật latency degradation mức medium: 250-500ms, 50% request | `registration-service-alb` |
| POST | `/api/chaos/latency-degradation/high` | Bật latency degradation mức high: 500-900ms, 60% request | `registration-service-alb` |
| POST | `/api/chaos/latency-degradation/disable` | Tắt latency degradation | `registration-service-alb` |
| POST | `/api/chaos/dependency-slowdown/enable?extraMinMs=200&extraMaxMs=400&probability=100` | Bật dependency slowdown tùy chỉnh | `registration-service-alb` |
| POST | `/api/chaos/dependency-slowdown/medium` | Bật dependency slowdown medium: cộng 200-400ms vào DB hold | `registration-service-alb` |
| POST | `/api/chaos/dependency-slowdown/high` | Bật dependency slowdown high: cộng 400-800ms vào DB hold | `registration-service-alb` |
| POST | `/api/chaos/dependency-slowdown/disable` | Tắt dependency slowdown | `registration-service-alb` |
| POST | `/api/chaos/reset` | Tắt toàn bộ chaos và dừng background threads | `registration-service-alb` |
| GET | `/api/chaos/status` | Xem trạng thái chaos hiện tại | `registration-service-alb` |

### 7.6. Actuator endpoint

Các actuator endpoint được expose theo `application.yml`:

| Module | Endpoint được expose |
|---|---|
| `api-gateway-alb` | `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics` |
| `registration-service-alb` | `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics` |

`eureka-server` không cấu hình expose actuator riêng trong `application.yml`; tuy nhiên Docker healthcheck có gọi `/actuator/health`.

## 8. Cách chạy dự án

### 8.1. Yêu cầu môi trường

Môi trường cần chuẩn bị:

- Java 21
- Maven 3.9+
- Docker
- Docker Compose v2
- Benchmark:
  - Apache JMeter 5.6.3
  - Plugin jp@gc Throughput Shaping Timer
- CI/CD:
  - GitHub self-hosted runner trên server

### 8.2. Build toàn bộ project bằng Maven

Tại thư mục gốc:

```bash
mvn clean package -DskipTests
```

Dự án là Maven multi-module, gồm:

```text
eureka-server
api-gateway-alb
registration-service-alb
```

### 8.3. Chạy bằng Docker Compose

Tại thư mục gốc:

```bash
docker compose build
docker compose up -d
```

Kiểm tra container:

```bash
docker compose ps
```

Các container chính theo `docker-compose.yml`:

| Container | Port | CPU | RAM |
|---|---:|---:|---:|
| `eureka-server` | `8761` | `0.5` | `256m` |
| `registration-8081` | `8081` | `2.0` | `768m` |
| `registration-8082` | `8082` | `1.5` | `512m` |
| `registration-8083` | `8083` | `1.0` | `384m` |
| `api-gateway-alb` | `8080` | `2.0` | `1g` |

Dừng hệ thống:

```bash
docker compose down
```

Xem log Gateway:

```bash
docker compose logs -f api-gateway-alb
```

Xem log một backend:

```bash
docker compose logs -f registration-8081
```

### 8.4. Chạy thủ công từng service

Build trước:

```bash
mvn clean package -DskipTests
```

Chạy Eureka Server:

```bash
java -jar eureka-server/target/*.jar
```

Chạy Registration Service instance 8081:

```bash
PORT=8081 EUREKA_URL=http://localhost:8761/eureka/ java -jar registration-service-alb/target/*.jar
```

Chạy Registration Service instance 8082:

```bash
PORT=8082 EUREKA_URL=http://localhost:8761/eureka/ java -jar registration-service-alb/target/*.jar
```

Chạy Registration Service instance 8083:

```bash
PORT=8083 EUREKA_URL=http://localhost:8761/eureka/ java -jar registration-service-alb/target/*.jar
```

Chạy API Gateway:

```bash
EUREKA_URL=http://localhost:8761/eureka/ java -jar api-gateway-alb/target/*.jar
```

Lưu ý: với `registration-service-alb`, nên set biến môi trường `PORT` thay vì chỉ dùng `--server.port`, vì `application.yml` dùng `${PORT:8081}` cho cả `server.port` và `eureka.instance.instance-id`.

### 8.5. Lệnh kiểm tra nhanh

Kiểm tra Eureka:

```bash
curl http://localhost:8761
```

Kiểm tra Gateway route đến backend:

```bash
curl http://localhost:8080/api/simulate-call
```

Kiểm tra mixed workload:

```bash
curl "http://localhost:8080/api/simulate-mixed-call?profile=light"
curl "http://localhost:8080/api/simulate-mixed-call?profile=mixed"
```

Kiểm tra metrics của Gateway:

```bash
curl http://localhost:8080/actuator/prometheus
```

Kiểm tra metrics trực tiếp từ backend:

```bash
curl http://localhost:8081/api/alb-metrics
```

Reset state ALB trước benchmark:

```bash
curl -X POST http://localhost:8080/actuator/alb/reset
```

Reset chaos trên một backend:

```bash
curl -X POST http://localhost:8083/api/chaos/reset
```

Bật dependency slowdown medium trên backend 8083:

```bash
curl -X POST http://localhost:8083/api/chaos/dependency-slowdown/medium
```

Tắt toàn bộ chaos trên backend 8083:

```bash
curl -X POST http://localhost:8083/api/chaos/reset
```

### 8.6. Chạy monitoring

Monitoring được cấu hình trong thư mục `monitoring`, gồm Prometheus, Grafana và cAdvisor.

Chạy monitoring:

```bash
cd monitoring
docker compose up -d
```

Các service monitoring:

| Service | Port | Vai trò |
|---|---:|---|
| Prometheus | `9090` | Thu thập và lưu trữ metrics |
| Grafana | `3000` | Hiển thị dashboard giám sát |
| cAdvisor | `8088` | Thu thập metrics CPU/RAM của container |

Prometheus hiện scrape Spring Boot Gateway tại:

```text
172.30.35.37:8080/actuator/prometheus
```

Nếu chạy dự án trên máy hoặc server khác, cần cập nhật lại địa chỉ IP trong file:

```text
monitoring/prometheus.yml
```

Dashboard Grafana được lưu tại:

```text
monitoring/dashboard-grafana.json
```

Dashboard có tiêu đề:

```text
ALB — Routing Score & Score Breakdown
```

Dashboard này dùng để theo dõi pipeline điểm số và quyết định định tuyến của Adaptive Load Balancer, bao gồm health score, MCDM weights, routing weights, routing cost, phân phối request, latency percentile và tài nguyên container.

Sau khi Grafana chạy, có thể import dashboard bằng cách mở Grafana tại:

```text
http://localhost:3000
```

Sau đó import file:

```text
monitoring/dashboard-grafana.json
```

Khi import dashboard, cần chọn datasource Prometheus tương ứng với Prometheus đang chạy trong monitoring stack.

### 8.7. CI/CD

File workflow:

```text
.github/workflows/deploy.yml
```

Workflow chạy khi push lên branch `main` và dùng self-hosted runner.

Logic chính:

- Checkout source code.
- Detect file thay đổi.
- Nếu thay đổi file ảnh hưởng toàn hệ thống như `docker-compose.yml`, `pom.xml`, `.github/workflows/`, `mvnw`, `.mvn/` thì build/deploy toàn bộ.
- Nếu chỉ thay đổi một module thì build/deploy service tương ứng.
- Nếu thay đổi `registration-service-alb`, workflow deploy cả 3 container:
  - `registration-8081`
  - `registration-8082`
  - `registration-8083`

Lệnh deploy trong workflow:

```bash
docker compose build
docker compose up -d
```

Hoặc khi chỉ deploy service thay đổi:

```bash
docker compose build $SERVICES
docker compose up -d --no-deps $SERVICES
```

## 9. Cấu hình quan trọng

### 9.1. Root `pom.xml`

Root project là Maven parent project:

```xml
<packaging>pom</packaging>
```

Các module:

```xml
<modules>
    <module>eureka-server</module>
    <module>api-gateway-alb</module>
    <module>registration-service-alb</module>
</modules>
```

Version chính:

```xml
<spring-boot.version>3.2.4</spring-boot.version>
<java.version>21</java.version>
<spring-cloud.version>2023.0.1</spring-cloud.version>
```

### 9.2. Cấu hình Gateway

File:

```text
api-gateway-alb/src/main/resources/application.yml
```

Port:

```yaml
server:
  port: 8080
```

Service name:

```yaml
spring:
  application:
    name: api-gateway-alb
```

Gateway HTTP client:

```yaml
spring:
  cloud:
    gateway:
      httpclient:
        connect-timeout: 2000
        response-timeout: 15s
        pool:
          type: elastic
          max-connections: 1000
```

LoadBalancer cache:

```yaml
spring:
  cloud:
    loadbalancer:
      cache:
        ttl: 5s
```

Chiến lược ALB hiện tại:

```yaml
alb:
  strategy: random
```

Các giá trị có thể dùng:

```text
adaptive
round-robin
random
least-connections
```

Polling metrics:

```yaml
alb:
  polling:
    interval: 200
```

EWMA:

```yaml
alb:
  ewma:
    tau-min: 200.0
    tau-max: 2000.0
    k: 3.0
```

PID:

```yaml
alb:
  pid:
    kp: 1.0
    ki: 0.08
    kd: 0.04
    tau-d: 2.0
    min-i: -0.8
    max-i: 2.5
    lambda: 0.8
    kappa: 1.2
```

Routing:

```yaml
alb:
  routing:
    warmup-ms: 5000
    min-expected-inflight: 3.0
    low-load-inflight: 20
    low-load-health-spread: 0.12
    low-load-load-spread: 0.25
    min-health-weight: 0.25
    max-health-weight: 0.75
    stale-penalty-weight: 0.15
    stale-soft-ms: 1500
    stale-hard-ms: 5000
    unhealthy-score-cutoff: 2.0
    hard-inflight-cap: 220
    probe-interval-ms: 3000
    probe-probability: 0.005
```

Actuator/Prometheus:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

Eureka client:

```yaml
eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
    registry-fetch-interval-seconds: 5
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
```

### 9.3. Cấu hình Registration Service

File:

```text
registration-service-alb/src/main/resources/application.yml
```

Port:

```yaml
server:
  port: ${PORT:8081}
```

Tomcat thread pool:

```yaml
server:
  tomcat:
    threads:
      max: 500
      min-spare: 50
    accept-count: 200
```

Service name:

```yaml
spring:
  application:
    name: REGISTRATION-SERVICE-ALB
```

Eureka:

```yaml
eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
  instance:
    instance-id: ${spring.application.name}:${PORT:8081}
    prefer-ip-address: true
```

Actuator/Prometheus:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "health,info,prometheus,metrics"
```

Một số metrics JVM và Tomcat bị tắt:

```yaml
management:
  metrics:
    enable:
      jvm: false
      tomcat: false
```

### 9.4. Cấu hình Eureka Server

File:

```text
eureka-server/src/main/resources/application.yml
```

Port:

```yaml
server:
  port: 8761
```

Hostname có thể truyền bằng biến môi trường:

```yaml
eureka:
  instance:
    hostname: ${EUREKA_HOSTNAME:localhost}
```

### 9.5. Cấu hình Docker Compose

File:

```text
docker-compose.yml
```

Network:

```yaml
networks:
  alb-network:
    driver: bridge
```

Các container chính:

| Container | Vai trò | Port | CPU | RAM |
|---|---|---:|---:|---:|
| `eureka-server` | Service Discovery | `8761` | `0.5` | `256m` |
| `registration-8081` | Backend instance mạnh | `8081` | `2.0` | `768m` |
| `registration-8082` | Backend instance trung bình | `8082` | `1.5` | `512m` |
| `registration-8083` | Backend instance yếu | `8083` | `1.0` | `384m` |
| `api-gateway-alb` | Gateway + ALB | `8080` | `2.0` | `1g` |

Các backend dùng cùng source code `registration-service-alb`, khác nhau qua biến môi trường `PORT`.

### 9.6. Metrics Prometheus do ALB tạo

Các metric ALB được đăng ký trong ứng dụng:

| Metric | Ý nghĩa |
|---|---|
| `alb.latency.ewma` | EWMA latency theo backend |
| `alb.queue.current` | Queue/inflight hiện tại theo backend |
| `alb.final.score` | Final score sau EMA theo backend |
| `alb.routing.score` | Final routing cost theo backend |
| `alb.routing.health.cost` | Health cost theo backend |
| `alb.routing.load.cost` | Load cost đã chuẩn hóa theo backend |
| `alb.routing.load.raw` | Load ratio thô theo backend |
| `alb.routing.capacity.weight` | Capacity weight theo backend |
| `alb.routing.weight` | Trọng số health/load trong routing |
| `alb.routing.selected` | Counter số lần backend được chọn |
| `alb.mcdm.weight` | Trọng số MCDM theo criterion `latency`, `queue`, `cpu` |

### 9.7. Cấu hình monitoring

Monitoring stack nằm trong thư mục:

```text
monitoring/
```

Các file chính:

| File | Vai trò |
|---|---|
| `docker-compose.yml` | Khởi chạy Prometheus, Grafana và cAdvisor |
| `prometheus.yml` | Cấu hình scrape metrics cho Prometheus |
| `dashboard-grafana.json` | Dashboard Grafana theo dõi ALB routing score và tài nguyên hệ thống |

Service trong `monitoring/docker-compose.yml`:

| Service | Port | Chức năng |
|---|---:|---|
| `prometheus` | `9090` | Thu thập metrics từ Spring Boot Actuator và cAdvisor |
| `grafana` | `3000` | Hiển thị dashboard giám sát |
| `cadvisor` | `8088` | Theo dõi CPU, memory và thông tin container |

File cấu hình Prometheus:

```text
monitoring/prometheus.yml
```

Scrape config hiện tại:

```yaml
scrape_configs:
  - job_name: "spring-boot"
    metrics_path: "/actuator/prometheus"
    static_configs:
      - targets: ["172.30.35.37:8080"]

  - job_name: 'cadvisor'
    static_configs:
      - targets: ['cadvisor:8080']
```

Trong cấu hình hiện tại, Prometheus scrape metrics Spring Boot từ API Gateway tại địa chỉ `172.30.35.37:8080`. Nếu triển khai trên môi trường khác, cần thay địa chỉ này bằng IP hoặc hostname phù hợp.

Grafana dashboard được lưu tại:

```text
monitoring/dashboard-grafana.json
```

Dashboard có tên:

```text
ALB — Routing Score & Score Breakdown
```

Dashboard này được thiết kế để theo dõi toàn bộ quá trình đánh giá backend và ra quyết định định tuyến của Adaptive Load Balancer.

Các nhóm panel chính trong dashboard:

| Nhóm panel | Metric chính | Ý nghĩa |
|---|---|---|
| Backend health score | `alb_final_score` | Theo dõi điểm sức khỏe cuối cùng của từng backend sau khi tổng hợp latency, queue, CPU, MCDM và PID |
| Dynamic MCDM weights | `alb_mcdm_weight` | Theo dõi trọng số động của các tiêu chí latency, queue và CPU |
| Adaptive routing weights | `alb_routing_weight` | Theo dõi tỷ trọng giữa health cost và load cost trong quyết định routing |
| Backend capacity weight | `alb_routing_capacity_weight` | Hiển thị năng lực tương đối của từng backend dựa trên CPU quota container |
| Health cost | `alb_routing_health_cost` | Phản ánh chi phí sức khỏe của backend sau khi chuẩn hóa từ final score |
| Capacity-normalized load ratio | `alb_routing_load_raw` | So sánh lượng request đang xử lý với tải kỳ vọng theo năng lực của từng backend |
| Final routing cost | `alb_routing_score` | Chi phí định tuyến cuối cùng; giá trị càng thấp thì backend càng có khả năng được chọn |
| Routing selection rate by backend | `rate(alb_routing_selected_total[10s])` | Theo dõi số request mỗi giây được chọn vào từng backend |
| Routing selection rate by decision reason | `rate(alb_routing_selected_total[10s])` theo `reason` | Cho biết Adaptive đang route theo warmup, low-load, health-dominant, load-dominant, normal P2C hoặc probe recovery |
| EWMA latency | `alb_latency_ewma` | Theo dõi độ trễ đã được làm mượt của từng backend |
| Queue depth | `alb_queue_current` | Theo dõi số request đang xử lý hoặc đang chờ trên từng backend |
| Gateway latency percentiles | `spring_cloud_gateway_requests_seconds_bucket` | Tính P50, P90, P95 và P99 của request đi qua API Gateway |
| Gateway P99 latency | `histogram_quantile(0.99, ...)` | Theo dõi riêng độ trễ P99 của route backend để đánh giá tail latency |
| Gateway error rate | `spring_cloud_gateway_requests_seconds_count` | Tính tỷ lệ request non-2xx của route backend |
| CPU usage by container | `container_cpu_usage_seconds_total`, `container_spec_cpu_quota`, `container_spec_cpu_period` | Theo dõi CPU usage đã chuẩn hóa theo CPU quota của container |
| Memory usage by container | `container_memory_working_set_bytes`, `container_spec_memory_limit_bytes` | Theo dõi memory usage theo phần trăm giới hạn RAM của container |

Dashboard này giúp kiểm chứng ba nhóm thông tin quan trọng:

1. **Backend có đang bị suy giảm hiệu năng hay không** thông qua health score, EWMA latency, queue depth, CPU và memory.
2. **Adaptive Load Balancer phản ứng như thế nào** thông qua routing weights, health/load cost, final routing cost và decision reason.
3. **Traffic có được phân phối hợp lý hay không** thông qua routing selection rate theo backend và latency percentile tại Gateway.

Lưu ý: dashboard sử dụng cả metrics từ Gateway và metrics từ cAdvisor. Vì vậy, để dashboard hiển thị đầy đủ, cần đảm bảo Prometheus scrape được cả `/actuator/prometheus` của Gateway và cAdvisor.

## 10. Kiểm thử hoặc benchmark nếu có

Dự án cung cấp các kịch bản JMeter trong thư mục `jmeter` và script `.bat` trong `scripts_run_jmeter` để chạy benchmark cho nhiều chiến lược cân bằng tải.

### 10.1. Endpoint benchmark chính

Các file JMeter sử dụng endpoint:

```text
/api/simulate-mixed-call
```

với các profile:

```text
profile=light
profile=medium
profile=slow
profile=very-slow
```

Workload được phân bổ theo tỷ lệ:

| Profile | Tỷ lệ |
|---|---:|
| `light` | 60% |
| `medium` | 25% |
| `slow` | 12% |
| `very-slow` | 3% |

### 10.2. Các kịch bản JMeter trong thư mục `jmeter`

| File | Mục đích | Tải / đặc điểm chính |
|---|---|---|
| `00_rsat_discovery_mixed_1000plus_600_1600_tst.jmx` | R_sat discovery | Mixed workload, tăng từ 600 đến 1600 RPS, không chaos |
| `00b_rsat_confirm_mixed_1000plus_1000_1250_tst.jmx` | R_sat confirm | Mixed workload, kiểm tra vùng 1000 đến 1250 RPS |
| `01_low_baseline_mixed_0300_nochaos_tst.jmx` | Low baseline | 300 RPS, không chaos |
| `02_medium_dependency_slowdown_mixed_0600_tst.jmx` | Medium degradation | 600 RPS, dependency slowdown trên một backend |
| `03_high_dependency_slowdown_mixed_0900_tst.jmx` | High degradation | 900 RPS, dependency slowdown trên một backend |
| `03_high_dependency_slowdown_mixed_0900_staged_tst.jmx` | High degradation staged ramp | Ramp 0→300→600→900 RPS, dependency slowdown |
| `04_stress_recovery_mixed_1200_to_0600_nochaos_tst.jmx` | Stress recovery | 1200 RPS rồi giảm về 600 RPS, không extra chaos |
| `04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst.jmx` | Stress recovery staged | Ramp 0→600→900→1200 RPS rồi giảm về 600 RPS |

### 10.3. Một số thông số benchmark chính

| Kịch bản | Thread Group | Duration | TST profile chính |
|---|---:|---:|---|
| Low baseline | 800 threads | 390s | 0→300 RPS 60s, giữ 300 RPS 270s, giảm 30s |
| Medium degradation | 1400 threads | 480s | 0→600 RPS 60s, giữ 600 RPS 360s, giảm 30s |
| High degradation | 2200 threads | 480s | 0→900 RPS 60s, giữ 900 RPS 360s, giảm 30s |
| High staged | 2200 threads | 540s | 0→300→600→900 RPS, giữ 900 RPS 390s, giảm 30s |
| Stress recovery | 2800 threads | 480s | 0→1200 RPS, giữ 1200 RPS, giảm về 600 RPS |
| Stress staged recovery | 2800 threads | 600s | 0→600→900→1200 RPS, giữ 1200 RPS, giảm về 600 RPS |

### 10.4. Chaos trong benchmark

Các kịch bản medium/high sử dụng dependency slowdown:

| Kịch bản | Endpoint chaos |
|---|---|
| Medium degradation | `POST /api/chaos/dependency-slowdown/medium` |
| High degradation | `POST /api/chaos/dependency-slowdown/high` |

Theo phần cài đặt hiện tại:

- Medium dependency slowdown cộng thêm `200-400ms` vào thời gian giữ DB pool.
- High dependency slowdown cộng thêm `400-800ms` vào thời gian giữ DB pool.
- Dependency slowdown chỉ tác động đến những request có dùng DB pool trong `/api/simulate-mixed-call`.

### 10.5. Script chạy benchmark

Thư mục:

```text
scripts_run_jmeter/
```

Các script chính:

| Script | Chức năng |
|---|---|
| `0-run_all_benchmark_scenarios.bat` | Chạy lần lượt low, medium, high, stress |
| `1-run_low_all_strategies.bat` | Chạy low benchmark cho 4 strategy |
| `2-run_medium_chaos_all_strategies.bat` | Chạy medium degradation cho 4 strategy |
| `3-run_high_all_strategies.bat` | Chạy high degradation cho 4 strategy |
| `4-run_stress-test_all_strategies.bat` | Chạy stress test cho 4 strategy |

Các script chạy theo thứ tự strategy:

```text
round-robin
random
least-connections
adaptive
```

Mỗi strategy được cấu hình:

```bat
RUNS_PER_STRATEGY=5
```

Script có cơ chế sửa `alb.strategy` trong `api-gateway-alb/src/main/resources/application.yml`, commit/push lên branch `main`, chờ CI/CD deploy rồi chạy JMeter non-GUI.

Lưu ý: trong script có nhắc endpoint tùy chọn:

```text
GET /actuator/alb/strategy
```

Repository hiện chưa cung cấp controller cho endpoint `GET /actuator/alb/strategy`. Script đang đặt:

```bat
STRICT_SERVER_STRATEGY_CHECK=false
```

nên bước kiểm tra này chỉ mang tính tùy chọn và không bắt buộc.

## 11. Ghi chú vận hành và giới hạn hiện tại

README này phản ánh trạng thái của mã nguồn, cấu hình, JMeter và script benchmark trong repository hiện tại. Nội dung không sử dụng hoặc phụ thuộc vào thư mục `docs`.

Một số điểm cần lưu ý khi sử dụng dự án:

- `application.yml` của Gateway đang cấu hình `alb.strategy=random`, trong khi giá trị mặc định trong `AlbProperties.java` là `adaptive`. Khi benchmark cần kiểm tra và đổi đúng chiến lược mong muốn.
- `ScoreCalculator` nằm trong package `dataplane`.
- MCDM hiện dùng công thức kết hợp `0.70 * EWM + 0.30 * AHP`.
- Giới hạn beta trong `DynamicWeightEngine` nằm trong khoảng `0.08` đến `0.45`.
- Adaptive routing sử dụng `RoutingCostCalculator`, trong đó có health cost, load cost, overload penalty, cap pressure penalty, absolute health penalty, stale penalty và capacity-normalized inflight.
- Endpoint `/api/alb-metrics` trả thêm `rawCpu` và `capacityWeight` để phục vụ tính toán routing cost.
- Endpoint benchmark chính là `/api/simulate-mixed-call`; `/api/simulate-call` vẫn tồn tại cho các kịch bản mô phỏng cũ hơn.
- Chaos chính trong benchmark là `dependency-slowdown` và `latency-degradation`. Các chế độ `async-io`, `heavy`, `cpu-spike`, `hidden` vẫn còn trong code để phục vụ kiểm thử phụ hoặc tương thích với kịch bản cũ.
- Repository có sẵn kịch bản JMeter và script chạy benchmark nhiều chiến lược.
- Monitoring gồm Prometheus, Grafana, cAdvisor và dashboard Grafana `dashboard-grafana.json` trong thư mục `monitoring`.
- Dashboard `ALB — Routing Score & Score Breakdown` theo dõi Adaptive Load Balancer ở ba lớp chính: health score, routing cost và kết quả phân phối request. Dashboard cũng có các panel latency percentile, CPU usage và memory usage của container.

Các giới hạn cần kiểm tra thêm trước khi triển khai production:

- Repository chưa khai báo Git remote dùng cho môi trường triển khai.
- Endpoint `GET /actuator/alb/strategy` chưa được implement, dù script benchmark có cấu hình kiểm tra tùy chọn endpoint này.
- Dự án chưa tích hợp database thật; DB pool trong benchmark được mô phỏng bằng `Semaphore`.
- Repository chưa cấu hình authentication, authorization, TLS hoặc rate limiting.
- `monitoring/prometheus.yml` chưa scrape trực tiếp 3 backend; cấu hình hiện tại scrape Gateway và cAdvisor. Dashboard Grafana vì vậy sử dụng metrics từ Gateway và cAdvisor làm nguồn dữ liệu chính.
- Các địa chỉ IP như `172.30.35.37` trong JMeter, script và Prometheus là cấu hình theo môi trường hiện tại; cần cập nhật khi chuyển sang máy chủ khác.

Trước khi benchmark hoặc triển khai trên môi trường khác, nên kiểm tra lại:

- Giá trị `alb.strategy` trong Gateway.
- Địa chỉ IP trong các file JMeter, script `.bat` và `monitoring/prometheus.yml`.
- Trạng thái đăng ký của Eureka, Gateway và 3 backend.
- Plugin jp@gc Throughput Shaping Timer trong JMeter.
- Tài nguyên CPU/RAM trong `docker-compose.yml`.
- Cách xác minh strategy đang chạy trên server, ví dụ bổ sung endpoint đọc strategy hoặc kiểm tra trực tiếp cấu hình container đang deploy.
