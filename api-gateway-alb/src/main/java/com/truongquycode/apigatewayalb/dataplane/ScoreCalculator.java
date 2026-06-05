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

		// [OPT-5a] Cache snap.p50() — record accessor gọi 2 lần (lRaw + fallbackP50 của
		// EWMA)
		PercentileSnapshot snap = windowManager.getSnapshot(instanceId);
		double p50 = snap.p50();
		double lRaw = current.getLatency() > 0 ? current.getLatency() : p50;

		// [OPT-5b] Cache getEwma() — tránh 3 lần gọi getter chain (props → ewma →
		// field)
		AlbProperties.Ewma ewmaCfg = props.getEwma();
		double ewmaLat = ewmaSmoother.smooth(instanceId, lRaw, ewmaCfg.getTauMin(), ewmaCfg.getTauMax(), ewmaCfg.getK(),
				p50);

		// System snapshot — 1 synchronized call (không đổi)
		SlidingWindowManager.SystemSnapshot sysSs = windowManager.getSystemSnapshot();
		double sysP5 = sysSs.p5();
		double sysP95 = sysSs.p95();
		if (sysP95 <= sysP5 || sysP5 < 1.0) {
			sysP5 = SYSTEM_P5_FALLBACK;
			sysP95 = SYSTEM_P95_FALLBACK;
		}

		// [OPT-3] Precompute invRange — dùng chung cho nL và normalizedP75
		// 1 phép chia + 2 phép nhân, thay vì 2 phép chia
		// An toàn: fallback check đảm bảo sysP95 > sysP5 > 0
		double invRange = 1.0 / (sysP95 - sysP5);

		// [OPT-3 tiếp] Inline normalizeLatency với invRange
		// Guards trong normalizeLatency không bao giờ kích hoạt tại đây:
		// - ewmaLat từ EWMA smoother luôn finite > 0 → guard NaN/negative bỏ qua
		// - fallback check trên đảm bảo sysP95 > sysP5 → guard p95<=p5 bỏ qua
		double nL = Math.max(0.0, Math.min(1.0, (ewmaLat - sysP5) * invRange));
		double nQ = norm.normalizeQueue(current.getQueueLength(), snap.qP99());
		double nC = norm.normalizeCpu(current.getCpu());

		// [OPT-4] computeBaseScore — 1 volatile read thay vì 3
		// (getAlpha/getBeta/getGamma)
		double baseScore = weightEngine.computeBaseScore(nL, nQ, nC);

		// [OPT-1] normalizedEwma bị xóa — nó bằng đúng nL vì cùng công thức, cùng đầu
		// vào
		// sau khi fallback check loại bỏ 2 guard trả về 0.5 trong normalizeLatency
		// [OPT-2] p5/p95 bị xóa — chỉ là bản sao vô nghĩa của sysP5/sysP95
		// [OPT-3 tiếp] normalizedP75 dùng lại invRange đã tính
		double normalizedP75 = Math.max(0.0, Math.min(1.0, (sysSs.p75() - sysP5) * invRange));

		double penalty = pidController.calculatePenalty(instanceId, nL, normalizedP75, props.getPid());
		double finalScore = baseScore + penalty;

		return new ScoreBreakdown(instanceId, ewmaLat, nL, nQ, nC, baseScore, penalty, finalScore, now);
	}
}