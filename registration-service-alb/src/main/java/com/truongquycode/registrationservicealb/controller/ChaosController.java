package com.truongquycode.registrationservicealb.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    public static final AtomicBoolean heavyRequestEnabled = new AtomicBoolean(false);
    public static final AtomicBoolean cpuSpikeEnabled = new AtomicBoolean(false);
    public static final AtomicBoolean asyncIoEnabled = new AtomicBoolean(false);
    public static final AtomicBoolean hiddenDegradationEnabled = new AtomicBoolean(false);

    /**
     * Chaos chính thức cho benchmark: làm chậm request path trên một backend cụ thể.
     * Không dùng để phá container, không chủ động tạo lỗi; mục tiêu là tạo tín hiệu latency.
     */
    public static final AtomicBoolean latencyDegradationEnabled = new AtomicBoolean(false);
    private static final AtomicInteger latencyMinMs = new AtomicInteger(0);
    private static final AtomicInteger latencyMaxMs = new AtomicInteger(0);
    private static final AtomicInteger latencyProbabilityPercent = new AtomicInteger(0);

    /**
     * Chaos chính thức cho benchmark: mô phỏng dependency/DB/cache chậm cục bộ.
     * Chỉ những request có dùng DB pool trong simulate-mixed-call mới bị cộng thêm độ trễ.
     */
    public static final AtomicBoolean dependencySlowdownEnabled = new AtomicBoolean(false);
    private static final AtomicInteger dependencyExtraMinMs = new AtomicInteger(0);
    private static final AtomicInteger dependencyExtraMaxMs = new AtomicInteger(0);
    private static final AtomicInteger dependencyProbabilityPercent = new AtomicInteger(100);

    private static volatile double cpuSink = 0.0;

    private final Object chaosLock = new Object();
    private final List<Thread> cpuSpikeThreads = new CopyOnWriteArrayList<>();
    private final List<Thread> hiddenThreads = new CopyOnWriteArrayList<>();

    @Value("${chaos.burner-threads:2}")
    private int burnerThreadCount;

    @Value("${chaos.cpu-spike.work-iterations:20000}")
    private int cpuSpikeWorkIterations;

    @Value("${chaos.cpu-spike.duration-ms:30000}")
    private long cpuSpikeDurationMs;

    @Value("${chaos.hidden.work-iterations:80000}")
    private int hiddenWorkIterations;

    @Value("${chaos.hidden.cooldown-ms:4}")
    private long hiddenCooldownMs;

    /** Heavy request: giữ lại để tương thích các kịch bản cũ, không dùng làm chaos chính. */
    @PostMapping({"/heavy/enable", "/enable"})
    public ResponseEntity<Map<String, Object>> enableHeavyRequest() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            heavyRequestEnabled.set(true);
            return statusResponse("HEAVY REQUEST ENABLED");
        }
    }

    @PostMapping({"/heavy/disable", "/disable"})
    public ResponseEntity<Map<String, Object>> disableHeavyRequest() {
        synchronized (chaosLock) {
            heavyRequestEnabled.set(false);
            return statusResponse("HEAVY REQUEST DISABLED");
        }
    }

    /** CPU spike: giữ lại cho test phụ, không dùng làm kịch bản chính trong luận văn. */
    @PostMapping("/cpu-spike/enable")
    public ResponseEntity<Map<String, Object>> enableCpuSpike() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            cpuSpikeEnabled.set(true);

            for (int i = 0; i < burnerThreadCount; i++) {
                Thread thread = new Thread(() -> runCpuSpikeBurner(cpuSpikeEnabled), "cpu-spike-burner-" + i);
                thread.setDaemon(true);
                thread.start();
                cpuSpikeThreads.add(thread);
            }

            startCpuSpikeAutoStopper();
            return statusResponse("CPU SPIKE ENABLED");
        }
    }

    @PostMapping("/cpu-spike/disable")
    public ResponseEntity<Map<String, Object>> disableCpuSpike() {
        synchronized (chaosLock) {
            stopCpuSpikeNoLock();
            return statusResponse("CPU SPIKE DISABLED");
        }
    }

    /** I/O degradation cũ: giữ lại để tương thích /simulate-call. */
    @PostMapping("/async-io/enable")
    public ResponseEntity<Map<String, Object>> enableAsyncIo() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            asyncIoEnabled.set(true);
            return statusResponse("ASYNC I/O ENABLED");
        }
    }

    @PostMapping("/async-io/disable")
    public ResponseEntity<Map<String, Object>> disableAsyncIo() {
        synchronized (chaosLock) {
            asyncIoEnabled.set(false);
            return statusResponse("ASYNC I/O DISABLED");
        }
    }

    /** Hidden CPU: giữ lại cho noisy-neighbor test phụ, không dùng làm kịch bản chính. */
    @PostMapping("/hidden/enable")
    public ResponseEntity<Map<String, Object>> enableHidden() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            hiddenDegradationEnabled.set(true);

            for (int i = 0; i < burnerThreadCount; i++) {
                Thread thread = new Thread(() -> runHiddenBurner(hiddenDegradationEnabled), "hidden-burner-" + i);
                thread.setDaemon(true);
                thread.start();
                hiddenThreads.add(thread);
            }

            return statusResponse("HIDDEN CPU ENABLED");
        }
    }

    @PostMapping("/hidden/disable")
    public ResponseEntity<Map<String, Object>> disableHidden() {
        synchronized (chaosLock) {
            stopHiddenNoLock();
            return statusResponse("HIDDEN CPU DISABLED");
        }
    }

    /**
     * Localized latency degradation: thêm delay vào một tỷ lệ request trên backend đang bật chaos.
     * Ví dụ: POST /api/chaos/latency-degradation/enable?minMs=250&maxMs=500&probability=50
     */
    @PostMapping("/latency-degradation/enable")
    public ResponseEntity<Map<String, Object>> enableLatencyDegradation(
            @RequestParam(defaultValue = "250") int minMs,
            @RequestParam(defaultValue = "500") int maxMs,
            @RequestParam(defaultValue = "50") int probability) {
        synchronized (chaosLock) {
            resetInternalNoLock();
            setLatencyDegradationNoLock(minMs, maxMs, probability);
            return statusResponse("LATENCY DEGRADATION ENABLED");
        }
    }

    @PostMapping("/latency-degradation/medium")
    public ResponseEntity<Map<String, Object>> enableMediumLatencyDegradation() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            setLatencyDegradationNoLock(250, 500, 50);
            return statusResponse("MEDIUM LATENCY DEGRADATION ENABLED");
        }
    }

    @PostMapping("/latency-degradation/high")
    public ResponseEntity<Map<String, Object>> enableHighLatencyDegradation() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            setLatencyDegradationNoLock(500, 900, 60);
            return statusResponse("HIGH LATENCY DEGRADATION ENABLED");
        }
    }

    @PostMapping("/latency-degradation/disable")
    public ResponseEntity<Map<String, Object>> disableLatencyDegradation() {
        synchronized (chaosLock) {
            clearLatencyDegradationNoLock();
            return statusResponse("LATENCY DEGRADATION DISABLED");
        }
    }

    /**
     * Dependency slowdown: mô phỏng DB/cache/external service chậm cục bộ.
     * Ví dụ: POST /api/chaos/dependency-slowdown/enable?extraMinMs=200&extraMaxMs=400&probability=100
     */
    @PostMapping("/dependency-slowdown/enable")
    public ResponseEntity<Map<String, Object>> enableDependencySlowdown(
            @RequestParam(defaultValue = "200") int extraMinMs,
            @RequestParam(defaultValue = "400") int extraMaxMs,
            @RequestParam(defaultValue = "100") int probability) {
        synchronized (chaosLock) {
            resetInternalNoLock();
            setDependencySlowdownNoLock(extraMinMs, extraMaxMs, probability);
            return statusResponse("DEPENDENCY SLOWDOWN ENABLED");
        }
    }

    /** Kịch bản chính cho medium load: 600 RPS. */
    @PostMapping("/dependency-slowdown/medium")
    public ResponseEntity<Map<String, Object>> enableMediumDependencySlowdown() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            setDependencySlowdownNoLock(200, 400, 100);
            return statusResponse("MEDIUM DEPENDENCY SLOWDOWN ENABLED");
        }
    }

    /** Kịch bản chính cho high load: 900 RPS. */
    @PostMapping("/dependency-slowdown/high")
    public ResponseEntity<Map<String, Object>> enableHighDependencySlowdown() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            setDependencySlowdownNoLock(400, 800, 100);
            return statusResponse("HIGH DEPENDENCY SLOWDOWN ENABLED");
        }
    }

    @PostMapping("/dependency-slowdown/disable")
    public ResponseEntity<Map<String, Object>> disableDependencySlowdown() {
        synchronized (chaosLock) {
            clearDependencySlowdownNoLock();
            return statusResponse("DEPENDENCY SLOWDOWN DISABLED");
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetAll() {
        synchronized (chaosLock) {
            resetInternalNoLock();
            return statusResponse("ALL CHAOS RESET");
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        synchronized (chaosLock) {
            return statusResponse("CHAOS STATUS");
        }
    }

    public static int sampledLatencyDegradationMs(ThreadLocalRandom random) {
        if (!latencyDegradationEnabled.get()) {
            return 0;
        }
        if (random.nextInt(100) >= latencyProbabilityPercent.get()) {
            return 0;
        }
        int min = latencyMinMs.get();
        int max = latencyMaxMs.get();
        if (max <= min) {
            return Math.max(min, 0);
        }
        return random.nextInt(min, max + 1);
    }

    public static int sampledDependencyExtraHoldMs(ThreadLocalRandom random) {
        if (!dependencySlowdownEnabled.get()) {
            return 0;
        }
        if (random.nextInt(100) >= dependencyProbabilityPercent.get()) {
            return 0;
        }
        int min = dependencyExtraMinMs.get();
        int max = dependencyExtraMaxMs.get();
        if (max <= min) {
            return Math.max(min, 0);
        }
        return random.nextInt(min, max + 1);
    }

    private void resetInternalNoLock() {
        heavyRequestEnabled.set(false);
        asyncIoEnabled.set(false);
        clearLatencyDegradationNoLock();
        clearDependencySlowdownNoLock();
        stopHiddenNoLock();
        stopCpuSpikeNoLock();
    }

    private void setLatencyDegradationNoLock(int minMs, int maxMs, int probability) {
        latencyMinMs.set(Math.max(0, minMs));
        latencyMaxMs.set(Math.max(Math.max(0, minMs), maxMs));
        latencyProbabilityPercent.set(clamp(probability, 0, 100));
        latencyDegradationEnabled.set(true);
    }

    private void clearLatencyDegradationNoLock() {
        latencyDegradationEnabled.set(false);
        latencyMinMs.set(0);
        latencyMaxMs.set(0);
        latencyProbabilityPercent.set(0);
    }

    private void setDependencySlowdownNoLock(int extraMinMs, int extraMaxMs, int probability) {
        dependencyExtraMinMs.set(Math.max(0, extraMinMs));
        dependencyExtraMaxMs.set(Math.max(Math.max(0, extraMinMs), extraMaxMs));
        dependencyProbabilityPercent.set(clamp(probability, 0, 100));
        dependencySlowdownEnabled.set(true);
    }

    private void clearDependencySlowdownNoLock() {
        dependencySlowdownEnabled.set(false);
        dependencyExtraMinMs.set(0);
        dependencyExtraMaxMs.set(0);
        dependencyProbabilityPercent.set(100);
    }

    private void stopCpuSpikeNoLock() {
        cpuSpikeEnabled.set(false);
        stopThreads(cpuSpikeThreads);
    }

    private void stopHiddenNoLock() {
        hiddenDegradationEnabled.set(false);
        stopThreads(hiddenThreads);
    }

    private void runCpuSpikeBurner(AtomicBoolean enabledFlag) {
        double value = cpuSink;
        while (enabledFlag.get() && !Thread.currentThread().isInterrupted()) {
            for (int i = 0; i < cpuSpikeWorkIterations; i++) {
                value += Math.sqrt((i * 31.0 + 17.0) % 997.0);
            }
            cpuSink = value;
        }
    }

    private void runHiddenBurner(AtomicBoolean enabledFlag) {
        double value = cpuSink;
        while (enabledFlag.get() && !Thread.currentThread().isInterrupted()) {
            for (int i = 0; i < hiddenWorkIterations; i++) {
                value += Math.sqrt((i * 31.0 + 17.0) % 997.0);
            }
            cpuSink = value;

            try {
                Thread.sleep(hiddenCooldownMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void startCpuSpikeAutoStopper() {
        if (cpuSpikeDurationMs <= 0) {
            return;
        }

        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(cpuSpikeDurationMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            synchronized (chaosLock) {
                if (cpuSpikeEnabled.get()) {
                    stopCpuSpikeNoLock();
                }
            }
        }, "cpu-spike-auto-stop");

        stopper.setDaemon(true);
        stopper.start();
    }

    private void stopThreads(List<Thread> threads) {
        List<Thread> snapshot = new ArrayList<>(threads);
        threads.clear();

        snapshot.forEach(Thread::interrupt);

        for (Thread thread : snapshot) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private ResponseEntity<Map<String, Object>> statusResponse(String statusMsg) {
        Map<String, Object> states = new LinkedHashMap<>();
        states.put("activeMode", activeMode());
        states.put("heavyRequest", heavyRequestEnabled.get());
        states.put("cpuSpike", cpuSpikeEnabled.get());
        states.put("asyncIo", asyncIoEnabled.get());
        states.put("hiddenCpu", hiddenDegradationEnabled.get());
        states.put("latencyDegradation", latencyDegradationEnabled.get());
        states.put("dependencySlowdown", dependencySlowdownEnabled.get());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("burnerThreads", burnerThreadCount);
        config.put("cpuSpikeWorkIterations", cpuSpikeWorkIterations);
        config.put("cpuSpikeDurationMs", cpuSpikeDurationMs);
        config.put("hiddenWorkIterations", hiddenWorkIterations);
        config.put("hiddenCooldownMs", hiddenCooldownMs);
        config.put("latencyMinMs", latencyMinMs.get());
        config.put("latencyMaxMs", latencyMaxMs.get());
        config.put("latencyProbabilityPercent", latencyProbabilityPercent.get());
        config.put("dependencyExtraMinMs", dependencyExtraMinMs.get());
        config.put("dependencyExtraMaxMs", dependencyExtraMaxMs.get());
        config.put("dependencyProbabilityPercent", dependencyProbabilityPercent.get());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", statusMsg);
        response.put("port", System.getenv().getOrDefault("PORT", "unknown"));
        response.put("states", states);
        response.put("config", config);
        return ResponseEntity.ok(response);
    }

    private String activeMode() {
        int activeCount = 0;
        String mode = "NONE";

        if (heavyRequestEnabled.get()) {
            activeCount++;
            mode = "HEAVY_REQUEST";
        }
        if (cpuSpikeEnabled.get()) {
            activeCount++;
            mode = "CPU_SPIKE";
        }
        if (asyncIoEnabled.get()) {
            activeCount++;
            mode = "ASYNC_IO";
        }
        if (hiddenDegradationEnabled.get()) {
            activeCount++;
            mode = "HIDDEN_CPU";
        }
        if (latencyDegradationEnabled.get()) {
            activeCount++;
            mode = "LATENCY_DEGRADATION";
        }
        if (dependencySlowdownEnabled.get()) {
            activeCount++;
            mode = "DEPENDENCY_SLOWDOWN";
        }

        return activeCount > 1 ? "MULTIPLE" : mode;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
