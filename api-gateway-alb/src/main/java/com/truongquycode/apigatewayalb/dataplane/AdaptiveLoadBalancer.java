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

        int activeNodes = instances.size(); // Đếm tổng số node hiện có
        double score1 = calculateRealTimeScore(inst1.getInstanceId(), activeNodes);
        double score2 = calculateRealTimeScore(inst2.getInstanceId(), activeNodes);

        ServiceInstance selected = score1 <= score2 ? inst1 : inst2;

        Metrics.counter("alb.routing.selected",
        	    "backend", selected.getInstanceId(),
        	    "port", String.valueOf(selected.getPort())
        	).increment();

        return new DefaultResponse(selected);
    }

    // Tích hợp Local Inflight ngay tại thời điểm định tuyến (mili-giây)
    private double calculateRealTimeScore(String instanceId, int activeNodes) {
        ScoreBreakdown breakdown = cache.getScore(instanceId);
        double baseScore = (breakdown != null) ? breakdown.finalScore() : 0.5;

        int localInflight = inflightTracker.getInflight(instanceId);
        int totalInflight = inflightTracker.getTotalInflight();

        double inflightPenalty = 0.0;
        
        // Tránh chia 0 và chỉ phạt khi hệ thống có tải
        if (totalInflight > 0 && activeNodes > 0) {
            double relativeShare = (double) localInflight / totalInflight;
            double fairShare = 1.0 / activeNodes;
            
            // Tính mức độ gánh dư thừa (nếu <= 0 tức là đang gánh ít hơn phần của mình)
            double excessShare = Math.max(0.0, relativeShare - fairShare);
            
            // Hàm phạt Logarit (omega = 1.5). 
            // Nếu node gánh 100% traffic của cụm 3 node (excess = 0.67), Penalty đạt xấp xỉ 0.76.
            // Mức này vừa đủ để P2C chuyển traffic đi, nhưng không quá lớn để bóp méo BaseScore.
            double omega = 1.5; 
            inflightPenalty = omega * Math.log(1.0 + excessShare);
        }

        return baseScore + inflightPenalty;
    }
}