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
	// Khai báo concrete type ConcurrentHashMap
	private final ConcurrentHashMap<String, InstanceMetrics> metricsMap = new ConcurrentHashMap<>(4);
	private final ConcurrentHashMap<String, ScoreBreakdown> scoreMap = new ConcurrentHashMap<>(4);
	private final ConcurrentHashMap<String, Double> capacityWeightMap = new ConcurrentHashMap<>(4);

	// --- Quản lý Metrics ---
	public void putMetrics(String id, InstanceMetrics metrics) {
		metricsMap.put(id, metrics);
	}

	public List<InstanceMetrics> getAllMetrics() {
		return List.copyOf(metricsMap.values());
	}

	// --- Quản lý Score ---
	public void putScore(String id, ScoreBreakdown score) {
		scoreMap.put(id, score);
	}

	public ScoreBreakdown getScore(String id) {
		return scoreMap.get(id);
	}

	public List<ScoreBreakdown> getAllScores() {
		return List.copyOf(scoreMap.values());
	}

	// Tham số Set<String>
	// - retainAll gọi activeIds.contains() cho từng key → Set đảm bảo O(1)
	public void removeStaleInstances(Set<String> activeIds) {
	    metricsMap.keySet().retainAll(activeIds);
	    scoreMap.keySet().retainAll(activeIds);
	    capacityWeightMap.keySet().retainAll(activeIds);
	}

	public void clearAll() {
	    metricsMap.clear();
	    scoreMap.clear();
	    capacityWeightMap.clear();
	}
	
	public void putCapacityWeight(String id, double capacityWeight) {
	    if (!Double.isNaN(capacityWeight) && !Double.isInfinite(capacityWeight) && capacityWeight > 0.0) {
	        capacityWeightMap.put(id, capacityWeight);
	    }
	}

	public double getCapacityWeight(String id) {
	    return capacityWeightMap.getOrDefault(id, 1.0);
	}
}