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

	private static final double LN_0_97 = Math.log(0.97);

	public double calculatePenalty(String instanceId, double rawLat, double setpoint, PidConfig cfg) {
		long now = System.currentTimeMillis();

		final double kp = cfg.getKp();
		final double ki = cfg.getKi();
		final double kd = cfg.getKd();
		final double tauD = cfg.getTauD();
		final double minI = cfg.getMinI();
		final double maxI = cfg.getMaxI();
		final double lambda = cfg.getLambda();
		final double kappa = cfg.getKappa();

		PidState finalState = states.asMap().compute(instanceId, (k, state) -> {

			if (state == null) {
				state = new PidState();
				state.setLastTimestamp(now - 200L);
				state.setLastRawLat(rawLat);
			}

			final long prevTimestamp = state.getLastTimestamp();
			final double prevOutput = state.getLastOutput();
			final double prevIntegral = state.getIntegral();
			final double prevRawLat = state.getLastRawLat();
			final double prevFilteredD = state.getLastFilteredD();

			double actualDt = Math.min(5.0, Math.max(0.001, (now - prevTimestamp) / 1000.0));
			double error = rawLat - setpoint;

			// ----- P -----
			double p = kp * error;

			// ----- I với Conditional Anti-Windup -----
			boolean isSaturated = Math.abs(prevOutput) >= 2.0;
			boolean sameSign = (error * prevOutput) > 0.0;

			double integral = prevIntegral;
			if (!(isSaturated && sameSign)) {
				double newI = prevIntegral + (error * actualDt);

				if (Math.abs(error) < 0.1) {
					newI *= Math.exp(LN_0_97 * actualDt);
				}

				integral = newI < minI ? minI : (newI > maxI ? maxI : newI);
				state.setIntegral(integral);
			}

			double i = ki * integral;

			// ----- D với Low-Pass Filter -----
			double rawD = (rawLat - prevRawLat) / actualDt;

			double expTerm = Math.exp(-actualDt / tauD);
			double filteredD = ((1.0 - expTerm) * rawD) + (expTerm * prevFilteredD);
			double d = kd * filteredD;

			double u = p + i + d;

			state.setLastRawLat(rawLat);
			state.setLastFilteredD(filteredD);
			state.setLastOutput(u);
			state.setLastTimestamp(now);

			return state;
		});

		return lambda * Math.tanh(kappa * Math.max(0.0, finalState.getLastOutput()));
	}

	public void resetAllStates() {
		states.invalidateAll();
		log.info("PID states cleared — all instance integrals reset to 0");
	}
}