package com.truongquycode.apigatewayalb.dataplane;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.truongquycode.apigatewayalb.model.RealtimeDynamics;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RealtimeDynamicsTracker {

    private final Cache<String, State> states = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();

    public RealtimeDynamics update(
            String instanceId,
            double inflight,
            double latency,
            boolean failed
    ) {

        long now = System.currentTimeMillis();

        State state = states.asMap().compute(instanceId, (id, prev) -> {

            if (prev == null) {
                return new State(
                        inflight,
                        latency,
                        now,
                        0.0,
                        0.0,
                        failed ? 1.0 : 0.0
                );
            }

            double dt = Math.max(0.001,
                    (now - prev.timestampMs) / 1000.0);

            double queueGrowth =
                    (inflight - prev.lastInflight) / dt;

            double latencySlope =
                    (latency - prev.lastLatency) / dt;

            double failurePenalty =
                    failed
                    ? Math.min(1.0, prev.failurePenalty + 0.2)
                    : prev.failurePenalty * 0.92;

            return new State(
                    inflight,
                    latency,
                    now,
                    queueGrowth,
                    latencySlope,
                    failurePenalty
            );
        });

        return new RealtimeDynamics(
                inflight,
                state.queueGrowthRate,
                state.latencySlope,
                state.failurePenalty
        );
    }

    @Getter
    @RequiredArgsConstructor
    private static class State {

        private final double lastInflight;
        private final double lastLatency;
        private final long timestampMs;

        private final double queueGrowthRate;
        private final double latencySlope;
        private final double failurePenalty;
    }
}