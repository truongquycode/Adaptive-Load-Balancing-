package com.truongquycode.apigatewayalb.dataplane;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.stereotype.Component;

@Component
public class InflightLifecycle implements LoadBalancerLifecycle<Object, Object, ServiceInstance> {

    private final InflightTracker tracker;
    // Track instance nào được chọn cho request hiện tại
    // Key: requestId (hoặc context hash), Value: instanceId đã increment
    private final ConcurrentHashMap<Integer, String> activeRequests = new ConcurrentHashMap<>();

    public InflightLifecycle(InflightTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public boolean supports(Class requestContextClass, Class responseClass, Class serverTypeClass) {
        return true;
    }

    @Override
    public void onStart(Request<Object> request) {}

    @Override
    public void onStartRequest(Request<Object> request, Response<ServiceInstance> lbResponse) {
        if (lbResponse != null && lbResponse.hasServer()) {
            String instanceId = lbResponse.getServer().getInstanceId();
            int key = System.identityHashCode(request);
            
            // Nếu request này đã có instance cũ (retry), decrement instance cũ trước
            String prev = activeRequests.put(key, instanceId);
            if (prev != null && !prev.equals(instanceId)) {
                tracker.decrement(prev); // Giải phóng counter của instance thất bại
            }
            tracker.increment(instanceId);
        }
    }

    @Override
    public void onComplete(CompletionContext<Object, ServiceInstance, Object> completionContext) {
        if (completionContext.getLoadBalancerResponse() != null 
                && completionContext.getLoadBalancerResponse().hasServer()) {
            String instanceId = completionContext.getLoadBalancerResponse().getServer().getInstanceId();
            int key = System.identityHashCode(completionContext.getLoadBalancerResponse());
            activeRequests.remove(key);
            tracker.decrement(instanceId);
        }
    }
}