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

	// Guard: khi system histogram chưa có đủ dữ liệu (warmup),
	// dùng fallback bounds hợp lý để tránh normalizeLatency trả về 0.5 cho tất cả.
	// Với /api/simulate-call: latency baseline ≈ 40-80ms, dưới tải ≈ 100-400ms.
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
		double p75System = windowManager.getSystemP75();

		// ── EWMA latency smoothing ────────────────────────────────────────────
		double lRaw = current.getLatency() > 0 ? current.getLatency() : snap.p50();
		double ewmaLat = ewmaSmoother.smooth(instanceId, lRaw, props.getEwma().getTauMin(), props.getEwma().getTauMax(),
				props.getEwma().getK(), snap.p50());

		// ── FIX RC4: Dùng SYSTEM-WIDE P5/P95 cho latency normalization ───────
		//
		// TRƯỚC (per-instance):
		// 8083 (luôn chậm): P5=200ms, P95=700ms, EWMA=400ms → nL = 0.40
		// 8081 (nhanh): P5=40ms, P95=180ms, EWMA=80ms → nL = 0.29
		// Chênh lệch rất nhỏ (0.11) dù 8083 chậm hơn 5x
		//
		// SAU (system-wide):
		// Giả sử system P5=30ms, P95=500ms
		// 8083: EWMA=400ms → nL = (400-30)/(500-30) = 0.787 ← cao rõ ràng
		// 8081: EWMA=80ms → nL = (80-30)/(500-30) = 0.106 ← thấp rõ ràng
		// Chênh lệch 0.68 → MCDM phân kỳ mạnh, routing tránh 8083 đúng cách

		double sysP5 = windowManager.getSystemP5();
		double sysP95 = windowManager.getSystemP95();

		// Fallback khi global histogram chưa đủ sample (warmup <160 observations)
		if (sysP95 <= sysP5 || sysP5 < 1.0) {
			sysP5 = SYSTEM_P5_FALLBACK;
			sysP95 = SYSTEM_P95_FALLBACK;
		}

		// nL: dùng system bounds — node chậm hơn toàn hệ thống → nL cao hơn
		double nL = norm.normalizeLatency(ewmaLat, sysP5, sysP95);

		// nQ và nC vẫn dùng per-instance data (OK vì queue/CPU cần ngưỡng riêng)
		double nQ = norm.normalizeQueue(current.getQueueLength(), snap.qP99());
		double nC = norm.normalizeCpu(current.getCpu());

		double baseScore = (weightEngine.getAlpha() * nL) + (weightEngine.getBeta() * nQ)
				+ (weightEngine.getGamma() * nC);

		// ── PID Penalty: vẫn dùng system P5/P95 cho nhất quán ───────────────
		double p5 = sysP5;
		double p95 = sysP95;

		double normalizedEwma = Math.max(0.0, Math.min(1.0, (ewmaLat - p5) / (p95 - p5)));
		double normalizedP75 = Math.max(0.0, Math.min(1.0, (p75System - p5) / (p95 - p5)));

		double penalty = pidController.calculatePenalty(instanceId, normalizedEwma, normalizedP75, props.getPid());
		double finalScore = baseScore + penalty;

		return new ScoreBreakdown(instanceId, ewmaLat, nL, nQ, nC, baseScore, penalty, finalScore, now);
	}
}