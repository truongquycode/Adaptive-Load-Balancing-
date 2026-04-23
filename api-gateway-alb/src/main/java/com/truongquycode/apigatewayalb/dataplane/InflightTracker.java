package com.truongquycode.apigatewayalb.dataplane;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class InflightTracker {
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final AtomicInteger totalInflight = new AtomicInteger(0); // Biến tổng O(1)

    public void increment(String instanceId) {
        counts.computeIfAbsent(instanceId, k -> new AtomicInteger(0)).incrementAndGet();
        totalInflight.incrementAndGet(); // Tăng biến tổng
    }

    public void decrement(String instanceId) {
        AtomicInteger count = counts.get(instanceId);
        if (count != null) {
            count.updateAndGet(curr -> curr > 0 ? curr - 1 : 0);
            totalInflight.updateAndGet(curr -> curr > 0 ? curr - 1 : 0); // Giảm biến tổng an toàn
        }
    }

    public int getInflight(String instanceId) {
        AtomicInteger count = counts.get(instanceId);
        return count != null ? count.get() : 0;
    }

    public int getTotalInflight() {
        return totalInflight.get();
    }
}