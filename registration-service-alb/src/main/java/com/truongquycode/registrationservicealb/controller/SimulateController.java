package com.truongquycode.registrationservicealb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class SimulateController {

    private static final AtomicLong requestCount = new AtomicLong(0);

    // Biến này giúp JVM không tối ưu bỏ vòng lặp CPU giả lập.
    private static volatile double cpuSink = 0.0;

    // Baseline: CPU nhẹ -> I/O ngắn -> CPU nhẹ.
    private static final int NORMAL_CPU_PHASE_1_ITERATIONS = 4_000;
    private static final int NORMAL_CPU_PHASE_2_ITERATIONS = 6_000;
    private static final int NORMAL_IO_MIN_MS = 30;
    private static final int NORMAL_IO_MAX_MS_EXCLUSIVE = 81;

    // I/O degradation: giữ nguyên CPU phase, chỉ kéo dài phần chờ I/O.
    private static final int ASYNC_IO_MIN_MS = 600;
    private static final int ASYNC_IO_MAX_MS_EXCLUSIVE = 1001;

    // Heavy request: mô phỏng request nghiệp vụ nặng hơn bình thường.
    private static final int HEAVY_CPU_PHASE_1_ITERATIONS = 21_000;
    private static final int HEAVY_CPU_PHASE_2_ITERATIONS = 27_000;
    private static final int HEAVY_IO_MIN_MS = 80;
    private static final int HEAVY_IO_MAX_MS_EXCLUSIVE = 201;

    /**
     * Endpoint benchmark chính.
     * Cấu trúc request được giữ ổn định để dễ so sánh các thuật toán cân bằng tải.
     */
    @GetMapping("/simulate-call")
    public ResponseEntity<String> simulateInterServiceCommunication() throws InterruptedException {
        long startNs = System.nanoTime();
        long count = requestCount.incrementAndGet();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        String mode;
        int ioDelayMs;

        if (ChaosController.heavyRequestEnabled.get()) {
            mode = "HEAVY_REQUEST";
            ioDelayMs = random.nextInt(HEAVY_IO_MIN_MS, HEAVY_IO_MAX_MS_EXCLUSIVE);
            burnCpu(HEAVY_CPU_PHASE_1_ITERATIONS);
            Thread.sleep(ioDelayMs);
            burnCpu(HEAVY_CPU_PHASE_2_ITERATIONS);
        } else if (ChaosController.asyncIoEnabled.get()) {
            mode = "ASYNC_IO";
            ioDelayMs = random.nextInt(ASYNC_IO_MIN_MS, ASYNC_IO_MAX_MS_EXCLUSIVE);
            burnCpu(NORMAL_CPU_PHASE_1_ITERATIONS);
            Thread.sleep(ioDelayMs);
            burnCpu(NORMAL_CPU_PHASE_2_ITERATIONS);
        } else {
            // Hidden CPU và CPU spike chạy nền trong ChaosController.
            // Request path vẫn là baseline để không trộn thêm nguyên nhân gây nhiễu.
            mode = ChaosController.hiddenDegradationEnabled.get() ? "HIDDEN_CPU_BASELINE_PATH"
                    : ChaosController.cpuSpikeEnabled.get() ? "CPU_SPIKE_BASELINE_PATH"
                    : "BASELINE";
            ioDelayMs = random.nextInt(NORMAL_IO_MIN_MS, NORMAL_IO_MAX_MS_EXCLUSIVE);
            burnCpu(NORMAL_CPU_PHASE_1_ITERATIONS);
            Thread.sleep(ioDelayMs);
            burnCpu(NORMAL_CPU_PHASE_2_ITERATIONS);
        }

        long elapsedMs = elapsedMillis(startNs);
        return ResponseEntity.ok(String.format(
                "Mode: %s | Request #%d | I/O wait: %dms | Elapsed: %dms",
                mode, count, ioDelayMs, elapsedMs
        ));
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
}
