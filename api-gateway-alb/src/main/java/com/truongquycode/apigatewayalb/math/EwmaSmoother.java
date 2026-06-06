package com.truongquycode.apigatewayalb.math;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/**
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║          AEWMA — Adaptive Exponentially Weighted Moving Average             ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *
 * MỤC ĐÍCH
 * ─────────
 * Làm mượt latency thô (đo được mỗi 200ms) để:
 *   - Lọc nhiễu: 1 request chậm bất thường không làm score tăng vọt ngay lập tức
 *   - Phản ứng nhanh: khi server thực sự bị quá tải, thuật toán vẫn phát hiện được
 *
 * VẤN ĐỀ VỚI EWMA THÔNG THƯỜNG (τ cố định)
 * ─────────────────────────────────────────
 *   τ nhỏ  → phản ứng nhanh, nhưng 1 request chậm đơn lẻ cũng làm score dao động mạnh
 *   τ lớn  → ổn định, nhưng phát hiện server degradation chậm → route traffic sai trong vài giây
 *
 * GIẢI PHÁP: τ TỰ ĐIỀU CHỈNH THEO DEVIATION
 * ────────────────────────────────────────────
 * Deviation = mức độ latency mới lệch so với EWMA hiện tại (tính theo tỉ lệ).
 *
 *   deviation cao (spike lớn, server đang có vấn đề) → τ thu nhỏ về tauMin → phản ứng nhanh
 *   deviation thấp (ổn định, chỉ là nhiễu nhỏ)       → τ giữ ở tauMax   → lọc nhiễu
 *
 * Công thức điều chỉnh τ:
 *   τ(t) = tauMin + (tauMax - tauMin) × e^(−k × deviation)
 *
 *   deviation = 0    → e^0  = 1  → τ = tauMax  (ổn định, lọc nhiễu)
 *   deviation → ∞   → e^−∞ = 0  → τ = tauMin  (spike nặng, phản ứng nhanh)
 *   k = 3.0: cần deviation ≈ 33% để τ giảm xuống còn ~50% tauMax
 *
 * SAU KHI CÓ τ, TÍNH EWMA
 * ─────────────────────────
 *   θ = 1 − e^(−dt/τ)           ← hệ số trọng số, phụ thuộc cả thời gian lẫn τ
 *   smoothed = θ × raw + (1−θ) × prev
 *
 *   θ nhỏ → giữ nhiều lịch sử  → kết quả mượt hơn
 *   θ lớn → nặng vào raw mới   → phản ứng nhanh hơn
 */
@Component
@Slf4j
public class EwmaSmoother {

    /**
     * Bộ nhớ EWMA của từng instance backend.
     *
     * Key   = instanceId  (VD: "REGISTRATION-SERVICE-ALB:8081")
     * Value = EwmaState   (giá trị EWMA hiện tại + timestamp lần tính trước)
     *
     * Caffeine tự động xóa entry sau 5 phút không được đọc/ghi.
     * → Tự dọn instance đã down mà không cần cleanup thủ công.
     */
    private final Cache<String, EwmaState> states = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Làm mượt latency thô của một instance bằng AEWMA. Được gọi bởi ScoreCalculator
     * mỗi khi MetricsPoller poll xong metrics (mỗi ~200ms).
     *
     * @param instanceId   ID của instance cần làm mượt
     * @param rawLatency   Latency đo được trong lần poll này (ms) — có thể nhiễu
     * @param tauMin       τ tối thiểu (ms): giới hạn dưới khi spike mạnh (VD: 200ms)
     * @param tauMax       τ tối đa (ms):   giới hạn trên khi ổn định     (VD: 2000ms)
     * @param k            Hệ số nhạy với deviation: k lớn → τ giảm nhanh hơn khi lệch
     * @param fallbackP50  Giá trị EWMA khởi tạo khi chưa có state (dùng P50 lịch sử để tránh cold start từ 0)
     * @return Latency đã được làm mượt (ms) — đưa vào ScoreCalculator để tính score
     */
    public double smooth(String instanceId, double rawLatency, double tauMin, double tauMax, double k,
            double fallbackP50) {

        long now = System.currentTimeMillis();

        // Khoảng τ có thể dao động: từ tauMin đến tauMax.
        // τ thực tế sẽ nằm trong [tauMin, tauMin + tauRange] = [tauMin, tauMax]
        double tauRange = tauMax - tauMin;

        // dtCap: giới hạn trên của dt giữa 2 lần tính EWMA.
        //
        // Nếu không giới hạn: instance offline vài phút rồi quay lại → dt = vài triệu ms
        // → ratio = dt/τ rất lớn → θ ≈ 1.0 → smoothed = rawLatency (quên hết lịch sử, reset đột ngột)
        //
        // Đặt dtCap = 3 × tauMax (VD: 3 × 2000ms = 6 giây):
        // Dù instance offline bao lâu, dt tối đa cũng chỉ = 6 giây → không bị reset EWMA hoàn toàn
        long dtCap = (long) (3.0 * tauMax);

        // states.asMap().compute() đảm bảo đọc-tính-ghi trên cùng 1 key là atomic (thread-safe).
        // Nhiều request đến cùng lúc vẫn không race condition khi cập nhật state của cùng instance.
        return states.asMap().compute(instanceId, (id, state) -> {

            // ── COLD START: lần đầu gặp instance này ─────────────────────────────
            // Khởi tạo EWMA bằng fallbackP50 (median latency lịch sử từ HDR Histogram).
            // Không dùng 0 vì EWMA sẽ mất vài giây "leo" từ 0 lên giá trị thực → score sai trong warmup.
            if (state == null)
                return new EwmaState(fallbackP50, now);

            // ── TÍNH dt (milliseconds) ────────────────────────────────────────────
            // Clamp vào [1ms, dtCap]:
            //   Min 1ms: tránh dt=0 → ratio=0 → θ=0 → EWMA không bao giờ thay đổi
            //   Max dtCap: tránh dt quá lớn reset EWMA khi instance quay lại sau offline
            long dtMs = Math.min(dtCap, Math.max(1L, now - state.lastTimestamp));

            // ── TÍNH DEVIATION (độ lệch tương đối) ───────────────────────────────
            // deviation = |rawLatency − ewmaPrev| / ewmaPrev
            //
            // Ý nghĩa: rawLatency đang lệch bao nhiêu % so với giá trị EWMA hiện tại.
            //   VD: ewma=100ms, raw=160ms  → deviation = 60/100 = 0.60 → spike 60%, τ sẽ giảm
            //   VD: ewma=100ms, raw=105ms  → deviation = 5/100  = 0.05 → ổn định, τ giữ gần tauMax
            //
            // Math.max(state.value, 1.0): tránh chia cho 0 nếu ewma rất gần 0
            double deviation = Math.abs(rawLatency - state.value) / Math.max(state.value, 1.0);

            // ── TÍNH τ THÍCH NGHI (adaptiveTau) ──────────────────────────────────
            // Công thức: τ = tauMin + tauRange × e^(−k × deviation)
            //
            //   kd = k × deviation
            //   kd = 0   (deviation=0, hoàn toàn ổn định) → e^0 = 1  → τ = tauMax
            //   kd = 3   (deviation=1.0, lệch 100%)        → e^−3≈0.05 → τ ≈ tauMin + 5%×tauRange
            //   kd ≥ 6   (spike rất lớn, e^−6≈0.0025≈0)  → τ = tauMin  [shortcut: bỏ qua Math.exp]
            //
            // Với k=3.0: chỉ cần deviation = 33% là τ đã giảm về gần tauMin
            double kd = k * deviation;
            double adaptiveTau = (kd >= 6.0) ? tauMin : tauMin + tauRange * Math.exp(-kd);

            // ── TÍNH θ (theta) — hệ số trọng số của EWMA ─────────────────────────
            // Được suy ra từ mô hình RC low-pass filter: θ = 1 − e^(−dt/τ)
            //
            // Ý nghĩa:
            //   ratio = dt/τ nhỏ (poll nhanh & τ lớn) → θ nhỏ → EWMA dịch chuyển chậm (mượt)
            //   ratio = dt/τ lớn (poll chậm & τ nhỏ)  → θ lớn → EWMA nặng vào raw mới (nhanh)
            //
            //   VD: dt=200ms, τ=2000ms → ratio=0.1 → θ = 1−e^−0.1 ≈ 0.095 (95% giữ lịch sử)
            //   VD: dt=200ms, τ=200ms  → ratio=1.0 → θ = 1−e^−1.0 ≈ 0.632 (63% trọng vào raw)
            //
            // Shortcut khi ratio ≥ 10: e^−10 ≈ 0.00005 ≈ 0 → θ = 1.0 (smoothed = raw, bỏ lịch sử)
            double ratio = (double) dtMs / adaptiveTau;
            double theta = (ratio >= 10.0) ? 1.0 : (1.0 - Math.exp(-ratio));

            // ── TÍNH GIÁ TRỊ EWMA MỚI ────────────────────────────────────────────
            // smoothed = θ × rawLatency + (1−θ) × ewmaPrev
            //
            // Đây là công thức EWMA tiêu chuẩn, θ đóng vai trò như "alpha" thông thường.
            //   θ cao (spike, τ nhỏ, dt lớn)  → rawLatency được ưu tiên  → phát hiện nhanh
            //   θ thấp (ổn định, τ lớn, dt nhỏ) → ewmaPrev được ưu tiên → chống nhiễu tốt
            double smoothed = (theta * rawLatency) + ((1.0 - theta) * state.value);

            // Lưu lại để dùng cho lần tính tiếp theo
            state.value = smoothed;
            state.lastTimestamp = now;
            return state;

        }).value; // Lấy trường .value từ EwmaState để trả về double
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Trạng thái EWMA nội bộ của một instance.
     *
     * value         — giá trị EWMA hiện tại (ms).
     *                 Đóng vai trò "lịch sử" (ewmaPrev) trong công thức tính lần sau.
     *
     * lastTimestamp — thời điểm (epoch ms) tính EWMA lần trước.
     *                 Dùng để tính dt = now − lastTimestamp.
     */
    private static class EwmaState {
        double value;        // Giá trị EWMA hiện tại (ms)
        long lastTimestamp;  // Epoch ms của lần tính trước

        EwmaState(double v, long t) {
            this.value = v;
            this.lastTimestamp = t;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Xóa toàn bộ EWMA state của mọi instance.
     *
     * Khi nào gọi: AdminController gọi trước mỗi lần chạy benchmark mới
     * (POST /actuator/alb/reset) để benchmark không bị ảnh hưởng bởi EWMA state cũ.
     *
     * Sau reset: lần smooth() tiếp theo sẽ cold-start với fallbackP50.
     */
    public void resetAllStates() {
        states.invalidateAll();
        log.info("EWMA states cleared — cold start on next poll");
    }
}