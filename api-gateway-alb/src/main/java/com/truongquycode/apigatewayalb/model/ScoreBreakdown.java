package com.truongquycode.apigatewayalb.model;

public record ScoreBreakdown(

    String instanceId,

    double ewmaLatency,

    double inflightScore,
    double queueGrowthScore,
    double latencySlopeScore,
    double failureScore,

    double pidPenalty,

    double finalScore,

    long updatedAtMs

) {}