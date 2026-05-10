# Adaptive Load Balancer Project Context

This project implements an **Adaptive Load Balancer (ALB)** within a Spring Cloud Gateway ecosystem. It uses a Control Plane to monitor backend health and a Data Plane to route traffic using a multi-criteria scoring system.

## Project Structure

- **`eureka-server`**: Service Discovery registry.
- **`api-gateway-alb`**: The core component containing the Adaptive Load Balancer.
  - **Data Plane**: `AdaptiveLoadBalancer` (P2C strategy), `InflightTracker`.
  - **Control Plane**: `MetricsPoller`, `DynamicWeightEngine` (AHP-EWM fusion), `ScoreCalculator`, `InstanceCircuitBreaker`, `PIDController`.
- **`registration-service-alb`**: A sample backend service that exposes real-time metrics (CPU, request count, total time, inflight requests) via `/api/alb-metrics`.

## Key Technologies

- **Java 21**, **Spring Boot 3.2.4**, **Spring Cloud 2023.0.1**.
- **Spring Cloud Gateway** & **Spring Cloud LoadBalancer**.
- **Micrometer/Prometheus**: For metrics collection and observation.
- **Caffeine**: Local caching for scores and metrics.
- **Lombok**: Boilerplate reduction.
- **HdrHistogram**: For precise latency percentile calculation.

## Architecture & Logic

### 1. Data Plane (Routing)
The `AdaptiveLoadBalancer` implements the `ReactorServiceInstanceLoadBalancer` interface. It uses the **Power of Two Choices (P2C)** algorithm:
1. Pick two random available instances.
2. Calculate a real-time score for both.
3. Select the instance with the lower (better) score.

The score calculation includes a `localInflight` penalty to provide immediate feedback before the next polling cycle.

### 2. Control Plane (Adaptive Feedback)
- **Metrics Polling**: `MetricsPoller` fetches metrics from backends every 1 second.
- **Dynamic Weighting**: `DynamicWeightEngine` uses a fusion of **AHP (Analytic Hierarchy Process)** and **EWM (Entropy Weight Method)** to adjust the importance of Latency, Queue Length, and CPU Usage every 5 seconds.
- **Scoring**: `ScoreCalculator` uses:
  - **EWMA (Exponentially Weighted Moving Average)** for smoothed latency.
  - **Normalization**: Latency, Queue, and CPU are normalized to a 0-1 scale.
  - **PID Control**: A penalty is added if an instance's latency deviates significantly from the system average.
- **Circuit Breaker**: `InstanceCircuitBreaker` ejections instances that consistently fail or timeout.

## Building and Running

### Build
From the root directory:
```bash
mvn clean install
```

### Run Order
1.  **Eureka Server**:
    ```bash
    cd eureka-server && mvn spring-boot:run
    ```
2.  **Registration Service** (Run multiple instances on different ports):
    ```bash
    cd registration-service-alb && mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
    cd registration-service-alb && mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082"
    ```
3.  **API Gateway**:
    ```bash
    cd api-gateway-alb && mvn spring-boot:run
    ```

## Development Conventions

- **Metrics Exposure**: All metrics used for load balancing are exposed to Prometheus via `/actuator/prometheus` in the gateway.
- **Configuration**: ALB-specific settings (polling intervals, EWMA tau, PID parameters) are managed via `AlbProperties` and can be tuned in `application.yml`.
- **Logging**: Debug logs in `MetricsPoller` and `AdaptiveLoadBalancer` provide insights into score calculation and routing decisions.
- **Fail-Fast**: The Gateway is configured with aggressive timeouts (2s connect, 8s response) to ensure responsiveness.
