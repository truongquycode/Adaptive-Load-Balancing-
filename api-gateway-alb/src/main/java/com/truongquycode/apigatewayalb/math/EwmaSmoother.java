package com.truongquycode.apigatewayalb.math;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class EwmaSmoother {

    /*
     Cache lưu trạng thái EWMA của từng instance.

     Key  : instanceId
     Value: EwmaState (giá trị EWMA trước đó + timestamp)

     Caffeine dùng để:
     - truy cập O(1)
     - thread-safe
     - tự động xoá state nếu instance không được truy cập trong 5 phút
     */
    private final Cache<String, EwmaState> states = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();


    /*
     Hàm tính EWMA latency

     instanceId  : id của backend instance
     rawLatency  : latency đo được ở chu kỳ hiện tại (L_raw)
     tauMs       : time constant của EWMA
     fallbackP50 : giá trị fallback khi instance mới xuất hiện (cold start)

     return      : L_ewma
     */
    public double smooth(String instanceId, double rawLatency, 
                     double tauMs, double fallbackP50) {
    long now = System.currentTimeMillis();

    return states.asMap().compute(instanceId, (k, state) -> {

        // Cold start: khởi tạo bằng p50 hệ thống để tránh bias routing
        if (state == null)
            return new EwmaState(fallbackP50, now);

        // Giới hạn dtMs: tránh theta → 1 khi restart hoặc instance vừa thêm vào
        // Tại dt = 3τ: θ = 1 - exp(-3) ≈ 0.95, vẫn giữ 5% lịch sử
        long dtMs = Math.min((long)(3.0 * tauMs),
                             Math.max(1L, now - state.lastTimestamp));

        // θ = 1 - exp(-Δt/τ): hệ số smoothing động theo thời gian thực
        double theta = 1.0 - Math.exp(-(double) dtMs / tauMs);

        // L_ewma(t) = θ × L_raw(t) + (1-θ) × L_ewma(t-1)
        double smoothed = (theta * rawLatency) + ((1.0 - theta) * state.value);

        return new EwmaState(smoothed, now);

    }).value;
}


    /*
     class lưu trạng thái EWMA của instance

     value          : L_ewma trước đó
     lastTimestamp  : thời điểm cập nhật gần nhất
    */
    private static class EwmaState {

        double value;
        long lastTimestamp;

        EwmaState(double v, long t) {
            this.value = v;
            this.lastTimestamp = t;
        }
    }
}