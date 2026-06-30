package com.truongquycode.apigatewayalb.controlplane;

import com.truongquycode.apigatewayalb.config.AlbProperties;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
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
 * DynamicWeightEngine v3.
 *
 * Khác bản cũ:
 * - Không tính entropy trực tiếp trên raw latency/queue/cpu nữa.
 * - Tính trên normLatency/normQueue/normCpu do ScoreCalculator tạo ra.
 *
 * Lý do: ScoreCalculator đang dùng chính các giá trị normalized này để tính
 * baseScore. Nếu EWM cũng nhìn cùng không gian [0,1], trọng số động sẽ ăn khớp
 * với score thật hơn và dễ giải thích hơn trên Grafana.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicWeightEngine {

    private static final int CRITERIA_COUNT = 3;
    private static final double EPS = 1e-9;

    // AHP prior: latency quan trọng nhất, queue là tín hiệu sớm, CPU hỗ trợ hidden degradation.
    private static final double[] AHP_WEIGHTS = { 0.648, 0.230, 0.122 };

    // V3: tăng nhẹ tốc độ thích nghi nhưng vẫn không nhảy quá mạnh.
    private static final double WEIGHT_EMA_ALPHA_MIN = 0.08;
    private static final double WEIGHT_EMA_ALPHA_MAX = 0.22;

    // EWM vẫn là dữ liệu thực, AHP vẫn là neo chuyên gia.
    private static final double BLEND_FACTOR = 0.70;
    private static final double ONE_MINUS_BLEND = 1.0 - BLEND_FACTOR;

    private record McdmWeights(double alpha, double beta, double gamma) {}

    private volatile McdmWeights weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);

    // Prometheus/debug state cho biết MCDM đang học động hay bị khóa vì idle.
    // 0 = frozen/idle, 1 = dynamic update, 2 = fixed-weights ablation.
    private volatile double mcdmUpdateMode = 0.0;
    private volatile double lastTrafficCompletedRequests = 0.0;
    private volatile double lastTrafficRps = 0.0;

    private final MetricsCache cache;
    private final MeterRegistry registry;
    private final AlbProperties props;

    @Scheduled(fixedRateString = "${alb.weights.update-interval:5000}")
    public void computeMCDMWeights() {
        long nowMs = System.currentTimeMillis();
        MetricsCache.TrafficActivitySnapshot traffic = cache.snapshotAndResetMcdmTrafficWindow(nowMs);
        lastTrafficCompletedRequests = traffic.completedRequests();
        lastTrafficRps = traffic.actualRps();

        if (props.getAblation() != null && props.getAblation().isVariant("fixed-weights")) {
            mcdmUpdateMode = 2.0;
            resetWeights();
            return;
        }

        // Guard khoa học quan trọng:
        // Dynamic EWM chỉ được cập nhật khi có đủ request nghiệp vụ thật trong cửa sổ vừa qua.
        // Không dùng CPU nền, idle latency hoặc điểm score được tạo bởi metrics polling để học trọng số.
        if (!hasEnoughRealTraffic(traffic)) {
            mcdmUpdateMode = 0.0;
            if (props.getWeights() == null || props.getWeights().isResetToAhpWhenIdle()) {
                resetWeights();
            }
            log.debug("MCDM dynamic update skipped: insufficient real traffic, completed={}, rps={}, windowMs={}",
                    traffic.completedRequests(), traffic.actualRps(), traffic.windowMs());
            return;
        }

        List<ScoreBreakdown> scores = cache.getAllScores();
        int n = scores.size();
        if (n < 2) {
            mcdmUpdateMode = 0.0;
            return;
        }

        double avgQueue = 0.0;
        double avgCpu = 0.0;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (ScoreBreakdown s : scores) {
            avgQueue += s.normQueue();
            avgCpu += s.normCpu();
            minLat = Math.min(minLat, s.normLatency());
            maxLat = Math.max(maxLat, s.normLatency());
        }
        avgQueue /= n;
        avgCpu /= n;

        // Khi có traffic thật nhưng cụm vẫn rất ổn định, giữ AHP để tránh EWM khuếch đại nhiễu nhỏ.
        if (avgQueue < 0.08 && avgCpu < 0.08 && (maxLat - minLat) < 0.12) {
            mcdmUpdateMode = 0.0;
            this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
            return;
        }

        double[][] matrix = buildBadnessMatrix(scores, n);
        double[] ewm = calculateEntropyWeights(matrix, n);
        blendAndApplyWeights(ewm);
        mcdmUpdateMode = 1.0;
    }

    private boolean hasEnoughRealTraffic(MetricsCache.TrafficActivitySnapshot traffic) {
        AlbProperties.Weights weightProps = props.getWeights();
        long minCompleted = weightProps != null ? weightProps.getMinCompletedRequests() : 20L;
        double minRps = weightProps != null ? weightProps.getMinActualRps() : 5.0;

        return traffic.completedRequests() >= minCompleted && traffic.actualRps() >= minRps;
    }

    private double[][] buildBadnessMatrix(List<ScoreBreakdown> scores, int n) {
        double[][] data = new double[n][CRITERIA_COUNT];
        for (int i = 0; i < n; i++) {
            ScoreBreakdown s = scores.get(i);
            // Badness càng lớn càng xấu. EWM chỉ cần độ phân tán, không cần hướng tốt/xấu.
            data[i][0] = clamp01(s.normLatency()) + EPS;
            data[i][1] = clamp01(s.normQueue()) + EPS;
            data[i][2] = clamp01(s.normCpu()) + EPS;
        }
        return data;
    }

    private double[] calculateEntropyWeights(double[][] data, int n) {
        double[] diversity = new double[CRITERIA_COUNT];
        double sumDiversity = 0.0;
        double k = 1.0 / Math.log(n);

        for (int j = 0; j < CRITERIA_COUNT; j++) {
            double colSum = 0.0;
            for (int i = 0; i < n; i++) {
                colSum += data[i][j];
            }

            double entropySum = 0.0;
            for (int i = 0; i < n; i++) {
                double p = data[i][j] / Math.max(colSum, EPS);
                entropySum += p * Math.log(p);
            }

            double entropy = -k * entropySum;
            diversity[j] = Math.max(0.0, 1.0 - entropy);
            sumDiversity += diversity[j];
        }

        double[] weights = new double[CRITERIA_COUNT];
        if (sumDiversity <= EPS) {
            // Nếu mọi tiêu chí gần như không phân biệt được, quay về AHP prior thay vì đều 1/3.
            System.arraycopy(AHP_WEIGHTS, 0, weights, 0, CRITERIA_COUNT);
            return weights;
        }

        for (int j = 0; j < CRITERIA_COUNT; j++) {
            weights[j] = diversity[j] / sumDiversity;
        }
        return weights;
    }

    private void blendAndApplyWeights(double[] ewm) {
        double[] target = new double[CRITERIA_COUNT];
        double sum = 0.0;
        for (int j = 0; j < CRITERIA_COUNT; j++) {
            target[j] = BLEND_FACTOR * ewm[j] + ONE_MINUS_BLEND * AHP_WEIGHTS[j];
            sum += target[j];
        }
        for (int j = 0; j < CRITERIA_COUNT; j++) {
            target[j] /= Math.max(sum, EPS);
        }

        McdmWeights current = this.weights;
        double delta = (Math.abs(target[0] - current.alpha())
                + Math.abs(target[1] - current.beta())
                + Math.abs(target[2] - current.gamma())) / 3.0;

        double emaAlpha = WEIGHT_EMA_ALPHA_MIN
                + (WEIGHT_EMA_ALPHA_MAX - WEIGHT_EMA_ALPHA_MIN) * clamp01(delta * 3.0);

        double newAlpha = emaAlpha * target[0] + (1.0 - emaAlpha) * current.alpha();
        double newBeta = emaAlpha * target[1] + (1.0 - emaAlpha) * current.beta();
        double newGamma = emaAlpha * target[2] + (1.0 - emaAlpha) * current.gamma();

        // Bounds mềm: không cho một tiêu chí triệt tiêu hoàn toàn hoặc thống trị tuyệt đối.
        newGamma = clamp(newGamma, 0.08, 0.35);
        newBeta = clamp(newBeta, 0.08, 0.45);
        newAlpha = clamp(newAlpha, 0.15, 0.70);

        double s = newAlpha + newBeta + newGamma;
        this.weights = new McdmWeights(newAlpha / s, newBeta / s, newGamma / s);

        log.debug("MCDM weights v3 updated: α={} β={} γ={}", getAlpha(), getBeta(), getGamma());
    }

    public double getAlpha() { return weights.alpha(); }
    public double getBeta()  { return weights.beta(); }
    public double getGamma() { return weights.gamma(); }

    public double getMcdmUpdateMode() { return mcdmUpdateMode; }
    public double getLastTrafficCompletedRequests() { return lastTrafficCompletedRequests; }
    public double getLastTrafficRps() { return lastTrafficRps; }

    public double computeBaseScore(double nL, double nQ, double nC) {
        McdmWeights w = weights;
        return (w.alpha() * nL) + (w.beta() * nQ) + (w.gamma() * nC);
    }

    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("alb.mcdm.weight", this::getAlpha).tag("criterion", "latency").register(registry);
        Gauge.builder("alb.mcdm.weight", this::getBeta) .tag("criterion", "queue")  .register(registry);
        Gauge.builder("alb.mcdm.weight", this::getGamma).tag("criterion", "cpu")    .register(registry);

        Gauge.builder("alb.mcdm.update.mode", this::getMcdmUpdateMode)
                .description("0=frozen_idle_or_stable, 1=dynamic_real_traffic, 2=fixed_weights_ablation")
                .register(registry);
        Gauge.builder("alb.mcdm.recent.completed.requests", this::getLastTrafficCompletedRequests)
                .description("Completed real backend requests seen in the last MCDM update window")
                .register(registry);
        Gauge.builder("alb.mcdm.recent.actual.rps", this::getLastTrafficRps)
                .description("Actual real backend RPS seen in the last MCDM update window")
                .register(registry);
    }

    public void resetWeights() {
        this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
    }

    private double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
