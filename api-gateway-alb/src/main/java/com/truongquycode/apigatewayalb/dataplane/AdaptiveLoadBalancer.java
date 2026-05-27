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
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RequiredArgsConstructor
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    private final MetricsCache cache;
    private final InflightTracker inflightTracker;

    private static final int    INFLIGHT_HARD_CAP  = 80;
    private static final double DEGRADE_RATIO      = 2.0;
    private static final double DEGRADE_BUFFER     = 0.08;
    private static final double PROBE_PROBABILITY  = 0.03;
    private static final double OMEGA              = 1.2;

    // ── Stable baseline: chỉ cập nhật khi cluster THỰC SỰ cải thiện ──────────
    // Ngăn threshold tăng khi best node bị overload tạm thời
    // Initial = 0.30 (conservative estimate, sẽ tự hiệu chỉnh sau warmup)
    private volatile double clusterBestEma = 0.30;
    private static final double BASELINE_EMA_ALPHA = 0.08; // Rất chậm, chỉ cập nhật khi tốt hơn

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) return Mono.just(new EmptyResponse());
        return supplier.get(request).next().map(this::selectBestInstance);
    }

    private Response<ServiceInstance> selectBestInstance(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) return new EmptyResponse();
        if (instances.size() == 1) return new DefaultResponse(instances.get(0));

        int n = instances.size();
        int totalInflight = inflightTracker.getTotalInflight();

        // ══ PASS 1: Thu thập điểm số từ cache ══════════════════════════════
        record NodeInfo(ServiceInstance inst, double mcdm, int inflight) {}
        List<NodeInfo> nodes = new ArrayList<>(n);
        double instantMin = Double.MAX_VALUE;

        for (ServiceInstance inst : instances) {
            ScoreBreakdown bd = cache.getScore(inst.getInstanceId());
            double mcdm = (bd != null) ? bd.finalScore() : 0.5;
            int inflight = inflightTracker.getInflight(inst.getInstanceId());
            nodes.add(new NodeInfo(inst, mcdm, inflight));
            if (mcdm < instantMin) instantMin = mcdm;
        }

        // ══ Cập nhật baseline EMA — CHỈ KHI cluster thực sự cải thiện ══════
        // Quan trọng: Math.min() đảm bảo clusterBestEma không bao giờ TĂNG
        // khi instantMin tăng do overload tạm thời
        if (instantMin < clusterBestEma) {
            clusterBestEma = BASELINE_EMA_ALPHA * instantMin
                           + (1 - BASELINE_EMA_ALPHA) * clusterBestEma;
        }
        // effectiveBaseline = giá trị NHỎ HƠN giữa hiện tại và lịch sử tốt nhất
        // → Threshold không thể tăng khi best node bị overload
        double effectiveBaseline = Math.min(instantMin, clusterBestEma);
        double degradeThreshold  = effectiveBaseline * DEGRADE_RATIO + DEGRADE_BUFFER;

        // ══ PASS 2: Phân loại healthy/degraded ══════════════════════════════
        ServiceInstance best = null;
        double bestRealtime = Double.MAX_VALUE;
        List<ServiceInstance> degraded = new ArrayList<>();

        for (NodeInfo node : nodes) {
            if (node.inflight() >= INFLIGHT_HARD_CAP
                    || node.mcdm() >= degradeThreshold) {
                degraded.add(node.inst());
                continue;
            }

            // Healthy node: tính realtime score với inflight penalty có cap
            double excessShare = (totalInflight > 0)
                ? Math.max(0.0, (double) node.inflight() / totalInflight - 1.0 / n)
                : 0.0;
            // ── Cap inflightPenalty ở 0.40 để tránh oscillation ─────────────
            double inflightPenalty = Math.min(0.40, OMEGA * Math.log(1.0 + excessShare));
            double realtime = node.mcdm() + inflightPenalty;

            if (realtime < bestRealtime) {
                bestRealtime = realtime;
                best = node.inst();
            }
        }

        // ══ Fallback: chọn node tệ nhất ít nhất ════════════════════════════
        if (best == null) {
            best = nodes.stream()
                        .min(Comparator.comparingDouble(NodeInfo::mcdm))
                        .map(NodeInfo::inst)
                        .orElse(instances.get(0));
            log.warn("All nodes degraded (threshold={:.3f}) — routing to least-bad",
                     degradeThreshold);
        }

        // ══ Probe traffic 3% vào degraded nodes để detect recovery ══════════
        if (!degraded.isEmpty()
                && ThreadLocalRandom.current().nextDouble() < PROBE_PROBABILITY) {
            best = degraded.get(ThreadLocalRandom.current().nextInt(degraded.size()));
        }

        Metrics.counter("alb.routing.selected",
            "backend", best.getInstanceId(),
            "port", String.valueOf(best.getPort())).increment();

        return new DefaultResponse(best);
    }
}