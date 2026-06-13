package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.util.MetricsCache;
import io.micrometer.core.instrument.Counter;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AdaptiveLoadBalancer v2 — Hybrid Adaptive Health + Capacity-aware Least-Load + Bounded Probe.
 *
 * Mục tiêu thiết kế:
 * 1) Khi hệ thống bình thường: không tự tạo nhiễu, phân phối mềm theo năng lực instance.
 * 2) Khi một backend chậm do I/O: phản ứng nhanh bằng inflight guard, không phải chờ latency hoàn thành.
 * 3) Khi hidden CPU/latency degradation: vẫn dùng MCDM + PID + EWMA để tránh node xấu dù inflight chưa tăng.
 * 4) Khi node phục hồi: không bỏ đói hoàn toàn, nhưng chỉ probe có kiểm soát thay vì luôn giữ floor lớn.
 *
 * Điểm khác bản cũ:
 * - Không dùng weighted-random floor 1/8 cho mọi node nữa vì làm node xấu vẫn nhận quá nhiều traffic.
 * - Thêm fast inflight guard để chống async I/O bottleneck, nơi Least-Connections thường rất mạnh.
 * - Chọn cuối bằng Power-of-Two Choices trong nhóm node khỏe; nếu score gần nhau thì chọn node ít tải hơn.
 */
@Slf4j
@RequiredArgsConstructor
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    private final MetricsCache cache;
    private final InflightTracker inflightTracker;

    // ===== Core safety / defaults =====
    private static final double SCORE_FLOOR = 0.05;
    private static final double DEFAULT_SCORE = 0.35;
    private static final double UNHEALTHY_SCORE_CUTOFF = 2.0;
    private static final long WARMUP_MS = 5_000;

    // ===== Inflight fast path =====
    // Hard cap tuyệt đối vẫn giữ làm safety net, nhưng quyết định chính dùng relative guard bên dưới.
    private static final int INFLIGHT_HARD_CAP = 180;
    private static final double MIN_EXPECTED_INFLIGHT = 3.0;

    // Phạt realtime theo inflight. Cao hơn bản cũ để async-I/O bottleneck bị tránh sớm hơn.
    private static final double OMEGA_REL = 0.018;
    private static final double OMEGA_ABS = 0.60;
    private static final double PENALTY_EXPONENT = 1.5;
    private static final int LOW_INFLIGHT_THRESHOLD = 15;
    private static final double LOW_INFLIGHT_PENALTY_FACTOR = 0.25;

    // Relative overload gate: nếu node vượt xa node nhàn nhất và vượt expected share thì tạm loại.
    private static final int RELATIVE_INFLIGHT_GAP_CUTOFF = 25;
    private static final double EXPECTED_INFLIGHT_CUTOFF_RATIO = 1.60;

    // ===== Degradation gate =====
    // Ngưỡng tương đối + tuyệt đối để tránh overfit theo một kịch bản.
    private static final double SEVERE_SCORE_GAP = 0.45;
    private static final double SEVERE_SCORE_RATIO = 2.20;
    private static final double SEVERE_LATENCY_RATIO = 2.50;
    private static final double SEVERE_LATENCY_GAP_MS = 180.0;

    // ===== Selection =====
    private static final double SCORE_CLOSE_EPS = 0.08;

    // Bounded probing: node bị loại vẫn được kiểm tra định kỳ để phát hiện recovery.
    private static final long DEGRADED_PROBE_INTERVAL_MS = 2_000;
    private static final double PROBE_PROBABILITY_PER_REQUEST = 0.02;

    // Low-load guard để tránh adaptive tự khuếch đại nhiễu nhỏ.
    private static final int LOW_LOAD_STABILITY_INFLIGHT = 20;
    private static final double LOW_LOAD_LATENCY_SPREAD_MS = 80.0;
    private static final double LOW_LOAD_SCORE_SPREAD = 0.12;
    private static final double LOW_LOAD_CPU_MAX = 0.20;

    private static final ConcurrentHashMap<String, Long> firstSeenMs = new ConcurrentHashMap<>();
    private static final AtomicLong rrCounter = new AtomicLong(0);
    private static final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastSelectedMs = new ConcurrentHashMap<>();

    /** Snapshot đã tính cho mỗi node trong một lần choose(). */
    private record NodeInfo(
            ServiceInstance inst,
            double rawScore,
            double ewmaLatency,
            double normCpu,
            int inflight,
            boolean inWarmup,
            boolean hasMetrics,
            double capacityWeight,
            double capacityShare,
            double expectedInflight,
            double routingScore,
            boolean relativeOverloaded,
            boolean degraded
    ) {}

    public static void resetStaticState() {
        firstSeenMs.clear();
        rrCounter.set(0);
        counterCache.clear();
        lastSelectedMs.clear();
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) {
            return Mono.just(new EmptyResponse());
        }
        return supplier.get(request).next().map(this::selectBestInstance);
    }

    private Response<ServiceInstance> selectBestInstance(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return new EmptyResponse();
        }
        if (instances.size() == 1) {
            emitMetric(instances.get(0));
            return new DefaultResponse(instances.get(0));
        }

        long now = System.currentTimeMillis();
        int n = instances.size();
        int totalInflight = inflightTracker.getTotalInflight();

        // Pass 1: đọc metric/cache/inflight và capacity weight.
        List<NodeInfo> initial = new ArrayList<>(n);
        double sumCapacity = 0.0;
        int minInflight = Integer.MAX_VALUE;
        boolean allWarmup = true;

        for (ServiceInstance inst : instances) {
            String id = inst.getInstanceId();
            long firstSeen = firstSeenMs.computeIfAbsent(id, k -> now);
            boolean inWarmup = (now - firstSeen) < WARMUP_MS;
            if (!inWarmup) {
                allWarmup = false;
            }

            ScoreBreakdown bd = cache.getScore(id);
            double rawScore = (bd != null) ? Math.max(SCORE_FLOOR, bd.finalScore()) : DEFAULT_SCORE;
            double ewmaLatency = (bd != null) ? bd.ewmaLatency() : Double.NaN;
            double normCpu = (bd != null) ? bd.normCpu() : 0.0;
            int inflight = inflightTracker.getInflight(id);
            minInflight = Math.min(minInflight, inflight);

            double cap = capacityWeightOf(inst);
            sumCapacity += cap;

            initial.add(new NodeInfo(inst, rawScore, ewmaLatency, normCpu, inflight,
                    inWarmup, bd != null, cap, 0.0, 0.0, rawScore, false, false));
        }

        if (allWarmup) {
            ServiceInstance selected = roundRobin(instances);
            emitMetric(selected);
            return new DefaultResponse(selected);
        }

        // Pass 2: tính routingScore có capacity-aware inflight penalty.
        List<NodeInfo> scored = new ArrayList<>(n);
        double bestRoutingScore = Double.POSITIVE_INFINITY;
        double bestLatency = Double.POSITIVE_INFINITY;
        double minFiniteLatency = Double.POSITIVE_INFINITY;
        double maxFiniteLatency = 0.0;
        double minScore = Double.POSITIVE_INFINITY;
        double maxScore = 0.0;
        double maxCpu = 0.0;

        for (NodeInfo node : initial) {
            double capShare = node.capacityWeight() / Math.max(sumCapacity, 1.0);
            double expected = Math.max(MIN_EXPECTED_INFLIGHT, totalInflight * capShare);

            double relPenalty = OMEGA_REL * Math.max(0.0, node.inflight() - minInflight);
            double excessRatio = (expected > 0.0) ? ((double) node.inflight() / expected) - 1.0 : 0.0;
            double absPenalty = 0.0;
            if (excessRatio > 0.0) {
                double factor = (totalInflight < LOW_INFLIGHT_THRESHOLD) ? LOW_INFLIGHT_PENALTY_FACTOR : 1.0;
                absPenalty = OMEGA_ABS * factor * Math.pow(excessRatio, PENALTY_EXPONENT);
            }

            boolean relativeOverloaded = node.inflight() > minInflight + RELATIVE_INFLIGHT_GAP_CUTOFF
                    && node.inflight() > expected * EXPECTED_INFLIGHT_CUTOFF_RATIO;

            double routingScore = node.rawScore() + relPenalty + absPenalty;
            NodeInfo s = new NodeInfo(node.inst(), node.rawScore(), node.ewmaLatency(), node.normCpu(), node.inflight(),
                    node.inWarmup(), node.hasMetrics(), node.capacityWeight(), capShare, expected,
                    routingScore, relativeOverloaded, false);
            scored.add(s);

            bestRoutingScore = Math.min(bestRoutingScore, routingScore);
            if (Double.isFinite(node.ewmaLatency()) && node.ewmaLatency() > 1.0) {
                bestLatency = Math.min(bestLatency, node.ewmaLatency());
                minFiniteLatency = Math.min(minFiniteLatency, node.ewmaLatency());
                maxFiniteLatency = Math.max(maxFiniteLatency, node.ewmaLatency());
            }
            minScore = Math.min(minScore, routingScore);
            maxScore = Math.max(maxScore, routingScore);
            maxCpu = Math.max(maxCpu, node.normCpu());
        }

        // Low-load stable guard: khi mọi node gần như ngang nhau thì không nên adaptive quá mức.
        if (isLowLoadStable(totalInflight, minFiniteLatency, maxFiniteLatency, minScore, maxScore, maxCpu)) {
            ServiceInstance selected = roundRobin(instances);
            emitMetric(selected);
            return new DefaultResponse(selected);
        }

        // Pass 3: phân loại normal/degraded.
        List<NodeInfo> normal = new ArrayList<>(n);
        List<NodeInfo> degraded = new ArrayList<>(n);
        ServiceInstance fallbackLeastInflight = null;
        int fallbackMinInflight = Integer.MAX_VALUE;

        for (NodeInfo node : scored) {
            if (node.inflight() < fallbackMinInflight) {
                fallbackMinInflight = node.inflight();
                fallbackLeastInflight = node.inst();
            }

            if (node.inflight() >= INFLIGHT_HARD_CAP || node.rawScore() >= UNHEALTHY_SCORE_CUTOFF) {
                degraded.add(markDegraded(node));
                continue;
            }

            boolean severeScore = node.routingScore() > bestRoutingScore + SEVERE_SCORE_GAP
                    && node.routingScore() > bestRoutingScore * SEVERE_SCORE_RATIO;

            boolean severeLatency = Double.isFinite(node.ewmaLatency()) && Double.isFinite(bestLatency)
                    && node.ewmaLatency() > bestLatency * SEVERE_LATENCY_RATIO
                    && (node.ewmaLatency() - bestLatency) > SEVERE_LATENCY_GAP_MS;

            if (node.relativeOverloaded() || severeScore || severeLatency) {
                degraded.add(markDegraded(node));
            } else {
                normal.add(node);
            }
        }

        // Nếu tất cả bị đánh dấu degraded, không được fail cứng; quay về least-inflight để giữ availability.
        if (normal.isEmpty()) {
            // Probe/availability fallback: chọn node ít inflight nhất thay vì random.
            ServiceInstance selected = (fallbackLeastInflight != null) ? fallbackLeastInflight : instances.get(0);
            emitMetric(selected);
            return new DefaultResponse(selected);
        }

        // Probe node degraded định kỳ để phát hiện recovery. Không dùng floor xác suất lớn như bản cũ.
        NodeInfo probe = maybeProbe(degraded, now);
        if (probe != null) {
            emitMetric(probe.inst());
            return new DefaultResponse(probe.inst());
        }

        // Chọn cuối bằng Power-of-Two Choices trong nhóm node khỏe.
        NodeInfo selected = chooseByP2C(normal, totalInflight);
        emitMetric(selected.inst());
        return new DefaultResponse(selected.inst());
    }

    private boolean isLowLoadStable(int totalInflight, double minLat, double maxLat,
                                    double minScore, double maxScore, double maxCpu) {
        if (totalInflight > LOW_LOAD_STABILITY_INFLIGHT) {
            return false;
        }
        double latencySpread = (Double.isFinite(minLat) && Double.isFinite(maxLat)) ? (maxLat - minLat) : 0.0;
        double scoreSpread = maxScore - minScore;
        return latencySpread < LOW_LOAD_LATENCY_SPREAD_MS
                && scoreSpread < LOW_LOAD_SCORE_SPREAD
                && maxCpu < LOW_LOAD_CPU_MAX;
    }

    private NodeInfo markDegraded(NodeInfo n) {
        return new NodeInfo(n.inst(), n.rawScore(), n.ewmaLatency(), n.normCpu(), n.inflight(), n.inWarmup(),
                n.hasMetrics(), n.capacityWeight(), n.capacityShare(), n.expectedInflight(), n.routingScore(),
                n.relativeOverloaded(), true);
    }

    private NodeInfo maybeProbe(List<NodeInfo> degraded, long now) {
        if (degraded.isEmpty()) {
            return null;
        }
        for (NodeInfo node : degraded) {
            if (node.inflight() >= INFLIGHT_HARD_CAP) {
                continue;
            }
            long last = lastSelectedMs.getOrDefault(node.inst().getInstanceId(), 0L);
            if ((now - last) >= DEGRADED_PROBE_INTERVAL_MS
                    && ThreadLocalRandom.current().nextDouble() < PROBE_PROBABILITY_PER_REQUEST) {
                return node;
            }
        }
        return null;
    }

    private NodeInfo chooseByP2C(List<NodeInfo> candidates, int totalInflight) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        NodeInfo a = candidates.get(rnd.nextInt(candidates.size()));
        NodeInfo b = candidates.get(rnd.nextInt(candidates.size()));
        if (candidates.size() > 1) {
            // Tránh chọn cùng một node hai lần nếu có thể.
            int guard = 0;
            while (a.inst().getInstanceId().equals(b.inst().getInstanceId()) && guard++ < 3) {
                b = candidates.get(rnd.nextInt(candidates.size()));
            }
        }
        return better(a, b, totalInflight);
    }

    private NodeInfo better(NodeInfo a, NodeInfo b, int totalInflight) {
        double scoreDiff = Math.abs(a.routingScore() - b.routingScore());
        if (scoreDiff <= SCORE_CLOSE_EPS) {
            double loadA = normalizedLoad(a, totalInflight);
            double loadB = normalizedLoad(b, totalInflight);
            if (Math.abs(loadA - loadB) > 0.05) {
                return loadA <= loadB ? a : b;
            }
            // Nếu score và load đều gần nhau, ưu tiên capacity lớn hơn để tận dụng tài nguyên.
            return a.capacityWeight() >= b.capacityWeight() ? a : b;
        }
        return a.routingScore() <= b.routingScore() ? a : b;
    }

    private double normalizedLoad(NodeInfo node, int totalInflight) {
        double expected = Math.max(MIN_EXPECTED_INFLIGHT, totalInflight * node.capacityShare());
        return node.inflight() / expected;
    }

    private ServiceInstance roundRobin(List<ServiceInstance> instances) {
        int idx = (int) (Math.floorMod(rrCounter.getAndIncrement(), instances.size()));
        return instances.get(idx);
    }

    private double capacityWeightOf(ServiceInstance inst) {
        double weight = cache.getCapacityWeight(inst.getInstanceId());
        return Math.max(0.1, weight);
    }

    private void emitMetric(ServiceInstance inst) {
        lastSelectedMs.put(inst.getInstanceId(), System.currentTimeMillis());
        counterCache.computeIfAbsent(inst.getInstanceId(), k -> Metrics.counter(
                "alb.routing.selected",
                "backend", inst.getInstanceId(),
                "port", String.valueOf(inst.getPort())
        )).increment();
    }
}
