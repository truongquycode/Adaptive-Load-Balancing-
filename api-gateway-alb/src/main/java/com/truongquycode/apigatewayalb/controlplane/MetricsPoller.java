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

	// ID service được đăng ký trong Eureka — dùng để lookup danh sách instance
	private static final String REGISTRATION_SERVICE_ID = "REGISTRATION-SERVICE-ALB";

	private final MeterRegistry registry; // Prometheus metrics registry
	private final ScoreCalculator scoreCalculator; // Tính EWMA + MCDM + PID score
	private final DiscoveryClient discoveryClient; // Lấy danh sách instance từ Eureka
	private final MetricsCache metricsCache; // Lưu kết quả metrics + score để AdaptiveLoadBalancer đọc
	private final SlidingWindowManager windowManager; // HDR Histogram cho percentile (p5, p50, p95, p99)
	private final WebClient.Builder webClientBuilder; // HTTP client để poll /api/alb-metrics
	private final InflightTracker inflightTracker; // Theo dõi số request đang bay của mỗi instance
	private WebClient webClient; // Instance WebClient, được init sau khi bean ready
	private Set<String> lastActiveIds = Set.of(); // Snapshot topology lần poll trước — dùng để phát hiện instance
													// mới/down

	// ── Hệ số EMA bất đối xứng cho việc smooth finalScore ──────────────────
	// Mục đích: phát hiện degradation nhanh, nhưng phục hồi chậm để tránh flapping
	private static final double EMA_ALPHA_SPIKE = 0.60; // Score tăng đột biến (>30%) → react rất nhanh
	private static final double EMA_ALPHA_RISE = 0.35; // Score tăng nhẹ → react vừa
	private static final double EMA_ALPHA_RECOVER = 0.25; // Score giảm (instance đang hồi phục) → react chậm, tránh
															// flap
	private static final double EMA_SPIKE_THRESHOLD = 0.30; // Ngưỡng delta để phân loại "spike" vs "tăng nhẹ"
	private static final double IDLE_LATENCY_BASELINE_MS = 65.0;
	private static final double IDLE_DECAY_ALPHA = 0.20;

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

		return webClient.get().uri(url).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofMillis(400))
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
			// cpu: process.cpu.usage từ Micrometer, giá trị trong [0.0, 1.0]

			double currentCount = node.path("count").asDouble(0.0);
			double currentTotalTime = node.path("totalTime").asDouble(0.0);
			// count + totalTime: tổng tích lũy từ Micrometer Timer (http.server.requests).
			// Dùng để tính DELTA latency qua calculateDeltaLatency().
			// Không lấy trực tiếp latency vì Micrometer chỉ expose cumulative counter.

			double reportedQueue = node.path("queue").asDouble(-1.0);
			// queue: số request đang xử lý (http.server.requests.inflight gauge).
			// -1.0 là sentinel value khi instance chưa có gauge này.
			
			double capacityWeight = node.path("capacityWeight").asDouble(1.0);
			metricsCache.putCapacityWeight(instanceId, capacityWeight);

			// Tính latency trung bình trong window vừa qua (delta/delta logic)
			double latency = calculateDeltaLatency(instanceId, currentCount, currentTotalTime);

			// Fallback: nếu instance chưa có gauge inflight, dùng InflightTracker
			// (được tracking từ phía gateway, không phụ thuộc vào instance báo cáo)
			double realQueue = reportedQueue >= 0 ? reportedQueue : inflightTracker.getInflight(instanceId);

			// Tạo InstanceMetrics object để đưa vào pipeline tính score
			InstanceMetrics metrics = new InstanceMetrics(instanceId, latency, realQueue, cpu);

			// Lưu raw metrics vào cache (dùng cho DynamicWeightEngine tính MCDM weights)
			metricsCache.putMetrics(instanceId, metrics);

			// Cập nhật HDR Histogram (dùng cho ScoreCalculator lấy percentile làm setpoint
			// PID)
			windowManager.addMetrics(instanceId, latency, realQueue);

			// Tính score đầy đủ: EWMA latency → normalize (p5-p95) → MCDM baseScore → PID
			// penalty
			ScoreBreakdown rawBreakdown = scoreCalculator.calculateScore(instanceId, metrics);

			// Áp EMA bất đối xứng lên finalScore để:
			// - Tăng nhanh khi instance có dấu hiệu xấu (bảo vệ user)
			// - Giảm chậm khi instance hồi phục (tránh route quá sớm khi chưa ổn định)
			double smoothedFinalScore = applyScoreEma(instanceId, rawBreakdown.finalScore());

			// Lưu score vào cache với finalScore đã qua EMA
			// AdaptiveLoadBalancer sẽ đọc từ đây để chọn instance
			metricsCache.putScore(instanceId, rawBreakdown.withFinalScore(smoothedFinalScore));

			// Cập nhật backing maps cho Prometheus Gauges
			latencyValues.put(instanceId, rawBreakdown.ewmaLatency());
			queueValues.put(instanceId, realQueue);
			scoreValues.put(instanceId, smoothedFinalScore);

			// Đăng ký Prometheus Gauge lần đầu (idempotent nhờ registeredGauges set)
			registerPrometheusGauges(instanceId);

		} catch (Exception e) {
			log.warn("Lỗi parse metric cho {}: {}", instanceId, e.getMessage());
		}
	}

	private double applyScoreEma(String instanceId, double rawScore) {
		Double prevObj = smoothedScores.get(instanceId);

		// Lần đầu gặp instance: khởi tạo EMA state với rawScore hiện tại (cold start)
		if (prevObj == null) {
			smoothedScores.put(instanceId, rawScore);
			return rawScore;
		}

		double prev = prevObj;
		double delta = rawScore - prev; // Dương = score tăng (xấu hơn), âm = score giảm (tốt hơn)

		// Chọn alpha dựa vào chiều và độ lớn của delta:
		double alpha;
		if (delta > EMA_SPIKE_THRESHOLD) {
			// Score tăng đột biến >30% → phản ứng rất nhanh (alpha=0.60)
			// Mục đích: phát hiện degradation ngay lập tức, tránh gửi traffic đến instance
			// đang chết
			alpha = EMA_ALPHA_SPIKE;
		} else if (delta > 0.0) {
			// Score tăng nhẹ → phản ứng vừa (alpha=0.35)
			alpha = EMA_ALPHA_RISE;
		} else {
			// Score giảm (instance đang hồi phục) → phản ứng chậm (alpha=0.25)
			// Mục đích: tránh flapping — không vội route lại traffic khi instance mới
			// "xanh" vài giây
			alpha = EMA_ALPHA_RECOVER;
		}

		// Công thức EMA: smoothed = prev + alpha * (rawScore - prev)
		// Tương đương: smoothed = alpha * rawScore + (1 - alpha) * prev
		double smoothed = prev + alpha * delta;
		smoothedScores.put(instanceId, smoothed);
		return smoothed;
	}

	private double calculateDeltaLatency(String id, double currentCount, double currentTotalTime) {
		TrafficState prev = trafficStates.get(id);

		// Lần đầu gặp instance: thiết lập baseline counter của Micrometer Timer.
		if (prev == null) {
			double initLatency = initialLatencyFor(id);

			trafficStates.put(id, new TrafficState(currentCount, currentTotalTime, initLatency));

			log.debug("[MetricsPoller] Baseline established for {}: count={}, initLatency={}ms", id, currentCount,
					initLatency);

			return initLatency;
		}

		// Tính delta giữa 2 lần poll liên tiếp.
		double deltaCount = currentCount - prev.count();
		double deltaTotal = currentTotalTime - prev.totalTimeSec();

		double currentLatency;

		if (deltaCount > 0 && deltaTotal >= 0) {
			// Có request nghiệp vụ hoàn thành trong window vừa qua.
			// totalTime là giây, nên nhân 1000 để đổi sang millisecond.
			currentLatency = (deltaTotal / deltaCount) * 1000.0;

		} else if (deltaCount < 0 || deltaTotal < 0) {
			// Trường hợp backend container/JVM restart làm counter Micrometer reset về 0.
			// Nếu không xử lý, delta âm có thể làm latency/score sai.
			currentLatency = initialLatencyFor(id);

			log.info("[MetricsPoller] Counter reset detected for {}, re-baseline count={}", id, currentCount);

		} else {
			// Không có request mới hoàn thành.
			// Không kéo về 100ms cố định nữa vì node ít traffic sẽ bị score xấu giả.
			// Kéo nhẹ về baseline cụm hiện tại để tránh feedback loop gây starvation.
			double idleTarget = idleLatencyBaselineFor(id);
			currentLatency = prev.lastLatency() + IDLE_DECAY_ALPHA * (idleTarget - prev.lastLatency());
		}

		// Clamp vào [1ms, 3000ms] để tránh outlier.
		currentLatency = Math.min(Math.max(currentLatency, 1.0), 3000.0);

		trafficStates.put(id, new TrafficState(currentCount, currentTotalTime, currentLatency));

		return currentLatency;
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

	    return IDLE_LATENCY_BASELINE_MS;
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

			// alb.routing.score{backend=...}: score có điều chỉnh theo inflight
			// = finalScore + hệ số phạt nếu instance đang nhận quá nhiều traffic
			// so với phần chia công bằng (share[i] × totalInflight)
			// Công thức: excessRatio = (localInflight × n / totalInflight) - 1.0
			// score += 0.6 × excessRatio^1.3 nếu excessRatio > 0
			// Mục đích: Prometheus hiển thị "routing score thực tế" bao gồm cả áp lực
			// inflight,
			// hữu ích khi debug tại sao một instance ít được chọn hơn dù score thấp.
			Gauge.builder("alb.routing.score", this, self -> {
				double finalScore = self.scoreValues.getOrDefault(id, 0.5);
				int localInflight = self.inflightTracker.getInflight(id);
				int totalInflight = self.inflightTracker.getTotalInflight();

				if (totalInflight <= 0)
					return finalScore;

				int n = self.discoveryClient.getInstances(REGISTRATION_SERVICE_ID).size();
				if (n <= 0)
					return finalScore;

				double expected = Math.max(3.0, (double) totalInflight / n);
				double excessRatio = ((double) localInflight / expected) - 1.0;
				if (excessRatio <= 0)
					return finalScore;

				double factor = totalInflight < 15 ? 0.25 : 1.0;
				return finalScore + 0.35 * factor * Math.pow(excessRatio, 1.3);
			}).tag("backend", id).register(registry);
		}
	}
}