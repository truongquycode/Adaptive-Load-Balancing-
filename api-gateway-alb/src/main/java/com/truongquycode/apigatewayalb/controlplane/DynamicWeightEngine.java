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
    private static final double[] MU = {5.0, 1.0, 0.05};
    
    // Khai báo hằng số toán học rõ ràng (Tránh Magic Numbers)
    private static final double EPSILON = 1e-6; 
    private static final double BLEND_FACTOR = 0.7; // 70% EWM + 30% AHP
    private static final int CRITERIA_COUNT = 3;    // Latency, Queue, CPU
    
    private final MetricsCache cache;
    private final MeterRegistry registry;
    private final double[] ahpWeights = {0.5, 0.3, 0.2}; // Có thể đưa ra application.yml sau
    
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
        if (n < 2) return;

        // BẢN VÁ TỐI ƯU: NGỦ ĐÔNG TRỌNG SỐ KHI HỆ THỐNG NHÀN RỖI (IDLE)
        // Nếu không có request nào đang chờ (tổng Queue = 0) và CPU trung bình cực thấp (< 5%)
        double totalQueue = instances.stream().mapToDouble(InstanceMetrics::getQueueLength).sum();
        double avgCpu = instances.stream().mapToDouble(InstanceMetrics::getCpu).average().orElse(0.0);
        
        double avgQueue = totalQueue / n;
        if (avgQueue < 1.0 && avgCpu < 0.05) {
            log.debug("Hệ thống nhàn rỗi - Đóng băng và Reset trọng số về mặc định (AHP)");
            // Kéo trọng số về lại mức gốc ban đầu của hệ thống
            this.alpha = ahpWeights[0]; // 0.5
            this.beta  = ahpWeights[1]; // 0.3
            this.gamma = ahpWeights[2]; // 0.2
            return; // Dừng hàm, không tính toán EWM
        }

        // Nếu hệ thống đang có tải (Queue > 0 hoặc CPU > 5%), tiến hành tính toán bình thường
        double[][] normalizedMatrix = buildNormalizedMatrix(instances, n);
        double[] ewmWeights = calculateEntropyWeights(normalizedMatrix, n);
        blendAndApplyFinalWeights(ewmWeights);
        
        log.debug("MCDM Weights Updated: Alpha={}, Beta={}, Gamma={}", alpha, beta, gamma);
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
                if (val < minVal) minVal = val;
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
            for (int i = 0; i < n; i++) colSum += data[i][j];

            double sumEntropy = 0;
            for (int i = 0; i < n; i++) {
                double p = data[i][j] / colSum;
                if (p > 0) sumEntropy += p * Math.log(p);
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

        this.alpha = fusion[0] / sumFusion;
        this.beta  = fusion[1] / sumFusion;
        this.gamma = fusion[2] / sumFusion;
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
    public double getBeta() { return beta; }
    public double getGamma() { return gamma; }
}