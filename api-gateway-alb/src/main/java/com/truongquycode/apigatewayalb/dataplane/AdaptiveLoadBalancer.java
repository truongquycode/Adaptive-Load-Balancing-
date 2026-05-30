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

	// ── Hằng số ─────────────────────────────────────────────────────────────
	private static final int INFLIGHT_HARD_CAP = 200;

	// OMEGA_REL: penalty tuyến tính theo (inflight - minInflight).
	// Luôn active ở mọi mức tải.
	// Crossover point: khi 1 node có thêm DELTA_CROSSOVER requests hơn node nhẹ
	// nhất,
	// inflight bắt đầu thắng MCDM.
	// DELTA_CROSSOVER = typical_MCDM_diff / OMEGA_REL = 0.03 / 0.008 ≈ 4 requests.
	// → Khi chênh lệch inflight > 4: routing ngả về LeastConn.
	// → Khi chênh lệch inflight ≤ 4: MCDM quyết định (node tốt hơn thắng).
	private static final double OMEGA_REL = 0.008;

	// OMEGA_ABS: penalty power-law khi vượt capacity-weighted fair share.
	// Chỉ active khi ratio > 1.0. Bảo vệ khỏi overload ở high load.
	private static final double OMEGA_ABS = 3.0;

	// Exponent > 1 tạo convex curve: penalty tăng nhanh hơn tuyến tính.
	private static final double PENALTY_EXPONENT = 1.3;

	private static final double SCORE_FLOOR = 0.05;
	private static final double DEFAULT_SCORE = 0.35;

	// CAP_WEIGHT_EMA: EMA cân bằng (0.20, symmetric) cho smoothed capacity weight.
	// Trước: asymmetric 0.07/0.30 → alpha quá nhỏ khi giảm → scores hội tụ về bằng
	// nhau
	// → capacity weights bằng nhau → routing = round-robin.
	// Sau: symmetric 0.20 → phản ứng đều cả 2 chiều → weights thực sự phân kỳ.
	private static final double CAP_WEIGHT_EMA = 0.20;

	// Giới hạn tỷ lệ capacity weight tối đa giữa bất kỳ 2 node nào.
	// Ngăn một node nhận > totalInflight * 3/(3+1+1) = 60% traffic khi có 3 node.
	private static final double MAX_CAP_WEIGHT_RATIO = 3.0;

	// Rút ngắn warmup từ 15s → 5s.
	// 15s warmup round-robin tạo ra nhiều requests tại baseline thấp → kéo median
	// xuống
	// → bimodal distribution lớn hơn → Avg/Median ratio càng cao.
	// 5s đủ để JVM JIT compile xong và histogram có vài chục samples.
	private static final long WARMUP_MS = 5_000;

	// ── State fields (initialized inline → không vào Lombok constructor) ────
	private final ConcurrentHashMap<String, Double> smoothedCapMcdm = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Long> firstSeenMs = new ConcurrentHashMap<>();
	private final AtomicLong rrCounter = new AtomicLong(0);

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

		// ══ PASS 1: Thu thập + cập nhật smoothedCapMcdm ═════════════════════
		record NodeInfo(ServiceInstance inst, double rawMcdm, double smoothedMcdm, int inflight, boolean inWarmup) {
		}
		List<NodeInfo> nodes = new ArrayList<>(n);

		for (ServiceInstance inst : instances) {
			String id = inst.getInstanceId();
			long firstSeen = firstSeenMs.computeIfAbsent(id, k -> now);
			boolean inWarmup = (now - firstSeen) < WARMUP_MS;

			ScoreBreakdown bd = cache.getScore(id);
			double rawMcdm = (bd != null) ? Math.max(SCORE_FLOOR, bd.finalScore()) : DEFAULT_SCORE;

			// Symmetric EMA: 0.20 cả tăng lẫn giảm.
			// Đủ nhanh để phân kỳ (half-life ≈ 3 polls = 600ms @ 200ms interval),
			// đủ chậm để không oscillate theo từng request.
			double prevSmoothed = smoothedCapMcdm.getOrDefault(id, rawMcdm);
			double smoothed = CAP_WEIGHT_EMA * rawMcdm + (1 - CAP_WEIGHT_EMA) * prevSmoothed;
			smoothedCapMcdm.put(id, smoothed);

			int inflight = inflightTracker.getInflight(id);
			nodes.add(new NodeInfo(inst, rawMcdm, smoothed, inflight, inWarmup));
		}

		// ══ Warmup guard: all nodes chưa có score → round-robin ═════════════
		boolean allWarmup = nodes.stream().allMatch(NodeInfo::inWarmup);
		if (allWarmup) {
			int idx = (int) (rrCounter.getAndIncrement() % n);
			ServiceInstance sel = nodes.get(idx).inst();
			emitMetric(sel);
			return new DefaultResponse(sel);
		}

		// ══ PASS 2: Capacity-weighted fair share với capped ratio ════════════
		//
		// Dùng smoothedMcdm để tính capacity weight → ổn định hơn rawMcdm.
		// Hàm: capWeight = 1 / sqrt(smoothedMcdm)
		// → node tốt (score thấp) nhận nhiều traffic hơn
		// → nhưng không cực đoan như linear (1/score)
		//
		// Sau khi tính, cap ratio giữa 2 node bất kỳ ở MAX_CAP_WEIGHT_RATIO=3:1
		// → Ngăn 1 node nhận > ~60% traffic (với 3 node)
		// → Giữ 8083 luôn nhận ít nhất ~20% traffic (warm cache, detection ready)

		double[] capWeights = new double[n];
		double maxCapW = 0;
		for (int i = 0; i < n; i++) {
			capWeights[i] = nodes.get(i).inWarmup() ? 1.0
					: 1.0 / Math.sqrt(Math.max(SCORE_FLOOR, nodes.get(i).smoothedMcdm()));
			if (capWeights[i] > maxCapW)
				maxCapW = capWeights[i];
		}

		// Áp dụng floor = maxCapW / MAX_CAP_WEIGHT_RATIO → đảm bảo tỷ lệ ≤ 3:1
		double sumCap = 0;
		for (int i = 0; i < n; i++) {
			capWeights[i] = Math.max(capWeights[i], maxCapW / MAX_CAP_WEIGHT_RATIO);
			sumCap += capWeights[i];
		}
		double[] fairShare = new double[n];
		for (int i = 0; i < n; i++)
			fairShare[i] = capWeights[i] / sumCap;

		// ══ PASS 3: Tìm minimum inflight trong tất cả nodes ═════════════════
		//
		// minCurrentInflight dùng cho relative penalty (OMEGA_REL):
		// Bất kỳ node nào có inflight cao hơn min đều bị penalize tuyến tính.
		// → Active ngay cả khi ratio = 1.0 → ngăn extreme concentration ở low load.

		int minCurrentInflight = Integer.MAX_VALUE;
		for (NodeInfo node : nodes) {
			if (node.inflight() < minCurrentInflight) {
				minCurrentInflight = node.inflight();
			}
		}

		// ══ PASS 4: Tính routing score với dual penalty ══════════════════════
		//
		// routingScore = rawMcdm + relPenalty + absPenalty
		//
		// relPenalty = OMEGA_REL × (inflight - minInflight)
		// Ví dụ: inflight [20, 15, 5], OMEGA_REL=0.008:
		// Node0: 0.008 × 15 = 0.120 → bị phạt nặng
		// Node1: 0.008 × 10 = 0.080 → bị phạt vừa
		// Node2: 0.008 × 0 = 0 → không phạt → ưu tiên nhận traffic
		// Crossover: MCDM_diff=0.03 → delta_inflight=0.03/0.008=3.75
		// → Khi 1 node có thêm ≥ 4 inflight so với node nhẹ nhất:
		// inflight thắng MCDM (routing = LeastConn)
		// → Khi chênh lệch < 4: MCDM thắng (routing = capacity-aware)
		//
		// absPenalty = OMEGA_ABS × max(0, ratio−1)^1.3
		// Chỉ active khi inflight vượt capacity-weighted expected.
		// Bảo vệ lớp thứ 2: ngăn overload khi cả 2 loại penalty cùng lúc.

		ServiceInstance best = null;
		double bestScore = Double.MAX_VALUE;
		ServiceInstance leastLoadFb = null;
		int minInflFb = Integer.MAX_VALUE;

		for (int i = 0; i < n; i++) {
			NodeInfo node = nodes.get(i);
			if (node.inflight() >= INFLIGHT_HARD_CAP)
				continue;

			// Fallback: node ít inflight nhất (dùng nếu tất cả đều ở hard cap)
			if (node.inflight() < minInflFb) {
				minInflFb = node.inflight();
				leastLoadFb = node.inst();
			}

			// Relative penalty (luôn active)
			double relLoad = Math.max(0.0, node.inflight() - minCurrentInflight);
			double relPenalty = OMEGA_REL * relLoad;

			// Absolute penalty (chỉ active khi vượt expected)
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

		// Fallback nếu tất cả ở INFLIGHT_HARD_CAP
		if (best == null) {
			best = (leastLoadFb != null) ? leastLoadFb : instances.get(0);
			log.warn("All nodes at INFLIGHT_HARD_CAP={}", INFLIGHT_HARD_CAP);
		}

		emitMetric(best);
		return new DefaultResponse(best);
	}

	private void emitMetric(ServiceInstance inst) {
		Metrics.counter("alb.routing.selected", "backend", inst.getInstanceId(), "port", String.valueOf(inst.getPort()))
				.increment();
	}
}