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
    @Data public static class Ewma { 
    	private double tau    = 3000.0;  // Giữ làm τ_max (ms)
        private double tauMin = 300.0;   // τ_min khi hệ thống biến động mạnh (ms)
        private double tauMax = 8000.0;  // τ_max khi hệ thống ổn định (ms)
        private double k      = 1.0;     // Hệ số độ dốc của hàm mũ
        // Giải thích k:
        // k = 1.0: τ thay đổi chậm, cần δ ≈ 1.0 (100% deviation) để giảm τ về ~τ_min
        // k = 2.0: τ thay đổi nhanh hơn, δ ≈ 0.5 (50% deviation) đã đủ kéo τ xuống mức thấp
        // k = 3.0: rất nhạy với biến động nhỏ, phù hợp hệ thống cần phản ứng rất nhanh
    }
    @Data public static class Weights { private long updateInterval = 5000; }
}