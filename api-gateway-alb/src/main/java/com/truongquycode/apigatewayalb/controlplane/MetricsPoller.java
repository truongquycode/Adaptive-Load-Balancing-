package com.truongquycode.apigatewayalb.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.truongquycode.apigatewayalb.config.AlbProperties;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;
import com.truongquycode.apigatewayalb.dataplane.ScoreCalculator;
import com.truongquycode.apigatewayalb.dataplane.RoutingCostCalculator;
import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.model.RoutingCost;
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

	// ID service được đăng ký trong Eureka — dùng để lookup danh sách instance
	private static final String REGISTRATION_SERVICE_ID = "REGISTRATION-SERVICE-ALB";

	private final MeterRegistry registry; // Prometheus metrics registry
	private final ScoreCalculator scoreCalculator; // Tính EWMA + MCDM + PID score
	private final DiscoveryClient discoveryClient; // Lấy danh sách instance từ Eureka
	private final MetricsCache metricsCache; // Lưu kết quả metrics + score để AdaptiveLoadBalancer đọc
	private final SlidingWindowManager windowManager; // HDR Histogram cho percentile (p5, p50, p95, p99)
	private final WebClient.Builder webClientBuilder; // HTTP client để poll /api/alb-metrics
	private final InflightTracker inflightTracker; // Theo dõi số request đang bay của mỗi instance
	private final RoutingCostCalculator routingCostCalculator; // Tính routing cost thật của Adaptive v3
	private final AlbProperties albProperties; // Đọc ablation variant để benchmark từng thành phần Adaptive
	private WebClient webClient; // Instance WebClient, được init sau khi bean ready
	private Set<String> lastActiveIds = Set.of(); // Snapshot topology lần poll trước — dùng để phát hiện instance
													// mới/down

	// Các hệ số EMA score, timeout và idle baseline được đọc từ AlbProperties.Polling.
	// Việc đưa ra cấu hình giúp sensitivity analysis lặp lại được và tránh hard-code.

	// ── Backing maps cho Prometheus Gauges ──────────────────────────────────
	// Các map này là nguồn dữ liệu trực tiếp cho Gauge.builder().
	// Gauge đọc giá trị theo pull model (Prometheus scrape), nên phải giữ reference
	// sống.
	private final Map<String, Double> latencyValues = new ConcurrentHashMap<>(); // EWMA latency (ms) per instance
	private final Map<String, Double> queueValues = new ConcurrentHashMap<>(); // Queue length per instance
	private final Map<String, Double> scoreValues = new ConcurrentHashMap<>(); // Final score per instance (sau EMA)

	// ── State nội bộ của poller ──────────────────────────────────────────────
	private final Map<String, TrafficState> trafficStates = new ConcurrentHashMap<>();
	// trafficStates: lưu (count, totalTimeSec, lastLatency) của lần poll trước
	// → dùng để tính delta latency (xem calculateDeltaLatency)

	private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
	// consecutiveFailures: đếm số lần poll /api/alb-metrics thất bại liên tiếp
	// → khi fail, score bị penalty tăng dần (tối đa 10.0)

	private final Map<String, Double> smoothedScores = new ConcurrentHashMap<>();
	// smoothedScores: EMA state của finalScore per instance
	// → tách biệt với EWMA latency trong EwmaSmoother; đây là EMA ở tầng score tổng
	// hợp

	private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();
	// registeredGauges: tập instanceId đã đăng ký Prometheus Gauge
	// → tránh gọi Gauge.builder().register() lặp lại (sẽ gây exception
	// DuplicateMeterException)

	private final AtomicBoolean isPolling = new AtomicBoolean(false);
	// isPolling: mutex flag ngăn 2 poll cycle chạy song song
	// → nếu cycle trước chưa xong (do timeout hoặc nhiều instance) thì bỏ qua cycle
	// mới

	private record TrafficState(double count, double totalTimeSec, double lastLatency) {
	}

	private record LatencySample(double latencyMs, double completedRequests, boolean realLatencySample) {
	}

	@PostConstruct
	public void init() {
		// Khởi tạo WebClient sau khi tất cả bean đã sẵn sàng.
		// Không inject trực tiếp WebClient (là prototype bean) mà dùng
		// WebClient.Builder
		// để Spring có thể apply các customizer (timeout, codecs, v.v.) đã được cấu
		// hình.
		this.webClient = webClientBuilder.build();
	}

	@Scheduled(fixedRateString = "${alb.polling.interval:200}")
	public void pollMetrics() {
		// Kiểm tra mutex: nếu cycle trước vẫn đang chạy thì skip.
		// compareAndSet(false, true): trả về true chỉ khi CAS thành công (tức là poller
		// rảnh).
		// → Tránh tích lũy backlog request khi backend chậm hơn polling interval.
		if (!isPolling.compareAndSet(false, true)) {
			log.debug("Poll cycle skipped — previous cycle still running");
			return;
		}

		// Lấy danh sách instance đang UP từ Eureka cache (local, không gọi network).
		List<ServiceInstance> instances = discoveryClient.getInstances(REGISTRATION_SERVICE_ID);
		if (instances.isEmpty()) {
			isPolling.set(false); // Phải giải phóng mutex dù không có gì để poll
			return;
		}

		// Phát hiện thay đổi topology (instance mới join hoặc down).
		// So sánh Set<instanceId> hiện tại với lần trước → nếu khác thì cleanup dữ liệu
		// cũ.
		Set<String> currentIds = instances.stream().map(ServiceInstance::getInstanceId).collect(Collectors.toSet());
		if (!currentIds.equals(lastActiveIds)) {
			lastActiveIds = currentIds;
			cleanupStaleData(currentIds); // Xóa data của instance đã down
		}

		// Poll tất cả instance song song (reactive, non-blocking).
		// Mỗi pollSingleInstance() trả về Mono<Void>.
		// Mono.when() hoàn thành khi TẤT CẢ mono hoàn thành hoặc lỗi.
		// doFinally(): giải phóng mutex dù success hay error.
		List<Mono<Void>> polls = instances.stream().map(this::pollSingleInstance).toList();

		Mono.when(polls).doFinally(signal -> isPolling.set(false)).subscribe(); // Non-blocking, kết quả được xử lý qua
																				// doOnNext/onErrorResume
	}

	private Mono<Void> pollSingleInstance(ServiceInstance instance) {
		String instanceId = instance.getInstanceId();

		// URL endpoint trả về: { cpu, count, totalTime, queue }
		// Xem AlbMetricsController.java trong registration-service-alb
		String url = instance.getUri().toString() + "/api/alb-metrics";

		return webClient.get().uri(url).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofMillis(albProperties.getPolling().getMetricsTimeoutMs()))
				// ── Happy path: nhận được metrics ────────────────────────────
				.doOnNext(node -> {
					consecutiveFailures.put(instanceId, 0); // Reset failure counter vì poll thành công
					processMetrics(instanceId, node); // Parse và tính score
				})
				// ── Error path: timeout hoặc network error ────────────────────
				.onErrorResume(e -> {
					// Tăng failure counter và tính penalty score
					int failures = consecutiveFailures.merge(instanceId, 1, Integer::sum);

					// Penalty tăng dần theo số lần fail: 2.5, 5.0, 7.5, 10.0 (max)
					// Ví dụ: 1 lần fail → score 2.5, 4 lần fail → score 10.0
					double rawPenaltyScore = Math.min(10.0, failures * 2.5);

					// Áp EMA để tránh score nhảy thẳng lên 10 ngay lần fail đầu
					double smoothed = applyScoreEma(instanceId, rawPenaltyScore);

					// Tạo ScoreBreakdown giả (không có dữ liệu thực) với score cao
					// → AdaptiveLoadBalancer sẽ tránh route traffic đến instance này
					ScoreBreakdown penaltyBreakdown = new ScoreBreakdown(instanceId, 0, // ewmaLatency = 0 (không có dữ
																						// liệu)
							1, 1, 1, // normLatency=1, normQueue=1, normCpu=1 (worst case)
							rawPenaltyScore * 0.8, // baseScore
							rawPenaltyScore * 0.2, // pidPenalty
							smoothed, // finalScore (đã qua EMA)
							System.currentTimeMillis());
					metricsCache.putScore(instanceId, penaltyBreakdown);
					latencyValues.putIfAbsent(instanceId, 0.0);
					queueValues.putIfAbsent(instanceId, 0.0);
					scoreValues.put(instanceId, smoothed);
					registerPrometheusGauges(instanceId);

					log.warn("Poll failed for {} (attempt #{}), smoothedScore={}", instanceId, failures, smoothed);
					return Mono.empty(); // Không propagate lỗi lên Mono.when() để các instance khác vẫn chạy
				}).then();
	}

	private void cleanupStaleData(Set<String> activeIds) {
		// Xóa tất cả entry không còn trong activeIds (instance đã down hoặc
		// deregister).
		// retainAll() giữ lại key có trong activeIds, xóa key không có.
		// Quan trọng: phải cleanup để tránh memory leak và score cũ ảnh hưởng routing.
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
			// Parse 4 trường từ JSON trả về bởi AlbMetricsController:
			double cpu = node.path("cpu").asDouble(0.0);
			double currentCount = node.path("count").asDouble(0.0);
			double currentTotalTime = node.path("totalTime").asDouble(0.0);
			double reportedQueue = node.path("queue").asDouble(-1.0);

			double capacityWeight = node.path("capacityWeight").asDouble(1.0);
			metricsCache.putCapacityWeight(instanceId, capacityWeight);

			LatencySample latencySample = calculateDeltaLatency(instanceId, currentCount, currentTotalTime);
			if (latencySample.completedRequests() > 0.0) {
				metricsCache.recordCompletedRequestsForMcdm(latencySample.completedRequests());
			}

			// Fallback: nếu instance chưa có gauge inflight, dùng InflightTracker phía gateway.
			double realQueue = reportedQueue >= 0 ? reportedQueue : inflightTracker.getInflight(instanceId);

			ScoreBreakdown previousScore = metricsCache.getScore(instanceId);
			boolean hasRealLatencySample = latencySample.realLatencySample();
			boolean hasActiveWorkWithoutCompletedSample = !hasRealLatencySample && realQueue > 0.0;
			long now = System.currentTimeMillis();

			// Luôn lưu raw metrics gần nhất để các thành phần khác còn biết backend đang sống.
			metricsCache.putMetrics(instanceId, new InstanceMetrics(instanceId, latencySample.latencyMs(), realQueue, cpu));

			if (hasRealLatencySample) {
				// Chỉ latency sample phát sinh từ request hoàn thành thật mới được đưa vào histogram.
				// Điều này ngăn idle latency làm lệch p5/p75/p95 và PID setpoint.
				windowManager.addMetrics(instanceId, latencySample.latencyMs(), realQueue);

				InstanceMetrics metrics = new InstanceMetrics(instanceId, latencySample.latencyMs(), realQueue, cpu);
				ScoreBreakdown rawBreakdown = scoreCalculator.calculateScore(instanceId, metrics);
				double smoothedFinalScore = applyScoreEma(instanceId, rawBreakdown.finalScore());
				ScoreBreakdown finalBreakdown = rawBreakdown.withFinalScore(smoothedFinalScore);

				metricsCache.putScore(instanceId, finalBreakdown);
				latencyValues.put(instanceId, finalBreakdown.ewmaLatency());
				queueValues.put(instanceId, realQueue);
				scoreValues.put(instanceId, smoothedFinalScore);
				registerPrometheusGauges(instanceId);
				return;
			}

			if (hasActiveWorkWithoutCompletedSample && previousScore != null) {
				// Có request đang bay nhưng chưa hoàn thành trong window poll hiện tại.
				// Không tạo latency giả; giữ latency theo EWMA gần nhất, nhưng vẫn cập nhật queue/cpu
				// để routing tránh node đang tích inflight dài.
				InstanceMetrics metrics = new InstanceMetrics(instanceId, previousScore.ewmaLatency(), realQueue, cpu);
				ScoreBreakdown rawBreakdown = scoreCalculator.calculateScore(instanceId, metrics);
				double smoothedFinalScore = applyScoreEma(instanceId, rawBreakdown.finalScore());
				ScoreBreakdown finalBreakdown = rawBreakdown.withFinalScore(smoothedFinalScore);

				metricsCache.putScore(instanceId, finalBreakdown);
				latencyValues.put(instanceId, finalBreakdown.ewmaLatency());
				queueValues.put(instanceId, realQueue);
				scoreValues.put(instanceId, smoothedFinalScore);
				registerPrometheusGauges(instanceId);
				return;
			}

			// Idle thật: không cập nhật histogram, không cập nhật EWMA/score từ latency giả.
			// Chỉ refresh timestamp để score không bị stale vì hệ thống không có traffic.
			ScoreBreakdown refreshed = previousScore != null
					? previousScore.withUpdatedAt(now)
					: neutralIdleScore(instanceId, latencySample.latencyMs(), realQueue, cpu, now);
			metricsCache.putScore(instanceId, refreshed);
			latencyValues.put(instanceId, refreshed.ewmaLatency());
			queueValues.put(instanceId, realQueue);
			scoreValues.put(instanceId, refreshed.finalScore());
			registerPrometheusGauges(instanceId);

		} catch (Exception e) {
			log.warn("Lỗi parse metric cho {}: {}", instanceId, e.getMessage());
		}
	}

	private ScoreBreakdown neutralIdleScore(String instanceId, double latencyMs, double queue, double cpu, long now) {
		double safeLatency = clamp(latencyMs, 1.0, 3000.0);
		double normCpu = (Double.isNaN(cpu) || Double.isInfinite(cpu) || cpu < 0.0) ? 0.5 : clamp(cpu, 0.0, 1.0);
		double normQueue = queue > 0.0 ? 0.25 : 0.0;
		// Neutral score dùng để backend xuất hiện trên dashboard và không bị NO_METRICS trong warmup.
		// Nó không được đưa vào histogram/MCDM nên không làm lệch dữ liệu benchmark.
		return new ScoreBreakdown(instanceId, safeLatency, 0.5, normQueue, normCpu, 0.5, 0.0, 0.5, now);
	}

	private double applyScoreEma(String instanceId, double rawScore) {
		if (albProperties.getAblation() != null && albProperties.getAblation().isVariant("no-score-ema")) {
			smoothedScores.put(instanceId, rawScore);
			return rawScore;
		}

		Double prevObj = smoothedScores.get(instanceId);

		// Lần đầu gặp instance: khởi tạo EMA state với rawScore hiện tại (cold start)
		if (prevObj == null) {
			smoothedScores.put(instanceId, rawScore);
			return rawScore;
		}

		double prev = prevObj;
		double delta = rawScore - prev; // Dương = score tăng (xấu hơn), âm = score giảm (tốt hơn)

		// Chọn alpha dựa vào chiều và độ lớn của delta:
		AlbProperties.Polling pollingCfg = albProperties.getPolling();
		double alpha;
		if (delta > pollingCfg.getScoreEmaSpikeThreshold()) {
			// Score tăng đột biến >30% → phản ứng rất nhanh (alpha=0.60)
			// Mục đích: phát hiện degradation ngay lập tức, tránh gửi traffic đến instance
			// đang chết
			alpha = pollingCfg.getScoreEmaAlphaSpike();
		} else if (delta > 0.0) {
			// Score tăng nhẹ → phản ứng vừa (alpha=0.35)
			alpha = pollingCfg.getScoreEmaAlphaRise();
		} else {
			// Score giảm (instance đang hồi phục) → phản ứng chậm (alpha=0.25)
			// Mục đích: tránh flapping — không vội route lại traffic khi instance mới
			// "xanh" vài giây
			alpha = pollingCfg.getScoreEmaAlphaRecover();
		}

		// Công thức EMA: smoothed = prev + alpha * (rawScore - prev)
		// Tương đương: smoothed = alpha * rawScore + (1 - alpha) * prev
		double smoothed = prev + alpha * delta;
		smoothedScores.put(instanceId, smoothed);
		return smoothed;
	}

	private LatencySample calculateDeltaLatency(String id, double currentCount, double currentTotalTime) {
		TrafficState prev = trafficStates.get(id);

		// Lần đầu gặp instance: thiết lập baseline counter của Micrometer Timer.
		// Đây chưa phải traffic trong cửa sổ hiện tại, nên completedRequests = 0.
		if (prev == null) {
			double initLatency = initialLatencyFor(id);

			trafficStates.put(id, new TrafficState(currentCount, currentTotalTime, initLatency));

			log.debug("[MetricsPoller] Baseline established for {}: count={}, initLatency={}ms", id, currentCount,
					initLatency);

			return new LatencySample(initLatency, 0.0, false);
		}

		// Tính delta giữa 2 lần poll liên tiếp.
		double deltaCount = currentCount - prev.count();
		double deltaTotal = currentTotalTime - prev.totalTimeSec();

		double currentLatency;
		double completedRequests = 0.0;
		boolean realLatencySample = false;

		if (deltaCount > 0 && deltaTotal >= 0) {
			// Có request nghiệp vụ hoàn thành trong window vừa qua.
			// totalTime là giây, nên nhân 1000 để đổi sang millisecond.
			currentLatency = (deltaTotal / deltaCount) * 1000.0;
			completedRequests = deltaCount;
			realLatencySample = true;

		} else if (deltaCount < 0 || deltaTotal < 0) {
			// Trường hợp backend container/JVM restart làm counter Micrometer reset về 0.
			// Nếu không xử lý, delta âm có thể làm latency/score sai.
			// Không tính là traffic thật để tránh DynamicWeightEngine học từ dữ liệu reset.
			currentLatency = initialLatencyFor(id);

			log.info("[MetricsPoller] Counter reset detected for {}, re-baseline count={}", id, currentCount);

		} else {
			// Không có request mới hoàn thành.
			// Vẫn giữ latency idle cho routing/cache, nhưng KHÔNG ghi nhận completedRequests.
			// Nhờ vậy DynamicWeightEngine sẽ không cập nhật EWM từ nhiễu idle.
			double idleTarget = idleLatencyBaselineFor(id);
			double idleAlpha = clamp(albProperties.getPolling().getIdleDecayAlpha(), 0.0, 1.0);
			currentLatency = prev.lastLatency() + idleAlpha * (idleTarget - prev.lastLatency());
		}

		// Clamp vào [1ms, 3000ms] để tránh outlier.
		currentLatency = Math.min(Math.max(currentLatency, 1.0), 3000.0);

		trafficStates.put(id, new TrafficState(currentCount, currentTotalTime, currentLatency));

		return new LatencySample(currentLatency, completedRequests, realLatencySample);
	}
	
	private double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private double initialLatencyFor(String id) {
	    return idleLatencyBaselineFor(id);
	}

	private double idleLatencyBaselineFor(String id) {
		// Ưu tiên trung bình latency của các instance đang có dữ liệu thật.
		// Cách này tránh node ít traffic bị kéo về 100ms trong khi cụm thực tế chỉ 55-60ms.
		double sum = 0.0;
		int count = 0;
		for (Map.Entry<String, Double> entry : latencyValues.entrySet()) {
			double value = entry.getValue();
			if (value > 1.0 && !Double.isNaN(value) && !Double.isInfinite(value)) {
				sum += value;
				count++;
			}
		}
		if (count > 0) {
			return Math.min(Math.max(1.0, sum / count), 3000.0);
		}

		// Nếu chưa có dữ liệu cụm, dùng p50 trong sliding window của chính instance.
	    double p50 = windowManager.getSnapshot(id).p50();
	    if (p50 > 1.0 && !Double.isNaN(p50) && !Double.isInfinite(p50)) {
	        return Math.min(Math.max(1.0, p50), 3000.0);
	    }

	    return albProperties.getPolling().getIdleLatencyBaselineMs();
	}

	public void resetAllStates() {
		// Xóa toàn bộ state để bắt đầu benchmark sạch.
		// Sau reset, lần poll tiếp theo sẽ cold-start lại từ đầu:
		// - trafficStates: calculateDeltaLatency sẽ dùng p50 histogram làm baseline
		// - smoothedScores: applyScoreEma sẽ khởi tạo lại
		// Lưu ý: registeredGauges KHÔNG reset → Prometheus Gauges vẫn còn, chỉ backing
		// map bị xóa
		trafficStates.clear();
		consecutiveFailures.clear();
		smoothedScores.clear();
		latencyValues.clear();
		queueValues.clear();
		scoreValues.clear();
		routingCostCalculator.reset();
	}

	// Đăng ký Prometheus Gauges
	private void registerPrometheusGauges(String id) {
		// registeredGauges.add() trả về false nếu id đã tồn tại → skip, tránh
		// DuplicateMeterException
		if (registeredGauges.add(id)) {

			// alb.latency.ewma{backend=...}: EWMA latency (ms) của instance
			// Gauge đọc từ latencyValues map theo pull model
			Gauge.builder("alb.latency.ewma", latencyValues, map -> map.getOrDefault(id, 0.0)).tag("backend", id)
					.register(registry);

			// alb.queue.current{backend=...}: số request đang chờ xử lý
			Gauge.builder("alb.queue.current", queueValues, map -> map.getOrDefault(id, 0.0)).tag("backend", id)
					.register(registry);

			// alb.final.score{backend=...}: score sau EMA (càng thấp càng tốt)
			// AdaptiveLoadBalancer dùng score này để chọn instance
			Gauge.builder("alb.final.score", scoreValues, map -> map.getOrDefault(id, 0.0)).tag("backend", id)
					.register(registry);

				// Adaptive v3: routing.score là final routing cost thật đang được LoadBalancer dùng.
				// Giữ tên metric cũ để dashboard không bị vỡ, nhưng công thức đã đồng bộ với RoutingCostCalculator.
				Gauge.builder("alb.routing.score", this, self -> {
					RoutingCost cost = self.routingCostCalculator.getLastCost(id);
					return cost != null ? cost.finalCost() : self.scoreValues.getOrDefault(id, 0.5);
				}).tag("backend", id).register(registry);

				Gauge.builder("alb.routing.health.cost", this, self -> {
					RoutingCost cost = self.routingCostCalculator.getLastCost(id);
					return cost != null ? cost.healthCost() : 0.0;
				}).tag("backend", id).register(registry);

				Gauge.builder("alb.routing.load.cost", this, self -> {
					RoutingCost cost = self.routingCostCalculator.getLastCost(id);
					return cost != null ? cost.loadCost() : 0.0;
				}).tag("backend", id).register(registry);

				Gauge.builder("alb.routing.load.raw", this, self -> {
					RoutingCost cost = self.routingCostCalculator.getLastCost(id);
					return cost != null ? cost.loadCostRaw() : 0.0;
				}).tag("backend", id).register(registry);

				Gauge.builder("alb.routing.absolute.latency.cost", this, self -> {
					RoutingCost cost = self.routingCostCalculator.getLastCost(id);
					return cost != null ? cost.absoluteLatencyCost() : 0.0;
				}).tag("backend", id).register(registry);

				Gauge.builder("alb.routing.capacity.weight", this, self -> {
					RoutingCost cost = self.routingCostCalculator.getLastCost(id);
					return cost != null ? cost.capacityWeight() : 1.0;
				}).tag("backend", id).register(registry);
		}
	}
}
