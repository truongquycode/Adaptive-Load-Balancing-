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
    private final MetricsCache cache;
    private final MeterRegistry registry;
    private final double[] ahpWeights = {0.5, 0.3, 0.2};
    private volatile double alpha = 0.5, beta = 0.3, gamma = 0.2;
    
    @PostConstruct
    public void registerMetrics() {                // THÊM toàn bộ method này
        Gauge.builder("alb.mcdm.weight", () -> alpha)
            .tag("criterion", "latency")
            .description("MCDM weight alpha — trọng số latency (AHP-EWM fusion)")
            .register(registry);
        Gauge.builder("alb.mcdm.weight", () -> beta)
            .tag("criterion", "queue")
            .description("MCDM weight beta — trọng số queue length")
            .register(registry);
        Gauge.builder("alb.mcdm.weight", () -> gamma)
            .tag("criterion", "cpu")
            .description("MCDM weight gamma — trọng số CPU")
            .register(registry);
    }

    @Scheduled(fixedRateString = "${alb.weights.update-interval:5000}")
    public void computeMCDMWeights() {	
        // Đổi từ getAll() thành getAllMetrics()
        List<InstanceMetrics> instances = cache.getAllMetrics(); 
        int n = instances.size();
        if (n < 2) return;

        double[][] data = new double[n][3];
        double epsilon = 1e-6; 

        for (int j = 0; j < 3; j++) {
            double minVal = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double val = Math.max(epsilon, getMetric(instances.get(i), j));
                if (val < minVal) minVal = val;
            }
            for (int i = 0; i < n; i++) {
                double val = Math.max(epsilon, getMetric(instances.get(i), j));
                data[i][j] = minVal / val; 
            }
        }

        double[] ewm = new double[3];
        double sumEwm = 0;
        double k = 1.0 / Math.log(n);

        for (int j = 0; j < 3; j++) {
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
        
     // Normalize EWM
        double[] ewmNorm = new double[3];
        for (int j = 0; j < 3; j++) {
            ewmNorm[j] = (sumEwm == 0) ? (1.0 / 3.0) : (ewm[j] / sumEwm);
        }

        // BLEND: 70% EWM + 30% AHP — tránh weight về 0 khi metric uniform
        double blendFactor = 0.7;
        double[] blended = new double[3];
        for (int j = 0; j < 3; j++) {
            blended[j] = blendFactor * ewmNorm[j] + (1 - blendFactor) * ahpWeights[j];
        }

        // Fusion AHP × blended rồi normalize
        double sumFusion = 0;
        double[] fusion = new double[3];
        for (int j = 0; j < 3; j++) {
            fusion[j] = ahpWeights[j] * blended[j];
            sumFusion += fusion[j];
        }


        this.alpha = fusion[0] / sumFusion;
        this.beta = fusion[1] / sumFusion;
        this.gamma = fusion[2] / sumFusion;
        
        log.debug("MCDM Weights Updated: Alpha={}, Beta={}, Gamma={}", alpha, beta, gamma);
    }

    private double getMetric(InstanceMetrics m, int j) {
        return j == 0 ? m.getLatency() : j == 1 ? m.getQueueLength() : m.getCpu();
    }

    public double getAlpha() { return alpha; }
    public double getBeta() { return beta; }
    public double getGamma() { return gamma; }
}