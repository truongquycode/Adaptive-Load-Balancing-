package com.truongquycode.apigatewayalb.dataplane;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.stereotype.Component;

@Component
public class InflightLifecycle implements LoadBalancerLifecycle<Object, Object, ServiceInstance> {

    private final InflightTracker tracker;

    public InflightLifecycle(InflightTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public boolean supports(Class requestContextClass, Class responseClass, Class serverTypeClass) {
        return true; // Áp dụng cho mọi request đi qua LB
    }

    @Override
    public void onStart(Request<Object> request) {}

    @Override
    public void onStartRequest(Request<Object> request, Response<ServiceInstance> lbResponse) {
        // Kích hoạt ngay khi Gateway VỪA CHỌN XONG 1 máy chủ (kể cả khi Retry)
        if (lbResponse != null && lbResponse.hasServer()) {
            tracker.increment(lbResponse.getServer().getInstanceId());
        }
    }

    @Override
    public void onComplete(CompletionContext<Object, ServiceInstance, Object> completionContext) {
        // Kích hoạt khi Request kết thúc (Dù thành công, Lỗi, Timeout, hay Hủy)
        if (completionContext.getLoadBalancerResponse() != null && completionContext.getLoadBalancerResponse().hasServer()) {
            tracker.decrement(completionContext.getLoadBalancerResponse().getServer().getInstanceId());
        }
    }
}