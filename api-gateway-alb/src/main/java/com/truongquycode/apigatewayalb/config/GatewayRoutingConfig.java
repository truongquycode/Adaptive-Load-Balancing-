package com.truongquycode.apigatewayalb.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutingConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder, AlbProperties albProperties) {
        // Benchmark mode: không retry để tránh request bị nhân đôi khi hệ thống đã quá tải.
        // Retry có thể dùng cho production, nhưng khi so sánh thuật toán LB nó làm P95/P99 bị phóng đại
        // và che mất nguyên nhân thật của bottleneck.
        return builder.routes()
                .route("backend-route", r -> r.path("/api/**")
                        .uri("lb://REGISTRATION-SERVICE-ALB"))
                .build();
    }
}
