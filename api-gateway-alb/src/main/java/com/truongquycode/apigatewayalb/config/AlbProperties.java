package com.truongquycode.apigatewayalb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import com.truongquycode.apigatewayalb.model.PidConfig;
import lombok.Data;

/**
 * Bind toàn bộ config có prefix "alb" trong application.yml thành Java object.
 *
 * Mục đích: thay vì dùng @Value rải rác, tập trung config vào một chỗ, có
 * type-safety và dễ inject qua constructor.
 *
 * Các default value trong file này = fallback khi không có application.yml.
 * YAML override Java default
 *
 * Được inject vào: 
 * ScoreCalculator → ewma (tauMin, tauMax, k), pid 
 * GatewayRoutingConfig → strategy 
 * MetricsPoller → polling.interval (qua @Scheduled)
 * DynamicWeightEngine → weights.updateInterval (qua @Scheduled)
 * 
 * 
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "alb")
public class AlbProperties {

	/**
	 * Thuật toán load balancing: adaptive | round-robin | random |
	 * least-connections
	 */
	private String strategy = "adaptive";

	private Polling polling = new Polling();
	private Ewma ewma = new Ewma();
	private PidConfig pid = new PidConfig();
	private Weights weights = new Weights();

	@Data
	public static class Polling {
		private long interval = 200; // ms
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Tham số cho Adaptive EWMA (AEWMA) trong EwmaSmoother.
	//
	// τ (tau) điều chỉnh tốc độ phản ứng của EWMA:
	// τ nhỏ → phản ứng nhanh với biến động, dễ bị nhiễu
	// τ lớn → mượt hơn, phản ứng chậm hơn
	//
	// AEWMA tự động điều chỉnh τ theo độ lệch tức thời (deviation):
	// τ(t) = tauMin + (tauMax - tauMin) × exp(-k × δ(t))
	// δ = 0 (ổn định) → τ = tauMax (smooth nhiều)
	// δ → ∞ (spike) → τ → tauMin (phản ứng nhanh)
	//
	// k: hệ số độ dốc của hàm mũ
	// k = 1.0 → cần δ ≈ 100% deviation để τ giảm về ~tauMin
	// k = 2.0 → cần δ ≈ 50% deviation
	// k = 4.0 → cần δ ≈ 25% deviation (rất nhạy, phù hợp workload CPU-intensive)
	// ─────────────────────────────────────────────────────────────────────────
	@Data
	public static class Ewma {
		private double tauMin = 150.0; // ms — τ tối thiểu khi hệ thống biến động mạnh
		private double tauMax = 2000.0; // ms — τ tối đa khi hệ thống ổn định
		private double k = 4.0; // hệ số độ nhạy với deviation
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Tần suất DynamicWeightEngine tính lại trọng số MCDM (α, β, γ).
	// 5000ms: đủ để EWM thu thập đủ sample sau mỗi burst trước khi re-weight.
	// ─────────────────────────────────────────────────────────────────────────
	@Data
	public static class Weights {
		private long updateInterval = 5000; // ms
	}
}