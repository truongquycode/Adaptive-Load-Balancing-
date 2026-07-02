package com.truongquycode.apigatewayalb.model;

public record ScoreBreakdown(String instanceId, double ewmaLatency, double normLatency, double normQueue,
		double normCpu, double baseScore, double pidPenalty, double finalScore, long updatedAtMs) {
	public ScoreBreakdown withFinalScore(double newFinalScore) {
		return new ScoreBreakdown(instanceId, ewmaLatency, normLatency, normQueue, normCpu, baseScore, pidPenalty,
				newFinalScore, updatedAtMs);
	}

	/**
	 * Làm mới timestamp mà không thay đổi giá trị score.
	 * Dùng khi poll metrics thành công nhưng chưa có latency sample thật trong cửa sổ
	 * hiện tại. Nhờ vậy score không bị đánh dấu stale chỉ vì hệ thống đang idle,
	 * đồng thời EWMA/histogram không bị kéo bởi latency giả lập.
	 */
	public ScoreBreakdown withUpdatedAt(long newUpdatedAtMs) {
		return new ScoreBreakdown(instanceId, ewmaLatency, normLatency, normQueue, normCpu, baseScore, pidPenalty,
				finalScore, newUpdatedAtMs);
	}

}
