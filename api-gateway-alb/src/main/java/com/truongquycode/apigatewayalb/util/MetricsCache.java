package com.truongquycode.apigatewayalb.util;

import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MetricsCache {

    // capacity=4: tối thiểu chứa 3 instances tại loadFactor=0.75 mà không resize
    // Khai báo concrete type ConcurrentHashMap, không dùng interface Map
    private final ConcurrentHashMap<String, InstanceMetrics> metricsMap = new ConcurrentHashMap<>(4);
    private final ConcurrentHashMap<String, ScoreBreakdown>  scoreMap   = new ConcurrentHashMap<>(4);

    // --- Quản lý Metrics ---
    public void putMetrics(String id, InstanceMetrics metrics) { metricsMap.put(id, metrics); }
    // getAllMetrics: List.copyOf → immutable, exact-size array, không có slack capacity
    public List<InstanceMetrics> getAllMetrics() { return List.copyOf(metricsMap.values()); }

    // --- Quản lý Score ---
    public void putScore(String id, ScoreBreakdown score) { scoreMap.put(id, score); }
    public ScoreBreakdown getScore(String id) { return scoreMap.get(id); }

    // Tham số Set<String> thay vì Collection<String>:
    // - retainAll gọi activeIds.contains() cho từng key → Set đảm bảo O(1)
    // - Collection cho phép List → O(n) contains → O(n²) tổng thể
    public void removeStaleInstances(Set<String> activeIds) {
        metricsMap.keySet().retainAll(activeIds);
        scoreMap.keySet().retainAll(activeIds);
    }

    public void clearAll() {
        metricsMap.clear();
        scoreMap.clear();
    }
}