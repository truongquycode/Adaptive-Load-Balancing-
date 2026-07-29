# PHỤ LỤC

## PHỤ LỤC A. CẤU HÌNH VÀ TRIỂN KHAI HỆ THỐNG

### A.1. Cấu trúc dự án
Dự án được tổ chức theo kiến trúc Microservices sử dụng Maven đa module, bao gồm các thành phần chính sau [XÁC MINH TỪ SOURCE CODE]:
- `api-gateway-alb/`: Tầng giao tiếp và định tuyến, chứa mã nguồn thuật toán Adaptive Load Balancer, PID, MCDM, EWMA.
- `eureka-server/`: Service Registry phục vụ khám phá dịch vụ.
- `registration-service-alb/`: Backend service chứa logic nghiệp vụ mô phỏng và module tiêm lỗi (Chaos Engineering).
- `monitoring/`: Cấu hình hệ thống giám sát (Prometheus, Grafana, cAdvisor).

### A.2. Cấu hình Spring Cloud Gateway
Gateway được cấu hình với cơ chế timeout và luồng định tuyến tự động. Dưới đây là cấu hình cốt lõi trích xuất từ `api-gateway-alb/src/main/resources/application.yml` [XÁC MINH TỪ SOURCE CODE]:

```yaml
alb:
    strategy: adaptive
    polling:
        interval: 200
        metrics-timeout-ms: 800
    ewma:
        tau-min: 200.0 
        tau-max: 2000.0 
        k: 3.0 
    pid:
        kp: 1.0 
        ki: 0.08 
        kd: 0.04 
        lambda: 0.8 
```

### A.3. Cấu hình Eureka Server
Eureka Server đóng vai trò quản lý danh sách các node. Được cấu hình với tài nguyên giới hạn trong Docker [XÁC MINH TỪ SOURCE CODE]:
```yaml
  eureka-server:
    cpus: "0.5"
    mem_limit: 256m
    environment:
      - EUREKA_HOSTNAME=eureka-server
    ports:
      - "8761:8761"
```

### A.4. Cấu hình Registration Service
Registration Service được mở rộng luồng xử lý Tomcat để có khả năng chịu tải tốt hơn trong các kịch bản Stress Test [XÁC MINH TỪ SOURCE CODE]:
```yaml
server:
  tomcat:
    threads:
      max: 500        # Mở rộng trần luồng xử lý để gánh bão 500 users
      min-spare: 50   # Luôn giữ sẵn 50 luồng chờ
    accept-count: 200
```

### A.5. Cấu hình Docker và A.6. Cấu hình Docker Compose
Tính không đồng nhất của hệ thống được giả lập chặt chẽ thông qua tính năng CPU Quota của Docker [XÁC MINH TỪ SOURCE CODE]:
```yaml
  registration-8081:
    cpus: "2.0"
    mem_limit: 768m
    ports:
      - "8081:8081"

  registration-8082:
    cpus: "1.5"
    mem_limit: 512m
    ports:
      - "8082:8082"

  registration-8083:
    cpus: "1.0"
    mem_limit: 384m
    ports:
      - "8083:8083"
```

### A.7. Cấu hình tài nguyên Backend và A.8. Quy trình triển khai trên Ubuntu Server
[THEO LUẬN VĂN] Môi trường chạy Docker Compose được thiết lập trên máy ảo Ubuntu Server 26.04 với cấu hình 8 vCPU và 11 GiB RAM. Quá trình triển khai thực tế yêu cầu cài đặt Docker Engine và khởi chạy toàn cụm thông qua lệnh `docker-compose up -d`.

### A.9. Kết nối mạng bằng Tailscale
[THEO LUẬN VĂN] Việc kết nối giữa máy trạm điều khiển (chạy JMeter) và máy ảo Ubuntu (chạy hệ thống) được thực hiện thông qua mạng riêng ảo Tailscale để tránh nhiễu do mạng công cộng:
- Máy trạm (Windows): `100.84.45.114`
- Máy ảo Ubuntu: `100.72.219.55`


## PHỤ LỤC B. CẤU HÌNH HỆ THỐNG GIÁM SÁT

### B.1. Prometheus
Cấu hình thu thập số liệu (scraping) định kỳ 5s từ Gateway và các Backend [XÁC MINH TỪ SOURCE CODE]:
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
```

### B.2. cAdvisor
[XÁC MINH TỪ SOURCE CODE] Prometheus thu thập thông số tài nguyên vật lý của các Container thông qua cAdvisor:
```yaml
  - job_name: "cadvisor"
    static_configs:
      - targets: ["cadvisor:8080"]
```

### B.3, B.4, B.5, B.6. Grafana và Các Metric chính
[XÁC MINH TỪ SOURCE CODE] Datasource của Grafana trỏ trực tiếp đến Prometheus. File cấu hình `monitoring/dashboard-grafana.json` chứa cấu trúc các bảng điều khiển trực quan hóa các metrics quan trọng:
- `alb.mcdm.weight`: Trọng số động (α, β, γ) của MCDM.
- `alb.routing.selected`: Thông tin chọn máy chủ từ thuật toán định tuyến.


## PHỤ LỤC C. MÃ NGUỒN CÁC THÀNH PHẦN CỐT LÕI

Dưới đây là mã nguồn của các thuật toán quan trọng nhất [XÁC MINH TỪ SOURCE CODE].

### C.1. Adaptive Load Balancer và C.3. P2C (Power of Two Choices)
Vị trí: `api-gateway-alb/.../dataplane/AdaptiveLoadBalancer.java`
Thuật toán chọn máy chủ nhanh chóng và tránh hiệu ứng bầy đàn:
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

### C.4. PID Controller
Vị trí: `api-gateway-alb/.../dataplane/PIDController.java`
Phạt các node có độ trễ cao dựa trên sai số so với phân vị kỳ vọng:
```java
// Logic tính toán thành phần PID
double error = rawLat - setpoint; 
if (Math.abs(error) <= ERROR_DEADBAND) { 
    error = 0.0; 
} else if (error > 0.0) { 
    error -= ERROR_DEADBAND; 
} 
double p = kp * error; 
// Anti-Windup for Integral
if (!(isSaturated && sameSign)) { 
    double newI = prevIntegral + (error * actualDt); 
    // ...
}
// ... Tính D filter ...
double u = p + i + d; 
return lambda * Math.tanh(kappa * Math.max(0.0, u)); 
```

### C.5. Dynamic Weight Engine (EWM và AHP)
Vị trí: `api-gateway-alb/.../controlplane/DynamicWeightEngine.java`
Cập nhật trọng số MCDM động dựa trên độ phân tán dữ liệu:
```java
    private double[] calculateEntropyWeights(double[][] data, int n) {
        // ...
        double entropySum = 0.0;
        for (int i = 0; i < n; i++) {
            double p = data[i][j] / Math.max(colSum, EPS);
            entropySum += p * Math.log(p);
        }
        double entropy = -k * entropySum;
        diversity[j] = Math.max(0.0, 1.0 - entropy);
        // ...
    }
```

### C.7. Probe / Recovery
Vị trí: `api-gateway-alb/.../dataplane/AdaptiveLoadBalancer.java`
Kiểm tra ngẫu nhiên các node đang bị cách ly:
```java
    private RoutingCost maybeProbe(RoutingContext ctx, long now) {
        // ...
        for (RoutingCost cost : ordered) {
            if (!cost.hardExcluded() || "HARD_INFLIGHT_CAP".equals(cost.reason())) continue;
            long last = lastSelectedMs.getOrDefault(cost.instanceId(), 0L);
            if ((now - last) >= cfg.getProbeIntervalMs()
                    && ThreadLocalRandom.current().nextDouble() < cfg.getProbeProbability()) {
                return cost;
            }
        }
        return null;
    }
```


## PHỤ LỤC D. CẤU HÌNH VÀ QUY TRÌNH THỰC NGHIỆM

[THEO LUẬN VĂN] Quá trình kiểm thử bám sát 4 kịch bản tạo tải từ JMeter. 

### D.1. Cấu hình mức tải trong JMeter
- **Low Load:** 300 RPS, kiểm tra hệ thống tĩnh.
- **Medium Load:** 600 RPS kết hợp Chaos Engineering tiêm lỗi chậm dịch vụ (120s - 300s).
- **High Load:** 900 RPS kết hợp Chaos Engineering tiêm lỗi mức cao (180s - 360s).
- **Stress Test:** 1200 RPS trong 10 phút, vượt ngưỡng bão hòa để quan sát rớt tải.
- **Ablation Study:** Tại 600 RPS, vô hiệu hóa lần lượt các thành phần P2C, PID, EWMA để đối chiếu.

### D.8. Quy trình xử lý dữ liệu
[THEO LUẬN VĂN] Dữ liệu từ các file JTL của JMeter được xử lý qua Script Python. Lọc bỏ giai đoạn ramp-up/teardown. Kết quả được nội suy các giá trị P50, P95, P99 và Estimated Goodput, ghi xuất thành báo cáo dạng `aggregate_results.csv`.


## PHỤ LỤC E. SCRIPT VÀ TỰ ĐỘNG HÓA

[THEO LUẬN VĂN] Để hệ thống chạy tuần tự và công bằng qua 4 thuật toán, các chuỗi lệnh shell được sử dụng (ví dụ: `1-run_low_all_strategies.bat`). Workflow tổng thể của một script kiểm thử:
1. `docker-compose restart`
2. API call để reset nội bộ trạng thái các thuật toán (`/alb/reset`).
3. Chờ cooldown (30-60 giây) cho các services ổn định.
4. Gọi JMeter CLI thực thi file `.jmx`.
5. Lưu kết quả ra file `JTL` và HTML report.
