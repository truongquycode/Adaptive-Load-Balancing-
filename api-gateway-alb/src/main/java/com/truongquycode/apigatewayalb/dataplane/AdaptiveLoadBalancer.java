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

    // Không còn dùng threshold tuyệt đối
    private static final int    INFLIGHT_HARD_CAP  = 80;
    // Node bị "near-exclude" nếu score > (minScore × RATIO + BUFFER)
    // RATIO=2.0: node tệ hơn 2x so với node tốt nhất → degraded
    // BUFFER=0.15: tránh exclude khi scores gần nhau (normal variance)
    private static final double DEGRADE_RATIO      = 2.0;
    private static final double DEGRADE_BUFFER     = 0.08;
    // Probe traffic để phát hiện recovery
    private static final double PROBE_PROBABILITY  = 0.03;

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) return Mono.just(new EmptyResponse());
        return supplier.get(request).next().map(this::selectBestInstance);
    }

    private Response<ServiceInstance> selectBestInstance(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) return new EmptyResponse();
        if (instances.size() == 1)  return new DefaultResponse(instances.get(0));

        int n = instances.size();
        int totalInflight = inflightTracker.getTotalInflight();

        // ══ PASS 1: Thu thập MCDM scores (health signal, 1s lag) ═══════════
        record NodeInfo(ServiceInstance inst, double mcdm, int inflight) {}
        List<NodeInfo> nodes = new ArrayList<>(n);
        double minMcdm = Double.MAX_VALUE;

        for (ServiceInstance inst : instances) {
            ScoreBreakdown bd = cache.getScore(inst.getInstanceId());
            double mcdm = (bd != null) ? bd.finalScore() : 0.5;
            int inflight = inflightTracker.getInflight(inst.getInstanceId());
            nodes.add(new NodeInfo(inst, mcdm, inflight));
            if (mcdm < minMcdm) minMcdm = mcdm;
        }

        // ══ PASS 2: Phân loại healthy/degraded theo threshold TƯƠNG ĐỐI ════
        // threshold thay đổi theo điều kiện thực tế của cluster
        // → tự hiệu chỉnh với mọi điều kiện mạng, mọi kịch bản chaos
        double degradeThreshold = minMcdm * DEGRADE_RATIO + DEGRADE_BUFFER;

        ServiceInstance best = null;
        double bestRealtime   = Double.MAX_VALUE;
        List<ServiceInstance> degraded = new ArrayList<>();

        for (NodeInfo node : nodes) {
            // Node bị loại khỏi pool chính
            if (node.inflight() >= INFLIGHT_HARD_CAP
                    || node.mcdm() >= degradeThreshold) {
                degraded.add(node.inst());
                continue;
            }

            // ── Healthy node: MCDM score + inflight penalty (cân bằng tải thời gian thực)
            // Tách hai tín hiệu: health (MCDM) và load (inflight) không trộn lẫn
            double excessShare = (totalInflight > 0)
                ? Math.max(0.0, (double) node.inflight() / totalInflight - 1.0 / n)
                : 0.0;
            double realtime = node.mcdm() + 1.2 * Math.log(1.0 + excessShare);

            if (realtime < bestRealtime) {
                bestRealtime = realtime;
                best = node.inst();
            }
        }

        // ── Fallback: tất cả node đều degraded → chọn node MCDM thấp nhất ──
        if (best == null) {
            best = nodes.stream()
                        .min(Comparator.comparingDouble(NodeInfo::mcdm))
                        .map(NodeInfo::inst)
                        .orElse(instances.get(0));
            log.warn("All instances degraded — routing to least-bad node");
        }

        // ── Probe traffic: gửi 3% vào degraded nodes để phát hiện recovery ──
        if (!degraded.isEmpty()
                && ThreadLocalRandom.current().nextDouble() < PROBE_PROBABILITY) {
            best = degraded.get(
                ThreadLocalRandom.current().nextInt(degraded.size()));
            log.debug("Probe request sent to degraded node: {}", best.getInstanceId());
        }

        Metrics.counter("alb.routing.selected",
            "backend", best.getInstanceId(),
            "port", String.valueOf(best.getPort())).increment();

        return new DefaultResponse(best);
    }
}