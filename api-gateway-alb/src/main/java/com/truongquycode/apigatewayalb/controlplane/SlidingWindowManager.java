package com.truongquycode.apigatewayalb.controlplane;

import com.truongquycode.apigatewayalb.model.PercentileSnapshot;
import org.HdrHistogram.Histogram;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SlidingWindowManager {

	// Xóa toàn bộ latHistograms, queueHistograms cũ — chỉ giữ rotating pairs
	private final ConcurrentHashMap<String, Histogram[]> latHistPairs = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Histogram[]> qHistPairs = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, AtomicInteger> latActiveIdx = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, AtomicInteger> qActiveIdx = new ConcurrentHashMap<>();

	// Global histogram cũng dùng rotating pair
	private final Histogram[] globalPair = { new Histogram(1, 60000, 2), new Histogram(1, 60000, 2) };
	private final AtomicInteger globalActiveIdx = new AtomicInteger(0);

	private static final int WINDOW_SIZE = 150;
	private static final int GLOBAL_WIN_SIZE = 450;

	public void addMetrics(String instanceId, double lat, double queue) {
		long latVal = Math.min(Math.max(1, (long) lat), 60000);
		long qVal = Math.min(Math.max(1, (long) queue), 10000);

		recordRotating(latHistPairs, latActiveIdx, instanceId, latVal, 60000, WINDOW_SIZE);
		recordRotating(qHistPairs, qActiveIdx, instanceId, qVal, 10000, WINDOW_SIZE);

		// Cập nhật global histogram
		int gi = globalActiveIdx.get();
		globalPair[gi].recordValue(latVal);
		if (globalPair[gi].getTotalCount() > GLOBAL_WIN_SIZE) {
			int next = 1 - gi;
			globalPair[next].reset();
			globalActiveIdx.set(next);
		}
	}

	private void recordRotating(ConcurrentHashMap<String, Histogram[]> pairsMap,
			ConcurrentHashMap<String, AtomicInteger> idxMap, String instanceId, long value, long maxVal,
			int windowSize) {

		Histogram[] hists = pairsMap.computeIfAbsent(instanceId,
				k -> new Histogram[] { new Histogram(1, maxVal, 2), new Histogram(1, maxVal, 2) });
		AtomicInteger idx = idxMap.computeIfAbsent(instanceId, k -> new AtomicInteger(0));

		int active = idx.get();
		hists[active].recordValue(value);

		if (hists[active].getTotalCount() > windowSize) {
			int next = 1 - active;
			// Chỉ thread đầu tiên thực hiện rotation thành công
			if (idx.compareAndSet(active, next)) {
				hists[next].reset();
			}
		}
	}

	public PercentileSnapshot getSnapshot(String instanceId) {
		Histogram[] lHists = latHistPairs.get(instanceId);
		Histogram[] qHists = qHistPairs.get(instanceId);

		if (lHists == null) {
			return new PercentileSnapshot(0.0, 50.0, 100.0, 10.0);
		}

		// Đọc từ histogram ACTIVE hiện tại
		int lIdx = latActiveIdx.getOrDefault(instanceId, new AtomicInteger(0)).get();
		Histogram lh = lHists[lIdx];

		if (lh.getTotalCount() == 0) {
			return new PercentileSnapshot(0.0, 50.0, 100.0, 10.0);
		}

		double qP99 = 10.0;
		if (qHists != null) {
			int qIdx = qActiveIdx.getOrDefault(instanceId, new AtomicInteger(0)).get();
			Histogram qh = qHists[qIdx];
			if (qh.getTotalCount() > 0) {
				qP99 = qh.getValueAtPercentile(99.0);
			}
		}

		return new PercentileSnapshot(lh.getValueAtPercentile(5.0), lh.getValueAtPercentile(50.0),
				lh.getValueAtPercentile(95.0), qP99);
	}

	public double getSystemP50() {
		int gi = globalActiveIdx.get();
		if (globalPair[gi].getTotalCount() == 0)
			return 50.0;
		return globalPair[gi].getValueAtPercentile(50.0);
	}
}