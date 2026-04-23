package com.truongquycode.apigatewayalb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import com.truongquycode.apigatewayalb.model.PidConfig;
import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "alb")
public class AlbProperties {
    // Thuộc tính mới để chọn thuật toán
    private String strategy = "adaptive"; 
    
    private Polling polling = new Polling();
    private Ewma ewma = new Ewma();
    private PidConfig pid = new PidConfig();
    private Weights weights = new Weights();

    @Data public static class Polling { private long interval = 1000; }
    @Data public static class Ewma { private double tau = 3000.0; }
    @Data public static class Weights { private long updateInterval = 5000; }
}