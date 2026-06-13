package com.truongquycode.apigatewayalb.model;

import org.springframework.cloud.client.ServiceInstance;

import java.util.List;
import java.util.Map;

/**
 * Kết quả đánh giá toàn bộ cụm tại một lần định tuyến.
 * Data-plane dùng cùng object này để chọn backend; Prometheus đọc lại snapshot cuối
 * để dashboard phản ánh đúng quyết định thật của thuật toán.
 */
public record RoutingContext(
        List<RoutingCost> all,
        List<RoutingCost> eligible,
        Map<String, ServiceInstance> instancesById,
        double healthWeight,
        double loadWeight,
        String mode,
        long createdAtMs
) {
}
