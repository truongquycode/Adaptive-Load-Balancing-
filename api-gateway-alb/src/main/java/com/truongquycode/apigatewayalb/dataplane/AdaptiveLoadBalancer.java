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

		int n = instances.size();
		ThreadLocalRandom rng = ThreadLocalRandom.current();

		// P2C: chọn ngẫu nhiên 2 instance KHÁC NHAU
		int idxA = rng.nextInt(n);
		int idxB;
		do {
			idxB = rng.nextInt(n);
		} while (idxB == idxA);

		ServiceInstance candidateA = instances.get(idxA);
		ServiceInstance candidateB = instances.get(idxB);

		double scoreA = calculateRealTimeScore(candidateA.getInstanceId(), n);
		double scoreB = calculateRealTimeScore(candidateB.getInstanceId(), n);

		ServiceInstance selected = scoreA <= scoreB ? candidateA : candidateB;

		Metrics.counter("alb.routing.selected", "backend", selected.getInstanceId(), "port",
				String.valueOf(selected.getPort())).increment();

		return new DefaultResponse(selected);
	}

	// Tích hợp Local Inflight ngay tại thời điểm định tuyến (mili-giây)
	private double calculateRealTimeScore(String instanceId, int activeNodes) {
		ScoreBreakdown breakdown = cache.getScore(instanceId);
		double baseScore = (breakdown != null) ? breakdown.finalScore() : 0.5;

		int localInflight = inflightTracker.getInflight(instanceId);
		int totalInflight = inflightTracker.getTotalInflight();

		double inflightPenalty = 0.0;

		if (totalInflight > 0 && activeNodes > 0) {
			double relativeShare = (double) localInflight / totalInflight;
			double fairShare = 1.0 / activeNodes;
			double excessShare = Math.max(0.0, relativeShare - fairShare);

			// SỬA TẠI ĐÂY: Giảm omega từ 1.5 xuống 0.3
			// Khống chế sức mạnh của Data Plane. Inflight Penalty giờ đây tối đa chỉ rơi
			// vào khoảng 0.15 - 0.3
			// Đảm bảo nó KHÔNG THỂ lớn hơn điểm phạt từ MCDM và PID (luôn > 1.0 khi có
			// lỗi).
			double omega = 0.8;
			inflightPenalty = omega * Math.log(1.0 + excessShare);
		}

		return baseScore + inflightPenalty;
	}
}