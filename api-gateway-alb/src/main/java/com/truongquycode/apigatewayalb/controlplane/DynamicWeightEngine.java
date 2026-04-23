package com.truongquycode.apigatewayalb.controlplane;

import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.util.MetricsCache;
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
    private final double[] ahpWeights = {0.5, 0.3, 0.2};
    private volatile double alpha = 0.5, beta = 0.3, gamma = 0.2;

    @Scheduled(fixedRateString = "${alb.weights.update-interval:5000}")
    public void computeMCDMWeights() {
        // FIX: Đổi từ getAll() thành getAllMetrics()
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

        double sumFusion = 0;
        double[] fusion = new double[3];
        for (int j = 0; j < 3; j++) {
            ewm[j] = (sumEwm == 0) ? (1.0 / 3.0) : (ewm[j] / sumEwm);
            fusion[j] = ahpWeights[j] * ewm[j];
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