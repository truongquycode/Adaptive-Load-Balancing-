package com.truongquycode.registrationservicealb.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    public static final AtomicBoolean heavyRequestEnabled = new AtomicBoolean(false);
    public static final AtomicBoolean cpuSpikeEnabled = new AtomicBoolean(false);
    public static final AtomicBoolean asyncIoEnabled = new AtomicBoolean(false);
    public static final AtomicBoolean hiddenDegradationEnabled = new AtomicBoolean(false);

    private static volatile double cpuSink = 0.0;

    private final Object chaosLock = new Object();
    private final List<Thread> cpuSpikeThreads = new CopyOnWriteArrayList<>();
    private final List<Thread> hiddenThreads = new CopyOnWriteArrayList<>();

    @Value("${chaos.burner-threads:2}")
    private int burnerThreadCount;

    @Value("${chaos.cpu-spike.work-iterations:20000}")
    private int cpuSpikeWorkIterations;

    // Mặc định spike tự dừng sau 30 giây. Có thể tăng trong application.yml nếu phase dài hơn.
    @Value("${chaos.cpu-spike.duration-ms:30000}")
    private long cpuSpikeDurationMs;

    @Value("${chaos.hidden.work-iterations:80000}")
    private int hiddenWorkIterations;

    @Value("${chaos.hidden.cooldown-ms:4}")
    private long hiddenCooldownMs;

    /** Heavy request: request hiện tại nặng hơn baseline, không phải lỗi hạ tầng. */
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

    /** CPU spike: tranh chấp CPU nền, có thời lượng hữu hạn để đúng nghĩa spike. */
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

    /** I/O degradation: chỉ kéo dài thời gian chờ I/O, không tạo thêm CPU nền. */
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

    /** Hidden CPU: CPU bị tiêu thụ ở nền, request path không tự thêm CPU. */
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

    private void resetInternalNoLock() {
        heavyRequestEnabled.set(false);
        asyncIoEnabled.set(false);
        stopHiddenNoLock();
        stopCpuSpikeNoLock();
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
        Map<String, Object> states = Map.of(
                "activeMode", activeMode(),
                "heavyRequest", heavyRequestEnabled.get(),
                "cpuSpike", cpuSpikeEnabled.get(),
                "asyncIo", asyncIoEnabled.get(),
                "hiddenCpu", hiddenDegradationEnabled.get()
        );

        Map<String, Object> config = Map.of(
                "burnerThreads", burnerThreadCount,
                "cpuSpikeWorkIterations", cpuSpikeWorkIterations,
                "cpuSpikeDurationMs", cpuSpikeDurationMs,
                "hiddenWorkIterations", hiddenWorkIterations,
                "hiddenCooldownMs", hiddenCooldownMs
        );

        return ResponseEntity.ok(Map.of(
                "status", statusMsg,
                "port", System.getenv().getOrDefault("PORT", "unknown"),
                "states", states,
                "config", config
        ));
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

        return activeCount > 1 ? "MULTIPLE" : mode;
    }
}
