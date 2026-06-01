package com.truongquycode.apigatewayalb.controlplane;

import com.truongquycode.apigatewayalb.model.PercentileSnapshot;
import org.HdrHistogram.Histogram;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SlidingWindowManager {

	private static final long MAX_LATENCY_MS = 60000L;
	private static final long MAX_QUEUE_SIZE = 10000L;
	private static final int SIGNIFICANT_DIGITS = 2;

	private static final int WINDOW_SIZE = 100;
	private static final int GLOBAL_WIN_SIZE = 160;

	private final Object globalLock = new Object();

	private final ConcurrentHashMap<String, Histogram[]> latHistPairs = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Histogram[]> qHistPairs = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, AtomicInteger> latActiveIdx = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, AtomicInteger> qActiveIdx = new ConcurrentHashMap<>();

	private final Histogram[] globalPair = { new Histogram(1, MAX_LATENCY_MS, SIGNIFICANT_DIGITS),
			new Histogram(1, MAX_LATENCY_MS, SIGNIFICANT_DIGITS) };
	private final AtomicInteger globalActiveIdx = new AtomicInteger(0);

	public void addMetrics(String instanceId, double lat, double queue) {
		long latVal = Math.min(Math.max(1, (long) lat), MAX_LATENCY_MS);
		long qVal = Math.min(Math.max(1, (long) queue), MAX_QUEUE_SIZE);

		recordRotating(latHistPairs, latActiveIdx, instanceId, latVal, MAX_LATENCY_MS, WINDOW_SIZE);
		recordRotating(qHistPairs, qActiveIdx, instanceId, qVal, MAX_QUEUE_SIZE, WINDOW_SIZE);

		synchronized (globalLock) {
			int gi = globalActiveIdx.get();
			globalPair[gi].recordValue(latVal);
			if (globalPair[gi].getTotalCount() > GLOBAL_WIN_SIZE) {
				int next = 1 - gi;
				globalPair[next].reset();
				globalActiveIdx.set(next);
			}
		}
	}

	private void recordRotating(ConcurrentHashMap<String, Histogram[]> pairsMap,
			ConcurrentHashMap<String, AtomicInteger> idxMap, String instanceId, long value, long maxVal,
			int windowSize) {
		Histogram[] hists = pairsMap.computeIfAbsent(instanceId, k -> new Histogram[] {
				new Histogram(1, maxVal, SIGNIFICANT_DIGITS), new Histogram(1, maxVal, SIGNIFICANT_DIGITS) });
		AtomicInteger idx = idxMap.computeIfAbsent(instanceId, k -> new AtomicInteger(0));

		int active = idx.get();
		hists[active].recordValue(value);

		if (hists[active].getTotalCount() > windowSize) {
			int next = 1 - active;
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

	// global P5 cho cross-instance normalization
	// khi normalizeLatency dùng system-wide bounds, node luôn chậm
	// (như 8083) sẽ có nL cao hơn thay vì bị "normalize về 0.5" theo bounds
	// riêng của nó
	public double getSystemP5() {
		synchronized (globalLock) {
			int gi = globalActiveIdx.get();
			if (globalPair[gi].getTotalCount() == 0)
				return 5.0;
			return globalPair[gi].getValueAtPercentile(5.0);
		}
	}

	// global P95 cho cross-instance normalization
	public double getSystemP95() {
		synchronized (globalLock) {
			int gi = globalActiveIdx.get();
			if (globalPair[gi].getTotalCount() == 0)
				return 200.0;
			return globalPair[gi].getValueAtPercentile(95.0);
		}
	}

	public double getSystemP75() {
		synchronized (globalLock) {
			int gi = globalActiveIdx.get();
			if (globalPair[gi].getTotalCount() == 0)
				return 50.0;
			return globalPair[gi].getValueAtPercentile(75.0);
		}
	}

	public double getSystemP50() {
		synchronized (globalLock) {
			int gi = globalActiveIdx.get();
			if (globalPair[gi].getTotalCount() == 0)
				return 50.0;
			return globalPair[gi].getValueAtPercentile(50.0);
		}
	}

	public void resetAll() {
		latHistPairs.clear();
		qHistPairs.clear();
		latActiveIdx.clear();
		qActiveIdx.clear();
		globalPair[0].reset();
		globalPair[1].reset();
		globalActiveIdx.set(0);
	}
}