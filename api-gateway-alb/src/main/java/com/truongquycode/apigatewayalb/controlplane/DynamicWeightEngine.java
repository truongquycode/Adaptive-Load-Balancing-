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

/**
 * Tính toán trọng số MCDM (α, β, γ) cho 3 tiêu chí: latency, queue, CPU.
 *
 * Kết hợp 2 phương pháp: - EWM (Entropy Weight Method): tự động tăng trọng số
 * tiêu chí nào đang biến động nhiều nhất - AHP (Analytic Hierarchy Process):
 * trọng số cố định do chuyên gia định nghĩa
 *
 * Công thức blend: finalWeight = AHP × (0.8×EWM + 0.2×AHP), sau đó normalize về
 * tổng = 1 Kết quả được làm mượt bằng EMA trước khi cập nhật α/β/γ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicWeightEngine {

	// Hằng số dịch chuyển khi normalize ma trận — tránh chia 0 khi các instance có
	// metric bằng nhau
	// [latency, queue, CPU] — latency dùng mu lớn vì đơn vị ms (dao động hàng chục
	// ms)
	private static final double[] MU = { 5.0, 1.0, 0.05 };

	// Tốc độ EMA khi cập nhật trọng số — nhỏ = thay đổi chậm, ổn định hơn
	private static final double WEIGHT_EMA_ALPHA = 0.08;

	// Tỉ lệ EWM trong blend: 0.8 = EWM chiếm 80%, AHP chiếm 20%
	private static final double BLEND_FACTOR = 0.80;

	private static final double ONE_MINUS_BLEND = 1.0 - BLEND_FACTOR; // = 0.20
	private static final double ONE_MINUS_EMA = 1.0 - WEIGHT_EMA_ALPHA; // = 0.92

	private static final int CRITERIA_COUNT = 3; // latency, queue, CPU

	private final MetricsCache cache;
	private final MeterRegistry registry;

	// Trọng số AHP — định nghĩa tay, ưu tiên latency nhất
	// Workload CPU-intensive → CPU và latency quan trọng hơn queue
	private static final double[] AHP_WEIGHTS = { 0.648, 0.230, 0.122 };

	// Sử dụng Immutable Record để đảm bảo tính Nguyên tử (Atomicity) khi nhiều
	// Thread cùng đọc/ghi
	private record McdmWeights(double alpha, double beta, double gamma) {
	}

	private volatile McdmWeights weights = new McdmWeights(0.648, 0.230, 0.122);

	@Scheduled(fixedRateString = "${alb.weights.update-interval:5000}")
	public void computeMCDMWeights() {
		List<InstanceMetrics> instances = cache.getAllMetrics();
		int n = instances.size();
		if (n < 2)
			return; // EWM cần ít nhất 2 instance để tính entropy

		double totalQueue = 0.0, totalCpu = 0.0;
		for (InstanceMetrics m : instances) {
			totalQueue += m.getQueueLength();
			totalCpu += m.getCpu();
		}
		double avgQueue = totalQueue / n;
		double avgCpu = totalCpu / n;

		// Hệ thống đang idle → giữ nguyên AHP default, không cần EWM
		if (avgQueue < 2.0 && avgCpu < 0.06) {
			log.debug("System idle — weights frozen at AHP defaults");
			// Tạo record mới (bất biến) và gán lại cho volatile reference
			this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
			return;
		}

		double[][] normalizedMatrix = buildNormalizedMatrix(instances, n);
		double[] ewmWeights = calculateEntropyWeights(normalizedMatrix, n);
		blendAndApplyFinalWeights(ewmWeights);
	}

	/**
	 * Normalize ma trận metrics: giá trị tốt hơn → điểm cao hơn. Công thức: (minVal
	 * + mu) / (val + mu) → instance có metric thấp nhất được điểm cao nhất (gần
	 * 1.0)
	 */
	private double[][] buildNormalizedMatrix(List<InstanceMetrics> instances, int n) {
		// Pass 1: extract trực tiếp + tìm min đồng thời — loại bỏ hoàn toàn switch
		// dispatch
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

		// Pass 2: normalize — numerator cố định cho cả cột, tính ngoài vòng lặp
		double[][] data = new double[n][CRITERIA_COUNT];
		for (int j = 0; j < CRITERIA_COUNT; j++) {
			double numerator = minVal[j] + MU[j]; // tính 1 lần thay vì n lần
			double mu = MU[j];
			for (int i = 0; i < n; i++) {
				data[i][j] = numerator / (raw[i][j] + mu);
			}
		}
		return data;
	}

	/**
	 * Tính trọng số EWM từ entropy của từng tiêu chí. Tiêu chí nào có entropy thấp
	 * (các instance chênh lệch nhau nhiều) → trọng số cao hơn.
	 */
	private double[] calculateEntropyWeights(double[][] data, int n) {
		double[] ewm = new double[CRITERIA_COUNT];
		double sumEwm = 0;
		double k = 1.0 / Math.log(n);

		for (int j = 0; j < CRITERIA_COUNT; j++) {
			double colSum = 0;
			for (int i = 0; i < n; i++)
				colSum += data[i][j];

			double invColSum = 1.0 / colSum; // 1 phép chia thay vì n phép chia
			double sumEntropy = 0;
			for (int i = 0; i < n; i++) {
				double p = data[i][j] * invColSum; // nhân nhanh hơn chia
				sumEntropy += p * Math.log(p); // bỏ if (p>0) — luôn đúng
			}
			ewm[j] = Math.max(0.0, 1.0 - (-k * sumEntropy));
			sumEwm += ewm[j];
		}

		// Normalize EWM về tổng = 1
		double[] ewmNorm = new double[CRITERIA_COUNT];
		for (int j = 0; j < CRITERIA_COUNT; j++) {
			ewmNorm[j] = (sumEwm == 0) ? (1.0 / CRITERIA_COUNT) : (ewm[j] / sumEwm);
		}
		return ewmNorm;
	}

	/**
	 * Blend EWM + AHP → làm mượt bằng EMA → áp giới hạn → cập nhật α/β/γ.
	 */
	private void blendAndApplyFinalWeights(double[] ewmNorm) {
		double sumFusion = 0;
		double[] fusion = new double[CRITERIA_COUNT];
		for (int j = 0; j < CRITERIA_COUNT; j++) {
			fusion[j] = BLEND_FACTOR * ewmNorm[j] + ONE_MINUS_BLEND * AHP_WEIGHTS[j];
			sumFusion += fusion[j];
		}

		// Inline trực tiếp, dùng hằng số đã tính sẵn
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
		// Gán toàn bộ 3 trọng số vào 1 Record bất biến, đảm bảo Atomicity cho Data
		// Plane
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

	// Đăng ký gauge để Prometheus theo dõi trọng số thay đổi theo thời gian
	@PostConstruct
	public void registerMetrics() {
		// Dùng Method Reference để luôn đọc qua getter (đảm bảo tính volatile)
		Gauge.builder("alb.mcdm.weight", this::getAlpha).tag("criterion", "latency").register(registry);
		Gauge.builder("alb.mcdm.weight", this::getBeta).tag("criterion", "queue").register(registry);
		Gauge.builder("alb.mcdm.weight", this::getGamma).tag("criterion", "cpu").register(registry);
	}

	// Reset về AHP default — gọi trước mỗi lần benchmark
	public void resetWeights() {
		this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
	}
}