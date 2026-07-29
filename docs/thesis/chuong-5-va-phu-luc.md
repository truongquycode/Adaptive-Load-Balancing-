# CHƯƠNG 5: KẾT LUẬN VÀ HƯỚNG PHÁT TRIỂN

## 5.1. Kết quả đạt được

Nghiên cứu đã thiết kế và triển khai cơ chế cân bằng tải thích nghi (Adaptive Load Balancing) giải quyết bài toán phân bổ lưu lượng không đồng đều và hiện tượng trễ đuôi (Tail Latency) tại tầng API Gateway cho kiến trúc Microservices.

### 5.1.1. Kết quả về thiết kế và thuật toán
Luận văn đã phân định thành công Control Plane và Data Plane bên trong Spring Cloud Gateway, đảm bảo tính toán điểm số sức khỏe (Health Score) được xử lý độc lập mà không gây nghẽn luồng sự kiện I/O. Cơ chế tích hợp các thuật toán tối ưu:
*   Mô hình đánh giá đa tiêu chí (MCDM) với trọng số dự phòng (AHP) và phương pháp Entropy (EWM) điều chỉnh theo phân tán thực tế.
*   Bộ lọc trung bình trượt hàm mũ (EWMA) loại bỏ nhiễu tín hiệu.
*   Bộ điều khiển vi tích phân tỷ lệ (PID) đóng vai trò hàm phạt các máy chủ phản hồi chậm.
*   Thuật toán Sức mạnh của hai sự lựa chọn (P2C) tại Data Plane nhằm giải quyết hiệu ứng bầy đàn (Thundering Herd).

### 5.1.2. Kết quả thực nghiệm và đánh giá
Hệ thống được thử nghiệm qua các kịch bản chịu tải, môi trường bất đồng nhất và lỗi mạng (Chaos Engineering). Đáng chú ý, ở kịch bản Stress Test (tải mục tiêu 1200 RPS), Adaptive Load Balancer đạt hiệu suất:
*   Độ trễ phân vị P99 là 3890.9 ms, giảm 61.7% so với Least Connections (10177.4 ms).
*   Tỷ lệ lỗi (Error Rate) duy trì ở mức rất thấp (0.06%), trong khi Round Robin (43.27%) và Random (27.71%) bị ảnh hưởng nặng nề.
*   Thông lượng hữu ích (Estimated Goodput) đạt đỉnh 763.5 RPS.

Bên cạnh đó, phân tích cắt bỏ (Ablation Study) ở tải 600 RPS cung cấp bằng chứng định lượng vững chắc:
*   Loại bỏ PID Controller khiến độ trễ P99 tăng 17.79%, chứng tỏ vai trò then chốt của PID trong việc kiểm soát trễ đuôi.
*   Thiếu vắng tính năng Probe (Thăm dò phục hồi) khiến P95 tăng 26.32%.
*   Việc loại bỏ P2C khiến tỷ lệ lỗi tăng cao nhất trong các cấu hình thử nghiệm.

## 5.2. Hạn chế
*   **Điều kiện thực nghiệm:** Thực nghiệm hiện tại tập trung vào quy mô cụm 3 instance Backend nội bộ. Mô hình chưa được kiểm chứng trong điều kiện mạng diện rộng (WAN) hoặc cụm quy mô rất lớn. 
*   **Phạm vi giao thức:** Cơ chế được thiết kế chuyên biệt cho giao thức HTTP/REST phi trạng thái, chưa xem xét các giao thức duy trì kết nối (WebSockets, gRPC).
*   **Chi phí tính toán:** Dù Data Plane đã tối ưu nhờ P2C, quá trình tính toán MCDM tại Control Plane vẫn tiêu tốn tài nguyên. Chưa có đo lường Overhead CPU độc lập do hạn chế công cụ CPU Profiling trực tiếp tại API Gateway.

## 5.3. Hướng phát triển
Từ các hạn chế trên, nghiên cứu đề xuất 3 hướng phát triển:
1.  **Tối ưu tham số động:** Nghiên cứu áp dụng Học máy (Machine Learning) để nhận diện mẫu tải và tự động điều chỉnh các hằng số (Kp, Ki, Kd, Polling Interval) thay vì gán tĩnh.
2.  **Mở rộng môi trường triển khai:** Tích hợp và đánh giá giải pháp trên nền tảng điều phối Container (như Kubernetes) và môi trường Service Mesh (ví dụ đóng gói thành mô-đun Envoy Proxy).
3.  **Hỗ trợ đa giao thức:** Mở rộng thuật toán cho các giao thức duy trì kết nối dài hạn như gRPC nhằm tăng tính ứng dụng trong các hệ thống hiện đại.

---
\pagebreak

# PHỤ LỤC

## PHỤ LỤC A. CẤU HÌNH VÀ KIẾN TRÚC HỆ THỐNG

### A.1 Cấu hình Giới hạn Tài nguyên (Docker Compose)
Dự án giả lập môi trường bất đồng nhất bằng cách thiết lập quota `cpus` tại Docker.
*Nguồn: `docker-compose.yml`*
```yaml
services:
  registration-8081:
    cpus: "2.0"
    mem_limit: 768m
    # ...
  registration-8082:
    cpus: "1.5"
    mem_limit: 512m
    # ...
  registration-8083:
    cpus: "1.0"
    mem_limit: 384m
    # ...
```

### A.2 Cấu hình Tham số Cân bằng tải (API Gateway)
Định nghĩa các tham số cốt lõi cho Control Plane (PID, Routing, Polling).
*Nguồn: `api-gateway-alb/src/main/resources/application.yml`*
```yaml
alb:
    strategy: adaptive
    polling:
        interval: 200
        metrics-timeout-ms: 800
    pid:
        kp: 1.0 
        ki: 0.08 
        kd: 0.04 
        tau-d: 2.0
        min-i: -0.8 
        max-i: 2.5 
        lambda: 0.8 
        kappa: 1.2 
    routing:
        warmup-ms: 5000
        hard-inflight-cap: 220
        probe-interval-ms: 3000
```

### A.3 Cấu hình Giám sát và Đo lường (Monitoring)
Hệ thống sử dụng Prometheus và Grafana để thu thập, trực quan hóa Metric theo thời gian thực (real-time). Prometheus scrape dữ liệu từ Gateway qua endpoint Actuator và thu thập Metrics cAdvisor cho giám sát tài nguyên Docker.
*Nguồn: `monitoring/docker-compose.yml` và `monitoring/prometheus.yml`*
```yaml
# Prometheus Configuration (prometheus.yml)
scrape_configs:
  - job_name: "api-gateway-alb"
    metrics_path: "/actuator/prometheus"
    static_configs:
      - targets: ["172.30.35.37:8080"]

  - job_name: "cadvisor"
    static_configs:
      - targets: ["cadvisor:8080"]
```

## PHỤ LỤC B. MÃ NGUỒN CÁC THÀNH PHẦN CỐT LÕI

### B.1 Thuật toán PID Controller (Hàm phạt)
Tính toán hình phạt dựa trên mức độ vi phạm độ trễ.
*Nguồn: `api-gateway-alb/src/main/java/com/truongquycode/apigatewayalb/dataplane/PIDController.java`*
```java
// error > 0: instance chậm hơn setpoint (P75 hệ thống)
double error = rawLat - setpoint;
if (Math.abs(error) <= ERROR_DEADBAND) {
    error = 0.0;
} else if (error > 0.0) {
    error -= ERROR_DEADBAND;
}

double p = kp * error;

// Conditional Anti-Windup for Integral
boolean isSaturated = Math.abs(prevOutput) >= 2.0;
boolean sameSign    = (error * prevOutput) > 0.0;
double integral = prevIntegral;
if (!(isSaturated && sameSign)) {
    double newI = prevIntegral + (error * actualDt);
    if (Math.abs(error) < 0.1) {
        newI *= Math.exp(LN_0_97 * actualDt);
    }
    integral = newI < minI ? minI : (newI > maxI ? maxI : newI);
}
double i = ki * integral;

// Low-Pass Filter for Derivative
double rawD = (rawLat - prevRawLat) / actualDt;
double expTerm = Math.exp(-actualDt / tauD);
double filteredD = ((1.0 - expTerm) * rawD) + (expTerm * prevFilteredD);
double d = kd * filteredD;

double u = p + i + d;
return lambda * Math.tanh(kappa * Math.max(0.0, u)); // Trả về penalty scale [0, lambda]
```

### B.2 Thuật toán Power of Two Choices (P2C)
Lấy mẫu ngẫu nhiên 2 máy chủ, so sánh Routing Cost.
*Nguồn: `api-gateway-alb/src/main/java/com/truongquycode/apigatewayalb/dataplane/AdaptiveLoadBalancer.java`*
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
    return routingCostCalculator.better(a, b);
}
```

## PHỤ LỤC C. CẤU HÌNH THỰC NGHIỆM

Thông số kịch bản Stress Test (tải duy trì vượt ngưỡng) tại Apache JMeter.
*Nguồn: `jmeter/04_stress_overload_graceful_degradation_mixed_1200_tst.jmx`*
```xml
<kg.apc.jmeter.timers.VariableThroughputTimer guiclass="kg.apc.jmeter.timers.VariableThroughputTimerGui" testclass="kg.apc.jmeter.timers.VariableThroughputTimer" testname="jp@gc - Throughput Shaping Timer | Stress overload 1200 RPS">
    <collectionProp name="load_profile">
    <collectionProp name="-1878747406">
        <stringProp name="start_rps_0">0</stringProp>
        <stringProp name="end_rps_0">600</stringProp>
        <stringProp name="duration_sec_0">60</stringProp> <!-- Ramp-up baseline -->
    </collectionProp>
    <collectionProp name="-1827363598">
        <stringProp name="start_rps_1">600</stringProp>
        <stringProp name="end_rps_1">600</stringProp>
        <stringProp name="duration_sec_1">120</stringProp> <!-- Giữ tải baseline -->
    </collectionProp>
    <collectionProp name="-1782210666">
        <stringProp name="start_rps_2">600</stringProp>
        <stringProp name="end_rps_2">1200</stringProp>
        <stringProp name="duration_sec_2">60</stringProp> <!-- Ramp-up stress overload -->
    </collectionProp>
    <collectionProp name="-382064381">
        <stringProp name="start_rps_3">1200</stringProp>
        <stringProp name="end_rps_3">1200</stringProp>
        <stringProp name="duration_sec_3">360</stringProp> <!-- Giữ tải quá tải -->
    </collectionProp>
    <!-- Các chu kỳ phục hồi theo sau... -->
    </collectionProp>
</kg.apc.jmeter.timers.VariableThroughputTimer>
```

## PHỤ LỤC D. QUY TRÌNH XỬ LÝ VÀ PHÂN TÍCH DỮ LIỆU
Hệ thống tích hợp quy trình mã Python (pipeline) trích xuất Metric từ các tệp JTL thô và tự động sinh biểu đồ phục vụ trực quan hóa (Ablation Study).

*Nguồn tham khảo: `docs/benchmark-results/05_adaptive_ablation_medium_dependency_slowdown/scripts/process_adaptive_ablation_results.py`*
1.  Đọc file JTL (JMeter), xử lý loại bỏ Request thuộc các giai đoạn Ramp-up/Teardown.
2.  Tập hợp dữ liệu nội suy để tính Average, Median (P50), P95, và P99.
3.  Tính toán Estimated Goodput bằng công thức: `Throughput × (1.0 - ErrorRate)`.
4.  Tổng hợp và tự động lưu cấu trúc bảng số liệu (`aggregate_results.csv`).
