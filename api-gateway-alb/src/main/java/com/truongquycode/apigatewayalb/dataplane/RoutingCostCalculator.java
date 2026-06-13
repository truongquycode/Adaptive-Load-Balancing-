package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.config.AlbProperties;
import com.truongquycode.apigatewayalb.model.RoutingContext;
import com.truongquycode.apigatewayalb.model.RoutingCost;
import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.util.MetricsCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RoutingCostCalculator — điểm hợp nhất của Adaptive v3.
 *
 * Vai trò:
 * - Health cost đến từ control-plane: EWMA + normalize + MCDM + PID + score EMA.
 * - Load cost đến từ data-plane: inflight realtime, chuẩn hóa theo capacity container.
 * - Trọng số health/load tự đổi theo độ phân tán hiện tại của từng tín hiệu.
 *
 * Nhờ đó Adaptive không còn phụ thuộc vào nhiều hard-rule trong LoadBalancer:
 * khi load là tín hiệu rõ nhất, thuật toán nghiêng về least-load;
 * khi health là tín hiệu rõ nhất, thuật toán nghiêng về MCDM/PID/EWMA.
 */
@Component
@RequiredArgsConstructor
public class RoutingCostCalculator {

    private static final double EPS = 1e-9;
    private static final double DEFAULT_HEALTH_SCORE = 0.50;

    private final MetricsCache cache;
    private final InflightTracker inflightTracker;
    private final AlbProperties props;
    private final MeterRegistry registry;

    private final Map<String, RoutingCost> lastCosts = new ConcurrentHashMap<>();
    private volatile double lastHealthWeight = 0.50;
    private volatile double lastLoadWeight = 0.50;

    @PostConstruct
    public void registerGlobalRoutingGauges() {
        Gauge.builder("alb.routing.weight", this::getLastHealthWeight)
                .tag("component", "health")
                .register(registry);
        Gauge.builder("alb.routing.weight", this::getLastLoadWeight)
                .tag("component", "load")
                .register(registry);
    }

    public RoutingContext calculate(List<ServiceInstance> instances) {
        long now = System.currentTimeMillis();
        AlbProperties.Routing cfg = props.getRouting();
        int totalInflight = inflightTracker.getTotalInflight();

        Map<String, ServiceInstance> instanceMap = new HashMap<>();
        double sumCapacity = 0.0;
        for (ServiceInstance inst : instances) {
            instanceMap.put(inst.getInstanceId(), inst);
            sumCapacity += capacityWeightOf(inst);
        }
        sumCapacity = Math.max(sumCapacity, EPS);

        List<RawNode> rawNodes = new ArrayList<>(instances.size());
        for (ServiceInstance inst : instances) {
            String id = inst.getInstanceId();
            ScoreBreakdown bd = cache.getScore(id);

            double healthRaw = bd != null ? Math.max(0.0, bd.finalScore()) : DEFAULT_HEALTH_SCORE;
            long ageMs = bd != null ? Math.max(0L, now - bd.updatedAtMs()) : Long.MAX_VALUE;

            double cap = capacityWeightOf(inst);
            double capShare = cap / sumCapacity;
            double expected = Math.max(cfg.getMinExpectedInflight(), totalInflight * capShare);
            int inflight = inflightTracker.getInflight(id);
            double loadRaw = inflight / Math.max(expected, EPS);

            boolean hardExcluded = false;
            String reason = "NORMAL";
            if (bd == null) {
                hardExcluded = true;
                reason = "NO_METRICS";
            } else if (ageMs > cfg.getStaleHardMs()) {
                hardExcluded = true;
                reason = "STALE";
            } else if (healthRaw >= cfg.getUnhealthyScoreCutoff()) {
                hardExcluded = true;
                reason = "UNHEALTHY_SCORE";
            } else if (inflight >= cfg.getHardInflightCap()) {
                hardExcluded = true;
                reason = "HARD_INFLIGHT_CAP";
            }

            double stalePenalty = stalePenalty(ageMs, cfg);
            rawNodes.add(new RawNode(id, healthRaw, loadRaw, stalePenalty, cap, capShare,
                    expected, inflight, hardExcluded, reason, now));
        }

        double minHealth = rawNodes.stream().mapToDouble(RawNode::healthRaw).min().orElse(0.0);
        double maxHealth = rawNodes.stream().mapToDouble(RawNode::healthRaw).max().orElse(1.0);
        double minLoad = rawNodes.stream().mapToDouble(RawNode::loadRaw).min().orElse(0.0);
        double maxLoad = rawNodes.stream().mapToDouble(RawNode::loadRaw).max().orElse(1.0);

        double healthSpread = relativeSpread(rawNodes.stream().mapToDouble(RawNode::healthRaw).toArray());
        double loadSpread = relativeSpread(rawNodes.stream().mapToDouble(RawNode::loadRaw).toArray());

        double healthWeight = healthSpread / (healthSpread + loadSpread + EPS);
        healthWeight = clamp(healthWeight, cfg.getMinHealthWeight(), cfg.getMaxHealthWeight());
        double loadWeight = 1.0 - healthWeight;

        String mode = "NORMAL_P2C";
        if (isLowLoadStable(totalInflight, maxHealth - minHealth, maxLoad - minLoad, cfg)) {
            mode = "LOW_LOAD_RR";
        } else if (!rawNodes.stream().filter(n -> !n.hardExcluded()).findAny().isPresent()) {
            mode = "ALL_HARD_EXCLUDED_FALLBACK";
        } else if (healthWeight >= 0.60) {
            mode = "HEALTH_DOMINANT";
        } else if (loadWeight >= 0.60) {
            mode = "LOAD_DOMINANT";
        }

        List<RoutingCost> all = new ArrayList<>(rawNodes.size());
        List<RoutingCost> eligible = new ArrayList<>(rawNodes.size());
        for (RawNode node : rawNodes) {
            double healthCost = normalize(node.healthRaw(), minHealth, maxHealth);
            double loadCost = normalize(node.loadRaw(), minLoad, maxLoad);
            double finalCost = healthWeight * healthCost + loadWeight * loadCost + node.stalePenalty();

            RoutingCost cost = new RoutingCost(
                    node.instanceId(), node.healthRaw(), node.loadRaw(), healthCost, loadCost,
                    finalCost, node.capacityWeight(), node.capacityShare(), node.expectedInflight(),
                    node.inflight(), node.hardExcluded(), node.reason(), node.updatedAtMs());
            all.add(cost);
            lastCosts.put(node.instanceId(), cost);
            if (!node.hardExcluded()) {
                eligible.add(cost);
            }
        }

        this.lastHealthWeight = healthWeight;
        this.lastLoadWeight = loadWeight;
        return new RoutingContext(List.copyOf(all), List.copyOf(eligible), Map.copyOf(instanceMap),
                healthWeight, loadWeight, mode, now);
    }

    public RoutingCost better(RoutingCost a, RoutingCost b) {
        if (a.finalCost() < b.finalCost()) return a;
        if (b.finalCost() < a.finalCost()) return b;

        // Tie-break 1: node ít quá tải hơn so với phần tải kỳ vọng.
        if (a.loadCostRaw() < b.loadCostRaw()) return a;
        if (b.loadCostRaw() < a.loadCostRaw()) return b;

        // Tie-break 2: nếu mọi thứ gần như bằng nhau, node capacity lớn hơn được phép gánh nhiều hơn.
        return a.capacityWeight() >= b.capacityWeight() ? a : b;
    }

    public RoutingCost getLastCost(String instanceId) {
        return lastCosts.get(instanceId);
    }

    public double getLastHealthWeight() {
        return lastHealthWeight;
    }

    public double getLastLoadWeight() {
        return lastLoadWeight;
    }

    public void reset() {
        lastCosts.clear();
        lastHealthWeight = 0.50;
        lastLoadWeight = 0.50;
    }

    private double capacityWeightOf(ServiceInstance inst) {
        return Math.max(0.1, cache.getCapacityWeight(inst.getInstanceId()));
    }

    private double stalePenalty(long ageMs, AlbProperties.Routing cfg) {
        if (ageMs == Long.MAX_VALUE || ageMs <= cfg.getStaleSoftMs()) {
            return 0.0;
        }
        double span = Math.max(1.0, cfg.getStaleHardMs() - cfg.getStaleSoftMs());
        double ratio = clamp((ageMs - cfg.getStaleSoftMs()) / span, 0.0, 1.0);
        return cfg.getStalePenaltyWeight() * ratio;
    }

    private boolean isLowLoadStable(int totalInflight, double healthSpread, double loadSpread,
                                    AlbProperties.Routing cfg) {
        return totalInflight <= cfg.getLowLoadInflight()
                && healthSpread <= cfg.getLowLoadHealthSpread()
                && loadSpread <= cfg.getLowLoadLoadSpread();
    }

    private double normalize(double value, double min, double max) {
        double range = max - min;
        if (range <= EPS) return 0.0;
        return clamp((value - min) / range, 0.0, 1.0);
    }

    private double relativeSpread(double[] values) {
        if (values.length == 0) return 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        double mean = Math.abs(sum / values.length);
        return (max - min) / Math.max(mean, EPS);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record RawNode(
            String instanceId,
            double healthRaw,
            double loadRaw,
            double stalePenalty,
            double capacityWeight,
            double capacityShare,
            double expectedInflight,
            int inflight,
            boolean hardExcluded,
            String reason,
            long updatedAtMs
    ) {}
}
