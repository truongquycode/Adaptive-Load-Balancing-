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
                
                .uri("lb://REGISTRATION-SERVICE-ALB")
            )
            .build();
    }
}