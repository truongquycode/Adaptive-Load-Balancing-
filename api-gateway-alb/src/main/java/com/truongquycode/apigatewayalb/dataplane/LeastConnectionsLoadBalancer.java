package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.util.MetricsCache;
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
    private final MetricsCache cache;

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) {
            return Mono.just(new EmptyResponse());
        }
        // Gọi đúng tên hàm selectLeastConnectionsInstance
        return supplier.get(request).next().map(this::selectLeastConnectionsInstance);
    }

    // Đã đổi tên hàm cho khớp
    private Response<ServiceInstance> selectLeastConnectionsInstance(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return new EmptyResponse();
        }

        ServiceInstance bestInstance = null;
        double minConnections = Double.MAX_VALUE;

        for (ServiceInstance instance : instances) {
            String id = instance.getInstanceId();
            
            // Lấy metrics thô từ Cache (Control Plane đã cập nhật)
            InstanceMetrics metrics = cache.getMetrics(id);

            // Nếu chưa có metrics (lúc mới start), giả định số connection = 0
            double currentConnections = (metrics != null) ? metrics.getQueueLength() : 0;

            if (currentConnections < minConnections) {
                minConnections = currentConnections;
                bestInstance = instance;
            } else if (currentConnections == minConnections) {
                // Nếu số connection bằng nhau, Random để phân tải đều, tránh dồn cục (Herd Effect)
                if (bestInstance == null || ThreadLocalRandom.current().nextBoolean()) {
                    bestInstance = instance;
                }
            }
        }

        return new DefaultResponse(bestInstance != null ? bestInstance : instances.get(0));
    }
}