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
 * Kết hợp 2 phương pháp:
 *   - EWM (Entropy Weight Method): tự động tăng trọng số tiêu chí nào đang biến động nhiều nhất
 *   - AHP (Analytic Hierarchy Process): trọng số cố định do chuyên gia định nghĩa
 *
 * Công thức blend: finalWeight = AHP × (0.8×EWM + 0.2×AHP), sau đó normalize về tổng = 1
 * Kết quả được làm mượt bằng EMA trước khi cập nhật α/β/γ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicWeightEngine {

    // Hằng số dịch chuyển khi normalize ma trận — tránh chia 0 khi các instance có metric bằng nhau
    // [latency, queue, CPU] — latency dùng mu lớn vì đơn vị ms (dao động hàng chục ms)
    private static final double[] MU = { 5.0, 1.0, 0.05 };

    // Tốc độ EMA khi cập nhật trọng số — nhỏ = thay đổi chậm, ổn định hơn
    private static final double WEIGHT_EMA_ALPHA = 0.15;

    private static final double EPSILON = 1e-6;

    // Tỉ lệ EWM trong blend: 0.8 = EWM chiếm 80%, AHP chiếm 20%
    private static final double BLEND_FACTOR = 0.80;

    private static final int CRITERIA_COUNT = 3; // latency, queue, CPU

    private final MetricsCache cache;
    private final MeterRegistry registry;

    // Trọng số AHP — định nghĩa tay, ưu tiên latency nhất
    // Workload CPU-intensive → CPU và latency quan trọng hơn queue
    private final double[] ahpWeights = { 0.648, 0.230, 0.122 };

    // Trọng số hiện tại — volatile vì đọc từ nhiều thread (routing thread + scheduler)
    private volatile double alpha = 0.648, beta = 0.230, gamma = 0.122;

    // Đăng ký gauge để Prometheus theo dõi trọng số thay đổi theo thời gian
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
        if (n < 2) return; // EWM cần ít nhất 2 instance để tính entropy

        double totalQueue = instances.stream().mapToDouble(InstanceMetrics::getQueueLength).sum();
        double avgCpu     = instances.stream().mapToDouble(InstanceMetrics::getCpu).average().orElse(0.0);
        double avgQueue   = totalQueue / n;

        // Hệ thống đang idle → giữ nguyên AHP default, không cần EWM
        if (avgQueue < 0.3 && avgCpu < 0.02) {
            log.debug("System idle — weights frozen at AHP defaults");
            this.alpha = ahpWeights[0];
            this.beta  = ahpWeights[1];
            this.gamma = ahpWeights[2];
            return;
        }

        double[][] normalizedMatrix = buildNormalizedMatrix(instances, n);
        double[]   ewmWeights       = calculateEntropyWeights(normalizedMatrix, n);
        blendAndApplyFinalWeights(ewmWeights);
    }

    /**
     * Normalize ma trận metrics: giá trị tốt hơn → điểm cao hơn.
     * Công thức: (minVal + mu) / (val + mu)
     * → instance có metric thấp nhất được điểm cao nhất (gần 1.0)
     */
    private double[][] buildNormalizedMatrix(List<InstanceMetrics> instances, int n) {
        double[][] data = new double[n][CRITERIA_COUNT];

        for (int j = 0; j < CRITERIA_COUNT; j++) {
            double minVal = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double val = getMetric(instances.get(i), j);
                if (val < minVal) minVal = val;
            }
            for (int i = 0; i < n; i++) {
                double val = getMetric(instances.get(i), j);
                data[i][j] = (minVal + MU[j]) / (val + MU[j]);
            }
        }
        return data;
    }

    /**
     * Tính trọng số EWM từ entropy của từng tiêu chí.
     * Tiêu chí nào có entropy thấp (các instance chênh lệch nhau nhiều) → trọng số cao hơn.
     */
    private double[] calculateEntropyWeights(double[][] data, int n) {
        double[] ewm    = new double[CRITERIA_COUNT];
        double   sumEwm = 0;
        double   k      = 1.0 / Math.log(n);

        for (int j = 0; j < CRITERIA_COUNT; j++) {
            double colSum     = 0;
            for (int i = 0; i < n; i++) colSum += data[i][j];

            double sumEntropy = 0;
            for (int i = 0; i < n; i++) {
                double p = data[i][j] / colSum;
                if (p > 0) sumEntropy += p * Math.log(p);
            }
            ewm[j]  = Math.max(0.0, 1.0 - (-k * sumEntropy));
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
     * Giới hạn: γ ≤ 0.35, α ≤ 0.75, β ≤ 0.45 — tránh một tiêu chí chiếm quá nhiều.
     */
    private void blendAndApplyFinalWeights(double[] ewmNorm) {
        double sumFusion = 0;
        double[] fusion  = new double[CRITERIA_COUNT];
        for (int j = 0; j < CRITERIA_COUNT; j++) {
            double blended = BLEND_FACTOR * ewmNorm[j] + (1 - BLEND_FACTOR) * ahpWeights[j];
            fusion[j]  = ahpWeights[j] * blended;
            sumFusion += fusion[j];
        }

        double rawAlpha = fusion[0] / sumFusion;
        double rawBeta  = fusion[1] / sumFusion;
        double rawGamma = fusion[2] / sumFusion;

        // EMA làm mượt — tránh trọng số nhảy đột ngột giữa các chu kỳ
        double newAlpha = WEIGHT_EMA_ALPHA * rawAlpha + (1 - WEIGHT_EMA_ALPHA) * this.alpha;
        double newBeta  = WEIGHT_EMA_ALPHA * rawBeta  + (1 - WEIGHT_EMA_ALPHA) * this.beta;
        double newGamma = WEIGHT_EMA_ALPHA * rawGamma + (1 - WEIGHT_EMA_ALPHA) * this.gamma;

        double s = newAlpha + newBeta + newGamma;
        newAlpha /= s;
        newBeta  /= s;
        newGamma /= s;

        // Giới hạn trên — tránh một tiêu chí độc chiếm, phần dư phân bổ sang tiêu chí khác
        if (newGamma > 0.35) { double e = newGamma - 0.35; newGamma = 0.35; newAlpha += e * 0.70; newBeta  += e * 0.30; }
        if (newAlpha > 0.75) { double e = newAlpha - 0.75; newAlpha = 0.75; newBeta  += e; }
        if (newBeta  > 0.45) { double e = newBeta  - 0.45; newBeta  = 0.45; newAlpha += e; }

        // Giới hạn dưới — đảm bảo mỗi tiêu chí luôn có tiếng nói tối thiểu
        newAlpha = Math.max(0.15, newAlpha);
        newBeta  = Math.max(0.08, newBeta);
        newGamma = Math.max(0.08, newGamma);

        s = newAlpha + newBeta + newGamma;
        this.alpha = newAlpha / s;
        this.beta  = newBeta  / s;
        this.gamma = newGamma / s;
        
        if (this.beta < 0.08) {
            double deficit = 0.08 - this.beta;
            this.beta = 0.08;
            this.alpha = Math.max(0.15, this.alpha - deficit * 0.70);
            this.gamma = Math.max(0.08, this.gamma - deficit * 0.30);
        }

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

    public double getAlpha() { return alpha; }
    public double getBeta()  { return beta;  }
    public double getGamma() { return gamma; }

    // Reset về AHP default — gọi trước mỗi lần benchmark
    public void resetWeights() {
        this.alpha = ahpWeights[0];
        this.beta  = ahpWeights[1];
        this.gamma = ahpWeights[2];
    }
}