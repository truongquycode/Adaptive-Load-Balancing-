package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.util.MetricsCache;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

	private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
	private final MetricsCache cache;
	private final InflightTracker inflightTracker;

	// ── Tuning constants ─────────────────────────────────────────────────────
	private static final int INFLIGHT_HARD_CAP = 200;
	private static final double OMEGA_REL = 0.008;
	private static final double OMEGA_ABS = 0.3;
	private static final double PENALTY_EXPONENT = 1.3;
	private static final double SCORE_FLOOR = 0.05;
	private static final double DEFAULT_SCORE = 0.35;
	private static final double CAP_WEIGHT_EMA = 0.20;
	private static final double MAX_CAP_WEIGHT_RATIO = 3.0;
	private static final long WARMUP_MS = 5_000;

	// ── STATIC state: dùng static để AdminController có thể gọi reset() ─────
	//
	// Vấn đề trước đây: các field này là instance field (final ConcurrentHashMap).
	// AdaptiveLoadBalancer được tạo bởi LoadBalancerConfiguration trong Spring
	// Cloud
	// LoadBalancer child context → AdminController (parent context) không thể
	// inject
	// để reset → giữa các lần benchmark, state cũ còn tồn tại → Run 4 bị lệch.
	//
	// Fix: chuyển sang static → resetStaticState() callable từ bất kỳ đâu,
	// bao gồm AdminController.
	//
	// An toàn với static vì:
	// - Chỉ có 1 JVM instance → 1 class loader → 1 static state
	// - ConcurrentHashMap thread-safe cho multi-thread access
	// - reset() chỉ gọi khi benchmark kết thúc (không có concurrent request)

	private static final ConcurrentHashMap<String, Double> smoothedCapMcdm = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, Long> firstSeenMs = new ConcurrentHashMap<>();

	private static final AtomicLong rrCounter = new AtomicLong(0);

	/**
	 * Reset toàn bộ state của AdaptiveLoadBalancer. Phải gọi qua AdminController
	 * trước mỗi lần benchmark mới.
	 *
	 * Tại sao cần reset: - smoothedCapMcdm: nếu carry over từ run trước, capacity
	 * weights bị lệch → routing tập trung sai node → CPU saturation - firstSeenMs:
	 * nếu carry over, warmup guard không kích hoạt → không có giai đoạn round-robin
	 * ban đầu → cold start routing sai - rrCounter: reset về 0 để warmup
	 * round-robin bắt đầu từ đầu
	 */
	public static void resetStaticState() {
		smoothedCapMcdm.clear();
		firstSeenMs.clear();
		rrCounter.set(0);
		log.info("[ALB] Static state reset: smoothedCapMcdm={} entries cleared, "
				+ "firstSeenMs cleared, rrCounter reset to 0", smoothedCapMcdm.size());
	}

	@Override
	public Mono<Response<ServiceInstance>> choose(Request request) {
		ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
		if (supplier == null)
			return Mono.just(new EmptyResponse());
		return supplier.get(request).next().map(this::selectBestInstance);
	}

	private Response<ServiceInstance> selectBestInstance(List<ServiceInstance> instances) {
		if (instances == null || instances.isEmpty())
			return new EmptyResponse();
		if (instances.size() == 1)
			return new DefaultResponse(instances.get(0));

		int n = instances.size();
		int totalInflight = inflightTracker.getTotalInflight();
		long now = System.currentTimeMillis();

		// ══ PASS 1: Thu thập score + cập nhật smoothedCapMcdm ═══════════════
		record NodeInfo(ServiceInstance inst, double rawMcdm, double smoothedMcdm, int inflight, boolean inWarmup) {
		}
		List<NodeInfo> nodes = new ArrayList<>(n);

		for (ServiceInstance inst : instances) {
			String id = inst.getInstanceId();
			long firstSeen = firstSeenMs.computeIfAbsent(id, k -> now);
			boolean inWarmup = (now - firstSeen) < WARMUP_MS;

			ScoreBreakdown bd = cache.getScore(id);
			double rawMcdm = (bd != null) ? Math.max(SCORE_FLOOR, bd.finalScore()) : DEFAULT_SCORE;

			// Symmetric EMA 0.20: phân kỳ đủ nhanh (half-life ≈ 3 polls = 600ms)
			// mà không oscillate theo từng request
			double prevSmoothed = smoothedCapMcdm.getOrDefault(id, rawMcdm);
			double smoothed = CAP_WEIGHT_EMA * rawMcdm + (1.0 - CAP_WEIGHT_EMA) * prevSmoothed;
			smoothedCapMcdm.put(id, smoothed);

			int inflight = inflightTracker.getInflight(id);
			nodes.add(new NodeInfo(inst, rawMcdm, smoothed, inflight, inWarmup));
		}

		// ══ Warmup guard: tất cả nodes chưa có score → round-robin ══════════
		boolean allWarmup = nodes.stream().allMatch(NodeInfo::inWarmup);
		if (allWarmup) {
			int idx = (int) (rrCounter.getAndIncrement() % n);
			ServiceInstance sel = nodes.get(idx).inst();
			emitMetric(sel);
			return new DefaultResponse(sel);
		}

		// ══ PASS 2: Capacity-weighted fair share (capped 3:1) ════════════════
		double[] capWeights = new double[n];
		double maxCapW = 0;
		for (int i = 0; i < n; i++) {
			capWeights[i] = nodes.get(i).inWarmup() ? 1.0
					: 1.0 / Math.sqrt(Math.max(SCORE_FLOOR, nodes.get(i).smoothedMcdm()));
			if (capWeights[i] > maxCapW)
				maxCapW = capWeights[i];
		}

		double sumCap = 0;
		for (int i = 0; i < n; i++) {
			capWeights[i] = Math.max(capWeights[i], maxCapW / MAX_CAP_WEIGHT_RATIO);
			sumCap += capWeights[i];
		}
		double[] fairShare = new double[n];
		for (int i = 0; i < n; i++)
			fairShare[i] = capWeights[i] / sumCap;

		// ══ PASS 3: Minimum inflight để tính relative penalty ════════════════
		int minCurrentInflight = Integer.MAX_VALUE;
		for (NodeInfo node : nodes) {
			if (node.inflight() < minCurrentInflight) {
				minCurrentInflight = node.inflight();
			}
		}

		// ══ PASS 4: Routing score = rawMcdm + relPenalty + absPenalty ════════
		ServiceInstance best = null;
		double bestScore = Double.MAX_VALUE;
		ServiceInstance leastLoadFb = null;
		int minInflFb = Integer.MAX_VALUE;

		for (int i = 0; i < n; i++) {
			NodeInfo node = nodes.get(i);
			if (node.inflight() >= INFLIGHT_HARD_CAP)
				continue;

			if (node.inflight() < minInflFb) {
				minInflFb = node.inflight();
				leastLoadFb = node.inst();
			}

			// Relative penalty: crossover ở delta=4 inflight (4 × 0.008 = 0.032 > MCDM
			// diff)
			double relLoad = Math.max(0.0, node.inflight() - minCurrentInflight);
			double relPenalty = OMEGA_REL * relLoad;

			// Absolute penalty: chỉ khi vượt capacity-weighted expected
			double expectedInflight = totalInflight > 0 ? totalInflight * fairShare[i] : 0.0;
			double excessRatio = expectedInflight > 0 ? Math.max(0.0, (double) node.inflight() / expectedInflight - 1.0)
					: 0.0;
			double absPenalty = OMEGA_ABS * Math.pow(excessRatio, PENALTY_EXPONENT);

			double routingScore = node.rawMcdm() + relPenalty + absPenalty;

			if (routingScore < bestScore) {
				bestScore = routingScore;
				best = node.inst();
			}
		}

		if (best == null) {
			best = (leastLoadFb != null) ? leastLoadFb : instances.get(0);
			log.warn("[ALB] All nodes at INFLIGHT_HARD_CAP={}", INFLIGHT_HARD_CAP);
		}

		emitMetric(best);
		return new DefaultResponse(best);
	}

	private void emitMetric(ServiceInstance inst) {
		Metrics.counter("alb.routing.selected", "backend", inst.getInstanceId(), "port", String.valueOf(inst.getPort()))
				.increment();
	}
}