package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.config.AlbProperties;
import com.truongquycode.apigatewayalb.controlplane.DynamicWeightEngine;
import com.truongquycode.apigatewayalb.controlplane.SlidingWindowManager;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;
import com.truongquycode.apigatewayalb.math.NormalizationFunctions;
import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.model.PercentileSnapshot;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScoreCalculator {

	// ═══════════════════════════════════════════════════════════════════
	// HẰNG SỐ
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * Score trả về khi InstanceMetrics == null (instance không poll được metrics).
	 * Giá trị 20.0 rất cao → AdaptiveLoadBalancer gần như không bao giờ chọn
	 * instance này, tránh route traffic vào "hộp đen". So sánh: finalScore bình
	 * thường ∈ [0.05, ~1.8].
	 */
	private static final double SCORE_NULL_INSTANCE = 20.0;

	/**
	 * Giá trị fallback cho P5 toàn hệ thống (ms) khi histogram chưa đủ data (vừa
	 * reset hoặc hệ thống mới khởi động). 30ms ≈ latency tối thiểu thực tế của một
	 * Java microservice có 50ms I/O. Dùng làm cận dưới khi chuẩn hóa latency về
	 * [0,1].
	 */
	private static final double SYSTEM_P5_FALLBACK = 30.0; // ms

	/**
	 * Giá trị fallback cho P95 toàn hệ thống (ms) khi histogram chưa đủ data. 300ms
	 * ≈ giới hạn trên chấp nhận được của registration service khi bình thường. Dùng
	 * làm cận trên khi chuẩn hóa: instance có ewmaLat = 300ms → nL = 1.0 (tệ nhất).
	 */
	private static final double SYSTEM_P95_FALLBACK = 300.0; // ms

	/**
	 * Dải p5-p95 tối thiểu để normalize latency. Ở low-load bình thường, p5 và p95
	 * có thể chỉ lệch vài ms. Nếu dải quá hẹp, chênh lệch 3-5ms sẽ bị scale thành
	 * khác biệt score lớn.
	 */
	private static final double MIN_SYSTEM_LATENCY_RANGE_MS = 80.0;

	// ═══════════════════════════════════════════════════════════════════
	// DEPENDENCY INJECTION (qua @RequiredArgsConstructor của Lombok)
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * Cung cấp percentile snapshot (p5, p50, p95, qP99) từ HDR Histogram. -
	 * Per-instance: p50 làm fallback latency khi instance idle; p95 dùng khi system
	 * snapshot không khả dụng; qP99 làm ngưỡng chuẩn hóa queue. - System-wide:
	 * p5/p95 làm biên [min, max] cho Min-Max normalization latency; p75 làm
	 * setpoint cho PID controller.
	 */
	private final SlidingWindowManager windowManager;

	/**
	 * Tính baseScore = α×nL + β×nQ + γ×nC. Trọng số α/β/γ được DynamicWeightEngine
	 * cập nhật mỗi 5 giây dựa trên Entropy Weight Method (EWM) + AHP.
	 */
	private final DynamicWeightEngine weightEngine;

	/**
	 * Tính penalty dựa trên PID controller per-instance. Penalty tăng khi instance
	 * chậm hơn P75 hệ thống liên tục, giảm dần khi instance hồi phục. Khoảng [0,
	 * lambda=0.8].
	 */
	private final PIDController pidController;

	/**
	 * Làm mượt latency thô bằng Adaptive EWMA (AEWMA). τ (tau) tự động điều chỉnh:
	 * nhỏ khi latency spike (phản ứng nhanh), lớn khi ổn định (lọc nhiễu). Tránh
	 * score dao động quá mạnh vì 1 request chậm.
	 */
	private final EwmaSmoother ewmaSmoother;

	/**
	 * Tập hợp các hàm chuẩn hóa (normalize) về khoảng [0, 1]: - normalizeLatency():
	 * Min-Max theo dải [sysP5, sysP95] - normalizeQueue(): Log-scale theo qP99 -
	 * normalizeCpu(): Clamp đơn giản vào [0, 1]
	 */
	private final NormalizationFunctions norm;

	/**
	 * Đọc cấu hình EWMA (tauMin, tauMax, k) và PID (kp, ki, kd, ...) từ
	 * application.yml. Cho phép thay đổi tham số mà không cần recompile.
	 */
	private final AlbProperties props;

	// ═══════════════════════════════════════════════════════════════════
	// calculateScore() — Pipeline tính score chính
	//
	// Được gọi bởi MetricsPoller.processMetrics() mỗi 200ms cho mỗi instance.
	// Kết quả (ScoreBreakdown) được lưu vào MetricsCache.
	// AdaptiveLoadBalancer đọc từ MetricsCache khi có request cần routing.
	//
	// Pipeline tổng quát:
	// rawLatency
	// → EWMA smooth (ewmaLat)
	// → Min-Max normalize (nL ∈ [0,1]) ──┐
	// queueLength → Log normalize (nQ ∈ [0,1]) ├→ MCDM baseScore
	// cpuUsage → Clamp normalize (nC ∈ [0,1])┘ +
	// PID penalty
	// = finalScore
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * Tính điểm tổng hợp (finalScore) cho một instance tại thời điểm hiện tại.
	 *
	 * @param instanceId ID của instance (VD: "REGISTRATION-SERVICE-ALB:8081")
	 * @param current    Raw metrics vừa poll được: latency (ms), queueLength, cpu
	 *                   [0,1] Có thể null nếu poll thất bại.
	 * @return ScoreBreakdown chứa toàn bộ giá trị trung gian và finalScore.
	 *         finalScore ∈ [0.05, ~20]: càng thấp = instance càng tốt.
	 */
	public ScoreBreakdown calculateScore(String instanceId, InstanceMetrics current) {

		// Timestamp để ghi vào ScoreBreakdown.updatedAtMs — MetricsPoller dùng để
		// phát hiện score bị stale (không còn được cập nhật do instance down).
		long now = System.currentTimeMillis();

		// ── GUARD: instance không có metrics (poll thất bại hoặc chưa đăng ký) ──
		// Trả về ScoreBreakdown với tất cả norm = 1.0 (worst case) và
		// finalScore = 20.0 để ALB gần như không bao giờ route đến đây.
		// Lưu ý: MetricsPoller đã có cơ chế penalty riêng cho poll failure;
		// nhánh null này là safety net cho trường hợp ScoreCalculator bị gọi trực tiếp.
		if (current == null) {
			return new ScoreBreakdown(instanceId, 0, // ewmaLatency = 0 (không có data)
					1, // normLatency = 1.0 (worst)
					1, // normQueue = 1.0 (worst)
					1, // normCpu = 1.0 (worst)
					1, // baseScore = 1.0
					0, // pidPenalty = 0 (không tính PID khi null)
					SCORE_NULL_INSTANCE, // finalScore = 20.0
					now);
		}

		// ── BƯỚC 1: Lấy per-instance percentile snapshot ────────────────────────
		// Snapshot này đến từ HDR Histogram riêng của từng instance,
		// tích lũy từ các lần poll trước qua SlidingWindowManager.addMetrics().
		PercentileSnapshot snap = windowManager.getSnapshot(instanceId);

		// p50 (median latency của instance này): dùng làm giá trị khởi tạo cho EWMA
		// khi instance vừa cold-start (chưa có lịch sử EWMA), tránh bắt đầu từ 0ms.
		double p50 = snap.p50();

		// lRaw: latency thô để đưa vào EWMA.
		// Ưu tiên dùng giá trị vừa đo được từ MetricsPoller (current.getLatency()).
		// Nếu = 0 (instance idle, không có request nào hoàn thành trong window 200ms)
		// → fallback về p50 để EWMA không bị kéo xuống 0 một cách giả tạo.
		double lRaw = current.getLatency() > 0 ? current.getLatency() : p50;

		// ── BƯỚC 2: Adaptive EWMA smoothing ─────────────────────────────────────
		// ewmaLat: latency đã được làm mượt, đại diện cho "trạng thái hiện tại"
		// của instance tốt hơn là giá trị thô bị ảnh hưởng bởi noise/outlier.
		//
		// AEWMA tự điều chỉnh τ (tau):
		// - Khi latency đột biến lớn → τ nhỏ (= tauMin) → phản ứng nhanh
		// - Khi latency ổn định → τ lớn (= tauMax) → lọc nhiễu mạnh
		AlbProperties.Ewma ewmaCfg = props.getEwma();
		double ewmaLat = ewmaSmoother.smooth(instanceId, lRaw, ewmaCfg.getTauMin(), // τ tối thiểu (200ms) — phản ứng
																					// nhanh nhất
				ewmaCfg.getTauMax(), // τ tối đa (2000ms) — lọc nhiễu mạnh nhất
				ewmaCfg.getK(), // k=3.0 — độ nhạy với deviation
				p50 // fallback EWMA khởi tạo = p50 của instance
		);

		// ── BƯỚC 3: Lấy system-wide snapshot để làm biên chuẩn hóa ─────────────
		// System snapshot tổng hợp latency của TẤT CẢ instance → làm thước đo chung.
		// Mục đích: "instance này nhanh hay chậm SO VỚI TOÀN HỆ THỐNG?"
		// (không phải so với bản thân nó trong quá khứ).
		SlidingWindowManager.SystemSnapshot sysSs = windowManager.getSystemSnapshot();

		double sysP5 = sysSs.p5(); // Latency của 5% request nhanh nhất toàn hệ thống
		double sysP95 = sysSs.p95(); // Latency của 95% request toàn hệ thống

		// Kiểm tra tính hợp lệ của system snapshot:
		// sysP95 <= sysP5: histogram bị đảo (không thể xảy ra nếu có đủ data)
		// sysP5 < 1.0: histogram chưa có data (vừa reset, global histogram trống)
		// → Trong cả hai trường hợp, dùng fallback cứng để tránh invRange = Infinity
		// hoặc chia cho 0 ở bước chuẩn hóa.
		if (sysP95 <= sysP5 || sysP5 < 1.0) {
			sysP5 = SYSTEM_P5_FALLBACK; // 30ms
			sysP95 = SYSTEM_P95_FALLBACK; // 300ms
		}

		// Ở tải thấp bình thường, dải p5-p95 thường quá hẹp.
		// Mở rộng dải tối thiểu để tránh phóng đại nhiễu nhỏ thành khác biệt score lớn.
		double latencyRange = sysP95 - sysP5;
		if (latencyRange < MIN_SYSTEM_LATENCY_RANGE_MS) {
			double mid = (sysP5 + sysP95) * 0.5;
			sysP5 = Math.max(1.0, mid - (MIN_SYSTEM_LATENCY_RANGE_MS * 0.5));
			sysP95 = sysP5 + MIN_SYSTEM_LATENCY_RANGE_MS;
		}

		// invRange: nghịch đảo của dải [sysP5, sysP95], tính sẵn 1 lần để tái dùng.
		// Thay vì mỗi normalize phải chia, nhân với invRange nhanh hơn ~5 CPU cycle.
		// Dùng trong: normalizeLatency(ewmaLat), normalizeLatency(p75) ở bước PID.
		double invRange = 1.0 / (sysP95 - sysP5);

		// ── BƯỚC 4: Chuẩn hóa 3 tiêu chí về [0, 1] ─────────────────────────────

		// nL (normalized Latency): [0 = nhanh như P5 hệ thống, 1 = chậm như P95]
		// Công thức: (ewmaLat - sysP5) / (sysP95 - sysP5), clamp vào [0,1]
		double nL = norm.normalizeLatency(ewmaLat, sysP5, invRange);

		// nQ (normalized Queue): [0 = không có queue, ~1 = queue đầy tới P99]
		// Dùng log-scale vì phân phối queue thường right-skewed:
		// nhạy với queue nhỏ (1→5) nhưng không bị "sốc" khi queue spike lớn.
		// snap.qP99(): ngưỡng queue P99 lịch sử của chính instance này.
		double nQ = norm.normalizeQueue(current.getQueueLength(), snap.qP99());

		// nC (normalized CPU): đơn giản là clamp cpu vào [0, 1].
		// process.cpu.usage từ Micrometer đã ở dạng [0.0, 1.0] nhưng
		// đôi khi JVM burst trả về > 1.0 → cần clamp.
		// Nếu giá trị NaN hoặc âm (JVM chưa measure được) → fallback 0.5.
		double nC = norm.normalizeCpu(current.getCpu());

		// ── BƯỚC 5: Tính MCDM baseScore ─────────────────────────────────────────
		// baseScore = α×nL + β×nQ + γ×nC
		// α, β, γ: trọng số động từ DynamicWeightEngine (cập nhật mỗi 5 giây).
		// Mặc định AHP: α=0.648, β=0.230, γ=0.122
		// Khi CPU bão tải: γ tăng lên (tối đa 0.35), α giảm xuống.
		// baseScore ∈ [0, 1]: càng thấp = instance càng tốt trên cả 3 tiêu chí.
		double baseScore = weightEngine.computeBaseScore(nL, nQ, nC);

		// ── BƯỚC 6: Tính PID penalty ─────────────────────────────────────────────
		// Setpoint của PID = P75 toàn hệ thống, cũng được chuẩn hóa về [0,1]
		// (cùng thang đo với nL để PID so sánh apples-to-apples).
		//
		// Ý nghĩa: "instance này đang chậm hơn 75% hệ thống bao nhiêu?"
		// Nếu nL > normalizedP75 → instance chậm hơn median → PID tích lũy penalty.
		// Nếu nL < normalizedP75 → instance nhanh hơn → penalty = 0 (không thưởng).
		double normalizedP75 = norm.normalizeLatency(sysSs.p75(), sysP5, invRange);

		// penalty ∈ [0, lambda=0.8]:
		// - Phản ứng nhanh khi instance mới bắt đầu chậm (thành phần P)
		// - Tích lũy theo thời gian nếu chậm kéo dài (thành phần I)
		// - Hãm sớm khi latency đang có xu hướng tăng (thành phần D)
		double penalty = pidController.calculatePenalty(instanceId, nL, normalizedP75, props.getPid());

		// ── BƯỚC 7: Tổng hợp finalScore ──────────────────────────────────────────
		// finalScore = baseScore + pidPenalty
		// Khoảng thực tế: [~0.05, ~1.8]
		// ~0.05 → instance rất tốt (latency thấp, queue trống, CPU thấp, không bị PID
		// phạt)
		// ~1.8 → instance rất tệ (latency P95, queue đầy, CPU 100%, PID penalty tối đa)
		//
		// MetricsPoller sẽ áp thêm 1 lớp EMA lên finalScore này trước khi lưu vào
		// MetricsCache,
		// để tránh score nhảy đột ngột gây flapping routing.
		double finalScore = baseScore + penalty;

		// Trả về đầy đủ các giá trị trung gian để:
		// - Prometheus Gauges hiển thị từng thành phần trên Grafana
		// - Debug khi instance bị tránh không rõ lý do (xem nL/nQ/nC/penalty riêng)
		return new ScoreBreakdown(instanceId, ewmaLat, // EWMA latency (ms) — giá trị thực sau smoothing
				nL, // normLatency ∈ [0,1]
				nQ, // normQueue ∈ [0,1]
				nC, // normCpu ∈ [0,1]
				baseScore, // α×nL + β×nQ + γ×nC ∈ [0,1]
				penalty, // PID penalty ∈ [0, 0.8]
				finalScore, // finalScore = baseScore + penalty (trước EMA của MetricsPoller)
				now // timestamp tính score, dùng để phát hiện score stale
		);
	}
}