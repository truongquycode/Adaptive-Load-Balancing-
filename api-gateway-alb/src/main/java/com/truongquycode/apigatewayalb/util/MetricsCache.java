package com.truongquycode.apigatewayalb.util;

import com.truongquycode.apigatewayalb.model.InstanceMetrics;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MetricsCache {
    // Lưu metrics thô cho DynamicWeightEngine (MCDM)
    private final Map<String, InstanceMetrics> metricsMap = new ConcurrentHashMap<>();
    
    // Lưu điểm số đã tính toán sẵn cho Data Plane (AdaptiveLoadBalancer)
    private final Map<String, ScoreBreakdown> scoreMap = new ConcurrentHashMap<>();

    // --- Quản lý Metrics ---
    public void putMetrics(String id, InstanceMetrics metrics) { metricsMap.put(id, metrics); }
    public InstanceMetrics getMetrics(String id) { return metricsMap.get(id); }
    public List<InstanceMetrics> getAllMetrics() { return new ArrayList<>(metricsMap.values()); }

    // --- Quản lý Score ---
    public void putScore(String id, ScoreBreakdown score) { scoreMap.put(id, score); }
    public ScoreBreakdown getScore(String id) { return scoreMap.get(id); }

    public void remove(String id) { 
        metricsMap.remove(id); 
        scoreMap.remove(id); 
    }
    
    public void removeStaleInstances(List<String> activeIds) {
        metricsMap.keySet().retainAll(activeIds);
        scoreMap.keySet().retainAll(activeIds);
    }
}