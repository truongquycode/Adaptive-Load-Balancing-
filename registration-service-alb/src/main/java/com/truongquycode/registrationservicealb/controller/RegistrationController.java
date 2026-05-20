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
    public ResponseEntity<String> register(HttpServletRequest request) throws InterruptedException {
        long count = requestCount.incrementAndGet();
        int port = request.getServerPort();
        boolean isChaos = ChaosController.chaosEnabled.get();
        
        int delay = simulateDelay(isChaos);

        return ResponseEntity.ok(String.format(
            "Port: %d | Request #%d | Chaos: %s | Latency: %dms",
            port, count, isChaos ? "ON" : "OFF", delay
        ));
    }

    // Tách riêng logic tính toán độ trễ và giả lập bận CPU
    private int simulateDelay(boolean isChaos) throws InterruptedException {
        if (isChaos) {
            int delay = 1000 + random.nextInt(1000); // 1s - 2s
            long endTime = System.currentTimeMillis() + delay;
            double dummy = 0;
            // Vòng lặp bận (Busy-wait) để đốt CPU
            while (System.currentTimeMillis() < endTime) {
                dummy += Math.sqrt(Math.random()); 
            }
            return delay;
        } else {
            int delay = 10 + random.nextInt(40); // 10ms - 50ms
            Thread.sleep(delay);
            return delay;
        }
    }
}