# Adaptive Load Balancer for Spring Boot Microservices

## 1. Mục tiêu dự án

Dự án xây dựng một hệ thống microservices bằng Spring Boot để triển khai, quan sát và kiểm thử các chiến lược cân bằng tải trong môi trường nhiều backend có năng lực xử lý không đồng nhất.

Mục tiêu nghiên cứu chính là thiết kế một cơ chế cân bằng tải thích nghi tại API Gateway. Cơ chế này không định tuyến theo vòng lặp cố định, mà sử dụng metrics runtime như latency, queue/inflight, CPU, capacity weight và trạng thái stale metrics để tính chi phí định tuyến cho từng backend.

Dự án hiện được xem là một **prototype nghiên cứu/thực nghiệm**, không phải một load balancer production-ready. Các workload, chaos và dependency trong backend là mô phỏng có kiểm soát để phục vụ kiểm thử luận văn.

---

## 2. Kiến trúc tổng quan

```text
Client / JMeter
      |
      | GET /api/**
      v
API Gateway ALB :8080
      |
      | Spring Cloud Gateway route: backend-route
      | URI: lb://REGISTRATION-SERVICE-ALB
      v
Spring Cloud LoadBalancer
      |
      | Chọn strategy:
      | - adaptive
      | - round-robin
      | - random
      | - least-connections
      v
Eureka Server :8761
      |
      | REGISTRATION-SERVICE-ALB instances
      v
registration-8081 :8081
registration-8082 :8082
registration-8083 :8083
```

Các backend chạy cùng mã nguồn `registration-service-alb` nhưng khác CPU/RAM trong Docker Compose:

| Container | Port | CPU quota | Memory limit | Vai trò trong benchmark |
|---|---:|---:|---:|---|
| `registration-8081` | `8081` | `2.0` | `768m` | instance mạnh nhất |
| `registration-8082` | `8082` | `1.5` | `512m` | instance trung bình |
| `registration-8083` | `8083` | `1.0` | `384m` | instance yếu nhất, thường dùng làm chaos target |

Gateway lấy danh sách backend từ Eureka. Backend không tự cân bằng tải; toàn bộ quyết định routing nằm ở API Gateway.

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
| Load Balancer SPI | Spring Cloud LoadBalancer |
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
├── .github/workflows/deploy.yml
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
├── registration-service-alb/
├── docs/
│   ├── architecture/
│   ├── adaptive-ablation-study.md
│   ├── benchmark-methodology.md
│   ├── parameter-rationale.md
│   ├── routing-cost-calculator.md
│   └── threats-to-validity.md
├── jmeter/
├── monitoring/
├── scripts_run_jmeter/
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

## 5. Module chính

### 5.1. `eureka-server`

- Port: `8761`
- Class chính: `EurekaServerApplication`
- Vai trò: service discovery cho Gateway và các backend.

### 5.2. `api-gateway-alb`

- Port: `8080`
- Class chính: `ApiGatewayAlbApplication`
- Annotation chính: `@EnableDiscoveryClient`, `@EnableScheduling`
- Vai trò:
  - nhận request từ client/JMeter;
  - định tuyến `/api/**` đến `REGISTRATION-SERVICE-ALB`;
  - chứa các thuật toán cân bằng tải;
  - poll `/api/alb-metrics` từ backend;
  - export metrics ALB và Gateway cho Prometheus;
  - cung cấp endpoint reset/xác minh phục vụ benchmark.

Các class quan trọng:

| Nhóm | Class |
|---|---|
| Strategy selection | `LoadBalancerBeanConfig`, `LoadBalancerConfiguration` |
| Data-plane Adaptive | `AdaptiveLoadBalancer`, `RoutingCostCalculator`, `InflightTracker`, `InflightLifecycle` |
| Baseline algorithms | `MetricAwareLoadBalancer`, `LeastConnectionsLoadBalancer`, Spring Round Robin, Spring Random |
| Control-plane | `MetricsPoller`, `ScoreCalculator`, `DynamicWeightEngine`, `SlidingWindowManager` |
| Math/control | `EwmaSmoother`, `PIDController`, `NormalizationFunctions` |
| State/cache | `MetricsCache`, `ScoreBreakdown`, `RoutingCost` |
| Benchmark admin | `AdminController` |

### 5.3. `registration-service-alb`

- Port mặc định: `${PORT:8081}`
- Eureka service name: `REGISTRATION-SERVICE-ALB`
- Vai trò:
  - cung cấp endpoint nghiệp vụ mô phỏng;
  - cung cấp workload hỗn hợp nhẹ/vừa/chậm/rất chậm;
  - cung cấp chaos endpoint;
  - cung cấp `/api/alb-metrics` để Gateway poll;
  - phát hiện CPU quota container để trả về `capacityWeight`.

---

## 6. Cấu hình strategy

File cấu hình chính:

```text
api-gateway-alb/src/main/resources/application.yml
```

```yaml
alb:
  strategy: ${ALB_STRATEGY:adaptive}
  ablation:
    variant: ${ALB_ABLATION_VARIANT:full}
```

Các strategy hợp lệ:

| Strategy | Ý nghĩa |
|---|---|
| `adaptive` | Cơ chế thích nghi dựa trên metrics runtime và routing cost |
| `round-robin` | Chia vòng lặp tuần tự bằng Spring Round Robin |
| `random` | Chọn ngẫu nhiên bằng Spring Random |
| `least-connections` | Chọn backend có inflight thấp nhất theo `InflightTracker` |

Các ablation variant chỉ có ý nghĩa khi `strategy=adaptive`:

```text
full, no-pid, fixed-weights, no-ewma-latency, no-score-ema,
no-capacity, no-p2c, no-probe, no-low-load-rr
```

Kiểm tra strategy đang chạy thật trên server:

```bash
curl http://172.30.35.37:8080/actuator/alb/strategy
```

Reset trạng thái ALB trước benchmark:

```bash
curl -X POST http://172.30.35.37:8080/actuator/alb/reset
```

---

## 7. Pipeline Adaptive Load Balancer

```text
Backend /api/alb-metrics
      |
      v
MetricsPoller
      |
      | delta request count/total time
      | latency sample
      | queue/inflight
      | CPU normalized by capacity
      | capacityWeight
      v
SlidingWindowManager + MetricsCache
      |
      v
ScoreCalculator
      |
      | EWMA latency
      | normalized latency / queue / CPU
      | Dynamic MCDM base score
      | PID-inspired latency penalty
      v
final health score
      |
      v
RoutingCostCalculator
      |
      | health cost
      | capacity-normalized load cost
      | stale penalty
      | hard exclusion
      | dynamic health/load weights
      v
AdaptiveLoadBalancer
      |
      | warmup RR
      | low-load RR
      | probe recovery
      | P2C or least-cost fallback
      v
Selected backend
```

Adaptive được gọi là “thích nghi” vì quyết định routing thay đổi theo trạng thái runtime của backend. Tuy nhiên, đây là mô hình heuristic thực nghiệm, không phải thuật toán tối ưu toàn cục có chứng minh toán học.

---

## 8. Dynamic MCDM và lưu ý khoa học

`DynamicWeightEngine` dùng ba tiêu chí:

| Tiêu chí | Metric sử dụng | Ý nghĩa |
|---|---|---|
| Latency | `normLatency` từ EWMA latency | Backend chậm hay nhanh so với cụm |
| Queue | `normQueue` | Mức tải/concurrency hiện tại |
| CPU | `normCpu` | Áp lực tài nguyên |

Trọng số AHP prior hiện tại:

```text
latency = 0.648
queue   = 0.230
cpu     = 0.122
```

Dynamic EWM chỉ cập nhật khi có traffic nghiệp vụ thật trong cửa sổ update:

```yaml
alb:
  weights:
    update-interval: 5000
    min-completed-requests: 20
    min-actual-rps: 5.0
    reset-to-ahp-when-idle: true
```

Cơ chế này tránh trường hợp MCDM học từ nhiễu nền khi Gateway chỉ đang poll metrics nhưng không có JMeter/client traffic.

Metric debug:

| Metric | Ý nghĩa |
|---|---|
| `alb_mcdm_weight{criterion="latency"}` | Trọng số latency |
| `alb_mcdm_weight{criterion="queue"}` | Trọng số queue |
| `alb_mcdm_weight{criterion="cpu"}` | Trọng số CPU |
| `alb_mcdm_update_mode` | `0` idle/stable/frozen, `1` dynamic real traffic, `2` fixed-weights ablation |
| `alb_mcdm_recent_completed_requests` | Số request thật trong cửa sổ MCDM gần nhất |
| `alb_mcdm_recent_actual_rps` | RPS thật trong cửa sổ MCDM gần nhất |

---

## 9. Backend simulation và chaos

Endpoint chính cho benchmark:

```http
GET /api/simulate-mixed-call?profile=light
GET /api/simulate-mixed-call?profile=medium
GET /api/simulate-mixed-call?profile=slow
GET /api/simulate-mixed-call?profile=very-slow
GET /api/simulate-mixed-call?profile=mixed
```

Tỷ lệ `mixed` trong code:

| Profile | Tỷ lệ | Đặc điểm |
|---|---:|---|
| `light` | 60% | ít CPU/RAM, không dùng DB pool |
| `medium` | 25% | dùng CPU/RAM vừa, giữ DB pool ngắn |
| `slow` | 12% | request nặng hơn, giữ DB lâu hơn |
| `very-slow` | 3% | request đuôi dài, RAM/CPU/DB hold cao |

Chaos endpoint:

| Endpoint | Ý nghĩa |
|---|---|
| `POST /api/chaos/reset` | Tắt toàn bộ chaos |
| `POST /api/chaos/dependency-slowdown/medium` | Dependency slowdown cho medium load |
| `POST /api/chaos/dependency-slowdown/high` | Dependency slowdown cho high load |
| `POST /api/chaos/latency-degradation/medium` | Tăng latency cục bộ mức vừa |
| `POST /api/chaos/latency-degradation/high` | Tăng latency cục bộ mức cao |
| `POST /api/chaos/status` | Không có; dùng `GET /api/chaos/status` để xem trạng thái |

Backend dùng `Semaphore` để mô phỏng DB connection pool. Cần trình bày rõ đây là synthetic workload, không phải database hay dependency thật.

---

## 10. Monitoring

Monitoring nằm trong thư mục:

```text
monitoring/
```

Prometheus scrape:

- API Gateway: `172.30.35.37:8080/actuator/prometheus`
- backend trực tiếp: `8081`, `8082`, `8083`
- cAdvisor: `cadvisor:8080`

Dashboard Grafana chính:

```text
monitoring/dashboard-grafana.json
```

Các panel quan trọng:

| Panel/metric | Mục đích |
|---|---|
| `Backend Health Score — MCDM and PID` | Health score trước inflight adjustment |
| `Dynamic MCDM criterion weights` | Trọng số latency/queue/CPU |
| `Adaptive Routing Weights — Health vs Load` | Tỷ trọng health/load trong routing cost |
| `Final Routing Cost by Backend` | Cost cuối cùng dùng để chọn backend |
| `Routing Selection Rate by Backend` | Traffic thực tế vào từng backend |
| `Routing Selection Rate by Decision Reason` | Lý do chọn: warmup, low-load, probe, P2C... |
| `Gateway Latency Percentiles — Successful Requests Only` | p50/p90/p95/p99 của request 200 |
| `Gateway Latency Percentiles — All HTTP Statuses` | latency không che lỗi |
| `Gateway Error Rate — Non-2xx Requests` | tỷ lệ lỗi |
| `Gateway Throughput — Actual RPS` | throughput thực tế |

Khi báo cáo, không nên chỉ dùng latency HTTP 200. Cần đọc kèm all-status latency, error rate và actual RPS.

---

## 11. Benchmark JMeter

Các JMX chính:

| File | Mục tiêu |
|---|---|
| `00_rsat_discovery_mixed_1000plus_600_1600_tst.jmx` | Tìm vùng bão hòa R_sat |
| `00b_rsat_confirm_mixed_1000plus_1000_1250_tst.jmx` | Xác nhận R_sat |
| `01_low_baseline_mixed_0300_nochaos_tst.jmx` | Low load, no chaos |
| `02_medium_dependency_slowdown_mixed_0600_tst.jmx` | Medium load + dependency slowdown |
| `03_high_dependency_slowdown_mixed_0900_tst.jmx` | High load + dependency slowdown |
| `03_high_dependency_slowdown_mixed_0900_staged_tst.jmx` | High load staged |
| `04_stress_recovery_mixed_1200_to_0600_nochaos_tst.jmx` | Stress/recovery |
| `04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst.jmx` | Stress/recovery staged |

Script chạy:

```bat
scripts_run_jmeter\0-run_all_benchmark_scenarios.bat
scripts_run_jmeter\1-run_low_all_strategies.bat
scripts_run_jmeter\2-run_medium_chaos_all_strategies.bat
scripts_run_jmeter\3-run_high_all_strategies.bat
scripts_run_jmeter\4-run_stress-test_all_strategies.bat
scripts_run_jmeter\5-run_adaptive_ablation_medium.bat
```

Script lõi:

```bat
scripts_run_jmeter\_benchmark_common.bat
```

Các điểm bảo vệ tính khoa học đã được bổ sung:

- strict verify bằng `GET /actuator/alb/strategy`;
- randomize thứ tự strategy;
- reset ALB và chaos trước từng run;
- lưu metadata cho từng run;
- hỗ trợ ablation variants;
- script tổng hợp JTL bằng `summarize_jtl_results.py`.

Lưu ý: script benchmark hiện vẫn đổi strategy bằng cách sửa `application.yml`, commit/push và chờ CI/CD deploy. Cách này đã có strict verification để tránh gắn nhãn sai, nhưng khi viết luận văn cần ghi rõ đây là cơ chế vận hành thực nghiệm và vẫn có thể gây nhiễu thời gian deploy.

---

## 12. Quy trình chạy hệ thống

Build toàn bộ project:

```bash
mvn clean package -DskipTests
```

Chạy bằng Docker Compose:

```bash
docker compose build
docker compose up -d
```

Kiểm tra container:

```bash
docker ps
```

Kiểm tra Eureka:

```bash
curl http://172.30.35.37:8761
```

Kiểm tra Gateway:

```bash
curl http://172.30.35.37:8080/actuator/health
curl http://172.30.35.37:8080/api/simulate-mixed-call?profile=light
curl http://172.30.35.37:8080/actuator/alb/strategy
```

Reset trước benchmark:

```bash
curl -X POST http://172.30.35.37:8080/actuator/alb/reset
curl -X POST http://172.30.35.37:8081/api/chaos/reset
curl -X POST http://172.30.35.37:8082/api/chaos/reset
curl -X POST http://172.30.35.37:8083/api/chaos/reset
```

---

## 13. CI/CD

Workflow:

```text
.github/workflows/deploy.yml
```

Đặc điểm:

- chạy trên GitHub Actions self-hosted runner;
- kiểm tra YAML không có tab indentation;
- validate `docker compose config`;
- phát hiện service thay đổi để build/deploy chọn lọc;
- có `concurrency` để tránh nhiều deploy chồng nhau;
- cleanup stale `API-GATEWAY-ALB` instances trong Eureka sau khi deploy Gateway.

Gateway đã cấu hình `eureka.instance.instance-id: ${spring.application.name}:${server.port}` để tránh mỗi lần deploy sinh nhiều instance DOWN trong Eureka.

---

## 14. Tài liệu chi tiết

| Tài liệu | Nội dung |
|---|---|
| `docs/architecture/overview.md` | Kiến trúc tổng thể và luồng request |
| `docs/architecture/adaptive-load-balancer.md` | Data-plane Adaptive và cơ chế chọn backend |
| `docs/routing-cost-calculator.md` | Công thức routing cost cuối cùng |
| `docs/architecture/metrics-poller.md` | Poll metrics, delta latency, real traffic gate |
| `docs/architecture/score-calculator.md` | Health score, normalization, EWMA, PID-inspired penalty |
| `docs/architecture/dynamic-weight-engine.md` | AHP/EWM và điều kiện update khi có traffic thật |
| `docs/architecture/pid-controller.md` | PID-inspired latency penalty |
| `docs/architecture/ewma-smoother.md` | Adaptive EWMA smoother |
| `docs/architecture/sliding-window-manager.md` | HDRHistogram và percentile window |
| `docs/benchmark-methodology.md` | Quy trình benchmark nghiêm ngặt |
| `docs/adaptive-ablation-study.md` | Kế hoạch ablation study |
| `docs/parameter-rationale.md` | Lý do chọn tham số và giới hạn |
| `docs/threats-to-validity.md` | Các giới hạn khi diễn giải kết quả |

---

## 15. Giới hạn cần ghi rõ trong luận văn

- Backend là mô phỏng, không có database thật.
- Dependency slowdown là sleep/DB-hold mô phỏng, không phải service ngoài thật.
- Hệ thống chỉ có 3 backend, nên EWM trên số lượng instance nhỏ cần diễn giải thận trọng.
- CPU quota không phản ánh toàn bộ năng lực thực tế nếu bottleneck nằm ở network, DB hoặc memory.
- Benchmark có thể chịu ảnh hưởng bởi Docker, CI/CD, Prometheus scrape, Tailscale/VPN hoặc tài nguyên máy JMeter.
- Adaptive là hybrid heuristic adaptive load balancing model; không nên tuyên bố là thuật toán tối ưu toàn cục.

---

## 16. Cách trả lời ngắn khi bảo vệ

**Vì sao gọi là Adaptive?**  
Vì Gateway điều chỉnh routing dựa trên metrics runtime gồm latency EWMA, queue/inflight, CPU, capacity, stale metrics và routing cost. Khi backend xấu đi, cost tăng và traffic được giảm hoặc chuyển sang backend khác.

**Vì sao dùng latency, queue, CPU?**  
Latency phản ánh trải nghiệm request, queue/inflight phản ánh tải tức thời, CPU phản ánh áp lực tài nguyên. Ba tiêu chí bổ sung cho nhau.

**PID trong code có phải PID đầy đủ không?**  
Không nên diễn giải như bộ điều khiển PID cổ điển. Trong dự án, nó là PID-inspired latency penalty để tăng score của backend chậm kéo dài.

**Benchmark có công bằng không?**  
Bộ script hiện reset state, randomize thứ tự, verify strategy và lưu metadata. Khi phân tích vẫn cần dùng nhiều run, loại warmup, đọc kèm throughput/error rate và nêu rõ môi trường kiểm thử.
