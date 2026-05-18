package com.truongquycode.apigatewayalb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truongquycode.apigatewayalb.dataplane.PIDController;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;

import io.micrometer.core.instrument.MeterRegistry;

@RestController
@RequestMapping("/actuator")
public class AdminController {
	private final PIDController pidController;
    private final EwmaSmoother ewmaSmoother;

    public AdminController(PIDController pidController, EwmaSmoother ewmaSmoother) {
        this.pidController = pidController;
        this.ewmaSmoother = ewmaSmoother;
    }
	
	@PostMapping("/alb/reset")
	public ResponseEntity<String> resetState() {
	    // Xóa PID state để test run tiếp theo bắt đầu sạch
	    pidController.resetAllStates();
	    ewmaSmoother.resetAllStates();
	    return ResponseEntity.ok("ALB state reset — PID integral và EWMA cleared");
	}

}
