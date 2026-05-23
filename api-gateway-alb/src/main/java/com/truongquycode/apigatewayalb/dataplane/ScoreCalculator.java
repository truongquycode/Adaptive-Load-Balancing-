package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.config.AlbProperties;
import com.truongquycode.apigatewayalb.controlplane.SlidingWindowManager;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;
import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.model.PercentileSnapshot;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.truongquycode.apigatewayalb.dataplane.RealtimeDynamicsTracker;
import com.truongquycode.apigatewayalb.model.RealtimeDynamics;

@Service
@RequiredArgsConstructor
public class ScoreCalculator {

    private static final double SCORE_NULL_INSTANCE = 20.0;

    private final SlidingWindowManager windowManager;
    private final PIDController         pidController;
    private final EwmaSmoother          ewmaSmoother;
    private final AlbProperties          props;
    private final RealtimeDynamicsTracker realtimeTracker;

    public ScoreBreakdown calculateScore(String instanceId, InstanceMetrics current) {

    long now = System.currentTimeMillis();

    if (current == null) {
        return new ScoreBreakdown(
                instanceId,
                0,
                1,
                1,
                1,
                1,
                0,
                10.0,
                now
        );
    }

    PercentileSnapshot snap = windowManager.getSnapshot(instanceId);

    double rawLatency =
            current.getLatency() > 0
            ? current.getLatency()
            : snap.p50();

    double ewmaLatency = ewmaSmoother.smooth(
            instanceId,
            rawLatency,
            props.getEwma().getTauMin(),
            props.getEwma().getTauMax(),
            props.getEwma().getK(),
            snap.p50()
    );

    RealtimeDynamics dynamics =
            realtimeTracker.update(
                    instanceId,
                    current.getQueueLength(),
                    ewmaLatency,
                    false
            );

    /*
     * ───────────────────────────────────────────────
     * REALTIME CONGESTION COMPONENTS
     * ───────────────────────────────────────────────
     */

    double inflightScore =
            Math.min(1.0,
                    dynamics.inflight() / 100.0);

    double queueGrowthScore =
            Math.min(1.0,
                    Math.max(0.0,
                            dynamics.queueGrowthRate() / 50.0));

    double latencySlopeScore =
            Math.min(1.0,
                    Math.max(0.0,
                            dynamics.latencySlope() / 100.0));

    double failureScore =
            dynamics.recentFailurePenalty();

    /*
     * ───────────────────────────────────────────────
     * REALTIME BASE SCORE
     * ───────────────────────────────────────────────
     */

    double baseScore =
              (0.45 * inflightScore)
            + (0.30 * queueGrowthScore)
            + (0.20 * latencySlopeScore)
            + (0.05 * failureScore);

    /*
     * ───────────────────────────────────────────────
     * PID INSTABILITY PENALTY
     * ───────────────────────────────────────────────
     */

    double p50 = snap.p50();
    double p95 = snap.p95() > p50
            ? snap.p95()
            : 100.0;

    double normalizedLatency =
            Math.max(0.0,
                    Math.min(1.0,
                            (ewmaLatency - p50)
                                    / (p95 - p50)));

    double normalizedSystem =
            Math.max(0.0,
                    Math.min(1.0,
                            (windowManager.getSystemP75() - p50)
                                    / (p95 - p50)));

    double pidPenalty =
            pidController.calculatePenalty(
                    instanceId,
                    normalizedLatency,
                    normalizedSystem,
                    props.getPid()
            );

    double finalScore =
            baseScore + pidPenalty;

    return new ScoreBreakdown(
            instanceId,
            ewmaLatency,
            inflightScore,
            queueGrowthScore,
            latencySlopeScore,
            failureScore,
            pidPenalty,
            finalScore,
            now
    );
}
}