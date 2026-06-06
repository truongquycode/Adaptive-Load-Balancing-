package com.truongquycode.apigatewayalb.config;

import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;

/**
 * Chỉ đóng vai trò "holder" cho annotation @LoadBalancerClients.
 *
 * ⚠️ KHÔNG đặt @Bean ở đây.
 *
 * Lý do tách class:
 * Spring Cloud LoadBalancer tạo một child ApplicationContext riêng cho mỗi
 * service name. Configuration class được truyền vào @LoadBalancerClient phải
 * là một class KHÁC với class chứa @LoadBalancerClients — nếu tự trỏ vào
 * chính mình (self-reference), child context sẽ xử lý lại @LoadBalancerClients
 * một lần nữa, khiến ReactorLoadBalancer bean không được đăng ký đúng chỗ
 * → gateway không chọn được instance → 503 Service Unavailable.
 */
@Configuration
@LoadBalancerClients({
        @LoadBalancerClient(name = "REGISTRATION-SERVICE-ALB", configuration = LoadBalancerBeanConfig.class)
})
public class LoadBalancerConfiguration {
}