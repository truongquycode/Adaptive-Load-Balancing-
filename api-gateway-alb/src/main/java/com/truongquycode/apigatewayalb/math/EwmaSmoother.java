package com.truongquycode.apigatewayalb.math;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class EwmaSmoother {

	private final Cache<String, EwmaState> states = Caffeine.newBuilder().expireAfterAccess(5, TimeUnit.MINUTES)
			.build();

	/**
	 * Adaptive EWMA (AEWMA): τ điều chỉnh động theo độ lệch tức thời.
	 *
	 * @param rawLatency  Latency đo được ở chu kỳ hiện tại
	 * @param tauMin      τ tối thiểu khi hệ thống biến động mạnh (ms)
	 * @param tauMax      τ tối đa khi hệ thống ổn định (ms)
	 * @param k           Hệ số độ nhạy: k lớn → τ giảm nhanh hơn khi có biến động
	 * @param fallbackP50 Giá trị khởi tạo khi instance mới (cold start)
	 */
	public double smooth(String instanceId, double rawLatency, double tauMin, double tauMax, double k,
			double fallbackP50) {
		long now = System.currentTimeMillis();

		return states.asMap().compute(instanceId, (id, state) -> {

			// Cold start: khởi tạo bằng p50 hệ thống
			if (state == null)
				return new EwmaState(fallbackP50, now, tauMax);

			// Giới hạn dtMs: tránh θ → 1 khi restart hoặc long pause
			long dtMs = Math.min((long) (3.0 * tauMax), Math.max(1L, now - state.lastTimestamp));

			// ── ADAPTIVE τ ──────────────────────────────────────────────
			// Tính độ lệch tương đối giữa giá trị mới và EWMA trước đó
			double deviation = Math.abs(rawLatency - state.value) / Math.max(state.value, 1.0);

			// τ(t) = τ_min + (τ_max - τ_min) × exp(-k × δ(t))
			// δ = 0 (ổn định) → τ = τ_max (smooth nhiều)
			// δ = 1 (100% lệch) → τ ≈ τ_min + (τ_max-τ_min)/e ≈ 0.63τ_min + 0.37τ_max
			// δ → ∞ → τ → τ_min (phản ứng nhanh)
			double adaptiveTau = tauMin + (tauMax - tauMin) * Math.exp(-k * deviation);
			// ────────────────────────────────────────────────────────────

			// θ = 1 - exp(-Δt / τ_adaptive)
			double theta = 1.0 - Math.exp(-(double) dtMs / adaptiveTau);

			// L_ewma(t) = θ × L_raw(t) + (1-θ) × L_ewma(t-1)
			double smoothed = (theta * rawLatency) + ((1.0 - theta) * state.value);

			return new EwmaState(smoothed, now, adaptiveTau);

		}).value;
	}

	private static class EwmaState {
		double value;
		long lastTimestamp;
		double lastTau; // Lưu để expose Prometheus nếu cần quan sát

		EwmaState(double v, long t, double tau) {
			this.value = v;
			this.lastTimestamp = t;
			this.lastTau = tau;
		}
	}

	public void resetAllStates() {
		states.invalidateAll();
		log.info("EWMA states cleared — cold start on next poll");
	}
}