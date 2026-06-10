package com.truongquycode.registrationservicealb.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class RegistrationController {

    private static final AtomicLong requestCount = new AtomicLong(0);
    private static volatile double cpuSink = 0.0;

    private static final int REGISTER_BASELINE_MIN_MS = 10;
    private static final int REGISTER_BASELINE_MAX_MS_EXCLUSIVE = 51;

    private static final int REGISTER_HEAVY_CPU_MS = 100;
    private static final int REGISTER_HEAVY_IO_MIN_MS = 100;
    private static final int REGISTER_HEAVY_IO_MAX_MS_EXCLUSIVE = 301;

    private static final int REGISTER_ASYNC_IO_DELAY_MS = 800;

    private static final int REGISTER_USER_CPU_ITERATIONS = 20_000;
    private static final int REGISTER_USER_GC_OBJECTS = 1_000;
    private static final int REGISTER_USER_IO_MIN_MS = 150;
    private static final int REGISTER_USER_IO_MAX_MS_EXCLUSIVE = 351;

    public static class UserRegistrationDto {
        public String username;
        public String email;
        public String fullName;
        public String address;
        public String deviceId;
        public List<String> preferences;
    }

    /**
     * Endpoint demo nghiệp vụ đăng ký người dùng.
     * Không nên dùng endpoint này làm số liệu benchmark chính vì có allocation/GC giả lập.
     */
    @PostMapping("/register-user")
    public ResponseEntity<Map<String, Object>> registerUserPost(
            @RequestBody(required = false) UserRegistrationDto payload,
            HttpServletRequest request
    ) throws InterruptedException {
        long startNs = System.nanoTime();
        long count = requestCount.incrementAndGet();
        int port = request.getServerPort();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        burnCpu(REGISTER_USER_CPU_ITERATIONS);

        StringBuilder allocatedData = new StringBuilder(REGISTER_USER_GC_OBJECTS * 36);
        for (int i = 0; i < REGISTER_USER_GC_OBJECTS; i++) {
            allocatedData.append(UUID.randomUUID());
        }
        String generatedData = allocatedData.toString();

        int ioDelayMs = random.nextInt(REGISTER_USER_IO_MIN_MS, REGISTER_USER_IO_MAX_MS_EXCLUSIVE);
        Thread.sleep(ioDelayMs);

        long elapsedMs = elapsedMillis(startNs);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "port", port,
                "requestId", count,
                "processedUser", safeUsername(payload),
                "ioDelayMs", ioDelayMs,
                "elapsedMs", elapsedMs,
                "allocatedDataLength", generatedData.length()
        ));
    }

    /**
     * Endpoint tương thích cũ. Benchmark khoa học nên ưu tiên /api/simulate-call.
     */
    @GetMapping("/register")
    public ResponseEntity<String> register(HttpServletRequest request) throws InterruptedException {
        long startNs = System.nanoTime();
        long count = requestCount.incrementAndGet();
        int port = request.getServerPort();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        String mode;
        int ioDelayMs;

        if (ChaosController.heavyRequestEnabled.get()) {
            mode = "HEAVY_REQUEST";
            ioDelayMs = random.nextInt(REGISTER_HEAVY_IO_MIN_MS, REGISTER_HEAVY_IO_MAX_MS_EXCLUSIVE);
            burnCpuForMillis(REGISTER_HEAVY_CPU_MS);
            Thread.sleep(ioDelayMs);
        } else if (ChaosController.asyncIoEnabled.get()) {
            mode = "ASYNC_IO";
            ioDelayMs = REGISTER_ASYNC_IO_DELAY_MS;
            Thread.sleep(ioDelayMs);
        } else {
            // Hidden CPU và CPU spike chỉ chạy nền, endpoint này không tự thêm CPU nữa.
            mode = ChaosController.hiddenDegradationEnabled.get() ? "HIDDEN_CPU_BASELINE_PATH"
                    : ChaosController.cpuSpikeEnabled.get() ? "CPU_SPIKE_BASELINE_PATH"
                    : "BASELINE";
            ioDelayMs = random.nextInt(REGISTER_BASELINE_MIN_MS, REGISTER_BASELINE_MAX_MS_EXCLUSIVE);
            Thread.sleep(ioDelayMs);
        }

        long elapsedMs = elapsedMillis(startNs);
        return ResponseEntity.ok(String.format(
                "Port: %d | Request #%d | Mode: %s | I/O wait: %dms | Elapsed: %dms",
                port, count, mode, ioDelayMs, elapsedMs
        ));
    }

    private static String safeUsername(UserRegistrationDto payload) {
        if (payload == null || payload.username == null || payload.username.isBlank()) {
            return "anonymous";
        }
        return payload.username;
    }

    private static long elapsedMillis(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private static void burnCpu(int iterations) {
        double value = cpuSink;
        for (int i = 0; i < iterations; i++) {
            value += Math.sqrt((i * 31.0 + 17.0) % 997.0);
        }
        cpuSink = value;
    }

    private static void burnCpuForMillis(int millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        double value = cpuSink;
        int i = 0;
        while (System.nanoTime() < deadline) {
            value += Math.sqrt((i++ * 31.0 + 17.0) % 997.0);
        }
        cpuSink = value;
    }
}
