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

	private record PendingEntry(String instanceId, int reqKey) {
	}

	private final ConcurrentHashMap<Integer, PendingEntry> pendingComplete = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<Integer, Integer> requestToResponse = new ConcurrentHashMap<>();

	public InflightLifecycle(InflightTracker tracker) {
		this.tracker = tracker;
	}

	@Override
	public boolean supports(Class requestContextClass, Class responseClass, Class serverTypeClass) {
		return true;
	}

	@Override
	public void onStart(Request<Object> request) {
	}

	@Override
	public void onStartRequest(Request<Object> request, Response<ServiceInstance> lbResponse) {
		if (lbResponse == null || !lbResponse.hasServer())
			return;

		String instanceId = lbResponse.getServer().getInstanceId();
		int reqKey = System.identityHashCode(request);
		int resKey = System.identityHashCode(lbResponse);

		Integer prevResKey = requestToResponse.put(reqKey, resKey);
		if (prevResKey != null && prevResKey.intValue() != resKey) {
			PendingEntry prev = pendingComplete.remove(prevResKey);
			if (prev != null) {
				tracker.decrement(prev.instanceId());
			}
		}

		pendingComplete.put(resKey, new PendingEntry(instanceId, reqKey));
		tracker.increment(instanceId);
	}

	@Override
	public void onComplete(CompletionContext<Object, ServiceInstance, Object> completionContext) {
		if (completionContext.getLoadBalancerResponse() == null
				|| !completionContext.getLoadBalancerResponse().hasServer())
			return;

		int resKey = System.identityHashCode(completionContext.getLoadBalancerResponse());
		PendingEntry e = pendingComplete.remove(resKey);

		if (e != null) {
			tracker.decrement(e.instanceId());
			requestToResponse.remove(e.reqKey());
		}
	}

	public void resetActiveRequests() {
		pendingComplete.clear();
		requestToResponse.clear();
	}
}