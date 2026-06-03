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

		// 🔴 MÔ PHỎNG 1: TĂNG CPU OVERHEAD CỰC ĐẠI
		// Trong thực tế, lúc đăng ký user hệ thống phải chạy thuật toán băm mật khẩu
		// (BCrypt/Argon2).
		// 8.000 vòng lặp là quá nhẹ. Ta tăng lên 80.000 để mô phỏng thuật toán mã hóa
		// nặng.
		double dummy = 0;
		for (int i = 0; i < 20_000; i++) {
			dummy += Math.sqrt(Math.random());
		}

		// 🔴 MÔ PHỎNG 2: TĂNG ÁP LỰC RAM VÀ GARBAGE COLLECTOR (GC)
		// Tạo ra các Object rác để mô phỏng việc thư viện ORM (như Hibernate)
		// hoặc Jackson phải cấp phát bộ nhớ khi map JSON phức tạp.
		StringBuilder dummyMemory = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			dummyMemory.append(java.util.UUID.randomUUID().toString());
		}
		String trashData = dummyMemory.toString(); // Sinh rác để GC phải dọn

		// 🔴 MÔ PHỎNG 3: TĂNG TRỄ I/O (Database Transaction + Gửi Email)
		// Đăng ký user thường mất nhiều thời gian do phải mở Transaction ghi vào DB
		// và gọi API bên thứ 3 (như gửi Email OTP/Welcome).
		// Nâng delay từ 60-150ms lên thành 150-350ms.
		int delay = 150 + random.nextInt(200);
		Thread.sleep(delay);

		return ResponseEntity.ok(Map.of("status", "success", "port", port, "requestId", count, "processedUser",
				payload.username, "latency", delay, "trashLength", trashData.length() // Ngăn compiler tối ưu hóa biến
																						// rác
		));
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