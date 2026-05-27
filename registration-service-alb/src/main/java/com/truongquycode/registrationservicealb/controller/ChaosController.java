package com.truongquycode.registrationservicealb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    public static final AtomicBoolean originalChaos            = new AtomicBoolean(false);
    public static final AtomicBoolean cpuSpikeEnabled          = new AtomicBoolean(false);
    public static final AtomicBoolean asyncIoEnabled           = new AtomicBoolean(false);
    public static final AtomicBoolean hiddenDegradationEnabled = new AtomicBoolean(false);

    // ── FIX ROOT CAUSE 2: Tách riêng 2 list, dùng CopyOnWriteArrayList (thread-safe) ──
    private final List<Thread> cpuSpikeThreads  = new CopyOnWriteArrayList<>();
    private final List<Thread> hiddenThreads    = new CopyOnWriteArrayList<>();

    // ── FIX ROOT CAUSE 1: Giới hạn số thread theo container CPU, không dùng host CPU ──
    // Docker container 8083 có 1.0 CPU → tối đa 2 burner threads là đủ đốt 100%
    // Dùng hằng số thay vì availableProcessors() để kiểm soát chính xác
    private static final int BURNER_THREAD_COUNT = 1;

    // ─── KỊCH BẢN CŨ: Vừa trễ vừa đốt CPU ───────────────────────────────────────────
    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enableOriginal() {
        originalChaos.set(true);
        return statusResponse("ORIGINAL CHAOS ENABLED");
    }

    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disableOriginal() {
        originalChaos.set(false);
        return statusResponse("ORIGINAL CHAOS DISABLED");
    }

    // ─── CPU SPIKE ────────────────────────────────────────────────────────────────────
    @PostMapping("/cpu-spike/enable")
    public ResponseEntity<Map<String, Object>> enableCpuSpike() {
        if (cpuSpikeEnabled.compareAndSet(false, true)) {
            for (int i = 0; i < BURNER_THREAD_COUNT; i++) {
                Thread t = new Thread(() -> {
                    double dummy = 0;
                    // ── FIX: check cả isInterrupted() để interrupt() có tác dụng ──
                    while (cpuSpikeEnabled.get() && !Thread.currentThread().isInterrupted()) {
                        dummy += Math.sqrt(Math.random());
                    }
                });
                t.setName("cpu-spike-burner-" + i);
                t.start();
                cpuSpikeThreads.add(t);
            }
        }
        return statusResponse("BACKGROUND CPU SPIKE ENABLED");
    }

    @PostMapping("/cpu-spike/disable")
    public ResponseEntity<Map<String, Object>> disableCpuSpike() {
        cpuSpikeEnabled.set(false);
        stopThreads(cpuSpikeThreads); // ← chỉ dừng cpuSpikeThreads, không đụng hiddenThreads
        return statusResponse("BACKGROUND CPU SPIKE DISABLED");
    }

    // ─── ASYNC IO ─────────────────────────────────────────────────────────────────────
    @PostMapping("/async-io/enable")
    public ResponseEntity<Map<String, Object>> enableAsyncIo() {
        asyncIoEnabled.set(true);
        return statusResponse("ASYNC I/O LATENCY ENABLED");
    }

    @PostMapping("/async-io/disable")
    public ResponseEntity<Map<String, Object>> disableAsyncIo() {
        asyncIoEnabled.set(false);
        return statusResponse("ASYNC I/O LATENCY DISABLED");
    }

    // ─── HIDDEN DEGRADATION ──────────────────────────────────────────────────────────
    @PostMapping("/hidden/enable")
    public ResponseEntity<Map<String, Object>> enableHidden() {
        if (hiddenDegradationEnabled.compareAndSet(false, true)) {
            for (int i = 0; i < BURNER_THREAD_COUNT; i++) {
                Thread t = new Thread(() -> {
                    double dummy = 0;
                    // ── FIX: check cả isInterrupted() ──
                    while (hiddenDegradationEnabled.get()
                    	       && !Thread.currentThread().isInterrupted()) {

                    	    for (int j = 0; j < 200000; j++) {
                    	        dummy += Math.sqrt(Math.random());
                    	    }

                    	    Thread.onSpinWait();

                    	    try {
                    	        Thread.sleep(1);
                    	    } catch (InterruptedException e) {
                    	        Thread.currentThread().interrupt();
                    	        break;
                    	    }
                    	}
                });
                t.setDaemon(true); // daemon: JVM shutdown không bị block
                t.setName("hidden-burner-" + i);
                t.start();
                hiddenThreads.add(t); // ← vào list riêng, không bị resetAll() xóa nhầm
            }
        }
        return statusResponse("HIDDEN DEGRADATION ENABLED");
    }

    @PostMapping("/hidden/disable")
    public ResponseEntity<Map<String, Object>> disableHidden() {
        hiddenDegradationEnabled.set(false);
        stopThreads(hiddenThreads); // ← chỉ dừng hiddenThreads
        return statusResponse("HIDDEN DEGRADATION DISABLED");
    }

    // ─── RESET TẤT CẢ ────────────────────────────────────────────────────────────────
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetAll() {
        // ── FIX ROOT CAUSE 2: Gọi đúng method cho từng loại ──
        originalChaos.set(false);
        asyncIoEnabled.set(false);
        hiddenDegradationEnabled.set(false);
        cpuSpikeEnabled.set(false);
        // Dừng threads của từng list riêng biệt
        stopThreads(hiddenThreads);
        stopThreads(cpuSpikeThreads);
        return statusResponse("ALL CHAOS RESET");
    }

    // ─── HELPER: Dừng và clear một list threads ──────────────────────────────────────
    private void stopThreads(List<Thread> threads) {
        // Snapshot để tránh concurrent modification
        List<Thread> snapshot = new ArrayList<>(threads);
        threads.clear(); // Xóa references ngay

        // Interrupt trước để báo hiệu
        snapshot.forEach(Thread::interrupt);

        // Join với timeout ngắn (threads thoát rất nhanh vì flag đã false)
        // Chạy song song để tránh blocking HTTP response quá lâu
        snapshot.forEach(t -> {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                
            }
        });
    }

    private ResponseEntity<Map<String, Object>> statusResponse(String statusMsg) {
        return ResponseEntity.ok(Map.of(
            "status", statusMsg,
            "port",   System.getenv("PORT") != null ? System.getenv("PORT") : "unknown",
            "states", Map.of(
                "original",          originalChaos.get(),
                "cpuSpike",          cpuSpikeEnabled.get(),
                "asyncIo",           asyncIoEnabled.get(),
                "hiddenDegradation", hiddenDegradationEnabled.get()
            )
        ));
    }
}