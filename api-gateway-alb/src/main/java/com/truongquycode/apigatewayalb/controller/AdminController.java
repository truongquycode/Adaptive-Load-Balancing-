package com.truongquycode.apigatewayalb.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truongquycode.apigatewayalb.config.AlbProperties;
import com.truongquycode.apigatewayalb.dataplane.AdaptiveLoadBalancer;
import com.truongquycode.apigatewayalb.dataplane.InflightLifecycle;
import com.truongquycode.apigatewayalb.dataplane.PIDController;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;
import com.truongquycode.apigatewayalb.controlplane.SlidingWindowManager;
import com.truongquycode.apigatewayalb.controlplane.MetricsPoller;
import com.truongquycode.apigatewayalb.controlplane.DynamicWeightEngine;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;
import com.truongquycode.apigatewayalb.util.MetricsCache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/actuator")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private static final List<String> SUPPORTED_STRATEGIES = List.of(
            "adaptive", "round-robin", "random", "least-connections");

    private static final List<String> SUPPORTED_ABLATION_VARIANTS = List.of(
            "full", "no-pid", "fixed-weights", "no-ewma-latency", "no-score-ema",
            "no-capacity", "no-p2c", "no-probe", "no-low-load-rr");

    private final PIDController pidController;
    private final EwmaSmoother ewmaSmoother;
    private final SlidingWindowManager windowManager;
    private final InflightTracker inflightTracker;
    private final InflightLifecycle inflightLifecycle;
    private final MetricsPoller metricsPoller;
    private final DynamicWeightEngine weightEngine;
    private final MetricsCache metricsCache;
    private final AlbProperties albProperties;

    /**
     * Endpoint bắt buộc cho benchmark strict mode.
     *
     * Script benchmark dùng endpoint này để xác minh strategy và ablation variant
     * đang chạy thật trên Gateway trước khi gắn nhãn kết quả JMeter. Nếu endpoint
     * không trả về đúng giá trị kỳ vọng thì script phải dừng benchmark.
     */
    @GetMapping("/alb/strategy")
    public ResponseEntity<Map<String, Object>> currentStrategy() {
        String strategy = normalize(albProperties.getStrategy(), "adaptive");
        String ablationVariant = normalize(albProperties.getAblation().getVariant(), "full");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategy", strategy);
        body.put("ablationVariant", ablationVariant);
        body.put("supportedStrategies", SUPPORTED_STRATEGIES);
        body.put("supportedAblationVariants", SUPPORTED_ABLATION_VARIANTS);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/alb/reset")
    public ResponseEntity<String> resetState() {
        log.info("--- BẮT ĐẦU RESET TOÀN BỘ TRẠNG THÁI ALB ---");

        // 1. Data Plane Resets
        inflightTracker.resetAll();
        AdaptiveLoadBalancer.resetStaticState();
        inflightLifecycle.resetActiveRequests();

        // 2. Control Plane - Math & Algorithms Resets
        pidController.resetAllStates();
        ewmaSmoother.resetAllStates();
        windowManager.resetAll();

        // 3. Control Plane - State & Cache Resets
        metricsPoller.resetAllStates();
        weightEngine.resetWeights();
        metricsCache.clearAll();

        log.info("--- HOÀN TẤT RESET ALB ---");

        return ResponseEntity.ok("ALB State Reset hoàn toàn:\n" + "  ✓ InflightTracker      → counters về 0\n"
                + "  ✓ AdaptiveLoadBalancer → smoothedCapMcdm, firstSeenMs, rrCounter cleared\n"
                + "  ✓ PIDController        → integral accumulation cleared\n"
                + "  ✓ EwmaSmoother         → EWMA state cleared\n" + "  ✓ SlidingWindowManager → histogram cleared\n"
                + "  ✓ MetricsPoller        → traffic history & smoothed scores cleared\n"
                + "  ✓ DynamicWeightEngine  → MCDM weights reset to defaults\n"
                + "  ✓ MetricsCache         → stale scores cleared\n"
                + "  ✓ Strategy             → " + normalize(albProperties.getStrategy(), "adaptive") + "\n"
                + "  ✓ Ablation variant     → " + normalize(albProperties.getAblation().getVariant(), "full") + "\n"
                + "Sẵn sàng cho benchmark tiếp theo.");
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase();
    }
}
