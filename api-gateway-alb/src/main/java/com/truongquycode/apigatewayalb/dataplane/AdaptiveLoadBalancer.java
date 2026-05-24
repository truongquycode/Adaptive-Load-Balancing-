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

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) {
            return Mono.just(new EmptyResponse());
        }
        return supplier.get(request).next().map(this::selectBestInstance);
    }

    private Response<ServiceInstance> selectBestInstance(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) return new EmptyResponse();
        if (instances.size() == 1) return new DefaultResponse(instances.get(0));

        int activeNodes = instances.size();
        ServiceInstance bestInstance = null;
        double bestScore = Double.MAX_VALUE;

        for (ServiceInstance instance : instances) {
        	double score = calculateScore(instance.getInstanceId());
            if (score < bestScore) {
                bestScore = score;
                bestInstance = instance;
            }
        }

        ServiceInstance selected = bestInstance != null ? bestInstance : instances.get(0);

        Metrics.counter("alb.routing.selected",
            "backend", selected.getInstanceId(),
            "port", String.valueOf(selected.getPort())
        ).increment();
        
        

        return new DefaultResponse(selected);
    }
    private double calculateScore(String instanceId) {

        ScoreBreakdown breakdown =
                cache.getScore(instanceId);

        if (breakdown == null) {
            return 999.0;
        }

        return breakdown.finalScore();
    }
}