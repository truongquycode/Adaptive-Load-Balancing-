package com.truongquycode.apigatewayalb.dataplane;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.stereotype.Component;

/**
 * Theo dõi số request đang bay (in-flight) cho mỗi instance.
 *
 * FIX ROOT CAUSE 3: Key mismatch giữa onStartRequest và onComplete
 *
 * Lỗi cũ:
 *   onStartRequest  → key = System.identityHashCode(REQUEST)      ← object A
 *   onComplete      → key = System.identityHashCode(LB_RESPONSE)  ← object B
 *   → keys KHÔNG BAO GIỜ khớp → activeRequests tích lũy vô hạn
 *   → Memory leak + JVM tái dụng địa chỉ → false decrement khi hash va chạm
 *
 * Fix:
 *   Dùng duy nhất LB_RESPONSE hash cho cả hai hook.
 *   Retry detection vẫn hoạt động qua requestKey map riêng biệt:
 *   requestKey tracks request → current lbResponse, phát hiện khi retry
 *   chọn instance khác và giảm counter cho instance cũ.
 */
@Component
public class InflightLifecycle implements LoadBalancerLifecycle<Object, Object, ServiceInstance> {

    private final InflightTracker tracker;

    /**
     * lbResponseKey → instanceId: dùng cho onComplete để decrement chính xác.
     * Key = System.identityHashCode(lbResponse) — đồng nhất với onComplete.
     */
    private final ConcurrentHashMap<Integer, String> pendingComplete = new ConcurrentHashMap<>();

    /**
     * requestKey → lbResponseKey: dùng để phát hiện retry (cùng request,
     * nhưng load balancer chọn instance khác lần này).
     */
    private final ConcurrentHashMap<Integer, Integer> requestToResponse = new ConcurrentHashMap<>();

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
        if (lbResponse == null || !lbResponse.hasServer()) return;

        String instanceId  = lbResponse.getServer().getInstanceId();
        int    reqKey      = System.identityHashCode(request);
        int    resKey      = System.identityHashCode(lbResponse);

        // Retry detection: nếu request này đã được gán lbResponse khác trước đó,
        // decrement counter của instance cũ (attempt thất bại).
        Integer prevResKey = requestToResponse.put(reqKey, resKey);
        if (prevResKey != null && !prevResKey.equals(resKey)) {
            String prevInstanceId = pendingComplete.remove(prevResKey);
            if (prevInstanceId != null) {
                tracker.decrement(prevInstanceId);
            }
        }

        pendingComplete.put(resKey, instanceId);
        tracker.increment(instanceId);
    }

    @Override
    public void onComplete(CompletionContext<Object, ServiceInstance, Object> completionContext) {
        if (completionContext.getLoadBalancerResponse() == null
                || !completionContext.getLoadBalancerResponse().hasServer()) return;

        // FIX: dùng lbResponse hash — khớp chính xác với onStartRequest
        int    resKey     = System.identityHashCode(completionContext.getLoadBalancerResponse());
        String instanceId = pendingComplete.remove(resKey);

        if (instanceId != null) {
            tracker.decrement(instanceId);
        }
        // Nếu null: onStartRequest chưa được gọi hoặc đã bị retry cleanup — an toàn để bỏ qua.
    }

    /** Gọi từ AdminController.resetState() trước mỗi benchmark mới. */
    public void resetActiveRequests() {
        pendingComplete.clear();
        requestToResponse.clear();
    }
}