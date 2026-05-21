package com.truongquycode.apigatewayalb.dataplane;

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
public class LeastConnectionsLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    // THAY ĐỔI 1: Bỏ MetricsCache, thay bằng InflightTracker giống hệt Adaptive
    private final InflightTracker inflightTracker; 

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) {
            return Mono.just(new EmptyResponse());
        }
        return supplier.get(request).next().map(this::selectLeastConnectionsInstance);
    }

    private Response<ServiceInstance> selectLeastConnectionsInstance(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return new EmptyResponse();
        }

        ServiceInstance bestInstance = null;
        int minConnections = Integer.MAX_VALUE;

        for (ServiceInstance instance : instances) {
            String id = instance.getInstanceId();
            
            // THAY ĐỔI 2: Đọc trực tiếp từ InflightTracker ở tốc độ mili-giây (O(1))
            int currentConnections = inflightTracker.getInflight(id);

            if (currentConnections < minConnections) {
                minConnections = currentConnections;
                bestInstance = instance;
            } else if (currentConnections == minConnections) {
                // Xử lý Tie-breaker: Phân tải đều bằng Random khi bằng điểm
                if (bestInstance == null || ThreadLocalRandom.current().nextBoolean()) {
                    bestInstance = instance;
                }
            }
        }

        ServiceInstance selected = bestInstance != null ? bestInstance : instances.get(0);

        io.micrometer.core.instrument.Metrics.counter("alb.routing.selected",
            "backend", selected.getInstanceId(),
            "port", String.valueOf(selected.getPort())
        ).increment();

        return new DefaultResponse(selected);
    }
}