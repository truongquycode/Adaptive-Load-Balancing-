package com.truongquycode.apigatewayalb.math;

import org.springframework.stereotype.Component;

@Component
public class NormalizationFunctions {

    /**
     * Chuẩn hóa latency về [0,1] theo Min-Max scaling trên dải [P5, P95].
     * P5 và P95 lấy từ SlidingWindowManager của từng instance.
     *
     * 0 → instance rất nhanh (latency ≈ P5)
     * 1 → instance rất chậm (latency ≈ P95)
     */
    public double normalizeLatency(double ewma, double p5, double p95) {
        if (Double.isNaN(ewma) || ewma < 0) return 0.5;
        if (p95 <= p5) return 0.5;
        return Math.max(0.0, Math.min(1.0, (ewma - p5) / (p95 - p5)));
    }

    /**
     * Chuẩn hóa queue length về [0,1] theo Log scaling.
     * Log scale phù hợp vì phân phối queue thường right-skewed:
     * - Nhạy với queue nhỏ (vùng quan trọng)
     * - Tránh score tăng quá mạnh khi queue đột biến lớn
     *
     * qMax >= 10 để tránh mẫu số log(1) = 0 khi traffic thấp.
     */
    public double normalizeQueue(double qRaw, double qP99) {
        if (qRaw < 0) qRaw = 0;
        double qMax = Math.max(qP99, 10.0);
        return Math.min(1.0, Math.log(1.0 + qRaw) / Math.log(1.0 + qMax));
    }

    /**
     * Chuẩn hóa CPU usage về [0,1].
     * process.cpu.usage từ Micrometer đã trong [0,1],
     * nhưng một số JVM version trả về > 1.0 trong burst ngắn.
     */
    public double normalizeCpu(double cpuRaw) {
        if (Double.isNaN(cpuRaw) || cpuRaw < 0) return 0.5;
        return Math.max(0.0, Math.min(1.0, cpuRaw));
    }
}