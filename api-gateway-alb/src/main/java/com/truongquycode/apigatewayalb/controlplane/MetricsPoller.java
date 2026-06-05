package com.truongquycode.apigatewayalb.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;
import com.truongquycode.apigatewayalb.dataplane.ScoreCalculator;
import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.util.MetricsCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
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
import java.util.stream.Collectors;

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
	private WebClient webClient;
	private Set<String> lastActiveIds = Set.of();

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

	@PostConstruct
	public void init() {
		this.webClient = webClientBuilder.build();
	}

	@Scheduled(fixedRateString = "${alb.polling.interval:200}")
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
		Set<String> currentIds = instances.stream()
			    .map(ServiceInstance::getInstanceId)
			    .collect(Collectors.toSet());
			if (!currentIds.equals(lastActiveIds)) {
			    lastActiveIds = currentIds;
			    cleanupStaleData(currentIds);
			}

		// Luồng chính ánh xạ từng instance vào hàm pollSingleInstance
		List<Mono<Void>> polls = instances.stream().map(this::pollSingleInstance).toList();

		Mono.when(polls).doFinally(signal -> isPolling.set(false)).subscribe();
	}

	// Lấy dữ liệu từ một instance duy nhất
	private Mono<Void> pollSingleInstance(ServiceInstance instance) {
		String instanceId = instance.getInstanceId();

		String url = instance.getUri().toString() + "/api/alb-metrics";

		return webClient.get().uri(url).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofMillis(150))
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
					log.warn("Poll failed for {} (attempt #{}), smoothedScore={}", instanceId, failures, smoothed);
					return Mono.empty();
				}).then();
	}

	// Dọn dẹp cache của các instance đã offline
	private void cleanupStaleData(Set<String> activeIds) {
	    trafficStates.keySet().retainAll(activeIds);
	    metricsCache.removeStaleInstances(activeIds);
	    latencyValues.keySet().retainAll(activeIds);
	    queueValues.keySet().retainAll(activeIds);
	    scoreValues.keySet().retainAll(activeIds);
	    consecutiveFailures.keySet().retainAll(activeIds);
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
			double smoothedFinalScore = applyScoreEma(instanceId, rawBreakdown.finalScore());
			metricsCache.putScore(instanceId, rawBreakdown.withFinalScore(smoothedFinalScore));
			latencyValues.put(instanceId, rawBreakdown.ewmaLatency());
			queueValues.put(instanceId, realQueue);
			scoreValues.put(instanceId, smoothedFinalScore);

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
			// Tăng nhỏ → có thể là network jitter
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

	private double calculateDeltaLatency(String id, double currentCount, double currentTotalTime) {
		

		TrafficState prev = trafficStates.get(id);

		if (prev == null) {
			double p50 = windowManager.getSnapshot(id).p50();
			// POST-RESET: Thiết lập baseline mới từ giá trị backend hiện tại.
			// Trả về p50 (mặc định 50ms khi histogram rỗng) thay vì tính
			// historical average từ toàn bộ backend timer (gây ghost latency).
			double initLatency = Math.min(Math.max(1.0, p50), 3000.0);
			trafficStates.put(id, new TrafficState(currentCount, currentTotalTime, initLatency));
			log.debug("[MetricsPoller] Baseline established for {}: count={}, initLatency={}ms", id, currentCount,
					initLatency);
			return initLatency;
		}

		double deltaCount = currentCount - prev.count();
		double deltaTotal = currentTotalTime - prev.totalTimeSec();

		double currentLatency;
		if (deltaCount > 0) {
			currentLatency = (deltaTotal / deltaCount) * 1000.0;
		} else {
			// Không có request mới: giữ nguyên latency cũ, không decay
			currentLatency = prev.lastLatency();
		}

		currentLatency = Math.min(Math.max(currentLatency, 1.0), 3000.0);
		trafficStates.put(id, new TrafficState(currentCount, currentTotalTime, currentLatency));
		return currentLatency;
	}

	public void resetAllStates() {
		trafficStates.clear();
		consecutiveFailures.clear();
		smoothedScores.clear();
		latencyValues.clear();
		queueValues.clear();
		scoreValues.clear();
	}

	// Đăng ký Prometheus Gauges
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
					double excessShare = Math.max(0.0, ((double) localInflight / totalInflight) - (1.0 / 3.0));
					inflightPenalty = 0.8 * Math.log(1.0 + excessShare);
				}
				return finalScore + inflightPenalty;
			}).tag("backend", id).register(registry);
		}
	}
}