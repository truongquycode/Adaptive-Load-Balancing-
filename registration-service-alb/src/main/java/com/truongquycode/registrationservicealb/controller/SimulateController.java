package com.truongquycode.registrationservicealb.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class SimulateController {

    private static final AtomicLong requestCount = new AtomicLong(0);
    private static volatile double cpuSink = 0.0;
    private static volatile long memorySink = 0L;

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

    /**
     * Workload mô phỏng thực tế hơn cho benchmark ALB.
     * Ý tưởng:
     * - Một service thực tế thường có nhiều API nặng nhẹ khác nhau.
     * - Request không chỉ sleep I/O, mà còn tiêu tốn CPU, RAM tạm thời và DB connection pool.
     * - DB_POOL tạo hiện tượng queue/đợi tài nguyên giống connection pool thật.
     *
     * Lưu ý: DB_POOL là theo từng instance, vì mỗi instance chạy một JVM riêng.
     * Nếu chạy 3 instance thì tổng số DB slot xấp xỉ 3 * DB_POOL_SIZE_PER_INSTANCE.
     *
     * Bản R_sat 1000+:
     * - Mục tiêu: đẩy vùng bão hòa lên trên 1000 RPS nhưng vẫn giữ workload hỗn hợp.
     * - DB_POOL_SIZE_PER_INSTANCE = 40 được tính theo Little's Law cho vùng khảo sát
     *   khoảng 1200 RPS toàn hệ thống. Với mix 60/25/12/3 và thời gian giữ DB trung bình
     *   có trọng số khoảng 30.75ms/request, mỗi instance nhận 1200/3 = 400 RPS:
     *   L_db ≈ 400 * 0.03075 ≈ 12.3 connection. Nhân hệ số dự phòng khoảng 3 lần
     *   cho burst, lệch tải và queue ngắn hạn → khoảng 37, làm tròn thành 40.
     * - DB_ACQUIRE_TIMEOUT_MS = 12000 để hạn chế trả 503 quá sớm. Khi quá tải,
     *   hệ thống nên thể hiện bằng latency tăng trước; lỗi chỉ xuất hiện khi thật sự vượt xa ngưỡng.
     */
    private static final int DB_POOL_SIZE_PER_INSTANCE = 40;
    private static final int DB_ACQUIRE_TIMEOUT_MS = 12_000;
    private static final Semaphore DB_POOL = new Semaphore(DB_POOL_SIZE_PER_INSTANCE, true);
    private static final AtomicInteger activeMixedRequests = new AtomicInteger(0);

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
     * Endpoint dùng cho benchmark mixed workload.
     *
     * profile:
     * - light: request nhẹ, ít CPU/RAM, không dùng DB pool.
     * - medium: request nghiệp vụ thông thường, có CPU/RAM và dùng DB pool ngắn.
     * - slow: request nặng hơn, có CPU/RAM nhiều hơn và giữ DB pool lâu hơn.
     * - very-slow: request đuôi dài, tiêu tốn nhiều CPU/RAM và giữ DB pool lâu.
     * - mixed: server tự chọn theo tỷ lệ 60/25/12/3.
     *
     * Khi dùng JMeter đã chia tỷ lệ bằng 4 sampler thì nên gọi profile cụ thể.
     * Khi test nhanh bằng curl thì có thể gọi profile=mixed.
     * Các chaos chính thức dependency-slowdown và latency-degradation tác động lên endpoint này.
     */
    @GetMapping("/simulate-mixed-call")
    public ResponseEntity<String> simulateMixedWorkload(
            @RequestParam(defaultValue = "mixed") String profile) throws InterruptedException {

        long startNs = System.nanoTime();
        long count = requestCount.incrementAndGet();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        WorkProfile selected = selectProfile(profile, random);
        WorkResult result = runWorkProfile(selected, random);

        long elapsedMs = elapsedMillis(startNs);
        String body = String.format(
                "MixedProfile: %s | Request #%d | CPU iters: %d+%d | RAM: %dKB | I/O: %dms | Chaos latency: %dms | DB wait: %dms | DB hold: %dms | Dependency extra: %dms | Active mixed: %d | DB timeout: %s | Elapsed: %dms",
                selected.name(),
                count,
                selected.cpuPhase1Iterations(),
                selected.cpuPhase2Iterations(),
                selected.memoryKb(),
                result.ioDelayMs(),
                result.chaosLatencyMs(),
                result.dbWaitMs(),
                result.dbHoldMs(),
                result.dependencyExtraMs(),
                result.activeAtStart(),
                result.dbTimedOut(),
                elapsedMs
        );

        if (result.dbTimedOut()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        return ResponseEntity.ok(body);
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

    /** Mixed mặc định thực tế hơn: 60% nhẹ, 25% vừa, 12% chậm, 3% rất chậm. */
    private static WorkProfile weightedMixedProfile(ThreadLocalRandom random) {
        int r = random.nextInt(100);
        if (r < 60) return WorkProfile.LIGHT;
        if (r < 85) return WorkProfile.MEDIUM;
        if (r < 97) return WorkProfile.SLOW;
        return WorkProfile.VERY_SLOW;
    }

    private static WorkResult runWorkProfile(WorkProfile profile, ThreadLocalRandom random) throws InterruptedException {
        int active = activeMixedRequests.incrementAndGet();
        boolean dbAcquired = false;
        int ioDelayMs = 0;
        int dbHoldMs = 0;
        long dbWaitMs = 0;
        byte[] memoryBlock = null;

        try {
            // RAM tạm thời: giữ trong suốt vòng đời request để tạo áp lực heap/GC khi concurrent cao.
            memoryBlock = allocateAndTouchMemory(profile.memoryKb(), random);

            burnCpu(profile.cpuPhase1Iterations());

            ioDelayMs = random.nextInt(profile.ioMinMs(), profile.ioMaxExclusiveMs());
            Thread.sleep(ioDelayMs);

            // Localized latency degradation: mô phỏng backend bị chậm trên request path.
            int chaosLatencyMs = ChaosController.sampledLatencyDegradationMs(random);
            if (chaosLatencyMs > 0) {
                Thread.sleep(chaosLatencyMs);
            }

            int dependencyExtraMs = 0;
            if (profile.usesDbPool()) {
                long waitStartNs = System.nanoTime();
                dbAcquired = DB_POOL.tryAcquire(DB_ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                dbWaitMs = elapsedMillis(waitStartNs);

                if (!dbAcquired) {
                    // Không lấy được DB connection trong timeout: đây là dấu hiệu quá tải hợp lý.
                    return WorkResult.dbTimeout(ioDelayMs, (int) dbWaitMs, active, profile.memoryKb(), chaosLatencyMs);
                }

                int baseDbHoldMs = random.nextInt(profile.dbMinMs(), profile.dbMaxExclusiveMs());
                dependencyExtraMs = ChaosController.sampledDependencyExtraHoldMs(random);
                dbHoldMs = baseDbHoldMs + dependencyExtraMs;
                Thread.sleep(dbHoldMs);
            }

            burnCpu(profile.cpuPhase2Iterations());
            consumeMemory(memoryBlock);

            return WorkResult.ok(ioDelayMs, dbHoldMs, (int) dbWaitMs, active, profile.memoryKb(), chaosLatencyMs, dependencyExtraMs);
        } finally {
            if (dbAcquired) {
                DB_POOL.release();
            }
            activeMixedRequests.decrementAndGet();
        }
    }

    private enum WorkProfile {
        // Bản 1000+ giữ tỷ lệ 60/25/12/3 nhưng giảm CPU/RAM/DB hold so với bản quá tải sớm.
        // Mục tiêu là tạo R_sat > 1000 RPS và hạn chế nhiều lỗi 500/504 trong vùng khảo sát.
        // cpu1, cpu2, ioMin, ioMaxExclusive, memoryKb, usesDb, dbMin, dbMaxExclusive
        LIGHT(
                5_000, 8_000,
                20, 61,
                32,
                false, 0, 1
        ),
        MEDIUM(
                25_000, 35_000,
                120, 281,
                128,
                true, 30, 61
        ),
        SLOW(
                80_000, 120_000,
                700, 1201,
                512,
                true, 80, 141
        ),
        VERY_SLOW(
                160_000, 240_000,
                1_800, 2801,
                1_024,
                true, 160, 261
        );

        private final int cpuPhase1Iterations;
        private final int cpuPhase2Iterations;
        private final int ioMinMs;
        private final int ioMaxExclusiveMs;
        private final int memoryKb;
        private final boolean usesDbPool;
        private final int dbMinMs;
        private final int dbMaxExclusiveMs;

        WorkProfile(
                int cpuPhase1Iterations,
                int cpuPhase2Iterations,
                int ioMinMs,
                int ioMaxExclusiveMs,
                int memoryKb,
                boolean usesDbPool,
                int dbMinMs,
                int dbMaxExclusiveMs
        ) {
            this.cpuPhase1Iterations = cpuPhase1Iterations;
            this.cpuPhase2Iterations = cpuPhase2Iterations;
            this.ioMinMs = ioMinMs;
            this.ioMaxExclusiveMs = ioMaxExclusiveMs;
            this.memoryKb = memoryKb;
            this.usesDbPool = usesDbPool;
            this.dbMinMs = dbMinMs;
            this.dbMaxExclusiveMs = dbMaxExclusiveMs;
        }

        public int cpuPhase1Iterations() { return cpuPhase1Iterations; }
        public int cpuPhase2Iterations() { return cpuPhase2Iterations; }
        public int ioMinMs() { return ioMinMs; }
        public int ioMaxExclusiveMs() { return ioMaxExclusiveMs; }
        public int memoryKb() { return memoryKb; }
        public boolean usesDbPool() { return usesDbPool; }
        public int dbMinMs() { return dbMinMs; }
        public int dbMaxExclusiveMs() { return dbMaxExclusiveMs; }
    }

    private static final class WorkResult {
        private final int ioDelayMs;
        private final int dbHoldMs;
        private final int dbWaitMs;
        private final int activeAtStart;
        private final int memoryKb;
        private final int chaosLatencyMs;
        private final int dependencyExtraMs;
        private final boolean dbTimedOut;

        private WorkResult(
                int ioDelayMs,
                int dbHoldMs,
                int dbWaitMs,
                int activeAtStart,
                int memoryKb,
                int chaosLatencyMs,
                int dependencyExtraMs,
                boolean dbTimedOut
        ) {
            this.ioDelayMs = ioDelayMs;
            this.dbHoldMs = dbHoldMs;
            this.dbWaitMs = dbWaitMs;
            this.activeAtStart = activeAtStart;
            this.memoryKb = memoryKb;
            this.chaosLatencyMs = chaosLatencyMs;
            this.dependencyExtraMs = dependencyExtraMs;
            this.dbTimedOut = dbTimedOut;
        }

        static WorkResult ok(
                int ioDelayMs,
                int dbHoldMs,
                int dbWaitMs,
                int activeAtStart,
                int memoryKb,
                int chaosLatencyMs,
                int dependencyExtraMs
        ) {
            return new WorkResult(ioDelayMs, dbHoldMs, dbWaitMs, activeAtStart, memoryKb, chaosLatencyMs, dependencyExtraMs, false);
        }

        static WorkResult dbTimeout(int ioDelayMs, int dbWaitMs, int activeAtStart, int memoryKb, int chaosLatencyMs) {
            return new WorkResult(ioDelayMs, 0, dbWaitMs, activeAtStart, memoryKb, chaosLatencyMs, 0, true);
        }

        int ioDelayMs() { return ioDelayMs; }
        int dbHoldMs() { return dbHoldMs; }
        int dbWaitMs() { return dbWaitMs; }
        int activeAtStart() { return activeAtStart; }
        int memoryKb() { return memoryKb; }
        int chaosLatencyMs() { return chaosLatencyMs; }
        int dependencyExtraMs() { return dependencyExtraMs; }
        boolean dbTimedOut() { return dbTimedOut; }
    }

    private static long elapsedMillis(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private static byte[] allocateAndTouchMemory(int memoryKb, ThreadLocalRandom random) {
        int size = Math.max(1, memoryKb) * 1024;
        byte[] block = new byte[size];
        byte seed = (byte) random.nextInt(1, 127);

        // Chạm theo page để JVM thật sự cấp phát và làm việc với vùng nhớ.
        for (int i = 0; i < block.length; i += 4096) {
            block[i] = (byte) (seed + i);
        }
        block[block.length - 1] = seed;
        return block;
    }

    private static void consumeMemory(byte[] block) {
        if (block == null) return;
        long sum = memorySink;
        for (int i = 0; i < block.length; i += 4096) {
            sum += block[i];
        }
        sum += block[block.length - 1];
        memorySink = sum;
    }

    private static void burnCpu(int iterations) {
        double value = cpuSink;
        for (int i = 0; i < iterations; i++) {
            value += Math.sqrt((i * 31.0 + 17.0) % 997.0);
        }
        cpuSink = value;
    }
}
