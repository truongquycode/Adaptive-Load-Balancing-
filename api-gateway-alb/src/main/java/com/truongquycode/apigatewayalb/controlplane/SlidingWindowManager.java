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

	private record InstanceState(Histogram[] latHists, AtomicInteger latIdx, Histogram[] qHists, AtomicInteger qIdx) {
	}

	public record SystemSnapshot(double p5, double p75, double p95) {
	}

	public SystemSnapshot getSystemSnapshot() {
		synchronized (globalLock) {
			Histogram h = getSafeGlobalHistogram();
			if (h.getTotalCount() == 0)
				return new SystemSnapshot(5.0, 50.0, 200.0);
			return new SystemSnapshot(h.getValueAtPercentile(5.0), h.getValueAtPercentile(75.0),
					h.getValueAtPercentile(95.0));
		}
	}

	private final ConcurrentHashMap<String, InstanceState> instanceStates = new ConcurrentHashMap<>();

	public void addMetrics(String instanceId, double lat, double queue) {
		long latVal = Math.min(Math.max(1, (long) lat), MAX_LATENCY_MS);
		long qVal = Math.min(Math.max(1, (long) queue), MAX_QUEUE_SIZE);

		// 1 lookup duy nhất thay vì 4
		InstanceState s = instanceStates.computeIfAbsent(instanceId,
				k -> new InstanceState(
						new Histogram[] { new Histogram(1, MAX_LATENCY_MS, SIGNIFICANT_DIGITS),
								new Histogram(1, MAX_LATENCY_MS, SIGNIFICANT_DIGITS) },
						new AtomicInteger(0), new Histogram[] { new Histogram(1, MAX_QUEUE_SIZE, SIGNIFICANT_DIGITS),
								new Histogram(1, MAX_QUEUE_SIZE, SIGNIFICANT_DIGITS) },
						new AtomicInteger(0)));

		// ── Latency histogram ──────────────────────────────────────
		int lActive = s.latIdx().get();
		s.latHists()[lActive].recordValue(latVal);
		if (s.latHists()[lActive].getTotalCount() > WINDOW_SIZE) {
			int lNext = 1 - lActive;
			if (s.latIdx().compareAndSet(lActive, lNext)) {
				s.latHists()[lNext].reset();
			}
		}

		// ── Queue histogram ────────────────────────────────────────
		int qActive = s.qIdx().get();
		s.qHists()[qActive].recordValue(qVal);
		if (s.qHists()[qActive].getTotalCount() > WINDOW_SIZE) {
			int qNext = 1 - qActive;
			if (s.qIdx().compareAndSet(qActive, qNext)) {
				s.qHists()[qNext].reset();
			}
		}

		// ── Global histogram (vẫn giữ lock như cũ) ────────────────
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

	public PercentileSnapshot getSnapshot(String instanceId) {
		InstanceState s = instanceStates.get(instanceId); // 1 lookup thay vì 4

		if (s == null) {
			return new PercentileSnapshot(0.0, 50.0, 100.0, 10.0);
		}

		int lIdx = s.latIdx().get();
		Histogram lh = s.latHists()[lIdx];

		if (lh.getTotalCount() == 0) {
			return new PercentileSnapshot(0.0, 50.0, 100.0, 10.0);
		}

		double qP99 = 10.0;
		int qIdx = s.qIdx().get();
		Histogram qh = s.qHists()[qIdx];
		if (qh.getTotalCount() > 0) {
			qP99 = qh.getValueAtPercentile(99.0);
		}

		return new PercentileSnapshot(lh.getValueAtPercentile(5.0), lh.getValueAtPercentile(50.0),
				lh.getValueAtPercentile(95.0), qP99);
	}

	private Histogram getSafeGlobalHistogram() {
		int gi = globalActiveIdx.get();
		if (globalPair[gi].getTotalCount() >= 20) {
			return globalPair[gi];
		} else if (globalPair[1 - gi].getTotalCount() > 0) {
			return globalPair[1 - gi]; // Dùng histogram trước đó nếu cái hiện tại quá mới
		}
		return globalPair[gi];
	}

	public double getSystemP5() {
		synchronized (globalLock) {
			Histogram safeHist = getSafeGlobalHistogram();
			if (safeHist.getTotalCount() == 0)
				return 5.0;
			return safeHist.getValueAtPercentile(5.0);
		}
	}

	public double getSystemP95() {
		synchronized (globalLock) {
			Histogram safeHist = getSafeGlobalHistogram();
			if (safeHist.getTotalCount() == 0)
				return 200.0;
			return safeHist.getValueAtPercentile(95.0);
		}
	}

	public double getSystemP75() {
		synchronized (globalLock) {
			Histogram safeHist = getSafeGlobalHistogram();
			if (safeHist.getTotalCount() == 0)
				return 50.0;
			return safeHist.getValueAtPercentile(75.0);
		}
	}

	public void resetAll() {
		instanceStates.clear();
		globalPair[0].reset();
		globalPair[1].reset();
		globalActiveIdx.set(0);
	}
}