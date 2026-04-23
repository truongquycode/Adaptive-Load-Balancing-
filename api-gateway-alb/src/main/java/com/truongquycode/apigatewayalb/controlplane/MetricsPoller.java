package com.truongquycode.apigatewayalb.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;
import com.truongquycode.apigatewayalb.dataplane.ScoreCalculator;
import com.truongquycode.apigatewayalb.model.CircuitState;
import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.util.MetricsCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsPoller {

    private final MeterRegistry registry;
    private final ScoreCalculator scoreCalculator;
    private final DiscoveryClient discoveryClient;
    private final MetricsCache metricsCache;
    private final SlidingWindowManager windowManager;
    private final WebClient.Builder webClientBuilder;
    private final InflightTracker inflightTracker;
    private final InstanceCircuitBreaker circuitBreaker; // THÊM MỚI

    private final Map<String, Double> latencyValues = new ConcurrentHashMap<>();
    private final Map<String, Double> queueValues   = new ConcurrentHashMap<>();
    private final Map<String, Double> scoreValues   = new ConcurrentHashMap<>();
    private final Map<String, TrafficState> trafficStates = new ConcurrentHashMap<>();
    private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isPolling = new AtomicBoolean(false);

    private record TrafficState(double count, double totalTimeSec, double lastLatency) {}

    @Scheduled(fixedRateString = "${alb.polling.interval:1000}")
    public void pollMetrics() {
        if (!isPolling.compareAndSet(false, true)) {
            log.debug("Poll cycle skipped — previous cycle still running");
            return;
        }

        List<ServiceInstance> instances = discoveryClient.getInstances("REGISTRATION-SERVICE-ALB");
        if (instances.isEmpty()) {
            isPolling.set(false);
            return;
        }

        List<String> activeIds = instances.stream()
            .map(ServiceInstance::getInstanceId).toList();
        trafficStates.keySet().retainAll(activeIds);
        metricsCache.removeStaleInstances(activeIds);
        latencyValues.keySet().retainAll(activeIds);
        queueValues.keySet().retainAll(activeIds);
        scoreValues.keySet().retainAll(activeIds);

        WebClient webClient = webClientBuilder.build();

        List<Mono<Void>> polls = instances.stream().map(instance -> {
            String instanceId = instance.getInstanceId();
            String url = instance.getUri().toString() + "/api/alb-metrics";

            // THÊM MỚI: Kiểm tra circuit state trước khi poll
            // Nếu circuit đang OPEN, bỏ qua HTTP call nhưng vẫn áp dụng penalty
            CircuitState state = circuitBreaker.evaluateState(instanceId);
            if (state == CircuitState.OPEN) {
                log.debug("Circuit OPEN — bỏ qua poll cho {}", instanceId);
                applyCircuitOpenPenalty(instanceId);
                return Mono.<Void>empty();
            }

            return webClient.get().uri(url).retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(1500))
                .doOnNext(node -> {
                    // THÊM MỚI: Ghi nhận thành công trước khi xử lý metrics
                    circuitBreaker.recordSuccess(instanceId);
                    processMetrics(instanceId, node);
                })
                .onErrorResume(e -> {
                    // THÊM MỚI: Ghi nhận thất bại để circuit breaker theo dõi
                    circuitBreaker.recordFailure(instanceId);
                    log.debug("Timeout/lỗi kết nối tới {} — lần thất bại: {}",
                        instanceId, e.getMessage());
                    return Mono.empty();
                })
                .then();
        }).toList();

        Mono.when(polls).doFinally(signal -> isPolling.set(false)).subscribe();
    }

    /**
     * THÊM MỚI: Áp dụng penalty tối đa cho instance có circuit đang OPEN.
     * Instance này không được chọn bởi AdaptiveLoadBalancer vì finalScore rất cao.
     */
    private void applyCircuitOpenPenalty(String instanceId) {
        // finalScore = Double.MAX_VALUE / 2 đảm bảo instance không bao giờ được chọn
        // trong P2C algorithm nhưng không gây overflow khi so sánh
        ScoreBreakdown penaltyScore = new ScoreBreakdown(
            instanceId,
            0.0,   // ewmaLatency — không có dữ liệu
            1.0,   // normLatency — worst case
            1.0,   // normQueue   — worst case
            1.0,   // normCpu     — worst case
            1.0,   // baseScore   — worst case
            1.5,   // pidPenalty  — vượt ngưỡng lambda
            Double.MAX_VALUE / 2, // finalScore — loại khỏi routing
            System.currentTimeMillis()
        );
        metricsCache.putScore(instanceId, penaltyScore);

        // Vẫn expose Prometheus để quan sát trên Grafana
        scoreValues.put(instanceId, Double.MAX_VALUE / 2);
        if (registeredGauges.add(instanceId)) {
            Gauge.builder("alb.final.score", scoreValues,
                    map -> map.getOrDefault(instanceId, 0.0))
                .tag("backend", instanceId)
                .register(registry);
        }
    }

    private void processMetrics(String instanceId, JsonNode node) {
        try {
            double cpu             = node.path("cpu").asDouble(0.0);
            double currentCount    = node.path("count").asDouble(0.0);
            double currentTotalTime = node.path("totalTime").asDouble(0.0);
            double reportedQueue   = node.path("queue").asDouble(-1.0);

            double latency   = calculateDeltaLatency(instanceId, currentCount, currentTotalTime);
            double realQueue = reportedQueue >= 0
                ? reportedQueue
                : inflightTracker.getInflight(instanceId);

            InstanceMetrics metrics = new InstanceMetrics(instanceId, latency, realQueue, cpu);
            metricsCache.putMetrics(instanceId, metrics);
            windowManager.addMetrics(instanceId, latency, realQueue);

            ScoreBreakdown breakdown = scoreCalculator.calculateScore(instanceId, metrics);
            metricsCache.putScore(instanceId, breakdown);
            exposeToPrometheus(instanceId, breakdown, realQueue);

            if (log.isDebugEnabled()) {
                log.debug("Score [{}]: ewma={:.1f}ms nL={:.3f} nQ={:.3f} nC={:.3f} " +
                          "base={:.3f} pid={:.3f} final={:.3f}",
                    instanceId, breakdown.ewmaLatency(),
                    breakdown.normLatency(), breakdown.normQueue(), breakdown.normCpu(),
                    breakdown.baseScore(), breakdown.pidPenalty(), breakdown.finalScore());
            }

        } catch (Exception e) {
            log.warn("Lỗi parse metric cho {}: {}", instanceId, e.getMessage());
        }
    }

    private double calculateDeltaLatency(String id, double currentCount, double currentTotalTime) {
        double p50 = windowManager.getSnapshot(id).p50();
        TrafficState prev = trafficStates.getOrDefault(id, new TrafficState(0, 0, p50));

        double deltaCount = currentCount - prev.count();
        double deltaTotal = currentTotalTime - prev.totalTimeSec();

        double currentLatency = (deltaCount <= 0)
            ? (prev.lastLatency() * 0.9) + (p50 * 0.1)
            : (deltaTotal / deltaCount) * 1000.0;

        trafficStates.put(id, new TrafficState(currentCount, currentTotalTime, currentLatency));
        return currentLatency;
    }

    private void exposeToPrometheus(String id, ScoreBreakdown b, double rawQueue) {
        latencyValues.put(id, b.ewmaLatency());
        queueValues.put(id, rawQueue);
        scoreValues.put(id, b.finalScore());

        if (registeredGauges.add(id)) {
            Gauge.builder("alb.latency.ewma", latencyValues,
                    map -> map.getOrDefault(id, 0.0))
                .tag("backend", id).register(registry);
            Gauge.builder("alb.queue.current", queueValues,
                    map -> map.getOrDefault(id, 0.0))
                .tag("backend", id).register(registry);
            Gauge.builder("alb.final.score", scoreValues,
                    map -> map.getOrDefault(id, 0.0))
                .tag("backend", id).register(registry);
            Gauge.builder("alb.circuit.state",
                    () -> (double) circuitBreaker.getStateValue(id))
                .tag("backend", id)
                .description("0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                .register(registry);
        }
    }
}