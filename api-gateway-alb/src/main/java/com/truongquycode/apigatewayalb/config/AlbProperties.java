package com.truongquycode.apigatewayalb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.truongquycode.apigatewayalb.model.PidConfig;

import lombok.Data;

/**
 * Bind toàn bộ cấu hình có prefix "alb" trong application.yml.
 *
 * Bản v3 tách rõ:
 * - ewma/pid/weights: control-plane health score
 * - routing: data-plane fusion giữa health score và load realtime
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "alb")
public class AlbProperties {

    /** adaptive | round-robin | random | least-connections */
    private String strategy = "adaptive";

    private Polling polling = new Polling();
    private Ewma ewma = new Ewma();
    private PidConfig pid = new PidConfig();
    private Weights weights = new Weights();
    private Routing routing = new Routing();

    @Data
    public static class Polling {
        private long interval = 200; // ms
    }

    @Data
    public static class Ewma {
        private double tauMin = 150.0;
        private double tauMax = 2000.0;
        private double k = 4.0;
    }

    @Data
    public static class Weights {
        private long updateInterval = 5000; // ms
    }

    /**
     * Tham số cho tầng định tuyến cuối của Adaptive v3.
     * Các giá trị này là biên an toàn; quyết định chính vẫn được tính động theo
     * phân tán hiện tại của health score và capacity-normalized load.
     */
    @Data
    public static class Routing {
        private long warmupMs = 5000;
        private double minExpectedInflight = 3.0;

        private int lowLoadInflight = 20;
        private double lowLoadHealthSpread = 0.12;
        private double lowLoadLoadSpread = 0.25;

        private double minHealthWeight = 0.35;
        private double maxHealthWeight = 0.80;

        private double stalePenaltyWeight = 0.30;
        private long staleSoftMs = 800;
        private long staleHardMs = 2000;

        private double unhealthyScoreCutoff = 2.0;
        private int hardInflightCap = 180;

        private long probeIntervalMs = 2000;
        private double probeProbability = 0.02;
    }
}
