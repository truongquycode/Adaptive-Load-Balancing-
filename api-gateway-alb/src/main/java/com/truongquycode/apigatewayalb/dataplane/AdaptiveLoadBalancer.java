package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.config.AlbProperties;
import com.truongquycode.apigatewayalb.model.RoutingContext;
import com.truongquycode.apigatewayalb.model.RoutingCost;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AdaptiveLoadBalancer v3 — Health-aware Capacity-normalized P2C.
 *
 * Nguyên tắc thiết kế:
 * - Control-plane tạo health score: EWMA + SlidingWindow + Dynamic MCDM + PID.
 * - Data-plane chỉ hợp nhất health score với inflight realtime đã chuẩn hóa capacity.
 * - Không dùng nhiều hard-rule theo latency/score cố định; quyết định chính dựa trên
 *   routingCost tự chuẩn hóa theo phân phối hiện tại của cụm.
 * - Khi health khác biệt mạnh → ưu tiên health; khi load khác biệt mạnh → ưu tiên load.
 * - P2C giúp giữ chi phí thấp mà không cần weighted-random floor gây tail latency xấu.
 */
@Slf4j
@RequiredArgsConstructor
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    private final RoutingCostCalculator routingCostCalculator;
    private final AlbProperties props;

    private static final AtomicLong rrCounter = new AtomicLong(0);
    private static final ConcurrentHashMap<String, Long> firstSeenMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastSelectedMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    public static void resetStaticState() {
        rrCounter.set(0);
        firstSeenMs.clear();
        lastSelectedMs.clear();
        counterCache.clear();
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) {
            return Mono.just(new EmptyResponse());
        }
        return supplier.get(request).next().map(this::selectInstance);
    }

    private Response<ServiceInstance> selectInstance(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return new EmptyResponse();
        }
        if (instances.size() == 1) {
            ServiceInstance selected = instances.get(0);
            emitMetric(selected, "SINGLE_INSTANCE");
            return new DefaultResponse(selected);
        }

        long now = System.currentTimeMillis();
        boolean allWarmup = true;
        for (ServiceInstance inst : instances) {
            long firstSeen = firstSeenMs.computeIfAbsent(inst.getInstanceId(), k -> now);
            if ((now - firstSeen) >= props.getRouting().getWarmupMs()) {
                allWarmup = false;
            }
        }

        RoutingContext ctx = routingCostCalculator.calculate(instances);

        if (allWarmup || "LOW_LOAD_RR".equals(ctx.mode())) {
            ServiceInstance selected = roundRobin(instances);
            emitMetric(selected, allWarmup ? "WARMUP_RR" : "LOW_LOAD_RR");
            return new DefaultResponse(selected);
        }

        // Probe nhẹ node đang bị ít chọn hoặc bị hard-excluded để kiểm tra recovery.
        RoutingCost probe = maybeProbe(ctx, now);
        if (probe != null) {
            ServiceInstance selected = ctx.instancesById().get(probe.instanceId());
            if (selected != null) {
                emitMetric(selected, "PROBE_RECOVERY");
                return new DefaultResponse(selected);
            }
        }

        List<RoutingCost> candidates = ctx.eligible().isEmpty() ? ctx.all() : ctx.eligible();
        RoutingCost selectedCost = chooseByP2C(candidates);
        ServiceInstance selected = ctx.instancesById().get(selectedCost.instanceId());

        if (selected == null) {
            selected = leastCostFallback(instances, ctx.all());
        }

        emitMetric(selected, ctx.mode());
        return new DefaultResponse(selected);
    }

    private RoutingCost chooseByP2C(List<RoutingCost> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        RoutingCost a = candidates.get(rnd.nextInt(candidates.size()));
        RoutingCost b = candidates.get(rnd.nextInt(candidates.size()));

        int guard = 0;
        while (a.instanceId().equals(b.instanceId()) && guard++ < 4) {
            b = candidates.get(rnd.nextInt(candidates.size()));
        }
        return routingCostCalculator.better(a, b);
    }

    private RoutingCost maybeProbe(RoutingContext ctx, long now) {
        AlbProperties.Routing cfg = props.getRouting();
        List<RoutingCost> ordered = new ArrayList<>(ctx.all());
        ordered.sort(Comparator.comparingDouble(RoutingCost::finalCost).reversed());

        for (RoutingCost cost : ordered) {
            if (cost.inflight() >= cfg.getHardInflightCap()) {
                continue;
            }
            long last = lastSelectedMs.getOrDefault(cost.instanceId(), 0L);
            if ((now - last) >= cfg.getProbeIntervalMs()
                    && ThreadLocalRandom.current().nextDouble() < cfg.getProbeProbability()) {
                return cost;
            }
        }
        return null;
    }

    private ServiceInstance leastCostFallback(List<ServiceInstance> instances, List<RoutingCost> costs) {
        if (costs == null || costs.isEmpty()) {
            return instances.get(0);
        }
        RoutingCost best = costs.stream()
                .min(Comparator.comparingDouble(RoutingCost::finalCost))
                .orElse(costs.get(0));
        for (ServiceInstance inst : instances) {
            if (inst.getInstanceId().equals(best.instanceId())) {
                return inst;
            }
        }
        return instances.get(0);
    }

    private ServiceInstance roundRobin(List<ServiceInstance> instances) {
        int idx = (int) Math.floorMod(rrCounter.getAndIncrement(), instances.size());
        return instances.get(idx);
    }

    private void emitMetric(ServiceInstance inst, String reason) {
        String id = inst.getInstanceId();
        lastSelectedMs.put(id, System.currentTimeMillis());
        counterCache.computeIfAbsent(id + "|" + reason, k -> Metrics.counter(
                "alb.routing.selected",
                "backend", id,
                "port", String.valueOf(inst.getPort()),
                "reason", reason
        )).increment();
    }
}
