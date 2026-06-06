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
 * DynamicWeightEngine — Bộ máy tính trọng số MCDM động.
 *
 * Bài toán: hệ thống có 3 tiêu chí đánh giá instance:
 *   α (alpha) = trọng số latency
 *   β (beta)  = trọng số queue length
 *   γ (gamma) = trọng số CPU usage
 *
 * Vấn đề với trọng số cố định: lúc hệ thống bình thường, latency là yếu tố
 * phân biệt instance tốt/xấu nhất → α nên cao. Nhưng khi CPU bão tải, CPU lại
 * trở thành tín hiệu quan trọng hơn → γ nên tăng lên.
 *
 * Giải pháp: kết hợp 2 phương pháp:
 *   1. AHP (Analytic Hierarchy Process): trọng số "chuyên gia" tĩnh, đóng vai trò
 *      neo (anchor) để tránh thuật toán đi quá xa khỏi kinh nghiệm thực tế.
 *   2. EWM (Entropy Weight Method): trọng số động dựa trên mức độ phân tán
 *      (variance) thực tế của từng tiêu chí. Tiêu chí nào có variance cao hơn
 *      (tức đang phân biệt tốt hơn giữa các instance) sẽ được tăng trọng số.
 *
 * Công thức tổng hợp:
 *   fusion = 0.80 × EWM_weight + 0.20 × AHP_weight
 *   final  = EMA(fusion, alpha=0.08) → normalize → clamp vào [min, max]
 *
 * Kết quả được đọc bởi ScoreCalculator.computeBaseScore():
 *   baseScore = α × normLatency + β × normQueue + γ × normCpu
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicWeightEngine {

    // ─────────────────────────────────────────────────────────────────────────
    // MU[j]: hằng số nhỏ (micro-offset) cộng vào tử số và mẫu số khi build
    // normalized matrix để tránh division-by-zero và tránh log(0).
    //
    // Giá trị được chọn theo đặc tính từng metric:
    //   MU[0] = 5.0   → latency tính bằng ms, nên offset 5ms là hợp lý
    //   MU[1] = 1.0   → queue tính bằng số request, offset 1 request
    //   MU[2] = 0.05  → CPU là tỉ lệ [0,1], offset 5% (= 0.05)
    //
    // Ảnh hưởng thực tế: làm cho các instance có metric gần nhau (ổn định)
    // cho ra score gần bằng nhau → tránh dao động trọng số do nhiễu nhỏ.
    // ─────────────────────────────────────────────────────────────────────────
    private static final double[] MU = { 5.0, 1.0, 0.05 };

    // Alpha cho EMA của chính các trọng số (α, β, γ).
    // 0.08 = rất chậm: mỗi lần update (5 giây) chỉ dịch chuyển 8% về phía
    // target mới. Mục đích: tránh trọng số dao động mạnh khi metrics biến
    // động ngắn hạn (VD: 1 spike CPU trong 200ms không nên đổi hẳn trọng số).
    private static final double WEIGHT_EMA_ALPHA = 0.08;

    // Tỉ lệ pha trộn EWM vs AHP trong hàm blendAndApplyFinalWeights().
    // BLEND_FACTOR = 0.80 → 80% dựa vào EWM (dữ liệu thực), 20% dựa vào AHP (kinh nghiệm).
    // Nếu đặt = 1.0: hoàn toàn data-driven, dễ bị nhiễu.
    // Nếu đặt = 0.0: hoàn toàn cố định, không adaptive.
    private static final double BLEND_FACTOR = 0.80;

    // Cache các giá trị (1 - constant) để tránh tính lại mỗi lần gọi.
    private static final double ONE_MINUS_BLEND = 1.0 - BLEND_FACTOR; // = 0.20
    private static final double ONE_MINUS_EMA   = 1.0 - WEIGHT_EMA_ALPHA; // = 0.92

    // Số lượng tiêu chí: latency (0), queue (1), CPU (2).
    private static final int CRITERIA_COUNT = 3;

    private final MetricsCache cache;
    private final MeterRegistry registry;

    // ─────────────────────────────────────────────────────────────────────────
    // AHP_WEIGHTS: trọng số Analytic Hierarchy Process — được tính offline
    // từ ma trận so sánh cặp (pairwise comparison matrix) của chuyên gia.
    //
    // Ý nghĩa thực tế:
    //   α = 0.648 (~65%) → Latency là yếu tố quan trọng nhất với user-facing service.
    //                       Người dùng cảm nhận latency trực tiếp qua thời gian chờ.
    //   β = 0.230 (~23%) → Queue length là tín hiệu sớm của overload.
    //                       Queue tăng trước khi latency tăng → phát hiện sớm hơn.
    //   γ = 0.122 (~12%) → CPU quan trọng nhưng ít nhất vì:
    //                       (a) CPU cao không luôn có nghĩa là latency cao (JVM GC, background tasks)
    //                       (b) Container CPU được throttle bởi cgroups, không phản ánh trực tiếp
    //
    // Đây là "neo" (anchor) để EWM không đi quá xa khỏi kinh nghiệm thực tế.
    // ─────────────────────────────────────────────────────────────────────────
    private static final double[] AHP_WEIGHTS = { 0.648, 0.230, 0.122 };

    // Record bất biến lưu bộ ba trọng số hiện tại.
    // Dùng record thay vì 3 field riêng lẻ để đảm bảo đọc/ghi atomic:
    // khi update, gán 1 object mới thay vì update từng field → không race condition.
    private record McdmWeights(double alpha, double beta, double gamma) {}

    // Trạng thái trọng số hiện tại — volatile để đảm bảo visibility giữa các thread.
    // Thread Scheduler (update weights) và thread WebFlux (chọn instance) đọc ghi song song.
    // Khởi tạo bằng AHP_WEIGHTS: nếu hệ thống vừa start và chưa có đủ data thì
    // dùng trọng số chuyên gia làm mặc định.
    private volatile McdmWeights weights = new McdmWeights(0.648, 0.230, 0.122);

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Điểm vào chính — được @Scheduled gọi mỗi 5 giây (cấu hình trong application.yml).
     *
     * Pipeline:
     *   1. Lấy raw metrics của tất cả instance từ MetricsCache
     *   2. Kiểm tra hệ thống có đang idle không → nếu idle thì giữ nguyên AHP defaults
     *   3. Nếu không idle:
     *      a. buildNormalizedMatrix()    → chuẩn hoá metrics về [0,1] theo hướng "nhỏ hơn = tốt hơn"
     *      b. calculateEntropyWeights()  → tính trọng số EWM từ mức độ phân tán
     *      c. blendAndApplyFinalWeights()→ pha trộn EWM + AHP, EMA smoothing, clamp bounds
     */
    // ─────────────────────────────────────────────────────────────────────────
    @Scheduled(fixedRateString = "${alb.weights.update-interval:5000}")
    public void computeMCDMWeights() {
        List<InstanceMetrics> instances = cache.getAllMetrics();
        int n = instances.size();

        // Cần ít nhất 2 instance để so sánh và tính entropy có ý nghĩa.
        // Với 1 instance, mọi trọng số đều tương đương → skip.
        if (n < 2) return;

        // Tính trung bình queue và CPU để phát hiện trạng thái idle.
        double totalQueue = 0.0, totalCpu = 0.0;
        for (InstanceMetrics m : instances) {
            totalQueue += m.getQueueLength();
            totalCpu   += m.getCpu();
        }
        double avgQueue = totalQueue / n;
        double avgCpu   = totalCpu / n;

        // Ngưỡng idle: avgQueue < 2 request VÀ avgCpu < 6%.
        // Khi idle, các instance đều có metric gần bằng nhau → EWM sẽ trả về
        // trọng số gần đều nhau (entropy cao = không phân biệt được), không hữu ích.
        // Giải pháp: đóng băng trọng số tại AHP defaults khi hệ thống nhàn rỗi.
        if (avgQueue < 2.0 && avgCpu < 0.06) {
            log.debug("System idle — weights frozen at AHP defaults");
            this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
            return;
        }

        // Hệ thống đang có tải — tiến hành tính EWM động.
        double[][] normalizedMatrix = buildNormalizedMatrix(instances, n);
        double[]   ewmWeights       = calculateEntropyWeights(normalizedMatrix, n);
        blendAndApplyFinalWeights(ewmWeights);
    }

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Bước 1: Chuẩn hoá ma trận metrics về dạng "nhỏ hơn = tốt hơn" → [0, 1].
     *
     * Công thức: data[i][j] = (minVal[j] + MU[j]) / (raw[i][j] + MU[j])
     *
     * Ví dụ với latency (j=0, MU=5):
     *   Instance A: 50ms  → (30 + 5) / (50 + 5) = 35/55 ≈ 0.636
     *   Instance B: 30ms  → (30 + 5) / (30 + 5) = 35/35 = 1.000  ← instance tốt nhất = 1.0
     *   Instance C: 200ms → (30 + 5) / (200 + 5) = 35/205 ≈ 0.171
     *
     * Tính chất:
     *   - Instance tốt nhất (giá trị thấp nhất) luôn cho ra 1.0
     *   - Instance tệ hơn cho ra giá trị < 1.0
     *   - MU tránh division-by-zero khi raw[i][j] = 0
     *   - Phân phối phi tuyến: instance rất tệ (200ms) bị phạt nặng hơn
     *     so với instance trung bình (50ms) → phù hợp với cảm nhận user
     *
     * @return matrix [n instances × 3 criteria], mỗi giá trị trong (0, 1]
     */
    // ─────────────────────────────────────────────────────────────────────────
    private double[][] buildNormalizedMatrix(List<InstanceMetrics> instances, int n) {
        double[][] raw    = new double[n][CRITERIA_COUNT];
        double[]   minVal = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };

        // Pass 1: thu thập raw values và tìm min của mỗi cột (tiêu chí).
        // minVal[j] = giá trị tốt nhất hiện tại của tiêu chí j trên tất cả instances.
        for (int i = 0; i < n; i++) {
            InstanceMetrics m = instances.get(i);
            raw[i][0] = m.getLatency();      // ms, càng thấp càng tốt
            raw[i][1] = m.getQueueLength();  // số request đang chờ, càng thấp càng tốt
            raw[i][2] = m.getCpu();          // [0, 1], càng thấp càng tốt
            for (int j = 0; j < CRITERIA_COUNT; j++) {
                if (raw[i][j] < minVal[j]) minVal[j] = raw[i][j];
            }
        }

        // Pass 2: chuẩn hoá theo công thức (minVal + MU) / (raw + MU).
        // numerator[j] là hằng số với mỗi cột → tính 1 lần ở ngoài vòng lặp i.
        double[][] data = new double[n][CRITERIA_COUNT];
        for (int j = 0; j < CRITERIA_COUNT; j++) {
            double numerator = minVal[j] + MU[j]; // = hằng số cho cột j
            double mu        = MU[j];
            for (int i = 0; i < n; i++) {
                data[i][j] = numerator / (raw[i][j] + mu);
            }
        }
        return data;
    }

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Bước 2: Tính trọng số Entropy (EWM — Entropy Weight Method).
     *
     * Nguyên lý: tiêu chí nào có sự phân tán (variance) lớn hơn giữa các instance
     * thì mang nhiều "thông tin" hơn để phân biệt instance tốt/xấu
     * → nên được gán trọng số cao hơn.
     *
     * Thuật toán Shannon Entropy:
     *   1. Chuẩn hoá cột j thành phân phối xác suất: p_ij = data[i][j] / Σ data[i][j]
     *   2. Tính entropy:  E_j = -k × Σ_i (p_ij × ln(p_ij))
     *      trong đó k = 1/ln(n) để chuẩn hoá về [0, 1]
     *   3. Trọng số thô:  ewm[j] = max(0, 1 - E_j)
     *      → E_j thấp (phân tán cao)  → ewm[j] cao  → tiêu chí này phân biệt tốt
     *      → E_j cao  (phân tán thấp) → ewm[j] thấp → tiêu chí này ít có ích
     *   4. Chuẩn hoá: ewmNorm[j] = ewm[j] / Σ ewm[j]
     *
     * Ví dụ:
     *   CPU của 3 instance: [0.9, 0.9, 0.8] → gần bằng nhau → entropy cao → ewm[2] thấp
     *   CPU của 3 instance: [0.1, 0.5, 0.9] → rất khác nhau  → entropy thấp → ewm[2] cao
     *
     * @param data   ma trận đã chuẩn hoá từ buildNormalizedMatrix()
     * @param n      số instance
     * @return mảng 3 trọng số EWM đã chuẩn hoá (tổng = 1.0)
     */
    // ─────────────────────────────────────────────────────────────────────────
    private double[] calculateEntropyWeights(double[][] data, int n) {
        double[] ewm    = new double[CRITERIA_COUNT];
        double   sumEwm = 0;

        // k = 1/ln(n): hệ số chuẩn hoá entropy về [0, 1].
        // ln(2) ≈ 0.693 với 2 instance, ln(3) ≈ 1.099 với 3 instance.
        double k = 1.0 / Math.log(n);

        for (int j = 0; j < CRITERIA_COUNT; j++) {
            // Tính tổng cột j để chuẩn hoá thành phân phối xác suất.
            double colSum = 0;
            for (int i = 0; i < n; i++) colSum += data[i][j];

            // invColSum = 1/colSum: tính 1 lần, dùng để nhân thay vì chia trong vòng lặp.
            double invColSum    = 1.0 / colSum;
            double sumEntropy   = 0;

            for (int i = 0; i < n; i++) {
                // p_ij: xác suất (tỉ trọng) của instance i trong tiêu chí j.
                // Nếu tất cả instance có giá trị bằng nhau → p_ij = 1/n cho mọi i
                // → entropy đạt max → tiêu chí này không phân biệt được gì.
                double p = data[i][j] * invColSum;
                // p × ln(p): phần đóng góp entropy của instance i.
                // Khi p → 0, p×ln(p) → 0 (L'Hôpital); không cần kiểm tra p=0 vì
                // data[i][j] > 0 (đảm bảo bởi buildNormalizedMatrix với MU > 0).
                sumEntropy += p * Math.log(p);
            }

            // Entropy E_j = -k × Σ p_ij × ln(p_ij).
            // sumEntropy là âm (vì ln(p) < 0 với p ∈ (0,1]) → -k × sumEntropy > 0.
            // max(0, ...) để tránh giá trị âm do floating-point rounding.
            ewm[j]   = Math.max(0.0, 1.0 - (-k * sumEntropy));
            sumEwm  += ewm[j];
        }

        // Chuẩn hoá để tổng 3 trọng số = 1.0.
        // Nếu sumEwm = 0 (tất cả entropy đều = 1, mọi tiêu chí đều vô nghĩa)
        // → fallback về trọng số đều (1/3 mỗi tiêu chí).
        double[] ewmNorm = new double[CRITERIA_COUNT];
        for (int j = 0; j < CRITERIA_COUNT; j++) {
            ewmNorm[j] = (sumEwm == 0) ? (1.0 / CRITERIA_COUNT) : (ewm[j] / sumEwm);
        }
        return ewmNorm;
    }

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Bước 3: Pha trộn EWM + AHP → EMA smoothing → clamp bounds → gán weights.
     *
     * Pipeline chi tiết:
     *
     * [A] Fusion (pha trộn EWM và AHP):
     *     fusion[j] = 0.80 × ewmNorm[j] + 0.20 × AHP_WEIGHTS[j]
     *     → Kết quả phụ thuộc 80% data thực, 20% kinh nghiệm chuyên gia.
     *     → Re-normalize fusion để tổng = 1.0 (do floating-point drift).
     *
     * [B] EMA smoothing (làm mượt theo thời gian):
     *     newAlpha = 0.08 × fusionAlpha + 0.92 × currentAlpha
     *     → Tránh trọng số nhảy đột ngột khi metrics thay đổi trong 1 chu kỳ.
     *     → Với alpha=0.08, cần ~12 chu kỳ (1 phút) để đạt 70% giá trị mới.
     *
     * [C] Re-normalize sau EMA (EMA có thể làm lệch tổng khỏi 1.0).
     *
     * [D] Clamp bounds — giới hạn trọng số trong khoảng cho phép:
     *     Upper bounds (tránh một tiêu chí thống trị hoàn toàn):
     *       γ ≤ 0.35, α ≤ 0.70, β ≤ 0.55
     *     Lower bounds (đảm bảo mọi tiêu chí đều có ảnh hưởng tối thiểu):
     *       γ ≥ 0.08, β ≥ 0.08, α ≥ 0.15
     *     Excess/deficit được tái phân bổ sang tiêu chí khác theo tỉ lệ định sẵn.
     *
     * @param ewmNorm  mảng 3 trọng số EWM từ calculateEntropyWeights()
     */
    // ─────────────────────────────────────────────────────────────────────────
    private void blendAndApplyFinalWeights(double[] ewmNorm) {
        // ── [A] Fusion: 80% EWM + 20% AHP ────────────────────────────────────
        double sumFusion = 0;
        double[] fusion  = new double[CRITERIA_COUNT];
        for (int j = 0; j < CRITERIA_COUNT; j++) {
            fusion[j]  = BLEND_FACTOR * ewmNorm[j] + ONE_MINUS_BLEND * AHP_WEIGHTS[j];
            sumFusion += fusion[j];
        }

        // ── [B] EMA smoothing lên từng trọng số ───────────────────────────────
        // invSumFusion: tính 1 lần để dùng nhân thay vì chia trong vòng lặp.
        double invSumFusion = 1.0 / sumFusion;

        // Công thức EMA: new = alpha × target + (1 - alpha) × current
        // target = fusion[j] đã re-normalize về [0,1] qua invSumFusion.
        // current = giá trị trọng số trước đó (this.weights.alpha/beta/gamma).
        double newAlpha = WEIGHT_EMA_ALPHA * (fusion[0] * invSumFusion) + ONE_MINUS_EMA * this.weights.alpha();
        double newBeta  = WEIGHT_EMA_ALPHA * (fusion[1] * invSumFusion) + ONE_MINUS_EMA * this.weights.beta();
        double newGamma = WEIGHT_EMA_ALPHA * (fusion[2] * invSumFusion) + ONE_MINUS_EMA * this.weights.gamma();

        // ── [C] Re-normalize sau EMA ──────────────────────────────────────────
        // EMA không đảm bảo tổng = 1.0 chính xác do floating-point → chuẩn hoá lại.
        double s = newAlpha + newBeta + newGamma;
        newAlpha /= s;
        newBeta  /= s;
        newGamma /= s;

        // ── [D] Clamp bounds ──────────────────────────────────────────────────

        // --- Upper bound γ ≤ 0.35 ---
        // Tránh CPU chiếm quá 35% quyết định (CPU không đủ tin cậy làm signal chính).
        // Excess từ γ phân bổ: 60% sang α, 40% sang β (ưu tiên latency hơn queue).
        if (newGamma > 0.35) {
            double e  = newGamma - 0.35;
            newGamma  = 0.35;
            newAlpha += e * 0.60;
            newBeta  += e * 0.40;
        }

        // --- Upper bound α ≤ 0.70 ---
        // Tránh latency chiếm hơn 70% (khi CPU/queue có variance cao cũng cần được lắng nghe).
        // Excess từ α phân bổ toàn bộ sang β.
        if (newAlpha > 0.70) {
            double e = newAlpha - 0.70;
            newAlpha = 0.70;
            newBeta += e;
        }

        // --- Upper bound β ≤ 0.55 ---
        // Tránh queue chiếm quá 55% (queue có thể spike tạm thời mà không phản ánh vấn đề thật).
        // Excess từ β phân bổ toàn bộ sang α.
        if (newBeta > 0.55) {
            double e = newBeta - 0.55;
            newBeta  = 0.55;
            newAlpha += e;
        }

        // --- Lower bound γ ≥ 0.08 ---
        // CPU phải có ít nhất 8% ảnh hưởng, đặc biệt quan trọng cho kịch bản
        // "hidden degradation" (CPU cao nhưng latency chưa tăng rõ ràng).
        // Deficit lấy từ: 70% từ α, 30% từ β.
        if (newGamma < 0.08) {
            double deficit = 0.08 - newGamma;
            newGamma  = 0.08;
            newAlpha -= deficit * 0.70;
            newBeta  -= deficit * 0.30;
        }

        // --- Lower bound β ≥ 0.08 ---
        // Queue phải có ít nhất 8% ảnh hưởng (phát hiện overload sớm trước khi latency tăng).
        // Deficit lấy toàn bộ từ α.
        if (newBeta < 0.08) {
            double deficit = 0.08 - newBeta;
            newBeta  = 0.08;
            newAlpha -= deficit;
        }

        // --- Lower bound α ≥ 0.15 ---
        // Latency phải có ít nhất 15% ảnh hưởng (đây là signal trực tiếp nhất với user).
        // Deficit lấy toàn bộ từ β (β đã được đảm bảo ≥ 0.08 ở trên nên vẫn dương).
        if (newAlpha < 0.15) {
            double deficit = 0.15 - newAlpha;
            newAlpha  = 0.15;
            newBeta  -= deficit;
        }

        // ── [E] Gán kết quả cuối cùng (atomic write qua record mới) ──────────
        // Tạo record mới thay vì update từng field → thread đọc weights luôn thấy
        // snapshot nhất quán (không thấy α mới nhưng β cũ).
        this.weights = new McdmWeights(newAlpha, newBeta, newGamma);

        log.debug("MCDM weights updated: α={} β={} γ={}", newAlpha, newBeta, newGamma);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getter cho từng trọng số — được dùng bởi Prometheus Gauge (registerMetrics).
    // Khai báo method thay vì lambda inline để Gauge không capture `this.weights`
    // tại thời điểm đăng ký (sẽ bị stale), mà luôn đọc qua getter → volatile safe.
    // ─────────────────────────────────────────────────────────────────────────
    public double getAlpha() { return weights.alpha(); }
    public double getBeta()  { return weights.beta();  }
    public double getGamma() { return weights.gamma(); }

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Tính điểm nền (baseScore) cho một instance, được gọi bởi ScoreCalculator.
     *
     * Công thức: baseScore = α × normLatency + β × normQueue + γ × normCpu
     *
     * - Tất cả norm* đã được chuẩn hoá về [0, 1] bởi NormalizationFunctions.
     * - baseScore ∈ [0, 1]: càng thấp = instance càng tốt.
     * - Sau đó ScoreCalculator cộng thêm PID penalty → finalScore.
     *
     * Lưu ý: đọc `weights` 1 lần duy nhất vào biến local `w` để tránh race
     * condition giữa volatile read và 3 lần getAlpha/getBeta/getGamma.
     */
    // ─────────────────────────────────────────────────────────────────────────
    public double computeBaseScore(double nL, double nQ, double nC) {
        // 1 volatile read duy nhất → snapshot nhất quán của cả 3 trọng số.
        McdmWeights w = weights;
        return (w.alpha() * nL) + (w.beta() * nQ) + (w.gamma() * nC);
    }

    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Đăng ký Prometheus Gauges để theo dõi trọng số MCDM theo thời gian thực.
     *
     * Được gọi 1 lần sau khi bean khởi tạo xong (@PostConstruct).
     * 3 gauge: alb.mcdm.weight{criterion="latency/queue/cpu"}
     *
     * Dùng Method Reference (this::getAlpha) thay vì lambda (weights.alpha())
     * để Gauge luôn đọc giá trị hiện tại qua getter, không bị stale từ closure.
     */
    // ─────────────────────────────────────────────────────────────────────────
    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("alb.mcdm.weight", this::getAlpha).tag("criterion", "latency").register(registry);
        Gauge.builder("alb.mcdm.weight", this::getBeta) .tag("criterion", "queue")  .register(registry);
        Gauge.builder("alb.mcdm.weight", this::getGamma).tag("criterion", "cpu")    .register(registry);
    }

    /**
     * Reset trọng số về AHP defaults — được gọi bởi AdminController khi POST /actuator/alb/reset.
     *
     * Sau reset, chu kỳ computeMCDMWeights() tiếp theo sẽ tính lại từ đầu.
     * EMA state bị xoá ngầm vì smoothedScores không tồn tại trong class này
     * (EMA state là chính `this.weights`, được reset về AHP ở đây).
     */
    public void resetWeights() {
        this.weights = new McdmWeights(AHP_WEIGHTS[0], AHP_WEIGHTS[1], AHP_WEIGHTS[2]);
    }
}