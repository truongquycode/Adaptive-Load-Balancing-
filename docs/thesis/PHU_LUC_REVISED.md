# PHỤ LỤC

---

## PHỤ LỤC A. CÀI ĐẶT VÀ CẤU HÌNH MÔI TRƯỜNG HỆ THỐNG

### A.1. Cài đặt môi trường Ubuntu Server

Hệ thống được triển khai trên máy ảo Ubuntu Server với các phần mềm sau:

| Thành phần | Mục đích | Ghi chú |
|---|---|---|
| Java 21 (Eclipse Temurin) | Runtime cho các Microservices (tích hợp sẵn trong Docker image `eclipse-temurin:21-jre-alpine`) | Không cần cài riêng trên host |
| Maven 3.9.6 | Build multi-module Maven project (tích hợp sẵn trong Docker image `maven:3.9.6-eclipse-temurin-21-alpine`) | Không cần cài riêng trên host |
| Docker Engine | Chạy các container Microservices và Monitoring | Cài đặt theo hướng dẫn chính thức Docker cho Ubuntu |
| Docker Compose (plugin) | Quản lý và khởi chạy toàn bộ hệ thống | Phiên bản `docker compose` (plugin tích hợp) |
| Git | Quản lý mã nguồn và CI/CD deployment | Phục vụ GitHub Actions self-hosted runner |

Lưu ý: Toàn bộ quá trình build được thực hiện bên trong Docker multi-stage build. Do đó, máy chủ Ubuntu chỉ cần cài Docker Engine và Docker Compose plugin, không cần cài Java hay Maven riêng.

### A.2. Lấy mã nguồn và khởi chạy hệ thống

Lấy mã nguồn hệ thống từ kho lưu trữ GitHub:

```bash
git clone https://github.com/truongquycode/Adaptive-Load-Balancing-.git
cd Adaptive-Load-Balancing-
```

Kiểm tra Docker hoạt động:

```bash
docker --version
docker compose version
```

Build và khởi chạy toàn bộ hệ thống:

```bash
docker compose build
docker compose up -d
```

Kiểm tra trạng thái container:

```bash
docker ps
```

Kết quả mong đợi: 5 container đang chạy (`eureka-server`, `registration-8081`, `registration-8082`, `registration-8083`, `api-gateway-alb`).

### A.3. Cấu hình triển khai các Microservices

#### Eureka Server

File cấu hình: `eureka-server/src/main/resources/application.yml`

```yaml
server:
  port: 8761

eureka:
  instance:
    hostname: ${EUREKA_HOSTNAME:localhost}
  client:
    register-with-eureka: false
    fetch-registry: false
    service-url:
      defaultZone: http://${eureka.instance.hostname}:${server.port}/eureka/
```

#### Registration Service (Backend)

File cấu hình: `registration-service-alb/src/main/resources/application.yml`

```yaml
server:
  port: ${PORT:8081}
  tomcat:
    threads:
      max: 500
      min-spare: 50
    accept-count: 200

spring:
  application:
    name: REGISTRATION-SERVICE-ALB

eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
  instance:
    instance-id: ${spring.application.name}:${PORT:8081}
    prefer-ip-address: true

management:
  endpoints:
    web:
      exposure:
        include: "health,info,prometheus,metrics"
  metrics:
    tags:
      application: ${spring.application.name}
    enable:
      jvm: false
      tomcat: false
  observations:
    http:
      server:
        requests:
          enabled: true
```

#### API Gateway

File cấu hình chính: `api-gateway-alb/src/main/resources/application.yml` — xem mục A.4.

Cách kiểm tra Eureka đã đăng ký đầy đủ backend:

```bash
curl http://172.30.35.37:8761
```

Kiểm tra Gateway hoạt động:

```bash
curl http://172.30.35.37:8080/actuator/health
curl http://172.30.35.37:8080/actuator/alb/strategy
```

### A.4. Cấu hình Spring Cloud Gateway và Adaptive Load Balancer

Toàn bộ tham số thuật toán được cấu hình trong `api-gateway-alb/src/main/resources/application.yml`. Dưới đây là các nhóm cấu hình chính:

#### Chọn chiến lược và Ablation

```yaml
alb:
    strategy: adaptive     # adaptive | round-robin | random | least-connections
    ablation:
        variant: full      # full | no-pid | fixed-weights | no-ewma-latency |
                           # no-score-ema | no-capacity | no-p2c | no-probe |
                           # no-low-load-rr
```

#### Metrics Polling

```yaml
    polling:
        interval: 200                  # ms — chu kỳ poll /api/alb-metrics
        metrics-timeout-ms: 800        # timeout HTTP call tới backend
        score-ema-alpha-spike: 0.60    # EMA alpha khi score tăng đột biến (>30%)
        score-ema-alpha-rise: 0.35     # EMA alpha khi score tăng nhẹ
        score-ema-alpha-recover: 0.25  # EMA alpha khi score giảm (hồi phục)
        score-ema-spike-threshold: 0.30
        idle-latency-baseline-ms: 65.0
        idle-decay-alpha: 0.20
```

#### Adaptive EWMA

```yaml
    ewma:
        tau-min: 200.0    # τ tối thiểu (ms) — khi latency đột biến
        tau-max: 2000.0   # τ tối đa (ms) — khi latency ổn định
        k: 3.0            # Độ nhạy với deviation
```

#### PID Controller

```yaml
    pid:
        kp: 1.0           # Hệ số tỉ lệ (Proportional)
        ki: 0.08           # Hệ số tích phân (Integral)
        kd: 0.04           # Hệ số vi phân (Derivative)
        tau-d: 2.0         # Hằng số thời gian low-pass filter cho D
        min-i: -0.8        # Giới hạn dưới integral (anti-windup)
        max-i: 2.5         # Giới hạn trên integral
        lambda: 0.8        # Biên độ tối đa penalty output
        kappa: 1.2         # Độ dốc hàm tanh squashing
```

Hằng số cứng trong code: `ERROR_DEADBAND = 0.08`.

#### Dynamic MCDM Weights (AHP + EWM)

```yaml
    weights:
        update-interval: 5000          # ms — chu kỳ cập nhật trọng số
        min-completed-requests: 20     # Số request tối thiểu để cập nhật EWM
        min-actual-rps: 5.0            # RPS tối thiểu để cập nhật EWM
        reset-to-ahp-when-idle: true   # Reset về AHP khi không có traffic thật
        blend-factor: 0.70             # 70% EWM + 30% AHP prior
        ema-alpha-min: 0.08            # EMA alpha tối thiểu cho weight update
        ema-alpha-max: 0.22            # EMA alpha tối đa cho weight update
        stable-queue-threshold: 0.08   # Ngưỡng queue ổn định (giữ AHP)
        stable-cpu-threshold: 0.08     # Ngưỡng CPU ổn định (giữ AHP)
        stable-latency-spread: 0.12    # Ngưỡng latency spread ổn định
        alpha-min: 0.15                # Giới hạn dưới trọng số latency
        alpha-max: 0.70                # Giới hạn trên trọng số latency
        beta-min: 0.08                 # Giới hạn dưới trọng số queue
        beta-max: 0.45                 # Giới hạn trên trọng số queue
        gamma-min: 0.08                # Giới hạn dưới trọng số CPU
        gamma-max: 0.35                # Giới hạn trên trọng số CPU
```

#### Routing và Data Plane

```yaml
    routing:
        warmup-ms: 5000                          # Warmup Round Robin (ms)
        min-expected-inflight: 3.0                # Inflight kỳ vọng tối thiểu
        low-load-inflight: 20                     # Ngưỡng tổng inflight cho low-load RR
        low-load-health-spread: 0.12              # Ngưỡng health spread cho low-load
        low-load-load-spread: 0.25                # Ngưỡng load spread cho low-load
        min-health-weight: 0.25                   # Trọng số health tối thiểu trong routing
        max-health-weight: 0.75                   # Trọng số health tối đa trong routing
        stale-penalty-weight: 0.15                # Penalty khi metrics cũ
        stale-soft-ms: 1500                       # Ngưỡng soft stale (ms)
        stale-hard-ms: 5000                       # Ngưỡng hard exclusion (ms)
        unhealthy-score-cutoff: 2.0               # Score >= 2.0 → hard exclude
        hard-inflight-cap: 220                    # Giới hạn cứng inflight per instance
        probe-interval-ms: 3000                   # Chu kỳ probe node bị loại (ms)
        probe-probability: 0.005                  # Xác suất probe mỗi request
        min-routing-norm-range: 0.12              # Min range cho rank normalization
        routing-weight-ema-alpha: 0.18            # EMA alpha cho health/load weights
        dominant-threshold: 0.70                  # Ngưỡng dominant mode
        overload-penalty-weight: 0.30             # Trọng số penalty overload
        cap-pressure-penalty-weight: 0.20         # Trọng số penalty cap pressure
        absolute-health-penalty-weight: 0.12      # Trọng số penalty health tuyệt đối
        absolute-latency-penalty-weight: 0.12     # Trọng số penalty latency tuyệt đối
        absolute-latency-target-ms: 300.0         # Ngưỡng SLA latency mục tiêu
        absolute-latency-critical-ms: 1500.0      # Ngưỡng latency nghiêm trọng
        overload-start-ratio: 0.95                # Bắt đầu penalty overload
        overload-full-ratio: 1.40                 # Penalty overload tối đa
        cap-pressure-start-ratio: 0.70            # Bắt đầu cap pressure
        cap-pressure-full-ratio: 1.00             # Cap pressure tối đa
        absolute-health-start: 0.75               # Bắt đầu health penalty tuyệt đối
        absolute-health-full: 1.50                # Health penalty tuyệt đối tối đa
        hard-inflight-cap-min: 40                 # Hard cap tối thiểu
        capacity-cap-factor-min: 0.70             # Hệ số capacity cap tối thiểu
        capacity-cap-factor-max: 1.50             # Hệ số capacity cap tối đa
        probe-max-total-inflight-ratio: 0.70      # Probe chỉ chạy khi cụm chưa căng
        probe-max-load-raw: 1.10                  # Probe max load raw
        probe-max-absolute-latency-cost: 0.80     # Probe max absolute latency cost
        probe-max-final-cost: 1.50                # Probe max final cost
```

#### Eureka Discovery

```yaml
eureka:
    client:
        serviceUrl:
            defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
        registry-fetch-interval-seconds: 5
    instance:
        instance-id: ${spring.application.name}:${server.port}
        prefer-ip-address: true
        lease-renewal-interval-in-seconds: 5
        lease-expiration-duration-in-seconds: 15
```

#### Gateway HTTP Client

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

### A.5. Cài đặt và cấu hình Tailscale

Tailscale được sử dụng để tạo mạng riêng ảo (VPN mesh) giữa máy trạm điều khiển (chạy JMeter trên Windows) và máy chủ Ubuntu Server (chạy hệ thống Docker). Mục đích chính là đảm bảo kết nối ổn định, không bị ảnh hưởng bởi NAT hay tường lửa mạng trường học.

Cài đặt trên Ubuntu Server:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

Kiểm tra trạng thái:

```bash
tailscale status
tailscale ip -4
```

Sau khi kết nối, các dịch vụ trên Ubuntu Server có thể truy cập qua địa chỉ Tailscale (dạng `100.x.x.x`) từ máy trạm Windows. Prometheus, Grafana và các endpoint kiểm tra đều sử dụng địa chỉ LAN của máy ảo (`172.30.35.37`) trong cấu hình nội bộ.

### A.6. Cài đặt và cấu hình hệ thống giám sát

Hệ thống giám sát được triển khai bằng Docker Compose riêng trong thư mục `monitoring/`.

#### Docker Compose giám sát
Dưới đây là phần cấu hình đã được lược gọn của file `monitoring/docker-compose.yml`, chỉ giữ lại các tham số quan trọng nhất:

```yaml
services:
  prometheus:
    image: prom/prometheus
    ports: ["9090:9090"]
    volumes: ["./prometheus.yml:/etc/prometheus/prometheus.yml"]

  grafana:
    image: grafana/grafana
    ports: ["3000:3000"]

  cadvisor:
    image: gcr.io/cadvisor/cadvisor:latest
    ports: ["8088:8080"]
    privileged: true
    volumes: 
      # ... (Ánh xạ các thư mục hệ thống /rootfs, /var/run, /sys để đọc metrics)
```

Khởi chạy monitoring stack:

```bash
cd monitoring/
docker compose up -d
```

#### Prometheus

File cấu hình: `monitoring/prometheus.yml`

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: "api-gateway-alb"
    metrics_path: "/actuator/prometheus"
    static_configs:
      - targets: ["172.30.35.37:8080"]

  - job_name: "registration-service-alb"
    metrics_path: "/actuator/prometheus"
    static_configs:
      - targets:
          - "172.30.35.37:8081"
          - "172.30.35.37:8082"
          - "172.30.35.37:8083"

  - job_name: "cadvisor"
    static_configs:
      - targets: ["cadvisor:8080"]
```

Kiểm tra Prometheus đã scrape được dữ liệu: truy cập `http://<server-ip>:9090/targets`.

#### cAdvisor

cAdvisor thu thập thông số CPU và Memory của từng container Docker, Prometheus scrape dữ liệu này qua job `cadvisor` ở port `8088→8080`.

#### Grafana

Truy cập Grafana tại `http://<server-ip>:3000` (mặc định `admin/admin`).

Datasource: Prometheus tại `http://prometheus:9090`.

Dashboard JSON: `monitoring/dashboard-grafana.json` — import vào Grafana để hiển thị các panel:

| Panel | Mục đích |
|---|---|
| Backend Health Score — MCDM and PID | Health score trước inflight adjustment |
| Dynamic MCDM criterion weights | Trọng số động α, β, γ |
| Adaptive Routing Weights — Health vs Load | Tỷ trọng health/load trong routing cost |
| Final Routing Cost by Backend | Cost cuối cùng dùng để chọn backend |
| Routing Selection Rate by Backend | Traffic thực tế vào từng backend |
| Routing Selection Rate by Decision Reason | Lý do chọn: warmup, low-load, probe, P2C |
| Gateway Latency Percentiles | p50/p90/p95/p99 |
| Gateway Throughput — Actual RPS | Throughput thực tế |

### A.7. Cấu hình Docker Compose chính

File: `docker-compose.yml` (thư mục gốc dự án)

Dưới đây là phần cấu hình đã được lược gọn của file `docker-compose.yml`, chỉ giữ lại thông số cấp phát tài nguyên và cấu trúc triển khai chính (các node backend tương tự nhau được gộp chung):

```yaml
version: '3.8'
networks:
  alb-network:
    driver: bridge

services:
  eureka-server:
    cpus: "0.5"
    mem_limit: 256m
    build: { context: ., dockerfile: eureka-server/Dockerfile }
    ports: ["8761:8761"]
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8761/actuator/health | grep UP || exit 1"]
      interval: 15s

  # Các Backend Registration (8081, 8082, 8083) có cấu trúc tương tự nhau:
  registration-8081:
    cpus: "2.0"
    mem_limit: 768m
    build: { context: ., dockerfile: registration-service-alb/Dockerfile }
    ports: ["8081:8081"]
    environment:
      - PORT=8081
      - EUREKA_URL=http://eureka-server:8761/eureka/
    depends_on:
      eureka-server: { condition: service_healthy }

  api-gateway-alb:
    cpus: "2.0"
    mem_limit: 1g
    build: { context: ., dockerfile: api-gateway-alb/Dockerfile }
    ports: ["8080:8080"]
    depends_on:
      eureka-server: { condition: service_healthy }
```

#### Dockerfile (áp dụng chung cho cả 3 module)

Cả 3 module đều sử dụng multi-stage build giống nhau:

```dockerfile
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/<module-name>/target/*.jar app.jar
EXPOSE <port>
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Trong đó `<module-name>` lần lượt là `eureka-server`, `api-gateway-alb`, `registration-service-alb`.

*(Bảng chi tiết thông số tài nguyên CPU, RAM và vai trò của từng container đã được trình bày đầy đủ ở Chương 4 nên được lược bỏ tại đây để tránh trùng lặp).*

---

## PHỤ LỤC B. CẤU HÌNH THỰC NGHIỆM VÀ KIỂM THỬ

### B.1. Cấu hình JMeter

Dự án sử dụng Apache JMeter 5.6.3 với plugin `jp@gc – Throughput Shaping Timer` để tạo tải theo RPS mục tiêu. Các Test Plan chính trong thư mục `jmeter/`:

| File JMX | Target RPS | Kịch bản |
|---|---:|---|
| `01_low_baseline_mixed_0300_nochaos_tst.jmx` | 300 | Low load, không chaos |
| `02_medium_dependency_slowdown_mixed_0600_tst.jmx` | 600 | Medium load + dependency slowdown |
| `03_high_dependency_slowdown_mixed_0900_staged_tst.jmx` | 900 | High load + dependency slowdown (staged) |
| `04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst.jmx` | 1200→600 | Stress/recovery |
| `04_stress_overload_graceful_degradation_mixed_1200_tst.jmx` | 1200 | Overload graceful degradation |

Workload profile `mixed` có tỷ lệ:

| Profile | Tỷ lệ | Đặc điểm |
|---|---:|---|
| `light` | 60% | CPU/RAM thấp |
| `medium` | 25% | CPU/RAM trung bình |
| `slow` | 12% | Request nặng |
| `very-slow` | 3% | Request đuôi dài |

Endpoint chính: `GET /api/simulate-mixed-call?profile=mixed`

Các Test Plan sử dụng nhãn `MEASURE_` cho sampler đo lường chính thức và `DISCARD_` cho warmup/ramp-down, giúp script xử lý kết quả lọc chính xác.

### B.2. Cấu hình Chaos Engineering

Backend cung cấp các endpoint kích hoạt chaos:

| Endpoint | Ý nghĩa |
|---|---|
| `POST /api/chaos/dependency-slowdown/medium` | Mô phỏng dependency chậm mức trung bình |
| `POST /api/chaos/dependency-slowdown/high` | Mô phỏng dependency chậm mức cao |
| `POST /api/chaos/latency-degradation/medium` | Tăng latency cục bộ mức vừa |
| `POST /api/chaos/latency-degradation/high` | Tăng latency cục bộ mức cao |
| `POST /api/chaos/reset` | Tắt toàn bộ chaos |
| `GET /api/chaos/status` | Kiểm tra trạng thái chaos |

Trong các kịch bản Medium Load và High Load, JMeter Test Plan tự động gọi chaos endpoint vào thời điểm định trước trong quá trình chạy benchmark.

### B.3. Quy trình chạy benchmark

Quy trình chuẩn cho mỗi lần chạy benchmark (được tự động hóa trong `_benchmark_common.bat`):

1. **Cập nhật strategy**: Script sửa `application.yml` (strategy và ablation variant), commit và push lên GitHub để CI/CD deploy.
2. **Chờ deploy**: Chờ 120 giây cho GitHub Actions self-hosted runner build và deploy container mới.
3. **Xác minh server**: Gọi `GET /actuator/alb/strategy` để xác nhận strategy và ablation variant đã được deploy đúng. Retry tối đa 18 lần × 10 giây.
4. **Reset trạng thái**: Gọi `POST /actuator/alb/reset` (reset PID, EWMA, SlidingWindow, MetricsCache, InflightTracker) và `POST /api/chaos/reset` trên tất cả backend.
5. **Chờ ổn định**: 20 giây.
6. **Chạy JMeter**: CLI mode, lưu kết quả `.jtl` và HTML report.
7. **Lưu metadata**: Ghi scenario, strategy, ablation, git commit, timestamp vào file metadata.
8. **Cooldown**: 180 giây giữa các lần chạy.

Thứ tự chiến lược được ngẫu nhiên hóa (Fisher-Yates shuffle) để giảm run-order bias. Mỗi chiến lược chạy 5 lần (hoặc 3 lần tùy kịch bản).

---

## PHỤ LỤC C. MÃ NGUỒN CÁC THÀNH PHẦN CỐT LÕI

### C.1. PID Controller

File: `api-gateway-alb/src/main/java/.../dataplane/PIDController.java`

Chức năng: Tính penalty cho backend dựa trên sai số latency so với P75 hệ thống. Bao gồm Error Deadband, Conditional Anti-Windup, Integral Decay và Low-Pass Filtered Derivative.

```java
public double calculatePenalty(String instanceId, double rawLat,
                                double setpoint, PidConfig cfg) {
    long now = System.currentTimeMillis();
    final double kp = cfg.getKp();
    final double ki = cfg.getKi();
    final double kd = cfg.getKd();
    final double tauD = cfg.getTauD();
    final double minI = cfg.getMinI();
    final double maxI = cfg.getMaxI();
    final double lambda = cfg.getLambda();
    final double kappa = cfg.getKappa();

    PidState finalState = states.asMap().compute(instanceId, (k, state) -> {
        if (state == null) {
            state = new PidState();
            state.setLastTimestamp(now - 200L);
            state.setLastRawLat(rawLat);
        }

        double actualDt = Math.min(5.0,
            Math.max(0.001, (now - state.getLastTimestamp()) / 1000.0));

        // Error với Deadband
        double error = rawLat - setpoint;
        if (Math.abs(error) <= ERROR_DEADBAND) {
            error = 0.0;
        } else if (error > 0.0) {
            error -= ERROR_DEADBAND;
        } else {
            error += ERROR_DEADBAND;
        }

        // P
        double p = kp * error;

        // I với Conditional Anti-Windup
        boolean isSaturated = Math.abs(state.getLastOutput()) >= 2.0;
        boolean sameSign = (error * state.getLastOutput()) > 0.0;
        double integral = state.getIntegral();
        if (!(isSaturated && sameSign)) {
            double newI = integral + (error * actualDt);
            if (Math.abs(error) < 0.1) {
                newI *= Math.exp(LN_0_97 * actualDt);  // Decay
            }
            integral = Math.max(minI, Math.min(maxI, newI));
            state.setIntegral(integral);
        }
        double i = ki * integral;

        // D với Low-Pass Filter
        double rawD = (rawLat - state.getLastRawLat()) / actualDt;
        double expTerm = Math.exp(-actualDt / tauD);
        double filteredD = (1.0 - expTerm) * rawD
                         + expTerm * state.getLastFilteredD();
        double d = kd * filteredD;

        double u = p + i + d;
        // Cập nhật state ...
        state.setLastRawLat(rawLat);
        state.setLastFilteredD(filteredD);
        state.setLastOutput(u);
        state.setLastTimestamp(now);
        return state;
    });

    return lambda * Math.tanh(kappa * Math.max(0.0,
        finalState.getLastOutput()));
}
```

### C.2. P2C Selection

File: `api-gateway-alb/src/main/java/.../dataplane/AdaptiveLoadBalancer.java`

Chức năng: Chọn 2 ứng viên ngẫu nhiên khác nhau, so sánh `finalCost` và chọn node tốt hơn.

```java
private RoutingCost chooseByP2C(List<RoutingCost> candidates) {
    int size = candidates.size();
    if (size == 1) return candidates.get(0);

    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    int firstIndex = rnd.nextInt(size);
    int secondIndex = rnd.nextInt(size - 1);
    if (secondIndex >= firstIndex) {
        secondIndex++;
    }

    RoutingCost a = candidates.get(firstIndex);
    RoutingCost b = candidates.get(secondIndex);
    return routingCostCalculator.better(a, b);
}
```

Luồng ra quyết định chính trong `selectInstance()`:

```java
private Response<ServiceInstance> selectInstance(List<ServiceInstance> instances) {
    // ... kiểm tra warmup ...
    RoutingContext ctx = routingCostCalculator.calculate(instances);

    if (allWarmup || "LOW_LOAD_RR".equals(ctx.mode())) {
        return new DefaultResponse(roundRobin(instances));
    }

    RoutingCost probe = maybeProbe(ctx, now);
    if (probe != null) { /* chọn node probe */ }

    List<RoutingCost> candidates = ctx.eligible().isEmpty()
        ? ctx.all() : ctx.eligible();
    RoutingCost selectedCost = isAblation("no-p2c")
        ? chooseLeastCost(candidates) : chooseByP2C(candidates);
    // ...
}
```

### C.3. Routing Cost Calculator

File: `api-gateway-alb/src/main/java/.../dataplane/RoutingCostCalculator.java`

Chức năng: Hợp nhất health score, load tức thời (đã chuẩn hóa theo capacity), stale penalty và absolute latency cost thành `finalCost`. Phương thức `calculate()` trả về `RoutingContext` chứa danh sách `RoutingCost` cho tất cả backend và danh sách `eligible` (sau khi loại hard-excluded).

```java
double finalCost = healthWeight * healthCost
    + loadWeight * loadCost
    + overloadPenalty
    + capPressurePenalty
    + absoluteHealthPenalty
    + absoluteLatencyPenalty
    + stalePenalty;
```

Phương thức so sánh hai node:

```java
public RoutingCost better(RoutingCost a, RoutingCost b) {
    if (a.finalCost() < b.finalCost()) return a;
    if (b.finalCost() < a.finalCost()) return b;
    if (a.loadCostRaw() < b.loadCostRaw()) return a;
    if (b.loadCostRaw() < a.loadCostRaw()) return b;
    return a.capacityWeight() >= b.capacityWeight() ? a : b;
}
```

### C.4. Dynamic Weight Engine (EWM + AHP)

File: `api-gateway-alb/src/main/java/.../controlplane/DynamicWeightEngine.java`

Chức năng: Cập nhật trọng số MCDM mỗi 5 giây. Chỉ cập nhật khi có đủ traffic nghiệp vụ thật (≥20 request, ≥5 RPS).

Trọng số AHP mặc định: `[0.648, 0.230, 0.122]` (latency, queue, CPU).

Phần tính Entropy Weight:

```java
private double[] calculateEntropyWeights(double[][] data, int n) {
    double[] diversity = new double[CRITERIA_COUNT];
    double sumDiversity = 0.0;
    double k = 1.0 / Math.log(n);

    for (int j = 0; j < CRITERIA_COUNT; j++) {
        double colSum = 0.0;
        for (int i = 0; i < n; i++) colSum += data[i][j];

        double entropySum = 0.0;
        for (int i = 0; i < n; i++) {
            double p = data[i][j] / Math.max(colSum, EPS);
            entropySum += p * Math.log(p);
        }

        double entropy = -k * entropySum;
        diversity[j] = Math.max(0.0, 1.0 - entropy);
        sumDiversity += diversity[j];
    }
    // ... chuẩn hóa diversity thành weights ...
}
```

### C.5. Adaptive EWMA Smoother

File: `api-gateway-alb/src/main/java/.../math/EwmaSmoother.java`

Chức năng: Làm mượt latency với τ tự điều chỉnh theo deviation. Khi latency đột biến → τ giảm về `tauMin` (phản ứng nhanh), khi ổn định → τ giữ ở `tauMax` (lọc nhiễu).

```java
public double smooth(String instanceId, double rawLatency,
        double tauMin, double tauMax, double k, double fallbackP50) {
    // ...
    double deviation = Math.abs(rawLatency - state.value)
                     / Math.max(state.value, 1.0);

    double kd = k * deviation;
    double adaptiveTau = (kd >= 6.0) ? tauMin
        : tauMin + tauRange * Math.exp(-kd);

    double ratio = (double) dtMs / adaptiveTau;
    double theta = (ratio >= 10.0) ? 1.0 : (1.0 - Math.exp(-ratio));

    double smoothed = theta * rawLatency + (1.0 - theta) * state.value;
    // ...
}
```

### C.6. Score Calculator

File: `api-gateway-alb/src/main/java/.../dataplane/ScoreCalculator.java`

Chức năng: Pipeline 7 bước: (1) lấy percentile snapshot, (2) EWMA smoothing, (3) lấy system-wide snapshot, (4) chuẩn hóa 3 tiêu chí `[0,1]`, (5) tính MCDM baseScore, (6) tính PID penalty, (7) tổng hợp `finalScore = baseScore + penalty`.

```java
double nL = norm.normalizeLatency(ewmaLat, sysP5, invRange);
double nQ = norm.normalizeQueue(current.getQueueLength(), snap.qP99());
double nC = norm.normalizeCpu(current.getCpu());

double baseScore = weightEngine.computeBaseScore(nL, nQ, nC);
double normalizedP75 = norm.normalizeLatency(sysSs.p75(), sysP5, invRange);
double penalty = pidController.calculatePenalty(instanceId, nL,
    normalizedP75, props.getPid());
double finalScore = baseScore + penalty;
```

---

## PHỤ LỤC D. XỬ LÝ DỮ LIỆU VÀ TỰ ĐỘNG HÓA THỰC NGHIỆM

### D.1. Script chạy benchmark

Hệ thống benchmark sử dụng cấu trúc modular:

- `_benchmark_common.bat`: Script lõi xử lý deploy, verify, reset, chạy JMeter và lưu metadata.
- Các script kịch bản gọi `_benchmark_common.bat` với tham số:

| Script | Kịch bản | Chế độ |
|---|---|---|
| `1-run_low_all_strategies.bat` | Low 300 RPS | 4 strategies |
| `2-run_medium_chaos_all_strategies.bat` | Medium 600 RPS + chaos | 4 strategies |
| `3-run_high_all_strategies.bat` | High 900 RPS + chaos | 4 strategies |
| `4-run_stress-test_all_strategies.bat` | Stress 1200→600 RPS | 4 strategies |
| `5-run_adaptive_ablation_medium.bat` | Medium 600 RPS | 8 ablation variants |

Cấu hình mặc định trong `_benchmark_common.bat`:

```bat
JMETER_HOME=D:\Downloads\apache-jmeter-5.6.3
SERVER_BASE_URL=http://172.30.35.37:8080
BACKEND_BASE_URL=http://172.30.35.37
BACKEND_PORTS=8081 8082 8083
RUNS_PER_ITEM=5
WAIT_AFTER_PUSH=120
WAIT_BETWEEN_RUNS=180
WAIT_AFTER_RESET=20
RANDOMIZE_ORDER=true
STRICT_SERVER_STRATEGY_CHECK=true
```

### D.2. Script xử lý JTL/CSV

File: `scripts_run_jmeter/summarize_jtl_results.py`

Script Python sử dụng thư viện chuẩn (không cần cài thêm). Chức năng:

- Đọc file `.jtl` (CSV format) từ JMeter.
- Lọc chỉ giữ sampler có nhãn bắt đầu bằng `MEASURE_` (loại bỏ warmup, ramp-down, setup).
- Tính: avg, p50, p90, p95, p99, max latency; throughput (RPS); error rate.
- Tổng hợp kết quả nhiều lần chạy (mean, standard deviation).
- Xuất file `jtl-summary.csv`.

Cách chạy:

```bash
python summarize_jtl_results.py <thư-mục-chứa-jtl> --output summary.csv
```

### D.3. Pipeline Ablation Study

File: `5-run_adaptive_ablation_medium.bat` → gọi `_benchmark_common.bat` với mode `ablation`.

8 biến thể Ablation được cấu hình trong `_benchmark_common.bat`:

```bat
ITEM_1=adaptive,adaptive_no_pid,no-pid
ITEM_2=adaptive,adaptive_fixed_weights,fixed-weights
ITEM_3=adaptive,adaptive_no_ewma_latency,no-ewma-latency
ITEM_4=adaptive,adaptive_no_score_ema,no-score-ema
ITEM_5=adaptive,adaptive_no_capacity,no-capacity
ITEM_6=adaptive,adaptive_no_p2c,no-p2c
ITEM_7=adaptive,adaptive_no_probe,no-probe
ITEM_8=adaptive,adaptive_no_low_load_rr,no-low-load-rr
```

Sau khi chạy, kết quả được xử lý bằng:

- `process_adaptive_ablation_results.py`: Tính relative difference so với `adaptive_full`.
- `generate_ablation_visualizations.py`: Sinh biểu đồ so sánh (Matplotlib + Seaborn).

### D.4. Script sinh biểu đồ

Dự án có 3 script sinh biểu đồ Python:

| Script | Vị trí | Chức năng |
|---|---|---|
| `generate_ablation_visualizations.py` | `docs/benchmark-results/05_.../scripts/` | Biểu đồ Ablation Study |
| `generate_stress_test_visualizations.py` | `docs/benchmark-results/additional-tests/.../scripts/` | Biểu đồ Stress Test |
| `generate_benchmark_visualizations.py` | `docs/benchmark-results/official-runs/.../scripts/` | Biểu đồ benchmark chính thức |

Tất cả sử dụng Matplotlib và Seaborn, xuất file PNG và SVG 300 DPI.

### D.5. CI/CD Deployment

File: `.github/workflows/deploy.yml`

Workflow GitHub Actions self-hosted runner:

- Kiểm tra YAML indentation.
- Validate `docker compose config`.
- Phát hiện module thay đổi để build chọn lọc.
- Build và deploy container trên máy chủ Ubuntu.
- Cleanup instance cũ trong Eureka.

Script benchmark tận dụng CI/CD pipeline này để tự động đổi strategy bằng cách commit `application.yml` đã sửa và push, sau đó chờ deploy hoàn tất trước khi chạy JMeter.
