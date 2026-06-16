package com.truongquycode.registrationservicealb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class SimulateController {

    private static final AtomicLong requestCount = new AtomicLong(0);
    private static volatile double cpuSink = 0.0;

    // Baseline cũ: giữ nguyên để không phá các kịch bản low/medium/high/stress hiện có.
    private static final int NORMAL_CPU_PHASE_1_ITERATIONS = 4_000;
    private static final int NORMAL_CPU_PHASE_2_ITERATIONS = 6_000;
    private static final int NORMAL_IO_MIN_MS = 30;
    private static final int NORMAL_IO_MAX_MS_EXCLUSIVE = 81;

    private static final int ASYNC_IO_MIN_MS = 600;
    private static final int ASYNC_IO_MAX_MS_EXCLUSIVE = 1001;

    private static final int HEAVY_CPU_PHASE_1_ITERATIONS = 21_000;
    private static final int HEAVY_CPU_PHASE_2_ITERATIONS = 27_000;
    private static final int HEAVY_IO_MIN_MS = 80;
    private static final int HEAVY_IO_MAX_MS_EXCLUSIVE = 201;

    // Mixed workload thực tế: cùng một service nhận nhiều loại request khác nhau.
    private static final int LIGHT_IO_MIN_MS = 20;
    private static final int LIGHT_IO_MAX_MS_EXCLUSIVE = 61;
    private static final int MEDIUM_IO_MIN_MS = 180;
    private static final int MEDIUM_IO_MAX_MS_EXCLUSIVE = 351;
    private static final int SLOW_IO_MIN_MS = 900;
    private static final int SLOW_IO_MAX_MS_EXCLUSIVE = 1301;
    private static final int VERY_SLOW_IO_MIN_MS = 2000;
    private static final int VERY_SLOW_IO_MAX_MS_EXCLUSIVE = 3001;

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

    /**
     * Endpoint mới cho benchmark mixed workload.
     * profile:
     * - light: vài chục ms
     * - medium: vài trăm ms
     * - slow: gần hoặc hơn 1000 ms
     * - very-slow: khoảng 2000-3000 ms
     * - mixed: server tự chọn theo tỷ lệ 70/20/8/2
     */
    @GetMapping("/simulate-mixed-call")
    public ResponseEntity<String> simulateMixedWorkload(
            @RequestParam(defaultValue = "mixed") String profile) throws InterruptedException {

        long startNs = System.nanoTime();
        long count = requestCount.incrementAndGet();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        WorkProfile selected = selectProfile(profile, random);
        runWorkProfile(selected, random);

        long elapsedMs = elapsedMillis(startNs);
        return ResponseEntity.ok(String.format(
                "MixedProfile: %s | Request #%d | Target I/O: %d-%dms | Elapsed: %dms",
                selected.name(), count, selected.ioMinMs(), selected.ioMaxExclusiveMs() - 1, elapsedMs
        ));
    }

    private static WorkProfile selectProfile(String requestedProfile, ThreadLocalRandom random) {
        String p = requestedProfile == null ? "mixed" : requestedProfile.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "light" -> WorkProfile.LIGHT;
            case "medium" -> WorkProfile.MEDIUM;
            case "slow" -> WorkProfile.SLOW;
            case "very-slow", "veryslow", "very_slow" -> WorkProfile.VERY_SLOW;
            default -> weightedMixedProfile(random);
        };
    }

    private static WorkProfile weightedMixedProfile(ThreadLocalRandom random) {
        int r = random.nextInt(100);
        if (r < 70) return WorkProfile.LIGHT;
        if (r < 90) return WorkProfile.MEDIUM;
        if (r < 98) return WorkProfile.SLOW;
        return WorkProfile.VERY_SLOW;
    }

    private static void runWorkProfile(WorkProfile profile, ThreadLocalRandom random) throws InterruptedException {
        int ioDelayMs = random.nextInt(profile.ioMinMs(), profile.ioMaxExclusiveMs());
        burnCpu(profile.cpuPhase1Iterations());
        Thread.sleep(ioDelayMs);
        burnCpu(profile.cpuPhase2Iterations());
    }

    private enum WorkProfile {
        LIGHT(1_500, 2_500, LIGHT_IO_MIN_MS, LIGHT_IO_MAX_MS_EXCLUSIVE),
        MEDIUM(6_000, 9_000, MEDIUM_IO_MIN_MS, MEDIUM_IO_MAX_MS_EXCLUSIVE),
        SLOW(12_000, 16_000, SLOW_IO_MIN_MS, SLOW_IO_MAX_MS_EXCLUSIVE),
        VERY_SLOW(15_000, 20_000, VERY_SLOW_IO_MIN_MS, VERY_SLOW_IO_MAX_MS_EXCLUSIVE);

        private final int cpuPhase1Iterations;
        private final int cpuPhase2Iterations;
        private final int ioMinMs;
        private final int ioMaxExclusiveMs;

        WorkProfile(int cpuPhase1Iterations, int cpuPhase2Iterations, int ioMinMs, int ioMaxExclusiveMs) {
            this.cpuPhase1Iterations = cpuPhase1Iterations;
            this.cpuPhase2Iterations = cpuPhase2Iterations;
            this.ioMinMs = ioMinMs;
            this.ioMaxExclusiveMs = ioMaxExclusiveMs;
        }

        public int cpuPhase1Iterations() { return cpuPhase1Iterations; }
        public int cpuPhase2Iterations() { return cpuPhase2Iterations; }
        public int ioMinMs() { return ioMinMs; }
        public int ioMaxExclusiveMs() { return ioMaxExclusiveMs; }
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
