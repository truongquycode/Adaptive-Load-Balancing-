package com.truongquycode.apigatewayalb.dataplane;

import io.micrometer.core.instrument.Metrics;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import reactor.core.publisher.Mono;

public class MetricAwareLoadBalancer implements ReactorServiceInstanceLoadBalancer {

	private final ReactorServiceInstanceLoadBalancer delegate;

	public MetricAwareLoadBalancer(ReactorServiceInstanceLoadBalancer delegate) {
		this.delegate = delegate;
	}

	@Override
	public Mono<Response<ServiceInstance>> choose(Request request) {
		return delegate.choose(request).doOnNext(response -> {
			if (response.hasServer()) {
				ServiceInstance selected = response.getServer();
				Metrics.counter("alb.routing.selected", "backend", selected.getInstanceId(), "port",
						String.valueOf(selected.getPort())).increment();
			}
		});
	}
}