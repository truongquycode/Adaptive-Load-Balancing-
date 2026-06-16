package com.truongquycode.registrationservicealb.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.truongquycode.registrationservicealb.metrics.ContainerResourceDetector;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AlbMetricsController {

    private final MeterRegistry registry;
    private final ContainerResourceDetector resourceDetector;

    @GetMapping("/alb-metrics")
    public Map<String, Double> getMetrics() {
        double capacityWeight = resourceDetector.getCpuCapacityCores();

        // process.cpu.usage thường phản ánh mức dùng CPU của process.
        // Để so sánh công bằng giữa container 2.0 / 1.5 / 1.0 CPU, ALB cần CPU đã chuẩn hóa theo quota.
        // Ví dụ: cùng 0.6 core thực tế thì node 1 CPU chịu áp lực cao hơn node 2 CPU.
        double rawCpu = getMetricSafely(() -> registry.get("process.cpu.usage").gauge().value());
        double cpu = normalizeCpuByCapacity(rawCpu, capacityWeight);

        double queue = getMetricSafely(() -> registry.get("http.server.requests.inflight").gauge().value());

        double count = 0.0;
        double totalTime = 0.0;

        try {
            for (io.micrometer.core.instrument.Meter meter : registry.getMeters()) {
                if (!meter.getId().getName().equals("http.server.requests"))
                    continue;
                String method = meter.getId().getTag("method");
                String status = meter.getId().getTag("status");
                if (!"GET".equals(method) && !"POST".equals(method))
                    continue;
                if (status != null && status.startsWith("4"))
                    continue;
                String uri = meter.getId().getTag("uri");
                if (isControlEndpoint(uri))
                    continue;
                if (meter instanceof Timer t) {
                    count += t.count();
                    totalTime += t.totalTime(TimeUnit.SECONDS);
                }
            }
        } catch (Exception ignored) {
        }

        return Map.of(
                "cpu", cpu,
                "rawCpu", rawCpu,
                "count", count,
                "totalTime", totalTime,
                "queue", queue,
                "capacityWeight", capacityWeight
        );
    }

    private double normalizeCpuByCapacity(double rawCpu, double capacityWeight) {
        if (Double.isNaN(rawCpu) || Double.isInfinite(rawCpu) || rawCpu < 0.0) {
            return 0.5;
        }
        double cap = Math.max(0.1, capacityWeight);
        return clamp(rawCpu / cap, 0.0, 1.0);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isControlEndpoint(String uri) {
        return uri == null
                || uri.equals("/api/alb-metrics")
                || uri.startsWith("/actuator")
                || uri.startsWith("/api/chaos");
    }

    private double getMetricSafely(Supplier<Double> metricSupplier) {
        try {
            return metricSupplier.get();
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}
