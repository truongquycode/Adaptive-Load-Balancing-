package com.truongquycode.apigatewayalb.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Duration;

@Configuration
public class GatewayRoutingConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder, AlbProperties albProperties) {
        return builder.routes()
            .route("backend-route", r -> r
                .path("/api/**")
                .filters(f -> {
                    // LOGIC KIỂM TRA: CHỈ áp dụng Retry nếu thuật toán là "adaptive"
                    if ("adaptive".equalsIgnoreCase(albProperties.getStrategy())) {
                        f.retry(retryConfig -> {
                            retryConfig.setRetries(2);
                            retryConfig.setStatuses(
                                HttpStatus.BAD_GATEWAY, 
                                HttpStatus.GATEWAY_TIMEOUT, 
                                HttpStatus.SERVICE_UNAVAILABLE, 
                                HttpStatus.INTERNAL_SERVER_ERROR
                            );
                            retryConfig.setMethods(HttpMethod.GET, HttpMethod.OPTIONS);
                            retryConfig.setBackoff(
                                Duration.ofMillis(100), // firstBackoff
                                Duration.ofMillis(500), // maxBackoff
                                2,                      // factor
                                true                    // basedOnPreviousValue
                            );
                        });
                    }
                    return f;
                })
                .uri("lb://REGISTRATION-SERVICE-ALB")
            )
            .build();
    }
}