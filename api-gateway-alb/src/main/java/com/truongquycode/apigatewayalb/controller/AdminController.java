package com.truongquycode.apigatewayalb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truongquycode.apigatewayalb.dataplane.AdaptiveLoadBalancer;
import com.truongquycode.apigatewayalb.dataplane.InflightLifecycle;
import com.truongquycode.apigatewayalb.dataplane.PIDController;
import com.truongquycode.apigatewayalb.math.EwmaSmoother;
import com.truongquycode.apigatewayalb.controlplane.SlidingWindowManager;
import com.truongquycode.apigatewayalb.controlplane.MetricsPoller;
import com.truongquycode.apigatewayalb.controlplane.DynamicWeightEngine;
import com.truongquycode.apigatewayalb.dataplane.InflightTracker;
import com.truongquycode.apigatewayalb.util.MetricsCache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/actuator")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

	private final PIDController pidController;
	private final EwmaSmoother ewmaSmoother;
	private final SlidingWindowManager windowManager;
	private final InflightTracker inflightTracker;
	private final InflightLifecycle inflightLifecycle;
	private final MetricsPoller metricsPoller;
	private final DynamicWeightEngine weightEngine;
	private final MetricsCache metricsCache;

	@PostMapping("/alb/reset")
	public ResponseEntity<String> resetState() {
		log.info("--- BẮT ĐẦU RESET TOÀN BỘ TRẠNG THÁI ALB ---");

		// 1. Data Plane Resets
		inflightTracker.resetAll();
		AdaptiveLoadBalancer.resetStaticState();
		inflightLifecycle.resetActiveRequests();

		// 2. Control Plane - Math & Algorithms Resets
		pidController.resetAllStates();
		ewmaSmoother.resetAllStates();
		windowManager.resetAll();

		// 3. Control Plane - State & Cache Resets
		metricsPoller.resetAllStates();
		weightEngine.resetWeights();
		metricsCache.clearAll();

		log.info("--- HOÀN TẤT RESET ALB ---");

		return ResponseEntity.ok("ALB State Reset hoàn toàn:\n" + "  ✓ InflightTracker      → counters về 0\n"
				+ "  ✓ AdaptiveLoadBalancer → smoothedCapMcdm, firstSeenMs, rrCounter cleared\n"
				+ "  ✓ PIDController        → integral accumulation cleared\n"
				+ "  ✓ EwmaSmoother         → EWMA state cleared\n" + "  ✓ SlidingWindowManager → histogram cleared\n"
				+ "  ✓ MetricsPoller        → traffic history & smoothed scores cleared\n"
				+ "  ✓ DynamicWeightEngine  → MCDM weights reset to defaults\n"
				+ "  ✓ MetricsCache         → stale scores cleared\n" + "Sẵn sàng cho benchmark tiếp theo.");
	}
}