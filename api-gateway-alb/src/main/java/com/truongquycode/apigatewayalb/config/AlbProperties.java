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
    private Ablation ablation = new Ablation();

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

        /**
         * Dynamic MCDM chỉ được cập nhật khi có đủ request nghiệp vụ thật trong
         * cửa sổ update. Điều này tránh hiện tượng EWM học từ CPU nền/latency idle
         * khi Gateway chỉ đang poll /api/alb-metrics.
         */
        private long minCompletedRequests = 20;
        private double minActualRps = 5.0;
        private boolean resetToAhpWhenIdle = true;
    }

    /**
     * Cấu hình phục vụ ablation study.
     *
     * full              : dùng đầy đủ Adaptive hiện tại
     * no-pid            : bỏ PID-inspired latency penalty
     * fixed-weights     : bỏ dynamic EWM, dùng AHP/fixed weights
     * no-ewma-latency   : dùng latency thô thay vì EWMA latency
     * no-score-ema      : bỏ EMA làm mượt finalScore ở MetricsPoller
     * no-capacity       : xem các backend có capacity ngang nhau
     * no-p2c            : chọn backend có final routing cost thấp nhất toàn cục
     * no-probe          : tắt probe recovery
     * no-low-load-rr    : tắt low-load fallback về Round Robin
     */
    @Data
    public static class Ablation {
        private String variant = "full";

        public boolean isVariant(String expected) {
            return expected != null && expected.equalsIgnoreCase(variant);
        }
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
