package com.truongquycode.apigatewayalb.math;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class EwmaSmoother {

	private final Cache<String, EwmaState> states = Caffeine.newBuilder().expireAfterAccess(5, TimeUnit.MINUTES)
			.build();

	public double smooth(String instanceId, double rawLatency, double tauMin, double tauMax, double k,
			double fallbackP50) {
		long now = System.currentTimeMillis();

		double tauRange = tauMax - tauMin; 
		long dtCap = (long) (3.0 * tauMax);

		return states.asMap().compute(instanceId, (id, state) -> {

			if (state == null)
				return new EwmaState(fallbackP50, now);

			long dtMs = Math.min(dtCap, Math.max(1L, now - state.lastTimestamp));

			double deviation = Math.abs(rawLatency - state.value) / Math.max(state.value, 1.0);

			double kd = k * deviation;
			double adaptiveTau = (kd >= 6.0) ? tauMin : tauMin + tauRange * Math.exp(-kd);

			double ratio = (double) dtMs / adaptiveTau;
			double theta = (ratio >= 10.0) ? 1.0 : (1.0 - Math.exp(-ratio));

			double smoothed = (theta * rawLatency) + ((1.0 - theta) * state.value);

			state.value = smoothed;
			state.lastTimestamp = now;
			return state;

		}).value;
	}

	private static class EwmaState {
		double value;
		long lastTimestamp;

		EwmaState(double v, long t) {
			this.value = v;
			this.lastTimestamp = t;
		}
	}

	public void resetAllStates() {
		states.invalidateAll();
		log.info("EWMA states cleared — cold start on next poll");
	}
}