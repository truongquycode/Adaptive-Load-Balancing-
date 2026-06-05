package com.truongquycode.apigatewayalb.math;

import org.springframework.stereotype.Component;

@Component
public class NormalizationFunctions {
	
	/**
	 * Chuẩn hóa một giá trị về [0,1] theo Min-Max scaling trên dải [p5, p95].
	 *
	 * Nhận invRange = 1.0 / (p95 - p5) đã được tính sẵn ở caller,
	 *
	 * 0 → value ≈ p5 (nhanh / tốt)
	 * 1 → value ≈ p95 (chậm / tệ)
	 */
	public double normalizeLatency(double value, double p5, double invRange) {
	    return Math.max(0.0, Math.min(1.0, (value - p5) * invRange));
	}
	
	/**
	 * Chuẩn hóa queue length về [0,1] theo Log scaling. Log scale phù hợp vì phân
	 * phối queue thường right-skewed: - Nhạy với queue nhỏ (vùng quan trọng) -
	 * Tránh score tăng quá mạnh khi queue đột biến lớn
	 */
	public double normalizeQueue(double qRaw, double qP99) {
		// Short-circuit: không có queue → score = 0, bỏ qua toàn bộ tính toán
		if (qRaw <= 0)
			return 0.0;

		double qMax = Math.max(qP99, 40.0);

		// Math.log1p(x) chính xác hơn Math.log(1.0 + x) khi x nhỏ (queue thấp 1-5)
		// invLogDenom: nhân thay vì chia (~5x nhanh hơn về CPU cycle)
		double invLogDenom = 1.0 / Math.log1p(qMax);
		double logScore = Math.log1p(qRaw) * invLogDenom;

		double linearScore = Math.min(1.0, qRaw / (qMax * 2.0));

		return Math.min(1.0, 0.6 * logScore + 0.4 * linearScore);
	}

	/**
	 * Chuẩn hóa CPU usage về [0,1]. process.cpu.usage từ Micrometer đã trong [0,1],
	 * nhưng một số JVM version trả về > 1.0 trong burst ngắn.
	 */
	public double normalizeCpu(double cpuRaw) {
		if (Double.isNaN(cpuRaw) || cpuRaw < 0)
			return 0.5;
		return Math.min(1.0, cpuRaw);
	}
}