package com.truongquycode.apigatewayalb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truongquycode.apigatewayalb.dataplane.PIDController;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

import com.truongquycode.apigatewayalb.controlplane.SlidingWindowManager;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;

@RestController
@RequestMapping("/actuator")
@RequiredArgsConstructor
public class AdminController {
    
    private final PIDController pidController;
    private final EwmaSmoother ewmaSmoother;
    private final SlidingWindowManager windowManager;
    private final InflightTracker inflightTracker;

    @PostMapping("/alb/reset")
    public ResponseEntity<String> resetState() {
        pidController.resetAllStates();
        ewmaSmoother.resetAllStates();
        windowManager.resetAll();
        inflightTracker.resetAll();
        
        return ResponseEntity.ok("ALB State Reset Hòan Toàn — Sẵn sàng cho lần Benchmark tiếp theo");
    }
}