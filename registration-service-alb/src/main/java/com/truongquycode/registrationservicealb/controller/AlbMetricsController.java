package com.truongquycode.registrationservicealb.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AlbMetricsController {

	private final MeterRegistry registry;

	@GetMapping("/alb-metrics")
	public Map<String, Double> getMetrics() {
		double cpu = getMetricSafely(() -> registry.get("process.cpu.usage").gauge().value());
		double queue = getMetricSafely(() -> registry.get("http.server.requests.inflight").gauge().value());

		double count = 0.0;
		double totalTime = 0.0;

		// Duyệt tất cả URI đã đăng ký trong MeterRegistry
		// Không hardcode URI cụ thể → tự động tương thích với mọi endpoint
		try {
			for (io.micrometer.core.instrument.Meter meter : registry.getMeters()) {
				if (!meter.getId().getName().equals("http.server.requests"))
					continue;
				String method = meter.getId().getTag("method");
				String status = meter.getId().getTag("status");
				if (!"GET".equals(method))
					continue;
				if (status != null && status.startsWith("4"))
					continue; // bỏ 4xx
				if (meter instanceof Timer t) {
					count += t.count();
					totalTime += t.totalTime(TimeUnit.SECONDS);
				}
			}
		} catch (Exception ignored) {
		}

		return Map.of("cpu", cpu, "count", count, "totalTime", totalTime, "queue", queue);
	}

	// Hàm helper chuyên xử lý lỗi lấy metric
	private double getMetricSafely(Supplier<Double> metricSupplier) {
		try {
			return metricSupplier.get();
		} catch (Exception ignored) {
			return 0.0;
		}
	}
}