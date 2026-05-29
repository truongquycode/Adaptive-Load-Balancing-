package com.truongquycode.apigatewayalb.controlplane;

import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.util.MetricsCache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicWeightEngine {

	private static final double[] MU = { 5.0, 1.0, 0.05 };

	private static final double WEIGHT_EMA_ALPHA = 0.15;
	private static final double EPSILON = 1e-6;

	// ── FIX: Tăng BLEND_FACTOR: EWM chiếm 80% thay vì 70% ──────────────────
	// Lý do: với workload CPU-intensive trên heterogeneous nodes (8081: 2 CPU,
	// 8082: 1.5 CPU, 8083: 1 CPU), tín hiệu CPU (gamma) từ entropy rất quan trọng.
	// EWM phát hiện sự chênh lệch entropy CPU tốt hơn khi weight của nó cao hơn.
	private static final double BLEND_FACTOR = 0.80; // 0.70 → 0.80
	private static final int CRITERIA_COUNT = 3;

	private final MetricsCache cache;
	private final MeterRegistry registry;

	// AHP prior: latency vẫn quan trọng nhất (0.5),
	// nhưng giảm queue (0.25) và tăng CPU (0.25) so với trước (0.3/0.2).
	// Lý do: /api/simulate-call là CPU-bound, CPU là tín hiệu phân biệt chính.
	private final double[] ahpWeights = { 0.50, 0.25, 0.25 };

	private volatile double alpha = 0.50, beta = 0.25, gamma = 0.25;

	@PostConstruct
	public void registerMetrics() {
		Gauge.builder("alb.mcdm.weight", () -> alpha).tag("criterion", "latency").register(registry);
		Gauge.builder("alb.mcdm.weight", () -> beta).tag("criterion", "queue").register(registry);
		Gauge.builder("alb.mcdm.weight", () -> gamma).tag("criterion", "cpu").register(registry);
	}

	@Scheduled(fixedRateString = "${alb.weights.update-interval:5000}")
	public void computeMCDMWeights() {
		List<InstanceMetrics> instances = cache.getAllMetrics();
		int n = instances.size();
		if (n < 2)
			return;

		double totalQueue = instances.stream().mapToDouble(InstanceMetrics::getQueueLength).sum();
		double avgCpu = instances.stream().mapToDouble(InstanceMetrics::getCpu).average().orElse(0.0);
		double avgQueue = totalQueue / n;

		// ── FIX: Strict idle condition ────────────────────────────────────────
		// avgQueue < 0.3 (trước: 0.5) và avgCpu < 0.02 (trước: 0.03)
		// Đảm bảo không freeze weights quá sớm khi hệ thống đang idle giữa burst
		if (avgQueue < 0.3 && avgCpu < 0.02) {
			log.debug("System idle — weights frozen at AHP defaults");
			this.alpha = ahpWeights[0];
			this.beta = ahpWeights[1];
			this.gamma = ahpWeights[2];
			return;
		}

		double[][] normalizedMatrix = buildNormalizedMatrix(instances, n);
		double[] ewmWeights = calculateEntropyWeights(normalizedMatrix, n);
		blendAndApplyFinalWeights(ewmWeights);
	}

	private double[][] buildNormalizedMatrix(List<InstanceMetrics> instances, int n) {
		double[][] data = new double[n][CRITERIA_COUNT];

		for (int j = 0; j < CRITERIA_COUNT; j++) {
			double minVal = Double.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				double val = getMetric(instances.get(i), j);
				if (val < minVal)
					minVal = val;
			}
			for (int i = 0; i < n; i++) {
				double val = getMetric(instances.get(i), j);
				data[i][j] = (minVal + MU[j]) / (val + MU[j]);
			}
		}
		return data;
	}

	private double[] calculateEntropyWeights(double[][] data, int n) {
		double[] ewm = new double[CRITERIA_COUNT];
		double sumEwm = 0;
		double k = 1.0 / Math.log(n);

		for (int j = 0; j < CRITERIA_COUNT; j++) {
			double colSum = 0;
			for (int i = 0; i < n; i++)
				colSum += data[i][j];

			double sumEntropy = 0;
			for (int i = 0; i < n; i++) {
				double p = data[i][j] / colSum;
				if (p > 0)
					sumEntropy += p * Math.log(p);
			}
			ewm[j] = Math.max(0.0, 1.0 - (-k * sumEntropy));
			sumEwm += ewm[j];
		}

		double[] ewmNorm = new double[CRITERIA_COUNT];
		for (int j = 0; j < CRITERIA_COUNT; j++) {
			ewmNorm[j] = (sumEwm == 0) ? (1.0 / CRITERIA_COUNT) : (ewm[j] / sumEwm);
		}
		return ewmNorm;
	}

	private void blendAndApplyFinalWeights(double[] ewmNorm) {
		double sumFusion = 0;
		double[] fusion = new double[CRITERIA_COUNT];
		for (int j = 0; j < CRITERIA_COUNT; j++) {
			double blended = BLEND_FACTOR * ewmNorm[j] + (1 - BLEND_FACTOR) * ahpWeights[j];
			fusion[j] = ahpWeights[j] * blended;
			sumFusion += fusion[j];
		}

		double rawAlpha = fusion[0] / sumFusion;
		double rawBeta = fusion[1] / sumFusion;
		double rawGamma = fusion[2] / sumFusion;

		double newAlpha = WEIGHT_EMA_ALPHA * rawAlpha + (1 - WEIGHT_EMA_ALPHA) * this.alpha;
		double newBeta = WEIGHT_EMA_ALPHA * rawBeta + (1 - WEIGHT_EMA_ALPHA) * this.beta;
		double newGamma = WEIGHT_EMA_ALPHA * rawGamma + (1 - WEIGHT_EMA_ALPHA) * this.gamma;

		double s = newAlpha + newBeta + newGamma;
		newAlpha /= s;
		newBeta /= s;
		newGamma /= s;

		// ── Bounds: gamma được phép lên tới 0.55 để detect CPU overload ──────
		// (giữ nguyên so với trước — quan trọng cho heterogeneous capacity)
		if (newGamma > 0.55) {
			double excess = newGamma - 0.55;
			newGamma = 0.55;
			newAlpha += excess * 0.70;
			newBeta += excess * 0.30;
		}
		if (newAlpha > 0.70) {
			double excess = newAlpha - 0.70;
			newAlpha = 0.70;
			newBeta += excess * 0.60;
			newGamma += excess * 0.40;
		}
		if (newBeta > 0.45) {
			double excess = newBeta - 0.45;
			newBeta = 0.45;
			newAlpha += excess;
		}

		// Hard floor
		newAlpha = Math.max(0.15, newAlpha);
		newBeta = Math.max(0.08, newBeta);
		newGamma = Math.max(0.10, newGamma); // floor gamma cao hơn: 0.08 → 0.10

		s = newAlpha + newBeta + newGamma;
		this.alpha = newAlpha / s;
		this.beta = newBeta / s;
		this.gamma = newGamma / s;

		log.debug("MCDM weights updated: α={:.3f} β={:.3f} γ={:.3f}", this.alpha, this.beta, this.gamma);
	}

	private double getMetric(InstanceMetrics m, int criteriaIndex) {
		return switch (criteriaIndex) {
		case 0 -> m.getLatency();
		case 1 -> m.getQueueLength();
		case 2 -> m.getCpu();
		default -> EPSILON;
		};
	}

	public double getAlpha() {
		return alpha;
	}

	public double getBeta() {
		return beta;
	}

	public double getGamma() {
		return gamma;
	}
}