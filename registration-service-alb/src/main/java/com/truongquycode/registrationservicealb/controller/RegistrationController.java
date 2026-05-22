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
        int delay;

        if (ChaosController.originalChaos.get()) {
            // Kịch bản gốc: Vừa trễ mạng, vừa đốt CPU trên chính luồng HTTP
            delay = 1000 + random.nextInt(1000);
            long endTime = System.currentTimeMillis() + delay;
            double dummy = 0;
            while (System.currentTimeMillis() < endTime) {
                dummy += Math.sqrt(Math.random());
            }

        } else if (ChaosController.asyncIoEnabled.get()) {
            // TRƯỜNG HỢP 2: Lỗi I/O phi đồng bộ (Sleep thuần túy, không tốn CPU)
            delay = 1500;
            Thread.sleep(delay);

        } else {
            // TRƯỜNG HỢP 1 (CPU Spike) VÀ BÌNH THƯỜNG:
            // Ép luồng HTTP thực hiện một chút tính toán (mô phỏng parse JSON/Nghiệp vụ)
            // Vòng lặp cố định này tốn < 1ms khi CPU rảnh rỗi.
            // NHƯNG khi CPU bị Chaos vắt kiệt 100%, OS không cấp đủ time-slice, 
            // vòng lặp này sẽ bị kéo dãn ra tốn hàng trăm mili-giây!
            double httpDummy = 0;
            for (int i = 0; i < 50000; i++) {
                httpDummy += Math.sqrt(Math.random());
            }
            delay = 10 + random.nextInt(40);
            Thread.sleep(delay);
        }
        
        
        return ResponseEntity.ok(String.format(
            "Port: %d | Request #%d | Latency: %dms",
            port, count, delay
        ));
    }
}