package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.util.MetricsCache;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

	private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
	private final MetricsCache cache;
	private final InflightTracker inflightTracker;

	// ── Tuning constants ─────────────────────────────────────────────────────
	private static final int INFLIGHT_HARD_CAP = 200;

	// OMEGA: cường độ penalty khi node vượt expected inflight.
	// Với power-law: ratio=1.5 → penalty=4.0*0.5^1.3=1.62 (đủ để override MCDM)
	// ratio=2.0 → penalty=4.0*1.0^1.3=4.0 (node bị loại khỏi pool)
	private static final double OMEGA = 4.0;

	// Exponent của power-law penalty (>1 = convex → gradient tăng nhanh khi quá
	// tải)
	private static final double PENALTY_EXPONENT = 1.3;

	// Score khi cache miss (chưa có data): 0.35 → capacity weights gần bằng nhau
	private static final double DEFAULT_SCORE = 0.35;
	private static final double SCORE_FLOOR = 0.05;

	// Warmup: trong WARMUP_MS đầu tiên sau khi instance lần đầu xuất hiện,
	// dùng capacity weight = 1.0 (equal) thay vì tính từ score.
	// Tránh routing concentration khi score chưa hội tụ → giảm error rate.
	private static final long WARMUP_MS = 15_000;

	// ── Smooth MCDM dùng cho capacity weight ─────────────────────────────────
	// Mục đích: ngăn score transient thấp (VD: 8082=0.07) kéo capWeight lên 14.3x
	// → routing 73% traffic → 8082 quá tải → oscillation → P99 cao.
	//
	// Asymmetric EMA:
	// Khi score TĂNG (node xấu đi): alpha=0.30 → phản ứng nhanh để tránh routing
	// Khi score GIẢM (node tốt lên): alpha=0.07 → cẩn thận, tránh flood ngược lại
	private final ConcurrentHashMap<String, Double> smoothedCapMcdm = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Long> firstSeenMs = new ConcurrentHashMap<>();

	// Round-robin counter cho warmup routing
	private final AtomicLong rrCounter = new AtomicLong(0);

	@Override
	public Mono<Response<ServiceInstance>> choose(Request request) {
		ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
		if (supplier == null)
			return Mono.just(new EmptyResponse());
		return supplier.get(request).next().map(this::selectBestInstance);
	}

	private Response<ServiceInstance> selectBestInstance(List<ServiceInstance> instances) {
		if (instances == null || instances.isEmpty())
			return new EmptyResponse();
		if (instances.size() == 1)
			return new DefaultResponse(instances.get(0));

		int n = instances.size();
		int totalInflight = inflightTracker.getTotalInflight();
		long now = System.currentTimeMillis();

		// ══ PASS 1: Thu thập score + cập nhật smoothedCapMcdm ══════════════
		record NodeInfo(ServiceInstance inst, double rawMcdm, double smoothedMcdm, int inflight, boolean inWarmup) {
		}
		List<NodeInfo> nodes = new ArrayList<>(n);

		for (ServiceInstance inst : instances) {
			String id = inst.getInstanceId();

			// Warmup detection: lần đầu thấy instance này
			long firstSeen = firstSeenMs.computeIfAbsent(id, k -> now);
			boolean inWarmup = (now - firstSeen) < WARMUP_MS;

			// Raw MCDM từ cache
			ScoreBreakdown bd = cache.getScore(id);
			double rawMcdm = (bd != null) ? Math.max(SCORE_FLOOR, bd.finalScore()) : DEFAULT_SCORE;

			// Cập nhật smoothed score dùng cho capacity weight
			double prevSmoothed = smoothedCapMcdm.getOrDefault(id, rawMcdm);
			// Asymmetric: nhạy với degradation (score tăng), chậm với recovery
			double ema = rawMcdm > prevSmoothed ? 0.30 : 0.07;
			double smoothed = ema * rawMcdm + (1.0 - ema) * prevSmoothed;
			smoothedCapMcdm.put(id, smoothed);

			int inflight = inflightTracker.getInflight(id);
			nodes.add(new NodeInfo(inst, rawMcdm, smoothed, inflight, inWarmup));
		}

		// ══ Warmup guard: nếu TẤT CẢ nodes còn trong warmup → round-robin ══
		// Tránh concentration routing trước khi score hội tụ → giảm error rate
		boolean allWarmup = nodes.stream().allMatch(NodeInfo::inWarmup);
		if (allWarmup) {
			int idx = (int) (rrCounter.getAndIncrement() % n);
			ServiceInstance selected = nodes.get(idx).inst();
			emitMetric(selected);
			return new DefaultResponse(selected);
		}

		// ══ PASS 2: Tính capacity-weighted fair share ════════════════════════
		//
		// Dùng SMOOTHED score + sqrt (thay vì linear inverse):
		//
		// Linear: score=0.07 → weight=14.3 score=0.52 → weight=1.92 ratio=7.4x ← NGUY
		// HIỂM
		// Sqrt: score=0.07 → weight=3.78 score=0.52 → weight=1.39 ratio=2.7x ← AN TOÀN
		//
		// Sqrt giảm extreme concentration từ 73% xuống ~46% cho node có score thấp.
		// Kết hợp với smoothed score: giả sử smoothed=0.25 → weight=2.0 (stable).

		double[] capWeights = new double[n];
		double sumCap = 0;
		for (int i = 0; i < n; i++) {
			// Nodes đang warmup: dùng weight=1.0 (không bias theo score chưa hội tụ)
			capWeights[i] = nodes.get(i).inWarmup() ? 1.0
					: 1.0 / Math.sqrt(Math.max(SCORE_FLOOR, nodes.get(i).smoothedMcdm()));
			sumCap += capWeights[i];
		}
		// Normalize → fairShare[i] là tỷ lệ inflight kỳ vọng của node i
		double[] fairShare = new double[n];
		for (int i = 0; i < n; i++)
			fairShare[i] = capWeights[i] / sumCap;

		// ══ PASS 3: Routing score = rawMcdm + power-law inflight penalty ═════
		//
		// Power-law penalty (thay vì log cũ):
		//
		// Cũ (log): ratio=1.5 → log(1+0.5)=0.41 ratio=2.0 → log(2)=0.69
		// Mới (^1.3): ratio=1.5 → 0.5^1.3=0.41 ratio=2.0 → 1.0^1.3=1.0
		// nhân OMEGA=4: 1.62 và 4.0 → MCDM bị override hoàn toàn
		//
		// Cơ chế:
		// - ratio < 1.0 (node nhận ít hơn fair share): penalty = 0, traffic tự do chảy
		// vào
		// - ratio = 1.5 (vượt 50%): penalty = 1.62 → vượt MCDM score điển hình →
		// redirect
		// - ratio = 2.0 (vượt 100%): penalty = 4.0 → node gần như bị loại khỏi pool
		// Kết quả: queue depth tự cân bằng ở ~1.5x expected → max queue giảm từ 258 →
		// ~80

		ServiceInstance best = null;
		double bestScore = Double.MAX_VALUE;
		ServiceInstance leastInflightFb = null; // fallback nếu tất cả ở HARD_CAP
		int minInflight = Integer.MAX_VALUE;

		for (int i = 0; i < n; i++) {
			NodeInfo node = nodes.get(i);
			if (node.inflight() >= INFLIGHT_HARD_CAP)
				continue;

			// Fallback tracking
			if (node.inflight() < minInflight) {
				minInflight = node.inflight();
				leastInflightFb = node.inst();
			}

			double expectedInflight = totalInflight * fairShare[i];

			// ratio = actual / expected (1.0 = đang nhận đúng phần)
			double ratio = expectedInflight > 0 ? (double) node.inflight() / expectedInflight : 0.0;

			// Power-law penalty: chỉ áp dụng khi vượt expected (ratio > 1)
			double inflightPenalty = OMEGA * Math.pow(Math.max(0.0, ratio - 1.0), PENALTY_EXPONENT);

			// Dùng rawMcdm (không smoothed) cho routing discrimination
			// Smoothed chỉ dùng cho capacity weight ở PASS 2
			double routingScore = node.rawMcdm() + inflightPenalty;

			if (routingScore < bestScore) {
				bestScore = routingScore;
				best = node.inst();
			}
		}

		// ══ Fallback: tất cả ở HARD_CAP ════════════════════════════════════
		if (best == null) {
			best = leastInflightFb != null ? leastInflightFb : instances.get(0);
			log.warn("All nodes at INFLIGHT_HARD_CAP={}", INFLIGHT_HARD_CAP);
		}

		emitMetric(best);
		return new DefaultResponse(best);
	}

	private void emitMetric(ServiceInstance inst) {
		Metrics.counter("alb.routing.selected", "backend", inst.getInstanceId(), "port", String.valueOf(inst.getPort()))
				.increment();
	}
}