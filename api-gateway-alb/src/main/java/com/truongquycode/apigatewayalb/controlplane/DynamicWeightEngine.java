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

	private static final double WEIGHT_EMA_ALPHA = 0.08;

	private static final double BLEND_FACTOR = 0.80;

	private static final double ONE_MINUS_BLEND = 1.0 - BLEND_FACTOR; 
	private static final double ONE_MINUS_EMA = 1.0 - WEIGHT_EMA_ALPHA;

	private static final int CRITERIA_COUNT = 3; // latency, queue, CPU

	private final MetricsCache cache;
	private final MeterRegistry registry;

	private static final double[] AHP_WEIGHTS = { 0.648, 0.230, 0.122 };

	private record McdmWeights(double alpha, double beta, double gamma) {
	}

	private volatile McdmWeights weights = new McdmWeights(0.648, 0.230, 0.122);

	@Scheduled(fixedRateString = "${alb.weights.update-interval:5000}")
	public void computeMCDMWeights() {
		List<InstanceMetrics> instances = cache.getAllMetrics();
		int n = instances.size();
		if (n < 2)
			return;

		double totalQueue = 0.0, totalCpu = 0.0;
		for (InstanceMetrics m : instances) {
			totalQueue += m.getQueueLength();
			totalCpu += m.getCpu();
		}
		double avgQueue = totalQueue / n;
		double avgCpu = totalCpu / n;

		if (avgQueue < 2.0 && avgCpu < 0.06) {
			log.debug("System idle — weights frozen at AHP defaults");
			this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
			return;
		}

		double[][] normalizedMatrix = buildNormalizedMatrix(instances, n);
		double[] ewmWeights = calculateEntropyWeights(normalizedMatrix, n);
		blendAndApplyFinalWeights(ewmWeights);
	}

	private double[][] buildNormalizedMatrix(List<InstanceMetrics> instances, int n) {
		double[][] raw = new double[n][CRITERIA_COUNT];
		double[] minVal = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };

		for (int i = 0; i < n; i++) {
			InstanceMetrics m = instances.get(i);
			raw[i][0] = m.getLatency();
			raw[i][1] = m.getQueueLength();
			raw[i][2] = m.getCpu();
			for (int j = 0; j < CRITERIA_COUNT; j++) {
				if (raw[i][j] < minVal[j])
					minVal[j] = raw[i][j];
			}
		}

		double[][] data = new double[n][CRITERIA_COUNT];
		for (int j = 0; j < CRITERIA_COUNT; j++) {
			double numerator = minVal[j] + MU[j];
			double mu = MU[j];
			for (int i = 0; i < n; i++) {
				data[i][j] = numerator / (raw[i][j] + mu);
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

			double invColSum = 1.0 / colSum; 
			double sumEntropy = 0;
			for (int i = 0; i < n; i++) {
				double p = data[i][j] * invColSum; 
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
			fusion[j] = BLEND_FACTOR * ewmNorm[j] + ONE_MINUS_BLEND * AHP_WEIGHTS[j];
			sumFusion += fusion[j];
		}

		double invSumFusion = 1.0 / sumFusion;
		double newAlpha = WEIGHT_EMA_ALPHA * (fusion[0] * invSumFusion) + ONE_MINUS_EMA * this.weights.alpha();
		double newBeta = WEIGHT_EMA_ALPHA * (fusion[1] * invSumFusion) + ONE_MINUS_EMA * this.weights.beta();
		double newGamma = WEIGHT_EMA_ALPHA * (fusion[2] * invSumFusion) + ONE_MINUS_EMA * this.weights.gamma();

		double s = newAlpha + newBeta + newGamma;
		newAlpha /= s;
		newBeta /= s;
		newGamma /= s;

		// ── 1. GIỚI HẠN TRÊN (Upper bounds) ──
		if (newGamma > 0.35) {
			double e = newGamma - 0.35;
			newGamma = 0.35;
			newAlpha += e * 0.60;
			newBeta += e * 0.40;
		}
		if (newAlpha > 0.70) {
			double e = newAlpha - 0.70;
			newAlpha = 0.70;
			newBeta += e;
		}
		if (newBeta > 0.55) {
			double e = newBeta - 0.55;
			newBeta = 0.55;
			newAlpha += e;
		}

		// ── 2. GIỚI HẠN DƯỚI (Lower bounds - Phân bổ bù trừ trực tiếp) ──
		if (newGamma < 0.08) {
			double deficit = 0.08 - newGamma;
			newGamma = 0.08;
			newAlpha -= deficit * 0.70;
			newBeta -= deficit * 0.30;
		}

		if (newBeta < 0.08) {
			double deficit = 0.08 - newBeta;
			newBeta = 0.08;
			newAlpha -= deficit;
		}

		if (newAlpha < 0.15) {
			double deficit = 0.15 - newAlpha;
			newAlpha = 0.15;
			newBeta -= deficit;
		}

		// ── 3. GÁN KẾT QUẢ ──
		this.weights = new McdmWeights(newAlpha, newBeta, newGamma);

		log.debug("MCDM weights updated: α={} β={} γ={}", newAlpha, newBeta, newGamma);
	}

	public double getAlpha() {
		return weights.alpha();
	}

	public double getBeta() {
		return weights.beta();
	}

	public double getGamma() {
		return weights.gamma();
	}

	public double computeBaseScore(double nL, double nQ, double nC) {
		McdmWeights w = weights; // 1 volatile read duy nhất
		return (w.alpha() * nL) + (w.beta() * nQ) + (w.gamma() * nC);
	}

	// Đăng ký gauge để Prometheus theo dõi trọng số thay đổi theo thời gian
	@PostConstruct
	public void registerMetrics() {
		// Dùng Method Reference để luôn đọc qua getter (đảm bảo tính volatile)
		Gauge.builder("alb.mcdm.weight", this::getAlpha).tag("criterion", "latency").register(registry);
		Gauge.builder("alb.mcdm.weight", this::getBeta).tag("criterion", "queue").register(registry);
		Gauge.builder("alb.mcdm.weight", this::getGamma).tag("criterion", "cpu").register(registry);
	}

	public void resetWeights() {
		this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
	}
}