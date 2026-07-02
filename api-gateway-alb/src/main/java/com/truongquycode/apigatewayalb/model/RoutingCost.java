package com.truongquycode.apigatewayalb.model;

/**
 * Snapshot chi phí định tuyến của một backend tại thời điểm chọn request.
 *
 * healthCostRaw: finalScore từ ScoreCalculator (MCDM + PID + EWMA + score smoothing)
 * loadCostRaw: inflight / expectedInflight, đã chuẩn hóa theo capacity của container
 * healthCost/loadCost: rank-normalized trong cụm hiện tại, cùng thang [0,1]
 * absoluteLatencyCost: chi phí SLA tuyệt đối theo ewmaLatency, không phụ thuộc min-max trong cụm
 * finalCost: chi phí cuối cùng dùng cho P2C, càng thấp càng tốt
 */
public record RoutingCost(
        String instanceId,
        double healthCostRaw,
        double loadCostRaw,
        double healthCost,
        double loadCost,
        double absoluteLatencyCost,
        double finalCost,
        double capacityWeight,
        double capacityShare,
        double expectedInflight,
        int inflight,
        boolean hardExcluded,
        String reason,
        long updatedAtMs
) {
}
