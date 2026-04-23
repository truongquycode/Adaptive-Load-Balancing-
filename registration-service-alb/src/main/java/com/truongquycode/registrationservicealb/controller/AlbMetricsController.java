package com.truongquycode.registrationservicealb.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class AlbMetricsController {

    private final MeterRegistry registry;

    public AlbMetricsController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/alb-metrics")
    public Map<String, Double> getMetrics() {
        Map<String, Double> metrics = new HashMap<>();

        try { metrics.put("cpu", registry.get("process.cpu.usage").gauge().value()); } 
        catch (Exception e) { metrics.put("cpu", 0.0); }

        try {
            Timer timer = registry.get("http.server.requests").timer();
            metrics.put("count", (double) timer.count());
            metrics.put("totalTime", timer.totalTime(TimeUnit.SECONDS));
        } catch (Exception e) {
            metrics.put("count", 0.0);
            metrics.put("totalTime", 0.0);
        }

        try { metrics.put("queue", registry.get("http.server.requests.inflight").gauge().value()); } 
        catch (Exception e) { metrics.put("queue", 0.0); }

        return metrics;
    }
}