package com.truongquycode.apigatewayalb.config;

import com.truongquycode.apigatewayalb.dataplane.AdaptiveLoadBalancer;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;
import com.truongquycode.apigatewayalb.dataplane.LeastConnectionsLoadBalancer;
import com.truongquycode.apigatewayalb.util.MetricsCache;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.truongquycode.apigatewayalb.dataplane.MetricAwareLoadBalancer;

@Configuration
@LoadBalancerClients({
		@LoadBalancerClient(name = "REGISTRATION-SERVICE-ALB", configuration = LoadBalancerConfiguration.class) })
public class LoadBalancerConfiguration {

	// 1. Kích hoạt thuật toán THÍCH NGHI (Adaptive)
	@Bean
	@ConditionalOnProperty(name = "alb.strategy", havingValue = "adaptive", matchIfMissing = true)
	public ReactorLoadBalancer<ServiceInstance> adaptiveLoadBalancer(Environment environment,
			LoadBalancerClientFactory loadBalancerClientFactory, MetricsCache metricsCache,
			InflightTracker inflightTracker) {

		String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
		ObjectProvider<ServiceInstanceListSupplier> lazyProvider = loadBalancerClientFactory.getLazyProvider(name,
				ServiceInstanceListSupplier.class);

		return new AdaptiveLoadBalancer(lazyProvider, metricsCache, inflightTracker);
	}

	// 2. Kích hoạt thuật toán ROUND ROBIN
	@Bean
	@ConditionalOnProperty(name = "alb.strategy", havingValue = "round-robin")
	public ReactorLoadBalancer<ServiceInstance> roundRobinLoadBalancer(Environment environment,
			LoadBalancerClientFactory loadBalancerClientFactory) {
		String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
		ObjectProvider<ServiceInstanceListSupplier> lazyProvider = loadBalancerClientFactory.getLazyProvider(name,
				ServiceInstanceListSupplier.class);

		return new MetricAwareLoadBalancer(new RoundRobinLoadBalancer(lazyProvider, name));
	}

	// 3. Kích hoạt thuật toán RANDOM
	@Bean
	@ConditionalOnProperty(name = "alb.strategy", havingValue = "random")
	public ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(Environment environment,
			LoadBalancerClientFactory loadBalancerClientFactory) {
		String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
		ObjectProvider<ServiceInstanceListSupplier> lazyProvider = loadBalancerClientFactory.getLazyProvider(name,
				ServiceInstanceListSupplier.class);

		return new MetricAwareLoadBalancer(new RandomLoadBalancer(lazyProvider, name));
	}

	// Kích hoạt thuật toán LEAST CONNECTIONS
	@Bean
	@ConditionalOnProperty(name = "alb.strategy", havingValue = "least-connections")
	public ReactorLoadBalancer<ServiceInstance> leastConnectionsLoadBalancer(Environment environment,
			LoadBalancerClientFactory loadBalancerClientFactory, InflightTracker inflightTracker) {

		String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
		ObjectProvider<ServiceInstanceListSupplier> lazyProvider = loadBalancerClientFactory.getLazyProvider(name,
				ServiceInstanceListSupplier.class);

		return new LeastConnectionsLoadBalancer(lazyProvider, inflightTracker);
	}
}