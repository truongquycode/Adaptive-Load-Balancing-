package com.truongquycode.apigatewayalb.config;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@Configuration
public class GatewayRoutingConfig {

	@Bean
	public RouteLocator customRouteLocator(RouteLocatorBuilder builder, AlbProperties albProperties) {
		return builder.routes().route("backend-route", r -> r.path("/api/**")
				.filters(f -> f.circuitBreaker(c -> c.setName("backend-cb").setFallbackUri("forward:/fallback")
				// Mở circuit khi >20% lỗi trong 10 requests
				// Không cần cấu hình thêm nếu dùng application.yml
				)
						// Retry 1 lần (tổng tối đa 2 lần gọi) cho GET requests.
						// Backoff 10ms (ngắn, tránh làm trễ P99 khi retry thực sự cần thiết).
						// Chỉ retry với status 5xx, không retry 4xx (lỗi logic client).
						//
						// - Retry=1: đủ để cover transient error, nhiều hơn sẽ tăng latency tail khi
						// backend thực sự down
						.retry(config -> {
							config.setRetries(1);
							config.setMethods(HttpMethod.GET);
							config.setStatuses(HttpStatus.INTERNAL_SERVER_ERROR, // 500
									HttpStatus.BAD_GATEWAY, // 502
									HttpStatus.SERVICE_UNAVAILABLE, // 503
									HttpStatus.GATEWAY_TIMEOUT // 504
							);
							config.getExceptions().add(TimeoutException.class);
							config.getExceptions().add(ConnectException.class);
							config.setBackoff(Duration.ofMillis(20), Duration.ofMillis(200), 2, true);
						}))
				.uri("lb://REGISTRATION-SERVICE-ALB")).build();
	}
}