package com.truongquycode.apigatewayalb.dataplane;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.truongquycode.apigatewayalb.model.PidConfig;
import com.truongquycode.apigatewayalb.model.PidState;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PIDController {

	private final Cache<String, PidState> states = Caffeine.newBuilder().expireAfterAccess(5, TimeUnit.MINUTES).build();

	public double calculatePenalty(String instanceId, double rawLat, double setpoint, PidConfig cfg) {

		long now = System.currentTimeMillis();

		PidState finalState = states.asMap().compute(instanceId, (k, state) -> {

			if (state == null) {
				state = new PidState();
				// FIX LỖI 3: Để 200L cho khớp với polling interval
				state.setLastTimestamp(now - 200L);
				state.setLastRawLat(rawLat);
			}

			double actualDt = Math.min(5.0, Math.max(0.001, (now - state.getLastTimestamp()) / 1000.0));

			double error = rawLat - setpoint;

			// ----- P -----
			double p = cfg.getKp() * error;

			// ----- I với Conditional Anti-Windup -----
			boolean isSaturated = Math.abs(state.getLastOutput()) >= 2.0;
			boolean sameSign = Math.signum(error) == Math.signum(state.getLastOutput());

			if (!(isSaturated && sameSign)) {
				double newI = state.getIntegral() + (error * actualDt);

				// FIX LỖI 1: Time-dependent decay (Bảo đảm rò rỉ đúng 3% mỗi 1 giây THỰC TẾ)
				if (Math.abs(error) < 0.1) {
					double decayFactor = Math.pow(0.97, actualDt);
					newI = newI * decayFactor;
				}

				state.setIntegral(Math.max(cfg.getMinI(), Math.min(cfg.getMaxI(), newI)));
			}

			double i = cfg.getKi() * state.getIntegral();

			// ----- D với Low-Pass Filter -----
			// FIX LỖI 2: Đạo hàm trên Process Variable (tránh Derivative Kick do Setpoint
			// nhảy)
			double rawD = (rawLat - state.getLastRawLat()) / actualDt;

			double dynamicThetaD = 1.0 - Math.exp(-actualDt / cfg.getTauD());
			double filteredD = (dynamicThetaD * rawD) + ((1.0 - dynamicThetaD) * state.getLastFilteredD());
			double d = cfg.getKd() * filteredD;

			double u = p + i + d;

			state.setLastError(error);
			state.setLastRawLat(rawLat); // Lưu lại cho chu kỳ sau tính Derivative
			state.setLastFilteredD(filteredD);
			state.setLastOutput(u);
			state.setLastTimestamp(now);

			return state;
		});

		// ReLU + tanh
		return cfg.getLambda() * Math.tanh(cfg.getKappa() * Math.max(0.0, finalState.getLastOutput()));
	}

	public void resetAllStates() {
		states.invalidateAll();
		log.info("PID states cleared — all instance integrals reset to 0");
	}
}