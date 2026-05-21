package com.truongquycode.registrationservicealb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    // Các cờ hiệu cho từng kịch bản
    public static final AtomicBoolean originalChaos = new AtomicBoolean(false);
    public static final AtomicBoolean cpuSpikeEnabled = new AtomicBoolean(false);
    public static final AtomicBoolean asyncIoEnabled = new AtomicBoolean(false);

    private final List<Thread> cpuBurnerThreads = new ArrayList<>();

    // --- KỊCH BẢN CŨ: Vừa trễ vừa đốt CPU ---
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

    // --- TRƯỜNG HỢP 1: Tải CPU ngầm (CPU = 100%, Inflight = Thấp) ---
    @PostMapping("/cpu-spike/enable")
    public ResponseEntity<Map<String, Object>> enableCpuSpike() {
        if (cpuSpikeEnabled.compareAndSet(false, true)) {
            // Lấy số lượng core của máy/container để đốt sạch tài nguyên
            int cores = Runtime.getRuntime().availableProcessors();
            for (int i = 0; i < cores; i++) {
                Thread t = new Thread(() -> {
                    double dummy = 0;
                    while (cpuSpikeEnabled.get()) {
                        dummy += Math.sqrt(Math.random()); // Vòng lặp vắt kiệt CPU
                    }
                });
                t.start();
                cpuBurnerThreads.add(t);
            }
        }
        return statusResponse("BACKGROUND CPU SPIKE ENABLED");
    }

    @PostMapping("/cpu-spike/disable")
    public ResponseEntity<Map<String, Object>> disableCpuSpike() {
        cpuSpikeEnabled.set(false);
        cpuBurnerThreads.forEach(Thread::interrupt);
        cpuBurnerThreads.clear();
        return statusResponse("BACKGROUND CPU SPIKE DISABLED");
    }

    // --- TRƯỜNG HỢP 2: Lỗi độ trễ I/O (Latency cao, CPU = Thấp) ---
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

    // API tiện ích: Reset toàn bộ sự cố (Dùng trước mỗi lần chạy JMeter)
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetAll() {
        originalChaos.set(false);
        asyncIoEnabled.set(false);
        disableCpuSpike(); // Tái sử dụng hàm để dọn luồng ngầm
        return statusResponse("ALL CHAOS RESET");
    }

    private ResponseEntity<Map<String, Object>> statusResponse(String statusMsg) {
        return ResponseEntity.ok(Map.of(
            "status", statusMsg,
            "port", System.getenv("PORT") != null ? System.getenv("PORT") : "unknown",
            "states", Map.of(
                "original", originalChaos.get(),
                "cpuSpike", cpuSpikeEnabled.get(),
                "asyncIo", asyncIoEnabled.get()
            )
        ));
    }
}