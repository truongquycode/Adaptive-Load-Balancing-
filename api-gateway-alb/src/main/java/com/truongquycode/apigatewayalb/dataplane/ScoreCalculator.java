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

	private static final double SCORE_NULL_INSTANCE = 20.0;

	private static final double SYSTEM_P5_FALLBACK = 30.0; // ms
	private static final double SYSTEM_P95_FALLBACK = 300.0; // ms

	private final SlidingWindowManager windowManager;
	private final DynamicWeightEngine weightEngine;
	private final PIDController pidController;
	private final EwmaSmoother ewmaSmoother;
	private final NormalizationFunctions norm;
	private final AlbProperties props;

	public ScoreBreakdown calculateScore(String instanceId, InstanceMetrics current) {
		long now = System.currentTimeMillis();

		if (current == null) {
			return new ScoreBreakdown(instanceId, 0, 1, 1, 1, 1, 0, SCORE_NULL_INSTANCE, now);
		}

		PercentileSnapshot snap = windowManager.getSnapshot(instanceId);
		double p50 = snap.p50();
		double lRaw = current.getLatency() > 0 ? current.getLatency() : p50;

		AlbProperties.Ewma ewmaCfg = props.getEwma();
		double ewmaLat = ewmaSmoother.smooth(instanceId, lRaw, ewmaCfg.getTauMin(), ewmaCfg.getTauMax(), ewmaCfg.getK(),
				p50);

		SlidingWindowManager.SystemSnapshot sysSs = windowManager.getSystemSnapshot();
		double sysP5 = sysSs.p5();
		double sysP95 = sysSs.p95();
		if (sysP95 <= sysP5 || sysP5 < 1.0) {
			sysP5 = SYSTEM_P5_FALLBACK;
			sysP95 = SYSTEM_P95_FALLBACK;
		}

		double invRange = 1.0 / (sysP95 - sysP5);

		double nL = norm.normalizeLatency(ewmaLat, sysP5, invRange);
		double nQ = norm.normalizeQueue(current.getQueueLength(), snap.qP99());
		double nC = norm.normalizeCpu(current.getCpu());

		double baseScore = weightEngine.computeBaseScore(nL, nQ, nC);

		double normalizedP75 = norm.normalizeLatency(sysSs.p75(), sysP5, invRange);

		double penalty = pidController.calculatePenalty(instanceId, nL, normalizedP75, props.getPid());
		double finalScore = baseScore + penalty;

		return new ScoreBreakdown(instanceId, ewmaLat, nL, nQ, nC, baseScore, penalty, finalScore, now);
	}
}