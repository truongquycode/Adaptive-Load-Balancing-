package com.truongquycode.apigatewayalb.dataplane;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.truongquycode.apigatewayalb.model.PidConfig;
import com.truongquycode.apigatewayalb.model.PidState;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class PIDController {

    // Cache lưu trạng thái PID của từng instance
    // expireAfterAccess: nếu instance không được truy cập trong 5 phút thì xóa
    private final Cache<String, PidState> states = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES).build();

    public double calculatePenalty(String instanceId, double rawLat, 
                               double p50System, PidConfig cfg) {

    long now = System.currentTimeMillis();

    PidState finalState = states.asMap().compute(instanceId, (k, state) -> {

        if (state == null) {
            state = new PidState();
            state.setLastTimestamp(now - 1000L); // Đúng 1 chu kỳ poll
        }

        // Giới hạn cả hai đầu: tránh chia 0 và tránh spike tích phân khi restart
        double actualDt = Math.min(5.0, Math.max(0.001,
            (now - state.getLastTimestamp()) / 1000.0));

        double error = rawLat - p50System;

        // ----- P -----
        double p = cfg.getKp() * error;

        // ----- I với Conditional Anti-Windup -----
        boolean isSaturated = Math.abs(state.getLastOutput()) >= 2.0;
        boolean sameSign = Math.signum(error) == Math.signum(state.getLastOutput());

        if (!(isSaturated && sameSign)) {
            double newI = state.getIntegral() + (error * actualDt);
            state.setIntegral(Math.max(cfg.getMinI(), Math.min(cfg.getMaxI(), newI)));
        }

        double i = cfg.getKi() * state.getIntegral();

        // ----- D với Low-Pass Filter (Butterworth rời rạc) -----
        double rawD = (error - state.getLastError()) / actualDt;
        double dynamicThetaD = 1.0 - Math.exp(-actualDt / cfg.getTauD());
        double filteredD = (dynamicThetaD * rawD)
                         + ((1.0 - dynamicThetaD) * state.getLastFilteredD());
        double d = cfg.getKd() * filteredD;

        double u = p + i + d;

        state.setLastError(error);
        state.setLastFilteredD(filteredD);
        state.setLastOutput(u);
        state.setLastTimestamp(now);

        return state;
    });

    // ReLU + tanh: chỉ phạt node chậm, penalty ∈ [0, λ]
    return cfg.getLambda() *
           Math.tanh(cfg.getKappa() * Math.max(0.0, finalState.getLastOutput()));
}
}