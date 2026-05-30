package com.truongquycode.registrationservicealb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class SimulateController {

    private final Random random = new Random();
    private static final AtomicLong requestCount = new AtomicLong(0);

    @GetMapping("/simulate-call")
    public ResponseEntity<String> simulateInterServiceCommunication() throws InterruptedException {
        long count = requestCount.incrementAndGet();

        // 1. PHA 1: MÔ PHỎNG CPU PARSE JSON (Serialization)
        // Khi Service A chuẩn bị gọi Service B, nó tốn CPU để mã hóa dữ liệu thành JSON
        burnCpu(7000);  //nên đổi 

        // 2. PHA 2: MÔ PHỎNG NETWORK I/O (Chờ đợi)
        // Gửi request qua mạng và chờ Service B hoặc Database phản hồi. (Không tốn CPU)
        int networkDelay = 15 + random.nextInt(35); // Trễ ngẫu nhiên 15-50ms
        Thread.sleep(networkDelay);

        // 3. PHA 3: MÔ PHỎNG CPU ĐỌC KẾT QUẢ (Deserialization & Business Logic)
        // Khi nhận được dữ liệu về, tốn CPU để giải nén JSON và tính toán nghiệp vụ
        burnCpu(9000);

        return ResponseEntity.ok(String.format(
            "Inter-service call completed | Request #%d | Network I/O Wait: %dms", 
            count, networkDelay
        ));
    }

    /**
     * Hàm giả lập tải CPU. 
     * Số vòng lặp nhỏ (2000-3000) giúp tạo ra mức tiêu thụ CPU nền khoảng 15-30% 
     * khi chịu tải 300-400 RPS, phản ánh đúng thực tế của một microservice đang bận rộn.
     */
    private void burnCpu(int iterations) {
        double dummy = 0;
        for (int i = 0; i < iterations; i++) {
            dummy += Math.sqrt(Math.random());
        }
    }
}