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

    /**
     * Cửa sổ đếm traffic thật dùng riêng cho DynamicWeightEngine.
     *
     * Lý do cần tách: MetricsPoller vẫn poll /api/alb-metrics khi hệ thống idle.
     * Nếu DynamicWeightEngine cứ lấy ScoreBreakdown mới nhất để tính EWM thì MCDM
     * sẽ học từ nhiễu nền CPU/latency idle. Bộ đếm này chỉ tăng khi backend báo
     * deltaCount > 0, tức là có request nghiệp vụ thật hoàn thành.
     */
    private final Object mcdmTrafficLock = new Object();
    private long completedSinceLastMcdmUpdate = 0L;
    private long mcdmTrafficWindowStartedAtMs = System.currentTimeMillis();
    private long lastRealTrafficAtMs = 0L;

    public record TrafficActivitySnapshot(
            long completedRequests,
            double actualRps,
            long windowMs,
            long idleMs
    ) {}

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
        resetMcdmTrafficWindow();
    }

    public void putCapacityWeight(String id, double capacityWeight) {
        if (!Double.isNaN(capacityWeight) && !Double.isInfinite(capacityWeight) && capacityWeight > 0.0) {
            capacityWeightMap.put(id, capacityWeight);
        }
    }

    public double getCapacityWeight(String id) {
        return capacityWeightMap.getOrDefault(id, 1.0);
    }

    /**
     * Ghi nhận số request nghiệp vụ thật hoàn thành giữa hai lần poll metrics.
     * Không gọi hàm này cho idle sample, counter reset hoặc poll lỗi.
     */
    public void recordCompletedRequestsForMcdm(double completedRequests) {
        if (Double.isNaN(completedRequests) || Double.isInfinite(completedRequests) || completedRequests <= 0.0) {
            return;
        }

        long completed = Math.max(1L, Math.round(completedRequests));
        long nowMs = System.currentTimeMillis();
        synchronized (mcdmTrafficLock) {
            completedSinceLastMcdmUpdate += completed;
            lastRealTrafficAtMs = nowMs;
        }
    }

    /**
     * DynamicWeightEngine gọi hàm này đúng lúc cập nhật trọng số.
     * Hàm trả về số request thật trong cửa sổ vừa qua rồi reset bộ đếm cho cửa
     * sổ kế tiếp.
     */
    public TrafficActivitySnapshot snapshotAndResetMcdmTrafficWindow(long nowMs) {
        synchronized (mcdmTrafficLock) {
            long windowMs = Math.max(1L, nowMs - mcdmTrafficWindowStartedAtMs);
            long completed = completedSinceLastMcdmUpdate;
            double rps = completed * 1000.0 / windowMs;
            long idleMs = lastRealTrafficAtMs <= 0L ? Long.MAX_VALUE : Math.max(0L, nowMs - lastRealTrafficAtMs);

            completedSinceLastMcdmUpdate = 0L;
            mcdmTrafficWindowStartedAtMs = nowMs;

            return new TrafficActivitySnapshot(completed, rps, windowMs, idleMs);
        }
    }

    public void resetMcdmTrafficWindow() {
        synchronized (mcdmTrafficLock) {
            completedSinceLastMcdmUpdate = 0L;
            mcdmTrafficWindowStartedAtMs = System.currentTimeMillis();
            lastRealTrafficAtMs = 0L;
        }
    }
}
