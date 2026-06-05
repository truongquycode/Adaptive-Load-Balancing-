package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.util.MetricsCache;
import io.micrometer.core.instrument.Counter;
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
	private static final double OMEGA_REL = 0.010;
	private static final double OMEGA_ABS = 0.6;
	private static final double PENALTY_EXPONENT = 1.3;
	private static final double SCORE_FLOOR = 0.05;
	private static final double DEFAULT_SCORE = 0.35;
	private static final double MAX_CAP_WEIGHT_RATIO = 3.0;
	private static final long WARMUP_MS = 5_000;

	// ── Static state: dùng static để AdminController.resetStaticState() tiếp cận
	// được
	//
	// firstSeenMs : thời điểm instance xuất hiện lần đầu → dùng cho warmup guard
	// rrCounter : bộ đếm round-robin khi toàn bộ node đang trong warmup
	// counterCache : cache Micrometer Counter để tránh registry lookup trên mỗi
	// request
	//
	// Tại sao static:
	// AdaptiveLoadBalancer được tạo trong Spring Cloud LB child context →
	// AdminController (parent context) không thể inject để reset field thường.
	// Static + ConcurrentHashMap đảm bảo thread-safe và accessible từ bất kỳ đâu.
	private static final ConcurrentHashMap<String, Long> firstSeenMs = new ConcurrentHashMap<>();
	private static final AtomicLong rrCounter = new AtomicLong(0);
	private static final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

	public static void resetStaticState() {
		firstSeenMs.clear();
		rrCounter.set(0);
		counterCache.clear();
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

		// PASS 1: Thu thập score + min inflight
		record NodeInfo(ServiceInstance inst, double rawMcdm, int inflight, boolean inWarmup) {
		}
		List<NodeInfo> nodes = new ArrayList<>(n);
		int minCurrentInflight = Integer.MAX_VALUE;

		for (ServiceInstance inst : instances) {
			String id = inst.getInstanceId();
			long firstSeen = firstSeenMs.computeIfAbsent(id, k -> now);
			boolean inWarmup = (now - firstSeen) < WARMUP_MS;

			ScoreBreakdown bd = cache.getScore(id);
			double rawMcdm = (bd != null) ? Math.max(SCORE_FLOOR, bd.finalScore()) : DEFAULT_SCORE;

			int inflight = inflightTracker.getInflight(id);
			if (inflight < minCurrentInflight)
				minCurrentInflight = inflight; // ← merged từ PASS 3 cũ

			nodes.add(new NodeInfo(inst, rawMcdm, inflight, inWarmup));
		}

		// ══ Warmup guard: tất cả node chưa có score → round-robin ════════════
		boolean allWarmup = true;
		for (NodeInfo node : nodes) {
			if (!node.inWarmup()) {
				allWarmup = false;
				break;
			}
		}
		if (allWarmup) {
			int idx = (int) (rrCounter.getAndIncrement() % n);
			ServiceInstance sel = nodes.get(idx).inst();
			emitMetric(sel);
			return new DefaultResponse(sel);
		}

		// ══ PASS 2: Capacity-weighted fair share (capped 3:1) ════════════════
		//
		// share[] được tái sử dụng: đầu tiên chứa capWeight, cuối cùng chứa fairShare.
		// Tránh allocate thêm mảng fairShare[] thứ hai.
		double[] share = new double[n];
		double maxCapW = 0;
		for (int i = 0; i < n; i++) {
			share[i] = nodes.get(i).inWarmup() ? 1.0 : 1.0 / Math.sqrt(Math.max(SCORE_FLOOR, nodes.get(i).rawMcdm()));
			if (share[i] > maxCapW)
				maxCapW = share[i];
		}

		double sumCap = 0;
		double capFloor = maxCapW / MAX_CAP_WEIGHT_RATIO;
		for (int i = 0; i < n; i++) {
			if (share[i] < capFloor)
				share[i] = capFloor;
			sumCap += share[i];
		}
		for (int i = 0; i < n; i++)
			share[i] /= sumCap;

		// ══ PASS 3: Routing score = rawMcdm + relPenalty + absPenalty ════════
		ServiceInstance best = null;
		double bestScore = Double.MAX_VALUE;
		ServiceInstance leastLoadFb = null;
		int minInflFb = Integer.MAX_VALUE;

		for (int i = 0; i < n; i++) {
			NodeInfo node = nodes.get(i);
			int inf = node.inflight();

			if (inf >= INFLIGHT_HARD_CAP)
				continue;

			if (inf < minInflFb) {
				minInflFb = inf;
				leastLoadFb = node.inst();
			}

			// Relative penalty: crossover ở delta=4 inflight (4 × 0.010 = 0.040)
			double relPenalty = OMEGA_REL * Math.max(0.0, inf - minCurrentInflight);

			// Absolute penalty: guard excessRatio <= 0 để tránh Math.pow() thừa.
			// Trong điều kiện bình thường (load cân bằng), excessRatio = 0 cho tất cả node.
			// Math.pow(x, 1.3) là native call — bỏ qua khi không cần thiết.
			double absPenalty = 0.0;
			if (totalInflight > 0) {
				double expected = totalInflight * share[i];
				if (expected > 0) {
					double excessRatio = (double) inf / expected - 1.0;
					if (excessRatio > 0) {
						absPenalty = OMEGA_ABS * Math.pow(excessRatio, PENALTY_EXPONENT);
					}
				}
			}

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
		counterCache.computeIfAbsent(inst.getInstanceId(), k -> Metrics.counter("alb.routing.selected", "backend",
				inst.getInstanceId(), "port", String.valueOf(inst.getPort()))).increment();
	}
}