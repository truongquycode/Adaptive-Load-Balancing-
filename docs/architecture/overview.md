# Tổng quan kiến trúc hệ thống

## 1. Mục tiêu hệ thống

Dự án xây dựng một môi trường microservices dùng Spring Boot để mô phỏng, triển khai và kiểm thử các chiến lược cân bằng tải. Trọng tâm của dự án là cơ chế cân bằng tải thích nghi trong API Gateway, có khả năng quan sát trạng thái backend theo thời gian gần thực và đưa ra quyết định định tuyến dựa trên độ trễ, tải tức thời, CPU và năng lực tương đối của từng instance.

Các chiến lược cân bằng tải được hỗ trợ trong mã nguồn hiện tại gồm:

- `adaptive`
- `round-robin`
- `random`
- `least-connections`

Chiến lược đang được cấu hình mặc định trong `api-gateway-alb/src/main/resources/application.yml` là:

```yaml
alb:
    strategy: random
```

Khi benchmark, các file `.bat` trong `scripts_run_jmeter` sẽ tự thay đổi giá trị `alb.strategy`, commit, push và chờ CI/CD triển khai lại Gateway để chạy lần lượt từng chiến lược.

---

## 2. Kiến trúc runtime

```text
Client / JMeter
      |
      | HTTP request
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

Trong kiến trúc này:

- Client hoặc JMeter chỉ gửi request vào API Gateway tại port `8080`.
- API Gateway định tuyến toàn bộ request khớp `/api/**` đến service name `REGISTRATION-SERVICE-ALB`.
- Eureka Server quản lý danh sách instance backend đang hoạt động.
- Ba instance backend dùng cùng mã nguồn `registration-service-alb`, nhưng được chạy bằng các container khác nhau với port và tài nguyên khác nhau.
- Adaptive Load Balancer nằm trong module `api-gateway-alb`, không nằm trong backend service.

---

## 3. Các module chính

| Module | Port | Vai trò |
|---|---:|---|
| `eureka-server` | `8761` | Service Discovery cho toàn hệ thống |
| `api-gateway-alb` | `8080` | API Gateway, định tuyến request và chứa thuật toán cân bằng tải |
| `registration-service-alb` | `8081`, `8082`, `8083` | Backend service mô phỏng nghiệp vụ, workload, chaos và cung cấp metrics cho Gateway |

Dự án là Maven multi-module. File `pom.xml` gốc khai báo ba module:

```xml
<modules>
    <module>eureka-server</module>
    <module>api-gateway-alb</module>
    <module>registration-service-alb</module>
</modules>
```

Công nghệ nền tảng:

- Java 21
- Spring Boot 3.2.4
- Spring Cloud 2023.0.1
- Spring Cloud Gateway
- Spring Cloud LoadBalancer
- Netflix Eureka
- Micrometer, Actuator, Prometheus
- HdrHistogram
- Caffeine Cache
- Docker Compose
- JMeter

---

## 4. Cấu hình Docker Compose

File `docker-compose.yml` ở thư mục gốc triển khai 5 container:

| Container | Port | CPU limit | Memory limit |
|---|---:|---:|---:|
| `eureka-server` | `8761` | `0.5` | `256m` |
| `api-gateway-alb` | `8080` | `2.0` | `1g` |
| `registration-8081` | `8081` | `2.0` | `768m` |
| `registration-8082` | `8082` | `1.5` | `512m` |
| `registration-8083` | `8083` | `1.0` | `384m` |

Ba backend có năng lực không đồng nhất. Điều này phù hợp với mục tiêu kiểm thử thuật toán adaptive: instance 8081 mạnh nhất, 8082 trung bình, 8083 yếu nhất.

Backend tự phát hiện CPU quota thông qua `ContainerResourceDetector`. Giá trị này được trả về trong endpoint `/api/alb-metrics` dưới field `capacityWeight`, sau đó Gateway dùng để tính `expectedInflight` cho từng backend.

---

## 5. Luồng request chính

```text
Client/JMeter
    |
    v
GET /api/simulate-mixed-call?profile=...
    |
    v
API Gateway routeId = backend-route
    |
    v
Spring Cloud LoadBalancer
    |
    |-- round-robin
    |-- random
    |-- least-connections
    |-- adaptive
    |
    v
Một instance REGISTRATION-SERVICE-ALB
```

API Gateway định nghĩa route trong `GatewayRoutingConfig`:

```java
.route("backend-route", r -> r.path("/api/**")
        .uri("lb://REGISTRATION-SERVICE-ALB"))
```

Điều này có nghĩa là mọi request có path `/api/**` đi qua Gateway sẽ được Spring Cloud LoadBalancer chọn một instance backend phù hợp.

---

## 6. Luồng metrics cho Adaptive Load Balancer

Adaptive Load Balancer không gọi trực tiếp backend trong lúc chọn từng request. Thay vào đó, hệ thống có một control-plane chạy định kỳ để cập nhật trạng thái backend.

```text
MetricsPoller mỗi 200ms
    |
    | gọi Eureka lấy danh sách instance
    |
    | gọi /api/alb-metrics trên từng backend
    v
Raw metrics:
    - cpu
    - rawCpu
    - count
    - totalTime
    - queue
    - capacityWeight
    |
    v
ScoreCalculator
    |
    | AEWMA latency
    | Sliding window percentile
    | Normalization latency / queue / cpu
    | Dynamic MCDM weight
    | PID penalty
    v
ScoreBreakdown
    |
    v
MetricsCache
    |
    v
RoutingCostCalculator
    |
    | health cost
    | capacity-normalized load cost
    | stale penalty
    | overload penalty
    v
AdaptiveLoadBalancer chọn backend bằng P2C
```

Tần suất poll được cấu hình trong `application.yml`:

```yaml
alb:
    polling:
        interval: 200
```

Timeout khi Gateway gọi `/api/alb-metrics` trong mã nguồn hiện tại là `800ms`.

---

## 7. Endpoint chính

### Gateway

| Method | Endpoint | Vai trò |
|---|---|---|
| `POST` | `/actuator/alb/reset` | Reset toàn bộ trạng thái ALB trước benchmark |
| `GET` | `/actuator/prometheus` | Export metrics Prometheus |
| `GET` | `/actuator/health` | Kiểm tra trạng thái ứng dụng |
| `GET` | `/actuator/metrics` | Xem danh sách metrics Actuator |

### Registration Service

| Method | Endpoint | Vai trò |
|---|---|---|
| `GET` | `/api/simulate-call` | Endpoint mô phỏng cũ, dùng baseline/heavy/async I/O |
| `GET` | `/api/simulate-mixed-call?profile=...` | Endpoint benchmark chính với workload hỗn hợp |
| `GET` | `/api/register` | Endpoint tương thích cũ |
| `POST` | `/api/register-user` | Endpoint demo đăng ký người dùng |
| `GET` | `/api/alb-metrics` | Cung cấp metrics cho Gateway poll |
| `GET` | `/api/chaos/status` | Xem trạng thái chaos |
| `POST` | `/api/chaos/reset` | Tắt toàn bộ chaos |
| `POST` | `/api/chaos/dependency-slowdown/medium` | Bật dependency slowdown mức medium |
| `POST` | `/api/chaos/dependency-slowdown/high` | Bật dependency slowdown mức high |
| `POST` | `/api/chaos/dependency-slowdown/disable` | Tắt dependency slowdown |
| `POST` | `/api/chaos/latency-degradation/medium` | Bật latency degradation mức medium |
| `POST` | `/api/chaos/latency-degradation/high` | Bật latency degradation mức high |
| `POST` | `/api/chaos/latency-degradation/disable` | Tắt latency degradation |

Ngoài ra còn có các endpoint chaos phụ để tương thích hoặc test thủ công như `/api/chaos/heavy/enable`, `/api/chaos/async-io/enable`, `/api/chaos/cpu-spike/enable` và `/api/chaos/hidden/enable`.

---

## 8. Monitoring

Thư mục `monitoring` chứa Docker Compose riêng cho:

| Thành phần | Port | Vai trò |
|---|---:|---|
| Prometheus | `9090` | Thu thập metrics |
| Grafana | `3000` | Hiển thị dashboard |
| cAdvisor | `8088` | Xuất metrics tài nguyên container |

File `monitoring/prometheus.yml` hiện scrape:

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

Prometheus scrape trực tiếp API Gateway ở port `8080`. Các metrics backend dùng cho thuật toán adaptive được Gateway tự poll qua `/api/alb-metrics`, sau đó export lại dưới dạng metrics `alb_*`.

Dashboard chính nằm tại:

```text
monitoring/dashboard-grafana.json
```

Dashboard có tiêu đề:

```text
ALB — Routing Score & Score Breakdown
```

Các nhóm biểu đồ quan trọng:

- EWMA latency theo backend
- Queue depth theo backend
- Dynamic MCDM weights
- Backend health score
- Routing health/load weight
- Capacity weight
- Capacity-normalized load ratio
- Final routing cost
- Routing selection rate theo backend
- Routing selection rate theo decision reason
- Gateway latency percentiles P50/P90/P95/P99
- CPU và memory container qua cAdvisor

---

## 9. Benchmark

Thư mục `jmeter` chứa các kịch bản kiểm thử chính:

| File | Mục đích |
|---|---|
| `00_rsat_discovery_mixed_1000plus_600_1600_tst.jmx` | Khảo sát ngưỡng bão hòa từ 600 đến 1600 RPS |
| `00b_rsat_confirm_mixed_1000plus_1000_1250_tst.jmx` | Xác nhận vùng R_sat từ 1000 đến 1250 RPS |
| `01_low_baseline_mixed_0300_nochaos_tst.jmx` | Low load 300 RPS, không chaos |
| `02_medium_dependency_slowdown_mixed_0600_tst.jmx` | Medium load 600 RPS, dependency slowdown trên backend 8083 |
| `03_high_dependency_slowdown_mixed_0900_tst.jmx` | High load 900 RPS, dependency slowdown trên backend 8083 |
| `03_high_dependency_slowdown_mixed_0900_staged_tst.jmx` | High load staged ramp 300 → 600 → 900 RPS |
| `04_stress_recovery_mixed_1200_to_0600_nochaos_tst.jmx` | Stress 1200 RPS rồi recovery về 600 RPS |
| `04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst.jmx` | Stress staged ramp 600 → 900 → 1200 rồi recovery |

Workload chính dùng endpoint:

```text
GET /api/simulate-mixed-call?profile=...
```

Tỷ lệ profile trong JMeter:

| Profile | Tỷ lệ |
|---|---:|
| `light` | 60% |
| `medium` | 25% |
| `slow` | 12% |
| `very-slow` | 3% |

Các script trong `scripts_run_jmeter` chạy lần lượt 4 chiến lược:

1. `round-robin`
2. `random`
3. `least-connections`
4. `adaptive`

Mỗi chiến lược được chạy `5` lần. Thời gian chờ giữa các lần chạy là `180` giây, thời gian chờ sau khi push cấu hình strategy lên GitHub là `140` giây.

---

## 10. CI/CD

File `.github/workflows/deploy.yml` triển khai CI/CD bằng GitHub Actions self-hosted runner.

Workflow chạy khi push lên branch `main`.

Quy trình chính:

1. Checkout source code.
2. Phát hiện file thay đổi.
3. Nếu thay đổi file ảnh hưởng toàn hệ thống như `docker-compose.yml`, `pom.xml`, workflow hoặc Maven wrapper thì build/deploy toàn bộ.
4. Nếu chỉ thay đổi module cụ thể thì chỉ build/deploy container tương ứng.
5. Nếu thay đổi `registration-service-alb`, deploy cả 3 container backend:
   - `registration-8081`
   - `registration-8082`
   - `registration-8083`
6. Nếu thay đổi `api-gateway-alb`, chỉ deploy `api-gateway-alb`.
7. Sau deploy, chạy `docker compose ps` để hiển thị trạng thái container.

Các script benchmark thay đổi file:

```text
api-gateway-alb/src/main/resources/application.yml
api-gateway-alb/.alb-strategy-deploy-marker.txt
```

File marker được cập nhật để đảm bảo GitHub Actions có thay đổi thật và redeploy Gateway khi đổi thuật toán cân bằng tải.

---

## 11. Ghi chú vận hành

- Endpoint `/actuator/alb/reset` có trong mã nguồn và được dùng để reset state trước benchmark.
- Endpoint `/actuator/alb/strategy` được các script `.bat` tham chiếu như một endpoint tùy chọn, nhưng chưa được cài đặt trong mã nguồn hiện tại.
- Prometheus hiện chỉ scrape trực tiếp API Gateway và cAdvisor; backend metrics được Gateway poll qua `/api/alb-metrics`.
- Backend không sử dụng database thật. DB trong workload là mô phỏng bằng `Semaphore`.
- `registration-service-alb` loại trừ `DataSourceAutoConfiguration`, phù hợp với việc không dùng datasource thật.
