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
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class RegistrationController {

	private final Random random = new Random();
	private static final AtomicLong requestCount = new AtomicLong(0);

	public static class UserRegistrationDto {
		public String username;
		public String email;
		public String fullName;
		public String address;
		public String deviceId;
		public List<String> preferences;
	}

	// 2. Tạo endpoint POST
	@PostMapping("/register-user")
	public ResponseEntity<Map<String, Object>> registerUserPost(@RequestBody UserRegistrationDto payload,
			HttpServletRequest request) throws InterruptedException {

		long count = requestCount.incrementAndGet();
		int port = request.getServerPort();

		// Mô phỏng 1: CPU overhead cho việc Validate dữ liệu và Business Logic
		double dummy = 0;
		for (int i = 0; i < 8000; i++) {
			dummy += Math.sqrt(Math.random());
		}

		// Mô phỏng 2: I/O Database (Lưu user vào DB)
		// Lưu data thực tế tốn thời gian hơn đọc (GET), ta set khoảng 60-150ms
		int delay = 60 + random.nextInt(90);
		Thread.sleep(delay);

		return ResponseEntity.ok(Map.of("status", "success", "port", port, "requestId", count, "processedUser",
				payload.username, "latency", delay));
	}

	@GetMapping("/register")
	public ResponseEntity<String> register(HttpServletRequest request) throws InterruptedException {
		long count = requestCount.incrementAndGet();
		int port = request.getServerPort();
		int delay;

		if (ChaosController.originalChaos.get()) {
			// Kịch bản gốc: Vừa trễ mạng, vừa đốt CPU trên chính luồng HTTP
			delay = 100 + random.nextInt(200);
			long endTime = System.currentTimeMillis() + delay;
			double dummy = 0;
			while (System.currentTimeMillis() < endTime) {
				dummy += Math.sqrt(Math.random());
			}
			Thread.sleep(delay);

		} else if (ChaosController.asyncIoEnabled.get()) {
			// TRƯỜNG HỢP 2: Lỗi I/O phi đồng bộ (Sleep thuần túy, không tốn CPU)
			delay = 800;
			Thread.sleep(delay);

		} else if (ChaosController.cpuSpikeEnabled.get()) {
			// TRƯỜNG HỢP 1 (CPU SPIKE - KHI CỜ ĐƯỢC BẬT TRUE):
			// Mô phỏng việc các request đột nhiên trở nên phức tạp (như xuất báo cáo Excel,
			// mã hóa, xử lý ảnh)
			// Luồng HTTP phải cạnh tranh trực tiếp với nhóm luồng Chaos ngầm.
			double httpDummy = 0;
			for (int i = 0; i < 5000; i++) {
				httpDummy += Math.sqrt(Math.random());
			}
			delay = 10 + random.nextInt(40);
			Thread.sleep(delay);

		} else if (ChaosController.hiddenDegradationEnabled.get()) {
			// KỊCH BẢN "HIDDEN DEGRADATION":
			// CPU đang bị các burner thread chiếm 100% ngầm.
			// Mỗi HTTP request chỉ làm một lượng tính toán rất nhỏ
			// → Request hoàn thành trong 20-50ms (gần như bình thường)
			// → Inflight count gần như không đổi so với baseline
			// -------------------------------------------------------
			// LC: không thể phân biệt node này với node bình thường
			// vì inflight count tương đương → route đều 1/3
			//
			// Adaptive: MetricsPoller đọc process.cpu.usage = 90%+
			// MCDM tăng score của node này → P2C tránh
			// → Ít hơn 10% traffic đến node degraded
			double httpDummy = 0;
			for (int i = 0; i < 3000; i++) { // Chỉ 3000 iterations ≈ <1ms overhead
				httpDummy += Math.sqrt(Math.random());
			}
			delay = 20 + random.nextInt(30); // 20–50ms, gần bằng baseline
			Thread.sleep(delay);

		} else {
			// TRẠNG THÁI BÌNH THƯỜNG:
			// Tách biệt hoàn toàn, luồng xử lý siêu nhẹ, chỉ mô phỏng độ trễ mạng tự nhiên.
			delay = 10 + random.nextInt(40);
			Thread.sleep(delay);
		}

		return ResponseEntity.ok(String.format("Port: %d | Request #%d | Latency: %dms", port, count, delay));
	}
}