package com.truongquycode.apigatewayalb.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@Configuration
public class GatewayRoutingConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder,
                                           AlbProperties albProperties) {
        return builder.routes()
            .route("backend-route", r -> r
                .path("/api/**")
                .filters(f -> f
                    // ── Retry filter: fix lỗi 0.01% ──────────────────────────────
                    //
                    // Nguyên nhân lỗi 0.01%:
                    //   Một request hiếm gặp backend trả về 500/502/503 (JVM GC pause,
                    //   brief TCP reset, node chưa kịp warm up) → không có retry
                    //   → JMeter ghi nhận error ngay lập tức.
                    //
                    // Fix:
                    //   Retry 1 lần (tổng tối đa 2 lần gọi) cho GET requests.
                    //   Backoff 10ms (ngắn, tránh làm trễ P99 khi retry thực sự cần thiết).
                    //   Chỉ retry với status 5xx, không retry 4xx (lỗi logic client).
                    //
                    // Tại sao chỉ retry=1 (không phải 2 hay 3)?
                    //   - Retry=1: đủ để cover transient error (~13 lỗi / 131K requests)
                    //   - Retry=2: tăng latency tail khi backend thực sự down
                    //   - /api/simulate-call là GET + idempotent → safe to retry
                    .retry(config -> {
                        config.setRetries(1);
                        config.setMethods(HttpMethod.GET);
                        config.setStatuses(
                            HttpStatus.INTERNAL_SERVER_ERROR,   // 500
                            HttpStatus.BAD_GATEWAY,             // 502
                            HttpStatus.SERVICE_UNAVAILABLE,     // 503
                            HttpStatus.GATEWAY_TIMEOUT          // 504
                        );
                    })
                )
                .uri("lb://REGISTRATION-SERVICE-ALB")
            )
            .build();
    }
}