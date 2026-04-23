package com.truongquycode.registrationservicealb.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class RegistrationController {

    private final Random random = new Random();
    private static final AtomicLong requestCount = new AtomicLong(0);

    @GetMapping("/register")
public ResponseEntity<String> register(HttpServletRequest request) 
        throws InterruptedException {
    	long count = requestCount.incrementAndGet();
    int port = request.getServerPort();
    int delay;
    double dummy = 0;

    // Chaos kích hoạt dựa trên flag động, không hardcode port
    if (ChaosController.chaosEnabled.get()) {
        delay = 1000 + random.nextInt(1000);
        long endTime = System.currentTimeMillis() + delay;
        while (System.currentTimeMillis() < endTime) {
            dummy += Math.sqrt(Math.random());
        }
    } else {
        delay = 10 + random.nextInt(40);
        Thread.sleep(delay);
    }

    return ResponseEntity.ok(String.format(
    	    "Port: %d | Request #%d | Chaos: %s | Latency: %dms",
    	    port, count, ChaosController.chaosEnabled.get() ? "ON" : "OFF", delay
    	));
}
}
