package com.truongquycode.apigatewayalb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truongquycode.apigatewayalb.dataplane.AdaptiveLoadBalancer;
import com.truongquycode.apigatewayalb.dataplane.PIDController;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;
import com.truongquycode.apigatewayalb.controlplane.SlidingWindowManager;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/actuator")
@RequiredArgsConstructor
public class AdminController {

    private final PIDController        pidController;
    private final EwmaSmoother         ewmaSmoother;
    private final SlidingWindowManager windowManager;
    private final InflightTracker      inflightTracker;

    /**
     * Reset TOÀN BỘ state của ALB. Phải gọi trước mỗi lần benchmark.
     *
     * Thứ tự reset quan trọng:
     * 1. InflightTracker     → về 0 ngay để request mới không bị ảnh hưởng counter cũ
     * 2. AdaptiveLoadBalancer static state → smoothedCapMcdm, firstSeenMs, rrCounter
     *    (FIX ROOT CAUSE của Run 4 anomaly)
     * 3. PIDController       → integral accumulation từ run trước
     * 4. EwmaSmoother        → EWMA value từ run trước
     * 5. SlidingWindowManager → histogram percentile từ run trước
     *
     * Kết quả nếu KHÔNG reset:
     *   - smoothedCapMcdm carry over → capacity weights lệch → routing tập trung
     *     vào 1 node → CPU saturation → P99 tăng đột biến (như Adaptive Run 4)
     *   - PID integral carry over → penalty sai từ đầu → node bị tẩy chay oan
     *   - EWMA carry over → latency baseline sai → normalization lệch
     */
    @PostMapping("/alb/reset")
    public ResponseEntity<String> resetState() {

        // 1. Reset inflight counter (cao nhất ưu tiên - ảnh hưởng real-time)
        inflightTracker.resetAll();

        // 2. Reset AdaptiveLoadBalancer static state
        // FIX: đây là root cause của Adaptive Run 4 anomaly
        // smoothedCapMcdm và firstSeenMs là static → accessible qua class method
        AdaptiveLoadBalancer.resetStaticState();

        // 3. Reset PID integral accumulation
        pidController.resetAllStates();

        // 4. Reset EWMA smoothing state
        ewmaSmoother.resetAllStates();

        // 5. Reset sliding window histogram
        windowManager.resetAll();

        return ResponseEntity.ok(
            "ALB State Reset hoàn toàn:\n" +
            "  ✓ InflightTracker      → counters về 0\n" +
            "  ✓ AdaptiveLoadBalancer → smoothedCapMcdm, firstSeenMs, rrCounter cleared\n" +
            "  ✓ PIDController        → integral accumulation cleared\n" +
            "  ✓ EwmaSmoother         → EWMA state cleared\n" +
            "  ✓ SlidingWindowManager → histogram cleared\n" +
            "Sẵn sàng cho benchmark tiếp theo."
        );
    }
}