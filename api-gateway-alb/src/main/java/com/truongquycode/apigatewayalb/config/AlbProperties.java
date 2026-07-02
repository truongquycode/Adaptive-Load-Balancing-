package com.truongquycode.apigatewayalb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.truongquycode.apigatewayalb.model.PidConfig;

import lombok.Data;

/**
 * Bind toàn bộ cấu hình có prefix "alb" trong application.yml.
 *
 * Các tham số quan trọng của Adaptive được đưa ra cấu hình để benchmark,
 * ablation và sensitivity analysis có thể lặp lại được. Giá trị mặc định vẫn giữ
 * hành vi hiện tại, trừ các patch có chủ đích khoa học như real-traffic gate và
 * absolute latency cost.
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

        /** Timeout cho mỗi lần poll /api/alb-metrics. */
        private long metricsTimeoutMs = 800;

        /** EMA score tăng mạnh khi finalScore spike lớn. */
        private double scoreEmaAlphaSpike = 0.60;

        /** EMA score tăng vừa khi finalScore xấu hơn nhẹ. */
        private double scoreEmaAlphaRise = 0.35;

        /** EMA score hồi phục chậm để tránh flapping. */
        private double scoreEmaAlphaRecover = 0.25;

        /** Ngưỡng delta score để phân biệt spike và tăng nhẹ. */
        private double scoreEmaSpikeThreshold = 0.30;

        /** Baseline latency dùng khi chưa có sample thật. */
        private double idleLatencyBaselineMs = 65.0;

        /** Hệ số kéo latency idle về baseline; không dùng để học histogram. */
        private double idleDecayAlpha = 0.20;
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

        /** EWM/AHP blend: target = blendFactor * EWM + (1 - blendFactor) * AHP. */
        private double blendFactor = 0.70;

        /** Biên EMA cho trọng số động, phụ thuộc độ lệch target so với hiện tại. */
        private double emaAlphaMin = 0.08;
        private double emaAlphaMax = 0.22;

        /** Guard ổn định: khi cụm có traffic nhưng gần như không khác biệt, giữ AHP. */
        private double stableQueueThreshold = 0.08;
        private double stableCpuThreshold = 0.08;
        private double stableLatencySpread = 0.12;

        /** Bounds mềm để một tiêu chí không triệt tiêu/đè bẹp các tiêu chí khác. */
        private double alphaMin = 0.15;
        private double alphaMax = 0.70;
        private double betaMin = 0.08;
        private double betaMax = 0.45;
        private double gammaMin = 0.08;
        private double gammaMax = 0.35;
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
     * Tham số cho tầng định tuyến cuối của Adaptive.
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

        /** Dải min-max tối thiểu khi chuẩn hóa health/load trong RoutingCost. */
        private double minRoutingNormRange = 0.12;

        /** EMA cho healthWeight/loadWeight giữa các lần chọn request. */
        private double routingWeightEmaAlpha = 0.18;

        /** Ngưỡng đặt mode HEALTH_DOMINANT hoặc LOAD_DOMINANT. */
        private double dominantThreshold = 0.70;

        /** Penalty tuyệt đối khi backend nhận quá phần tải hợp lý theo capacity. */
        private double overloadPenaltyWeight = 0.30;

        /** Penalty khi inflight tiến gần hard cap. */
        private double capPressurePenaltyWeight = 0.20;

        /** Penalty theo final health score tuyệt đối, giữ tương thích logic cũ. */
        private double absoluteHealthPenaltyWeight = 0.12;

        /**
         * Penalty theo latency tuyệt đối để không mất tín hiệu khi toàn cụm cùng chậm.
         * Cost = clamp((ewmaLatency - target) / (critical - target), 0, 1).
         */
        private double absoluteLatencyPenaltyWeight = 0.12;
        private double absoluteLatencyTargetMs = 300.0;
        private double absoluteLatencyCriticalMs = 1500.0;

        /** Ngưỡng overload/cap pressure, đưa ra config để sensitivity analysis. */
        private double overloadStartRatio = 0.95;
        private double overloadFullRatio = 1.40;
        private double capPressureStartRatio = 0.70;
        private double capPressureFullRatio = 1.00;
        private double absoluteHealthStart = 0.75;
        private double absoluteHealthFull = 1.50;

        /** Biên hard-cap theo capacity. */
        private int hardInflightCapMin = 40;
        private double capacityCapFactorMin = 0.70;
        private double capacityCapFactorMax = 1.50;

        /** Guard thêm cho probe recovery để không làm xấu p99 khi cluster đang căng. */
        private double probeMaxTotalInflightRatio = 0.70;
        private double probeMaxLoadRaw = 1.10;
        private double probeMaxAbsoluteLatencyCost = 0.80;
        private double probeMaxFinalCost = 1.50;
    }
}
