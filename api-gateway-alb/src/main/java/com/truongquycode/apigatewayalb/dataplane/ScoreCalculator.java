package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.config.AlbProperties;
import com.truongquycode.apigatewayalb.controlplane.DynamicWeightEngine;
import com.truongquycode.apigatewayalb.controlplane.SlidingWindowManager;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;
import com.truongquycode.apigatewayalb.math.NormalizationFunctions;
import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.model.PercentileSnapshot;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScoreCalculator {

    private static final double SCORE_NULL_INSTANCE = Double.MAX_VALUE;

    private final SlidingWindowManager windowManager;
    private final DynamicWeightEngine   weightEngine;
    private final PIDController         pidController;
    private final EwmaSmoother          ewmaSmoother;
    private final NormalizationFunctions norm;
    private final AlbProperties          props;

    public ScoreBreakdown calculateScore(String instanceId, InstanceMetrics current) {
        long now = System.currentTimeMillis();

        if (current == null) {
            return new ScoreBreakdown(
                instanceId, 0, 1, 1, 1, 1, 0, SCORE_NULL_INSTANCE, now);
        }

        PercentileSnapshot snap     = windowManager.getSnapshot(instanceId);
        double             p50System = windowManager.getSystemP50();

        // Lấy latency thực, fallback về p50 nếu chưa có dữ liệu
        double lRaw    = current.getLatency() > 0 ? current.getLatency() : snap.p50();
        double ewmaLat = ewmaSmoother.smooth(
            instanceId, lRaw, props.getEwma().getTau(), snap.p50());

        // --- BaseScore: MCDM (AHP-EWM fusion) ---
        double nL = norm.normalizeLatency(ewmaLat, snap.p5(), snap.p95());
        double nQ = norm.normalizeQueue(current.getQueueLength(), snap.qP99());
        double nC = norm.normalizeCpu(current.getCpu());

        double baseScore = (weightEngine.getAlpha() * nL)
                         + (weightEngine.getBeta()  * nQ)
                         + (weightEngine.getGamma() * nC);

        // --- PID Penalty: tính trên tín hiệu đã làm mịn (nhất quán với baseScore) ---
        // Guard: dùng dải mặc định 100ms nếu histogram chưa đủ dữ liệu
        double p5  = snap.p5();
        double p95 = snap.p95() > p5 ? snap.p95() : 100.0;

        double normalizedEwma = Math.max(0.0, Math.min(1.0,
            (ewmaLat  - p5) / (p95 - p5)));
        double normalizedP50  = Math.max(0.0, Math.min(1.0,
            (p50System - p5) / (p95 - p5)));

        double penalty    = pidController.calculatePenalty(
            instanceId, normalizedEwma, normalizedP50, props.getPid());
        double finalScore = baseScore + penalty;

        return new ScoreBreakdown(
            instanceId, ewmaLat, nL, nQ, nC, baseScore, penalty, finalScore, now);
    }
}