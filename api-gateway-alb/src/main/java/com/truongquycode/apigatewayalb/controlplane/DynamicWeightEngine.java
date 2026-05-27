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

	// Mức nền tương ứng cho [Latency (5ms), Queue (1 request), CPU (0.05%)]
	private static final double[] MU = { 5.0, 1.0, 0.05 };

	private static final double WEIGHT_EMA_ALPHA = 0.15; // Chỉ cập nhật 25% mỗi chu kỳ 5s

	// Khai báo hằng số toán học rõ ràng (Tránh Magic Numbers)
	private static final double EPSILON = 1e-6;
	private static final double BLEND_FACTOR = 0.7; // 70% EWM + 30% AHP
	private static final int CRITERIA_COUNT = 3; // Latency, Queue, CPU

	private final MetricsCache cache;
	private final MeterRegistry registry;
	private final double[] ahpWeights = { 0.5, 0.3, 0.2 }; // Có thể đưa ra application.yml sau

	private volatile double alpha = 0.5, beta = 0.3, gamma = 0.2;

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

		// ── Sửa idle condition: chỉ freeze khi THỰC SỰ nhàn rỗi ─────────────────
		// Trước: avgQueue < 1.0 AND avgCpu < 0.05
		// Sau: avgQueue < 0.5 AND avgCpu < 0.03 (chặt hơn, tránh freeze khi hidden CPU)
		if (avgQueue < 0.5 && avgCpu < 0.03) {
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

	// --- BƯỚC 1: Xây dựng và chuẩn hóa ma trận dữ liệu ---
	// 2. Cập nhật toàn bộ nội dung của hàm buildNormalizedMatrix:
	private double[][] buildNormalizedMatrix(List<InstanceMetrics> instances, int n) {
		double[][] data = new double[n][CRITERIA_COUNT];

		for (int j = 0; j < CRITERIA_COUNT; j++) {
			double minVal = Double.MAX_VALUE;
			// Tìm Min cho từng tiêu chí
			for (int i = 0; i < n; i++) {
				double val = getMetric(instances.get(i), j);
				if (val < minVal)
					minVal = val;
			}

			// Chuẩn hóa Laplace: (Min + mu) / (Val + mu)
			// Triệt tiêu lỗi chia cho 0 và triệt tiêu luôn độ nhạy thái quá khi Val ≈ 0
			for (int i = 0; i < n; i++) {
				double val = getMetric(instances.get(i), j);
				data[i][j] = (minVal + MU[j]) / (val + MU[j]);
			}
		}
		return data;
	}

	// --- BƯỚC 2: Tính toán trọng số Entropy (EWM) ---
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

		// Chuẩn hóa EWM để tổng = 1
		double[] ewmNorm = new double[CRITERIA_COUNT];
		for (int j = 0; j < CRITERIA_COUNT; j++) {
			ewmNorm[j] = (sumEwm == 0) ? (1.0 / CRITERIA_COUNT) : (ewm[j] / sumEwm);
		}
		return ewmNorm;
	}

	// --- BƯỚC 3: Trộn EWM với AHP và cập nhật biến toàn cục ---
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

		// ── Nâng ceiling gamma: 0.40 → 0.55 ─────────────────────────────────
		// Lý do: khi hidden CPU degradation, EWM tự nhiên tăng gamma cao
		// Nếu cap ở 0.40, signal bị triệt tiêu → không detect được
		// Với cap 0.55: 8083 baseScore ≈ 0.60 → ratio 2.8x vs healthy → excluded ✓
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
		newGamma = Math.max(0.08, newGamma);

		s = newAlpha + newBeta + newGamma;
		this.alpha = newAlpha / s;
		this.beta = newBeta / s;
		this.gamma = newGamma / s;
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