package com.truongquycode.apigatewayalb.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;
import com.truongquycode.apigatewayalb.dataplane.ScoreCalculator;
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

	private static final String REGISTRATION_SERVICE_ID = "REGISTRATION-SERVICE-ALB";

	private final MeterRegistry registry;
	private final ScoreCalculator scoreCalculator;
	private final DiscoveryClient discoveryClient;
	private final MetricsCache metricsCache;
	private final SlidingWindowManager windowManager;
	private final WebClient.Builder webClientBuilder;
	private final InflightTracker inflightTracker;

	private final Map<String, Double> latencyValues = new ConcurrentHashMap<>();
	private final Map<String, Double> queueValues = new ConcurrentHashMap<>();
	private final Map<String, Double> scoreValues = new ConcurrentHashMap<>();
	private final Map<String, TrafficState> trafficStates = new ConcurrentHashMap<>();
	private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
	private final Map<String, Double> smoothedScores = new ConcurrentHashMap<>();
	private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();
	private final Set<String> registeredRoutingGauges = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean isPolling = new AtomicBoolean(false);

	private record TrafficState(double count, double totalTimeSec, double lastLatency) {
	}

	@Scheduled(fixedRateString = "${alb.polling.interval:500}")
	public void pollMetrics() {
		if (!isPolling.compareAndSet(false, true)) {
			log.debug("Poll cycle skipped — previous cycle still running");
			return;
		}

		List<ServiceInstance> instances = discoveryClient.getInstances(REGISTRATION_SERVICE_ID);
		if (instances.isEmpty()) {
			isPolling.set(false);
			return;
		}

		// Tách logic dọn dẹp ra hàm riêng
		cleanupStaleData(instances);

		WebClient webClient = webClientBuilder.build();

		// Luồng chính giờ đây chỉ là ánh xạ từng instance vào hàm pollSingleInstance
		List<Mono<Void>> polls = instances.stream().map(instance -> pollSingleInstance(instance, webClient)).toList();

		Mono.when(polls).doFinally(signal -> isPolling.set(false)).subscribe();
	}

	// --- Tách hàm: Lấy dữ liệu từ một instance duy nhất ---
	private Mono<Void> pollSingleInstance(ServiceInstance instance, WebClient webClient) {
		String instanceId = instance.getInstanceId();

		String url = instance.getUri().toString() + "/api/alb-metrics";

		return webClient.get().uri(url).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofMillis(800))
				.doOnNext(node -> {
					// Reset failure counter khi poll thành công
					consecutiveFailures.put(instanceId, 0);
					processMetrics(instanceId, node);
				}).onErrorResume(e -> {
					// ── Progressive penalty: không nhảy thẳng lên 10.0 ──────
					// Failure 1 → score ≈ 2.0, Failure 2 → ≈ 4.5, Failure 3+ → ≈ 8.0
					int failures = consecutiveFailures.merge(instanceId, 1, Integer::sum);
					double rawPenaltyScore = Math.min(10.0, failures * 2.5);

					// Áp dụng EMA smoothing ngay cả với penalty score
					double smoothed = applyScoreEma(instanceId, rawPenaltyScore);
					ScoreBreakdown penaltyBreakdown = new ScoreBreakdown(instanceId, 0, 1, 1, 1, rawPenaltyScore * 0.8, // baseScore
							rawPenaltyScore * 0.2, // pidPenalty
							smoothed, // finalScore (đã smoothed)
							System.currentTimeMillis());
					metricsCache.putScore(instanceId, penaltyBreakdown);
					log.warn("Poll failed for {} (attempt #{}), smoothedScore={:.2f}", instanceId, failures, smoothed);
					return Mono.empty();
				}).then();
	}

	// --- Tách hàm: Dọn dẹp cache của các instance đã offline ---
	private void cleanupStaleData(List<ServiceInstance> instances) {
		List<String> activeIds = instances.stream().map(ServiceInstance::getInstanceId).toList();
		trafficStates.keySet().retainAll(activeIds);
		metricsCache.removeStaleInstances(activeIds);
		latencyValues.keySet().retainAll(activeIds);
		queueValues.keySet().retainAll(activeIds);
		scoreValues.keySet().retainAll(activeIds);
	    consecutiveFailures.keySet().retainAll(activeIds);  // ← thêm dòng này
	    smoothedScores.keySet().retainAll(activeIds);      
	}

	private void processMetrics(String instanceId, JsonNode node) {
		try {
			double cpu = node.path("cpu").asDouble(0.0);
			double currentCount = node.path("count").asDouble(0.0);
			double currentTotalTime = node.path("totalTime").asDouble(0.0);
			double reportedQueue = node.path("queue").asDouble(-1.0);

			double latency = calculateDeltaLatency(instanceId, currentCount, currentTotalTime);
			double realQueue = reportedQueue >= 0 ? reportedQueue : inflightTracker.getInflight(instanceId);

			InstanceMetrics metrics = new InstanceMetrics(instanceId, latency, realQueue, cpu);
			metricsCache.putMetrics(instanceId, metrics);
			windowManager.addMetrics(instanceId, latency, realQueue);

			ScoreBreakdown rawBreakdown = scoreCalculator.calculateScore(instanceId, metrics);

			// ── EMA smoothing: ngăn score nhảy đột ngột ─────────────────────
			double smoothedFinal = applyScoreEma(instanceId, rawBreakdown.finalScore());

			ScoreBreakdown smoothedBreakdown = new ScoreBreakdown(rawBreakdown.instanceId(), rawBreakdown.ewmaLatency(),
					rawBreakdown.normLatency(), rawBreakdown.normQueue(), rawBreakdown.normCpu(),
					rawBreakdown.baseScore(), rawBreakdown.pidPenalty(), smoothedFinal, // ← dùng smoothed score
					rawBreakdown.updatedAtMs());
			metricsCache.putScore(instanceId, smoothedBreakdown);
			latencyValues.put(instanceId, smoothedBreakdown.ewmaLatency());
			queueValues.put(instanceId, realQueue);
			scoreValues.put(instanceId, smoothedFinal);

			registerPrometheusGauges(instanceId);

		} catch (Exception e) {
			log.warn("Lỗi parse metric cho {}: {}", instanceId, e.getMessage());
		}
	}

	// ── Helper: Asymmetric EMA ────────────────────────────────────────────────
	// Phát hiện degradation NHANH (alpha=0.55), phục hồi CHẬM (alpha=0.20)
	// → Tránh yo-yo: instance phục hồi nhưng ngay lập tức bị flood traffic lại
	private double applyScoreEma(String instanceId, double rawScore) {
	    double prev = smoothedScores.getOrDefault(instanceId, rawScore);
	    double delta = rawScore - prev;

	    double alpha;
	    if (delta > 0.30) {
	        // Tăng đột ngột lớn → chaos event thực sự (không phải network noise)
	        // Phản ứng NHANH để loại node ra khỏi pool trong vòng 1-2 giây
	        alpha = 0.60;
	    } else if (delta > 0.0) {
	        // Tăng nhỏ → có thể là network jitter (LRC-Student, Tailscale overhead)
	        // Phản ứng VỪA PHẢI để lọc noise
	        alpha = 0.35;
	    } else {
	        // Giảm → recovery, phục hồi thận trọng tránh flood traffic trở lại
	        alpha = 0.25;
	    }

	    double smoothed = alpha * rawScore + (1 - alpha) * prev;
	    smoothedScores.put(instanceId, smoothed);
	    return smoothed;
	}

	// Hàm calculateDeltaLatency giữ nguyên
	private double calculateDeltaLatency(String id, double currentCount, double currentTotalTime) {
		double p50 = windowManager.getSnapshot(id).p50();
		TrafficState prev = trafficStates.getOrDefault(id, new TrafficState(0, 0, p50));

		double deltaCount = currentCount - prev.count();
		double deltaTotal = currentTotalTime - prev.totalTimeSec();

		double currentLatency = (deltaCount <= 0) ? (prev.lastLatency() * 0.75) + (p50 * 0.25)
				: (deltaTotal / deltaCount) * 1000.0;

		currentLatency = Math.min(currentLatency, 1500.0);

		trafficStates.put(id, new TrafficState(currentCount, currentTotalTime, currentLatency));
		return currentLatency;
	}

	// --- Tách hàm: Đăng ký Prometheus Gauges ---
	private void registerPrometheusGauges(String id) {
		if (registeredGauges.add(id)) {
			Gauge.builder("alb.latency.ewma", latencyValues, map -> map.getOrDefault(id, 0.0)).tag("backend", id)
					.register(registry);
			Gauge.builder("alb.queue.current", queueValues, map -> map.getOrDefault(id, 0.0)).tag("backend", id)
					.register(registry);
			Gauge.builder("alb.final.score", scoreValues, map -> map.getOrDefault(id, 0.0)).tag("backend", id)
					.register(registry);

		}

		if (registeredRoutingGauges.add(id)) {
			Gauge.builder("alb.routing.score", () -> {
				double finalScore = scoreValues.getOrDefault(id, 0.5);
				int localInflight = inflightTracker.getInflight(id);
				int totalInflight = inflightTracker.getTotalInflight();

				double inflightPenalty = 0.0;
				if (totalInflight > 0) {
					// Giả định cụm luôn có 3 node để vẽ đồ thị
					double excessShare = Math.max(0.0, ((double) localInflight / totalInflight) - (1.0 / 3.0));
					inflightPenalty = 0.8 * Math.log(1.0 + excessShare);
				}
				return finalScore + inflightPenalty;
			}).tag("backend", id).register(registry);
		}
	}
}