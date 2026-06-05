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
) {
	public ScoreBreakdown withFinalScore(double newFinalScore) {
        return new ScoreBreakdown(
            instanceId, ewmaLatency, normLatency, normQueue, normCpu,
            baseScore, pidPenalty, newFinalScore, updatedAtMs
        );
    }
	
}