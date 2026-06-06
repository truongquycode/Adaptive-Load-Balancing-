package com.truongquycode.apigatewayalb.dataplane;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.truongquycode.apigatewayalb.model.PidConfig;
import com.truongquycode.apigatewayalb.model.PidState;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PIDController {
	
	// Cache lưu trạng thái PID riêng cho từng instance (theo instanceId).
	// Caffeine tự xóa entry sau 5 phút không được truy cập → tự dọn instance đã down.
	private final Cache<String, PidState> states = Caffeine.newBuilder().expireAfterAccess(5, TimeUnit.MINUTES).build();

	// Tính trước ln(0.97) để dùng trong công thức decay integral: e^(ln(0.97) * dt)
	// = 0.97^dt → mỗi giây integral giảm ~3% khi error gần 0 (tránh windup ở vùng ổn định).
	private static final double LN_0_97 = Math.log(0.97);

	/**
	 * Tính penalty cho một instance dựa trên PID controller.
	 *
	 * @param instanceId  ID của instance backend
	 * @param rawLat      normLatency ∈ [0,1] (từ ScoreCalculator, đã chuẩn hóa theo sysP5-sysP95)
	 * @param setpoint    normalizedP75 — ngưỡng "chấp nhận được"; instance vượt ngưỡng này bị phạt
	 * @param cfg         hệ số PID từ application.yml (kp, ki, kd, lambda, kappa, ...)
	 * @return penalty ∈ [0, lambda]; cộng vào baseScore trong ScoreBreakdown → score càng cao càng kém
	 */
	public double calculatePenalty(String instanceId, double rawLat, double setpoint, PidConfig cfg) {
		long now = System.currentTimeMillis();

		// Unpack config một lần vào local final để tránh getter overhead trong lambda bên dưới.
		final double kp = cfg.getKp();   // Hệ số P: phản ứng tức thì theo error hiện tại
		final double ki = cfg.getKi();   // Hệ số I: tích lũy error theo thời gian (phạt instance chậm mãn tính)
		final double kd = cfg.getKd();   // Hệ số D: phản ứng với TỐC ĐỘ thay đổi của error
		final double tauD = cfg.getTauD(); // Hằng số thời gian (giây) của low-pass filter cho D
		final double minI = cfg.getMinI(); // Giới hạn dưới của integral (tránh windup âm)
		final double maxI = cfg.getMaxI(); // Giới hạn trên của integral (tránh windup dương)
		final double lambda = cfg.getLambda(); // Biên độ tối đa của penalty output
		final double kappa  = cfg.getKappa(); // Độ dốc hàm tanh: kappa lớn → penalty bão hòa nhanh hơn

		PidState finalState = states.asMap().compute(instanceId, (k, state) -> {

			if (state == null) {
			    // Khởi tạo lần đầu: giả sử lần poll trước cách đây 200ms (= polling interval)
			    // để actualDt hợp lý ngay từ đầu, không bị chia cho 0.
			    state = new PidState();
			    state.setLastTimestamp(now - 200L);
			    state.setLastRawLat(rawLat); // dùng rawLat hiện tại làm baseline → rawD = 0 ở bước đầu
			}

			final long prevTimestamp = state.getLastTimestamp();
			final double prevOutput = state.getLastOutput();
			final double prevIntegral = state.getIntegral();
			final double prevRawLat = state.getLastRawLat();
			final double prevFilteredD = state.getLastFilteredD();

			// Δt (giây) kể từ lần tính penalty trước. Clamp [0.001s, 5s]:
			// - Min 0.001: tránh chia cho 0 trong D và tránh I tích lũy vô hạn khi dt → 0
			// - Max 5.0: nếu instance offline lâu rồi quay lại, không cho I nhảy đột biến
			double actualDt = Math.min(5.0, Math.max(0.001, (now - prevTimestamp) / 1000.0));

			// error > 0: instance chậm hơn setpoint (P75 hệ thống) → cần phạt
			// error < 0: instance nhanh hơn P75 → penalty âm (sẽ bị clamp ở bước cuối: max(0, u))
			double error = rawLat - setpoint;

			// ----- P -----
			// P: phản ứng tức thì.
			// Khi instance đột ngột chậm → p tăng ngay lập tức trong cùng poll cycle.
			double p = kp * error;

			// ----- I với Conditional Anti-Windup -----
			// Conditional Anti-Windup: dừng tích lũy I khi output đã bão hòa VÀ
			// error đang đẩy output tiếp tục theo hướng bão hòa đó.
			// Mục đích: tránh integral "chạy ngầm" khi không còn tác dụng điều chỉnh.
			boolean isSaturated = Math.abs(prevOutput) >= 2.0;  // output đã đạt biên
			boolean sameSign    = (error * prevOutput) > 0.0;   // error cùng chiều → tệ thêm không có ích

			double integral = prevIntegral;
			if (!(isSaturated && sameSign)) {
				double newI = prevIntegral + (error * actualDt); // Euler forward integration

				// Decay khi error nhỏ (< 0.1): tức là instance gần bằng setpoint.
				// Mục đích: tránh integral tích lũy lớn rồi "trôi" sang giai đoạn instance đã hồi phục.
				// 0.97^dt/s ≈ giảm 3%/giây → integral về 0 chậm nhưng chắc.
				if (Math.abs(error) < 0.1) {
				    newI *= Math.exp(LN_0_97 * actualDt);
				}

				// Clamp vào [minI, maxI] = [-0.8, 2.5]: giới hạn ảnh hưởng tối đa của I
				integral = newI < minI ? minI : (newI > maxI ? maxI : newI);
				state.setIntegral(integral);
			}

			double i = ki * integral;

			// ----- D với Low-Pass Filter -----
			
			// rawD: tốc độ thay đổi latency (Δlatency/Δt).
			// Dương → latency đang tăng (xấu), âm → latency đang giảm (tốt).
			double rawD = (rawLat - prevRawLat) / actualDt;

			// Low-pass filter bậc 1 cho D: loại bỏ nhiễu cao tần (spike latency ngắn).
			// expTerm = e^(-dt/tauD): hệ số "nhớ" của filter; tauD=2s → filter rất mượt.
			// filteredD = (1 - e^(-dt/τ)) × rawD + e^(-dt/τ) × prevFilteredD
			double expTerm = Math.exp(-actualDt / tauD);
			double filteredD = ((1.0 - expTerm) * rawD) + (expTerm * prevFilteredD);
			double d = kd * filteredD;

			// u = tổng P + I + D (chưa squash).
			// Dương → instance cần bị phạt; âm → instance đang tốt (sẽ bị clamp về 0 ở bước cuối).
			double u = p + i + d;

			// Cập nhật state cho lần poll tiếp theo
			state.setLastRawLat(rawLat);      // baseline mới cho D
			state.setLastFilteredD(filteredD);// tiếp tục filter D
			state.setLastOutput(u);           // dùng để kiểm tra saturation lần sau
			state.setLastTimestamp(now);

			return state;
		});
		
		// max(0, u): chỉ phạt khi PID output dương (instance chậm hơn setpoint).
		//      Instance nhanh hơn P75 không được "thưởng" bằng PID → MCDM baseScore đã xử lý.
		//tanh(kappa × u): squash về [0, 1) — tránh penalty vô hạn khi u rất lớn.
		//lambda × ...: scale về [0, lambda] = [0, 0.8] → pidPenalty tối đa 0.8
		//         (cộng vào baseScore ∈ [0,1], finalScore tối đa ~1.8).
		return lambda * Math.tanh(kappa * Math.max(0.0, finalState.getLastOutput()));
	}
	
	/**
	 * Xóa toàn bộ PID state (integral, derivative, output) của tất cả instance.
	 * Được gọi bởi AdminController trước mỗi benchmark để tránh integral cũ
	 * từ lần chạy trước gây penalty sai cho lần chạy mới.
	 */
	public void resetAllStates() {
		states.invalidateAll();
		log.info("PID states cleared — all instance integrals reset to 0");
	}
}