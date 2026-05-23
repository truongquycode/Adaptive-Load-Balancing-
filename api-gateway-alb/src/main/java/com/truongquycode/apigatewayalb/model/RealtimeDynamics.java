package com.truongquycode.apigatewayalb.model;

public record RealtimeDynamics(
    double inflight,
    double queueGrowthRate,
    double latencySlope,
    double recentFailurePenalty
) {}