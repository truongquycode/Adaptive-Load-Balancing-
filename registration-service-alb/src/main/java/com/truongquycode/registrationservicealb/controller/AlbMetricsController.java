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
        
        // Timer cần xử lý riêng vì trả về 2 giá trị
        double count = 0.0;
        double totalTime = 0.0;
        try {
            Timer timer = registry.get("http.server.requests")
                    .tag("uri", "/api/register")
                    .tag("method", "GET")
                    .timer();
            count = timer.count();
            totalTime = timer.totalTime(TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Im lặng bỏ qua khi chưa có request nào đập vào
        }

        return Map.of(
            "cpu", cpu,
            "count", count,
            "totalTime", totalTime,
            "queue", queue
        );
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