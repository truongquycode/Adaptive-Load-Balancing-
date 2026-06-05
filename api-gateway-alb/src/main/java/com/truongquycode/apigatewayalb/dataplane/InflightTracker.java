package com.truongquycode.apigatewayalb.dataplane;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class InflightTracker {
	private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
	private final AtomicInteger totalInflight = new AtomicInteger(0);

	public void increment(String instanceId) {
		AtomicInteger c = counts.get(instanceId);
		if (c == null) {
			c = counts.computeIfAbsent(instanceId, k -> new AtomicInteger(0));
		}
		c.incrementAndGet();
		totalInflight.incrementAndGet();
	}

	public void decrement(String instanceId) {
		AtomicInteger count = counts.get(instanceId);
		if (count == null)
			return;
		int oldVal = count.getAndUpdate(curr -> curr > 0 ? curr - 1 : 0);
		if (oldVal > 0) {
			totalInflight.getAndUpdate(curr -> curr > 0 ? curr - 1 : 0);
		}
	}

	public int getInflight(String instanceId) {
		AtomicInteger count = counts.get(instanceId);
		return count != null ? count.get() : 0;
	}

	public int getTotalInflight() {
		return totalInflight.get();
	}

	public void resetAll() {
		counts.clear();
		totalInflight.set(0);
	}
}