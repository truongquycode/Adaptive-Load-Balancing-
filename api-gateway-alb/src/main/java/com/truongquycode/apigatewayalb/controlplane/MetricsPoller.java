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

	private static final double EMA_ALPHA_SPIKE = 0.60;
	private static final double EMA_ALPHA_RISE = 0.35;
	private static final double EMA_ALPHA_RECOVER = 0.25;
	private static final double EMA_SPIKE_THRESHOLD = 0.30;

	private final Map<String, Double> latencyValues = new ConcurrentHashMap<>();
	private final Map<String, Double> queueValues = new ConcurrentHashMap<>();
	private final Map<String, Double> scoreValues = new ConcurrentHashMap<>();
	private final Map<String, TrafficState> trafficStates = new ConcurrentHashMap<>();
	private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
	private final Map<String, Double> smoothedScores = new ConcurrentHashMap<>();
	private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();
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

		Set<String> currentIds = instances.stream().map(ServiceInstance::getInstanceId).collect(Collectors.toSet());
		if (!currentIds.equals(lastActiveIds)) {
			lastActiveIds = currentIds;
			cleanupStaleData(currentIds);
		}

		List<Mono<Void>> polls = instances.stream().map(this::pollSingleInstance).toList();

		Mono.when(polls).doFinally(signal -> isPolling.set(false)).subscribe();
	}

	private Mono<Void> pollSingleInstance(ServiceInstance instance) {
		String instanceId = instance.getInstanceId();

		String url = instance.getUri().toString() + "/api/alb-metrics";

		return webClient.get().uri(url).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofMillis(150))
				.doOnNext(node -> {
					consecutiveFailures.put(instanceId, 0);
					processMetrics(instanceId, node);
				}).onErrorResume(e -> {
					int failures = consecutiveFailures.merge(instanceId, 1, Integer::sum);
					double rawPenaltyScore = Math.min(10.0, failures * 2.5);

					double smoothed = applyScoreEma(instanceId, rawPenaltyScore);
					ScoreBreakdown penaltyBreakdown = new ScoreBreakdown(instanceId, 0, 1, 1, 1, rawPenaltyScore * 0.8, // baseScore
							rawPenaltyScore * 0.2,
							smoothed,
							System.currentTimeMillis());
					metricsCache.putScore(instanceId, penaltyBreakdown);
					log.warn("Poll failed for {} (attempt #{}), smoothedScore={}", instanceId, failures, smoothed);
					return Mono.empty();
				}).then();
	}

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

	private double applyScoreEma(String instanceId, double rawScore) {
		Double prevObj = smoothedScores.get(instanceId);
		if (prevObj == null) {
			smoothedScores.put(instanceId, rawScore);
			return rawScore;
		}

		double prev = prevObj;
		double delta = rawScore - prev;

		double alpha;
		if (delta > EMA_SPIKE_THRESHOLD) {
			alpha = EMA_ALPHA_SPIKE; 
		} else if (delta > 0.0) {
			alpha = EMA_ALPHA_RISE; 
		} else {
			alpha = EMA_ALPHA_RECOVER; 
		}

		double smoothed = prev + alpha * delta;
		smoothedScores.put(instanceId, smoothed);
		return smoothed;
	}

	private double calculateDeltaLatency(String id, double currentCount, double currentTotalTime) {

		TrafficState prev = trafficStates.get(id);

		if (prev == null) {
			double p50 = windowManager.getSnapshot(id).p50();
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

			Gauge.builder("alb.routing.score", this, self -> {
				double finalScore = self.scoreValues.getOrDefault(id, 0.5);
				int localInflight = self.inflightTracker.getInflight(id);
				int totalInflight = self.inflightTracker.getTotalInflight();

				if (totalInflight <= 0)
					return finalScore;

				int n = self.discoveryClient.getInstances(REGISTRATION_SERVICE_ID).size();
				if (n <= 0)
					return finalScore;

				double excessRatio = ((double) localInflight * n / totalInflight) - 1.0;
				if (excessRatio <= 0)
					return finalScore;

				return finalScore + 0.6 * Math.pow(excessRatio, 1.3);
			}).tag("backend", id).register(registry);
		}
	}
}