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
        if (instances == null || instances.isEmpty()) {
            return new EmptyResponse();
        }
        if (instances.size() == 1) {
            return new DefaultResponse(instances.get(0));
        }

        // THUẬT TOÁN P2C (Power of Two Choices) - Khắc phục bầy đàn
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int i = random.nextInt(instances.size());
        int j = random.nextInt(instances.size());
        while (i == j) { // Đảm bảo chọn 2 node khác nhau
            j = random.nextInt(instances.size());
        }

        ServiceInstance inst1 = instances.get(i);
        ServiceInstance inst2 = instances.get(j);

        double score1 = calculateRealTimeScore(inst1.getInstanceId());
        double score2 = calculateRealTimeScore(inst2.getInstanceId());

        ServiceInstance selected = score1 <= score2 ? inst1 : inst2;

        Metrics.counter("alb.routing.selected",
        	    "backend", selected.getInstanceId(),
        	    "port", String.valueOf(selected.getPort())
        	).increment();

        return new DefaultResponse(selected);
    }

    // Tích hợp Local Inflight ngay tại thời điểm định tuyến (mili-giây)
    private double calculateRealTimeScore(String instanceId) {
        ScoreBreakdown breakdown = cache.getScore(instanceId);
        double baseScore = (breakdown != null) ? breakdown.finalScore() : 0.5;
        
        int localInflight = inflightTracker.getInflight(instanceId);
        
        // Dùng hàm log để tránh penalty tuyến tính quá cứng khi có bão tải
        double inflightPenalty = 0.4 * Math.log(1.0 + localInflight);
        
        return baseScore + inflightPenalty;
    }
}