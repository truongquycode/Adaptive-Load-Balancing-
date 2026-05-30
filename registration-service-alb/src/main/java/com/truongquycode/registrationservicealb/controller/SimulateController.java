package com.truongquycode.registrationservicealb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api")
public class SimulateController {

	private final Random random = new Random();
	private static final AtomicLong requestCount = new AtomicLong(0);
	private static final Semaphore DB_POOL = new Semaphore(50);

	@GetMapping("/simulate-call")
	public ResponseEntity<String> simulateInterServiceCommunication() throws InterruptedException {

		long count = requestCount.incrementAndGet();

		// JSON parse
		burnCpu(2500);

		// network
		Thread.sleep(10 + random.nextInt(20));
		DB_POOL.acquire();
		try {
			double p = random.nextDouble();

			if (p < 0.80) {
				// normal request
				Thread.sleep(20 + ThreadLocalRandom.current().nextInt(30));
			} else if (p < 0.95) {
				// hơi chậm
				Thread.sleep(80 + ThreadLocalRandom.current().nextInt(40));
			} else {
				// tail latency
				Thread.sleep(200 + ThreadLocalRandom.current().nextInt(150));
			}
		} finally {
			DB_POOL.release();
		}

		// business logic
		burnCpu(3000);

		return ResponseEntity.ok(String.format("Request #%d completed", count));
	}

	/**
	 * Hàm giả lập tải CPU. Số vòng lặp nhỏ (2000-3000) giúp tạo ra mức tiêu thụ CPU
	 * nền khoảng 15-30% khi chịu tải 300-400 RPS, phản ánh đúng thực tế của một
	 * microservice đang bận rộn.
	 */
	private void burnCpu(int iterations) {
		double dummy = 0;

		for (int i = 1; i <= iterations; i++) {
			dummy += Math.sqrt(i);
		}

		if (dummy == Double.MAX_VALUE) {
			System.out.println(dummy);
		}
	}
}