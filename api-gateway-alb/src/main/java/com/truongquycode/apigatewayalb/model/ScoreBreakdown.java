package com.truongquycode.apigatewayalb.model;

public record ScoreBreakdown(
    String instanceId,
    double ewmaLatency,
    double normLatency,
    double normQueue,
    double normCpu,
    double baseScore,
    double pidPenalty,
    double finalScore,
    long updatedAtMs
) {}