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
import java.util.Comparator;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

	private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
	private final MetricsCache cache;
	private final InflightTracker inflightTracker;

	// Hard cap chỉ khi inflight vượt ngưỡng vật lý
	private static final int INFLIGHT_HARD_CAP = 200;

	// OMEGA: trọng số của inflight penalty trong routing score.
	// Tăng lên 2.5 để inflight trở thành tín hiệu PRIMARY,
	// không phải secondary sau MCDM score.
	private static final double OMEGA = 2.5;

	// MCDM_WEIGHT: trọng số của MCDM score trong routing decision.
	// Giảm xuống để MCDM đóng vai trò capacity estimation,
	// không phải bộ phán quyết tuyệt đối.
	private static final double MCDM_WEIGHT = 0.4;

	// Floor tránh chia cho 0 khi tính capacity weight
	private static final double SCORE_FLOOR = 0.05;

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

		// ── PASS 1: Thu thập score + inflight ────────────────────────────────
		record NodeInfo(ServiceInstance inst, double mcdm, int inflight) {
		}
		List<NodeInfo> nodes = new ArrayList<>(n);

		for (ServiceInstance inst : instances) {
			ScoreBreakdown bd = cache.getScore(inst.getInstanceId());
			// Dùng SCORE_FLOOR để tránh division by zero trong capacity weight
			double mcdm = (bd != null) ? Math.max(SCORE_FLOOR, bd.finalScore()) : 0.5;
			int inflight = inflightTracker.getInflight(inst.getInstanceId());
			nodes.add(new NodeInfo(inst, mcdm, inflight));
		}

		// ── PASS 2: Tính capacity-weighted expected inflight ─────────────────
		//
		// Nguyên lý: Node có MCDM score thấp hơn = node "tốt hơn" = capacity cao hơn.
		// capacity_weight_i = 1 / mcdm_i
		// Normalized: fairShare_i = capWeight_i / sum(capWeights)
		// → fairShare_i * totalInflight = expected inflight của node i
		//
		// Ví dụ với 3 nodes (mcdm: 0.3, 0.5, 0.8):
		// capWeights = [3.33, 2.0, 1.25] → sum=6.58
		// fairShare = [0.507, 0.304, 0.190] (sum=1)
		// Node 0 được phân bổ 50.7% traffic, node 2 chỉ 19%
		// → Phù hợp với năng lực thực tế

		double[] capWeights = new double[n];
		double sumCap = 0;
		for (int i = 0; i < n; i++) {
			capWeights[i] = 1.0 / nodes.get(i).mcdm();
			sumCap += capWeights[i];
		}
		// Normalize thành fair share (tổng = 1)
		double[] fairShare = new double[n];
		for (int i = 0; i < n; i++) {
			fairShare[i] = capWeights[i] / sumCap;
		}

		// ── PASS 3: Tính routing score cho từng node ──────────────────────────
		//
		// routingScore = MCDM_WEIGHT * mcdm + OMEGA * inflightPenalty
		//
		// inflightPenalty = log(1 + excess / expectedInflight)
		// excess = max(0, inflight_i - expectedInflight_i)
		// Khi node nhận đúng phần của mình: excess=0 → penalty=0
		// Khi node nhận vượt capacity: penalty tăng nhanh → route sang node khác
		//
		// Khác với trước (cap 0.40):
		// Không có cap cứng → khi node thực sự quá tải, penalty có thể lớn hơn MCDM
		// → Inflight trở thành tín hiệu dominant như trong LeastConn

		ServiceInstance best = null;
		double bestScore = Double.MAX_VALUE;
		ServiceInstance leastInflightFallback = null;
		int minInflight = Integer.MAX_VALUE;

		for (int i = 0; i < n; i++) {
			NodeInfo node = nodes.get(i);

			// Hard cap: chỉ loại hoàn toàn khi vượt INFLIGHT_HARD_CAP
			if (node.inflight() >= INFLIGHT_HARD_CAP)
				continue;

			// Cập nhật fallback (node ít inflight nhất, dùng khi tất cả đều xấu)
			if (node.inflight() < minInflight) {
				minInflight = node.inflight();
				leastInflightFallback = node.inst();
			}

			double expectedInflight = totalInflight > 0 ? totalInflight * fairShare[i] : 0.0;

			// Lượng inflight vượt capacity
			double excess = Math.max(0.0, node.inflight() - expectedInflight);

			// Chuẩn hóa theo expected để penalty độc lập với total load
			// (expectedInflight + 1) tránh chia cho 0 khi totalInflight nhỏ
			double normalizedExcess = excess / (expectedInflight + 1.0);
			double inflightPenalty = OMEGA * Math.log1p(normalizedExcess);

			// Routing score = capacity-adjusted quality + inflight pressure
			double routingScore = MCDM_WEIGHT * node.mcdm() + inflightPenalty;

			if (routingScore < bestScore) {
				bestScore = routingScore;
				best = node.inst();
			}
		}

		// ── Fallback: tất cả đều ở INFLIGHT_HARD_CAP ─────────────────────────
		if (best == null) {
			best = leastInflightFallback != null ? leastInflightFallback : instances.get(0);
			log.warn("All nodes at INFLIGHT_HARD_CAP={}, routing to least-inflight fallback", INFLIGHT_HARD_CAP);
		}

		Metrics.counter("alb.routing.selected", "backend", best.getInstanceId(), "port", String.valueOf(best.getPort()))
				.increment();

		return new DefaultResponse(best);
	}
}