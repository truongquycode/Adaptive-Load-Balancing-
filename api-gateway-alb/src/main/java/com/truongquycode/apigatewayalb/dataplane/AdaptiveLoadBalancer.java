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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RequiredArgsConstructor
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

	private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
	private final MetricsCache cache;
	private final InflightTracker inflightTracker; // Tích hợp Local Tracker
	private static final int INFLIGHT_HARD_CAP = 80; // Tomcat max=500, để an toàn
	private static final double SCORE_OPEN_THRESHOLD = 1.5; // Score này = instance "circuit open"

	@Override
	public Mono<Response<ServiceInstance>> choose(Request request) {
		ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
		if (supplier == null) {
			return Mono.just(new EmptyResponse());
		}
		return supplier.get(request).next().map(this::selectBestInstance);
	}

	private Response<ServiceInstance> selectBestInstance(List<ServiceInstance> instances) {
		if (instances == null || instances.isEmpty())
			return new EmptyResponse();
		if (instances.size() == 1)
			return new DefaultResponse(instances.get(0));

		int activeNodes = instances.size();
		ServiceInstance bestInstance = null;
		double bestScore = Double.MAX_VALUE;

		for (ServiceInstance instance : instances) {
			double score = calculateRealTimeScore(instance.getInstanceId(), activeNodes);
			if (score < bestScore) {
				bestScore = score;
				bestInstance = instance;
			}
		}

		ServiceInstance selected = bestInstance != null ? bestInstance : instances.get(0);

		Metrics.counter("alb.routing.selected", "backend", selected.getInstanceId(), "port",
				String.valueOf(selected.getPort())).increment();

		return new DefaultResponse(selected);
	}

	// Tích hợp Local Inflight ngay tại thời điểm định tuyến (mili-giây)
	private double calculateRealTimeScore(String instanceId, int activeNodes) {
		ScoreBreakdown breakdown = cache.getScore(instanceId);
		double baseScore = (breakdown != null) ? breakdown.finalScore() : 0.5;

		// ── Hard cap: nếu inflight quá cao, tạm thời không route vào đây ────
		int localInflight = inflightTracker.getInflight(instanceId);
		if (localInflight >= INFLIGHT_HARD_CAP) {
			log.debug("Instance {} hit inflight cap ({} >= {}), applying max penalty", instanceId, localInflight,
					INFLIGHT_HARD_CAP);
			return 15.0; // Cao hơn penalty error (10.0) để ưu tiên hơn node lỗi
		}

		// ── Soft exclusion: nếu score rất cao (circuit-open), vẫn cho 5% traffic ─
		// Lý do: tránh trường hợp TẤT CẢ instance đều bị exclude cùng lúc
		// và để detect khi instance đã phục hồi (nếu không route vào sẽ không biết)
		// Chú ý: với asymmetric EMA recovery chậm, instance sẽ không bị flooded ngay
		if (baseScore >= SCORE_OPEN_THRESHOLD) {
			// Chỉ route nếu ngẫu nhiên 5% (giống HALF_OPEN probe)
			if (ThreadLocalRandom.current().nextDouble() > 0.05) {
				return baseScore; // Vẫn trả score cao để ít được chọn nhất
			}
			// 5% còn lại: probe với score = 2.0 (thấp hơn healthy instances một chút)
			return 2.0;
		}

		int totalInflight = inflightTracker.getTotalInflight();
		double inflightPenalty = 0.0;

		if (totalInflight > 0 && activeNodes > 0) {
			double relativeShare = (double) localInflight / totalInflight;
			double fairShare = 1.0 / activeNodes;
			double excessShare = Math.max(0.0, relativeShare - fairShare);

			// ── Tăng omega từ 0.8 → 1.2 để phân tán load đều hơn ────────────
			// Kết hợp với hard cap, inflight penalty không cần quá mạnh
			double omega = 1.2;
			inflightPenalty = omega * Math.log(1.0 + excessShare);
		}

		return baseScore + inflightPenalty;
	}
}