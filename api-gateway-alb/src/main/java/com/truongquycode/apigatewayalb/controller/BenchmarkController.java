package com.truongquycode.apigatewayalb.controller;

import com.truongquycode.apigatewayalb.controlplane.SlidingWindowManager;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;
import com.truongquycode.apigatewayalb.dataplane.PIDController;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;
import com.truongquycode.apigatewayalb.util.MetricsCache;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/benchmark")
@RequiredArgsConstructor
public class BenchmarkController {

    private final PIDController pidController;
    private final SlidingWindowManager windowManager;
    private final MetricsCache metricsCache;
    private final InflightTracker inflightTracker;
    private final EwmaSmoother ewmaSmoother;

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetAll() {

        pidController.resetAllStates();

        windowManager.resetAll();

        inflightTracker.resetAll();

        ewmaSmoother.resetAllStates();
        
        metricsCache.resetAll();

        return ResponseEntity.ok(
                Map.of("status", "ALL STATES RESET")
        );
    }
}