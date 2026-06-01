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
    public ResponseEntity<String> simulateInterServiceCommunication()
            throws InterruptedException {
        long count = requestCount.incrementAndGet();

        // ── CHAOS BRANCH 1: I/O Bottleneck (async-io) ─────────────────────────
        // Kịch bản: database connection pool cạn kiệt, mỗi request phải chờ
        // lock hoặc connection → latency tăng vọt, CPU KHÔNG tăng.
        // → Test xem Adaptive có phát hiện latency spike mà không có CPU signal?
        // Đáp án: CÓ — EWMA latency + PID penalty phát hiện trong 200–400ms.
        // LeastConn: phát hiện qua inflight tăng, nhưng chậm hơn 0.8–1.5s.
        if (ChaosController.asyncIoEnabled.get()) {
            int ioDelay = 600 + random.nextInt(200); // 600–800ms per request
            Thread.sleep(ioDelay);
            return ResponseEntity.ok(String.format(
                "I/O Bottleneck simulated | Request #%d | I/O wait: %dms",
                count, ioDelay
            ));
        }

        // ── CHAOS BRANCH 2: CPU Overload (cpu-spike + normal burnCpu) ─────────
        // Kịch bản: background jobs (cron, GC, cache rebuild) đốt hết CPU.
        // Burner threads từ ChaosController + burnCpu trong HTTP thread tranh CPU.
        // → process.cpu.usage tăng lên 90–100% dù per-request logic không đổi.
        // → Adaptive phát hiện qua MCDM gamma (CPU weight ~44%) trong 200ms.
        // → LeastConn: không thấy CPU, chỉ thấy inflight khi latency tăng (chậm hơn).
        //
        // Không cần thêm code: burnCpu bên dưới sẽ tranh với burner threads.
        // Hiệu ứng tự nhiên: mỗi iteration của sqrt(random()) mất lâu hơn do CPU contention.

        // ── CHAOS BRANCH 3: Heavy Request (originalChaos) ─────────────────────
        // Kịch bản: một số loại request đột ngột trở nên "nặng" hơn bình thường
        // (VD: N+1 query, report generation, large payload processing).
        // Mỗi request tốn nhiều CPU VÀ có thêm delay → double penalty.
        if (ChaosController.originalChaos.get()) {
            // 3x CPU burn bình thường + thêm network delay
            burnCpu(21000);  // 7000 × 3
            int heavyDelay = 80 + random.nextInt(120); // 80–200ms
            Thread.sleep(heavyDelay);
            burnCpu(27000);  // 9000 × 3
            return ResponseEntity.ok(String.format(
                "Heavy request completed | Request #%d | Extra delay: %dms",
                count, heavyDelay
            ));
        }

        // ── NORMAL PATH ────────────────────────────────────────────────────────
        // hiddenDegradationEnabled: burner threads chạy ngầm, không cần xử lý riêng.
        // burnCpu bên dưới sẽ tự chậm lại khi tranh CPU với burner threads.

        // Pha 1: CPU parse / serialize
        burnCpu(7_000);

        // Pha 2: Network I/O (không tốn CPU)
        int networkDelay = 15 + random.nextInt(35); // 15–50ms
        Thread.sleep(networkDelay);

        // Pha 3: CPU deserialize / business logic
        burnCpu(9_000);

        return ResponseEntity.ok(String.format(
            "Inter-service call completed | Request #%d | Network I/O: %dms",
            count, networkDelay
        ));
    }

    private void burnCpu(int iterations) {
        double dummy = 0;
        for (int i = 0; i < iterations; i++) {
            dummy += Math.sqrt(Math.random());
        }
    }
}