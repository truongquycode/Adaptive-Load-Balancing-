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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RequiredArgsConstructor
public class LeastConnectionsLoadBalancer implements ReactorServiceInstanceLoadBalancer {

	private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;

	private final InflightTracker inflightTracker;

	@Override
	public Mono<Response<ServiceInstance>> choose(Request request) {

		ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();

		if (supplier == null) {
			return Mono.just(new EmptyResponse());
		}

		return supplier.get(request).next().map(this::selectInstance);
	}

	private Response<ServiceInstance> selectInstance(List<ServiceInstance> instances) {

		if (instances == null || instances.isEmpty()) {
			return new EmptyResponse();
		}

		if (instances.size() == 1) {
			return new DefaultResponse(instances.get(0));
		}

		int minInflight = Integer.MAX_VALUE;

		List<ServiceInstance> candidates = new ArrayList<>();

		for (ServiceInstance instance : instances) {

			String instanceId = instance.getInstanceId();

			int inflight = inflightTracker.getInflight(instanceId);

			if (inflight < minInflight) {

				minInflight = inflight;

				candidates.clear();
				candidates.add(instance);

			} else if (inflight == minInflight) {

				candidates.add(instance);
			}
		}

		ServiceInstance selected = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));

		log.debug("LeastConnections selected: {} | inflight={}", selected.getInstanceId(), minInflight);
		io.micrometer.core.instrument.Metrics.counter("alb.routing.selected", "backend", selected.getInstanceId(),
				"port", String.valueOf(selected.getPort())).increment();

		return new DefaultResponse(selected);
	}
}