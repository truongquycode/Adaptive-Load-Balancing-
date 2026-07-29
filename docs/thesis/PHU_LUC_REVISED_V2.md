# PHỤ LỤC

## PHỤ LỤC A. CÀI ĐẶT VÀ CẤU HÌNH HỆ THỐNG

Phụ lục này trình bày cấu hình thực tế dùng để cài đặt và triển khai hệ thống Adaptive Load Balancer (ALB). Các kiến trúc tổng thể đã được trình bày trong Chương 3.

### A.1. Cấu hình môi trường triển khai
Hệ thống được vận hành trên máy chủ Ubuntu Server với các công cụ nền tảng:
- **Java 21 (Eclipse Temurin)** & **Maven 3.9.6**: Tích hợp sẵn trong Docker image đa bước (multi-stage build), không cài đặt trực tiếp trên host.
- **Docker Engine & Docker Compose**: Quản lý vòng đời của toàn bộ cụm vi dịch vụ.
- **Git**: Quản lý mã nguồn và hỗ trợ tự động hóa triển khai (CI/CD) qua GitHub Actions runner.
- **Tailscale**: Cung cấp mạng riêng ảo (VPN mesh) để kết nối ổn định giữa máy trạm (JMeter) và máy chủ Ubuntu.
- **Prometheus & Grafana**: Thu thập và trực quan hóa các chỉ số hoạt động (metrics) của hệ thống.

### A.2. Cài đặt Docker trên Ubuntu Server và khởi chạy
Chi tiết cấu hình tài nguyên phần cứng ảo hóa (CPU, RAM) nhằm mô phỏng cụm backend không đồng nhất đã được trình bày chi tiết tại Chương 4. Dưới đây là các lệnh thiết lập môi trường Docker dành riêng cho máy chủ Ubuntu Server và khởi chạy hệ thống:

**1. Cài đặt Docker Engine và Docker Compose trên Ubuntu Server:**
```bash
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER
```

**2. Khởi chạy toàn bộ 5 container được định nghĩa thông qua file `docker-compose.yml`:**
```bash
docker compose up -d --build
```

### A.3. Cài đặt và cấu hình Spring Cloud Gateway

**1. Khai báo thư viện trong `pom.xml`:**
Để tích hợp Spring Cloud Gateway vào dự án, các dependencies sau được khai báo trong `api-gateway-alb/pom.xml`:

```xml
<dependencies>
    <!-- Core Spring Cloud Gateway -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-gateway</artifactId>
    </dependency>
    <!-- Tích hợp Service Discovery -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
    <!-- Spring Cloud LoadBalancer base -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-loadbalancer</artifactId>
    </dependency>
</dependencies>
```

**2. Cấu hình tham số thuật toán trong `application.yml`:**
Dưới đây là các tham số thuật toán cốt lõi được cấu hình thực tế cho chức năng Cân bằng tải thích nghi (Adaptive):

| Nhóm | Tham số | Giá trị | Ý nghĩa |
|---|---|---|---|
| **EWMA** | `tau-min`, `tau-max`, `k` | 200.0, 2000.0, 3.0 | Tham số làm mượt latency thích nghi |
| **PID** | `kp`, `ki`, `kd` | 1.0, 0.08, 0.04 | Hệ số điều khiển P-I-D |
| **PID** | `min-i`, `max-i` | -0.8, 2.5 | Giới hạn chống tích lũy lỗi (Anti-windup) |
| **MCDM** | `update-interval` | 5000 | Chu kỳ cập nhật trọng số (ms) |
| **MCDM** | `blend-factor` | 0.70 | Tỷ lệ pha trộn 70% EWM và 30% AHP |
| **MCDM** | `min-completed-requests` | 20 | Số request tối thiểu để học EWM |
| **Routing** | `hard-inflight-cap` | 220 | Giới hạn cứng số request đang xử lý (inflight) |
| **Routing** | `absolute-latency-target-ms` | 300.0 | Ngưỡng SLA latency mục tiêu |

### A.4. Cài đặt và cấu hình Eureka Server và Registration Service

**1. Khai báo thư viện trong `pom.xml`:**
- **Eureka Server:**
  - `spring-cloud-starter-netflix-eureka-server`: Kích hoạt máy chủ khám phá dịch vụ độc lập.
- **Registration Service (Backend):**
  - `spring-cloud-starter-netflix-eureka-client`: Cho phép ứng dụng tự động đăng ký với Eureka Server.
  - `spring-boot-starter-actuator`: Cung cấp các endpoint nội bộ để theo dõi trạng thái hệ thống (health, metrics).
  - `micrometer-registry-prometheus`: Định dạng lại số liệu do Actuator thu thập (CPU, latency) sang chuẩn tương thích với Prometheus để hệ thống giám sát dễ dàng trích xuất.

**2. Cấu hình cốt lõi trong `application.yml`:**
- **Eureka Server:**
  - Chạy ở cổng cố định: `port: 8761`.
  - Không tự đăng ký chính nó vào mạng lưới: `register-with-eureka: false`.
  - Không tải danh sách các dịch vụ từ các node Eureka khác (do hoạt động ở chế độ độc lập): `fetch-registry: false`.
- **Registration Service (Backend):**
  - Định danh động để chạy đa bản sao: `instance-id: ${spring.application.name}:${PORT}`.
  - Tối ưu Tomcat chịu tải cao: `tomcat.threads.max: 500` và `accept-count: 200`.
  - Mở cổng giám sát metrics: `management.endpoints.web.exposure.include: "health,info,prometheus,metrics"`.

### A.5. Tailscale
Để đảm bảo kết nối mạng riêng ảo ổn định giữa máy trạm JMeter và máy chủ Ubuntu (vượt tường lửa/NAT từ mạng trường học), hệ thống dùng Tailscale mesh VPN. API Gateway và các dịch vụ giao tiếp qua dải IP LAN nội bộ của Tailscale (`100.x.x.x`).

### A.6. Hệ thống giám sát
Hệ thống sử dụng Prometheus và Grafana qua file `monitoring/docker-compose.yml`:
- **Prometheus** (Port 9090): Định kỳ quét (scrape) số liệu Actuator từ Gateway và 3 backend mỗi 5 giây (`scrape_interval: 5s`).
- **Grafana** (Port 3000): Hiển thị dashboard biểu đồ cho các chỉ số thuật toán ALB, throughput, lỗi.
- **cAdvisor** (Port 8080): Thu thập thông số CPU/RAM sử dụng thực tế từ Docker daemon.


## PHỤ LỤC B. CẤU HÌNH THỰC NGHIỆM

Phần này bổ sung thông số thực nghiệm hệ thống. Phân tích kết quả chi tiết đã được trình bày ở Chương 4.

### B.1. JMeter
Dự án sử dụng JMeter 5.6.3 với plugin *Throughput Shaping Timer* để điều tiết tải chính xác. Danh sách chi tiết các kịch bản thực nghiệm (Low, Medium, High, Stress) cùng mục tiêu tải tương ứng đã được liệt kê và phân tích đầy đủ tại Chương 4.

Các Test Plan (`.jmx`) được cấu hình trong thư mục `jmeter/`. *Quy ước thu thập dữ liệu*: Mọi request đo lường đều có nhãn bắt đầu bằng `MEASURE_`. Các request mồi (warmup) dùng nhãn `DISCARD_` để hệ thống script tự động loại bỏ khi thống kê kết quả.

### B.2. Chaos Engineering
Các backend cung cấp API giả lập sự cố (Chaos) để kiểm thử sức chịu đựng của thuật toán trong môi trường khắc nghiệt:
- Tăng độ trễ ảo cho Dependency: `POST /api/chaos/dependency-slowdown/medium` (hoặc `high`)
- Gây suy thoái cục bộ phần cứng ảo: `POST /api/chaos/latency-degradation/medium`
- Khôi phục trạng thái bình thường: `POST /api/chaos/reset`

### B.3. Quy trình benchmark
Quá trình chạy benchmark được tự động hóa nghiêm ngặt bằng script:
1. **Deploy**: Chỉnh sửa cấu hình đổi chiến lược và tự động đẩy lên Git.
2. **Verify**: Kiểm tra liên tục `GET /actuator/alb/strategy` đến khi cấu hình mới phản hồi.
3. **Reset State**: Gọi `/actuator/alb/reset` để xóa sạch trạng thái PID, EWMA, MCDM.
4. **Execute**: Chạy JMeter ở CLI mode (`-n -t`), xuất file báo cáo JTL.


## PHỤ LỤC C. MÃ NGUỒN CỐT LÕI

Dưới đây là các đoạn mã cốt lõi thực thi các thuật toán đã mô tả lý thuyết ở Chương 2 và Chương 3.

### C.1. PID Controller
Đoạn mã tính điểm phạt (`penalty`) có tích hợp Deadband và Anti-windup (`PIDController.java`):
```java
// Tính Error có Deadband
double error = rawLat - setpoint;
if (Math.abs(error) <= ERROR_DEADBAND) error = 0.0;
else error += (error > 0) ? -ERROR_DEADBAND : ERROR_DEADBAND;

// Tích phân (I) với Anti-Windup
boolean isSaturated = Math.abs(prevOutput) >= 2.0;
boolean sameSign = (error * prevOutput) > 0.0;
if (!(isSaturated && sameSign)) {
    double newI = prevIntegral + (error * actualDt);
    if (Math.abs(error) < 0.1) newI *= Math.exp(LN_0_97 * actualDt); // Decay
    integral = Math.max(minI, Math.min(maxI, newI));
}

// Tính tổng u và giới hạn (Tanh squashing)
double u = (kp * error) + (ki * integral) + (kd * filteredD);
return lambda * Math.tanh(kappa * Math.max(0.0, u));
```

### C.2. Thuật toán P2C (Power of Two Choices)
Logic chọn ngẫu nhiên 2 node và chọn node có `RoutingCost` tối ưu hơn (`AdaptiveLoadBalancer.java`):
```java
private RoutingCost chooseByP2C(List<RoutingCost> candidates) {
    int size = candidates.size();
    if (size == 1) return candidates.get(0);

    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    int firstIndex = rnd.nextInt(size);
    int secondIndex = rnd.nextInt(size - 1);
    if (secondIndex >= firstIndex) secondIndex++;

    RoutingCost a = candidates.get(firstIndex);
    RoutingCost b = candidates.get(secondIndex);
    return routingCostCalculator.better(a, b); // So sánh finalCost
}
```

### C.3. Tính toán Score và Routing Cost
Hợp nhất MCDM Base Score và PID Penalty (`ScoreCalculator.java`):
```java
double baseScore = weightEngine.computeBaseScore(nL, nQ, nC);
double penalty = pidController.calculatePenalty(instanceId, nL, normP75, props.getPid());
double finalScore = baseScore + penalty;
```
Tính tổng chi phí định tuyến cuối cùng (`RoutingCostCalculator.java`):
```java
double finalCost = (healthWeight * healthCost) + (loadWeight * loadCost)
                 + overloadPenalty + capPressurePenalty
                 + absoluteHealthPenalty + absoluteLatencyPenalty + stalePenalty;
```

### C.4. Dynamic Weight (EWM)
Tính toán Entropy để cập nhật bộ trọng số tự động (`DynamicWeightEngine.java`):
```java
for (int j = 0; j < CRITERIA_COUNT; j++) {
    double entropySum = 0.0;
    for (int i = 0; i < n; i++) {
        double p = data[i][j] / Math.max(colSum, EPS);
        entropySum += p * Math.log(p);
    }
    double entropy = -k * entropySum;
    diversity[j] = Math.max(0.0, 1.0 - entropy);
}
// diversity[] sau đó được chuẩn hóa và trộn với AHP prior để ra weights.
```

### C.5. Metrics Polling
Lọc bỏ các tín hiệu nhiễu, chỉ cập nhật độ trễ khi có traffic thật (`MetricsPoller.java`):
```java
double deltaCount = currentCount - prev.count();
if (deltaCount > 0 && deltaTotal >= 0) {
    // Có request thực sự hoàn thành -> ghi nhận mẫu latency thật
    currentLatency = (deltaTotal / deltaCount) * 1000.0;
    windowManager.addMetrics(instanceId, currentLatency, realQueue);
} else {
    // Idle -> dùng idle decay để làm dịu, KHÔNG đưa vào histogram
    currentLatency = prev.lastLatency() + idleAlpha * (idleTarget - prev.lastLatency());
}
```


## PHỤ LỤC D. TỰ ĐỘNG HÓA VÀ XỬ LÝ DỮ LIỆU

### D.1. Script Benchmark
Sử dụng Batch script (`_benchmark_common.bat`) để đảm bảo quy trình chạy benchmark không bị sai lệch do yếu tố con người. File tự động thay đổi cấu hình, gửi lệnh Git để kích hoạt pipeline, và chờ server thiết lập ổn định trước khi bắn tải.

### D.2. Script xử lý dữ liệu (JTL)
File `summarize_jtl_results.py` phân tích kết quả thô, có nhiệm vụ:
- Lọc bỏ các request khởi động/kết thúc (chỉ giữ nhãn `MEASURE_`).
- Tính toán P50, P90, P95, P99, Throughput (RPS), và Tỷ lệ lỗi (Error Rate) chính xác.
- Tính trung bình (`mean`) và độ lệch chuẩn của nhiều lượt chạy lặp lại để loại nhiễu cục bộ.

### D.3. Script sinh biểu đồ
Sử dụng mã Python với thư viện `Matplotlib` và `Seaborn` (vd: `generate_benchmark_visualizations.py`) để sinh tự động hàng loạt biểu đồ:
- Biểu đồ hộp (Boxplot) thể hiện độ phân tán và độ ổn định của hệ thống.
- Biểu đồ Bar cho độ trễ phân vị cao (P95/P99).
- Biểu đồ Line cho xu hướng lỗi và thông lượng.

### D.4. CI/CD Pipeline
Dự án được tích hợp luồng GitHub Actions (`.github/workflows/deploy.yml`):
- Nhận diện khi mã nguồn hoặc cấu hình thuật toán thay đổi.
- Tự động đóng gói lại ứng dụng bằng Maven.
- Triển khai (restart) lại các Docker container trên máy chủ Ubuntu hoàn toàn tự động.
