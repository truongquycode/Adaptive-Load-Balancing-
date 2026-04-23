package com.truongquycode.apigatewayalb.controlplane;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.truongquycode.apigatewayalb.model.CircuitState;

import jakarta.annotation.PostConstruct;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class InstanceCircuitBreaker {

    // Số lần timeout liên tiếp để mở circuit
    private static final int FAILURE_THRESHOLD = 3;

    // Thời gian giữ circuit ở trạng thái OPEN trước khi thử HALF_OPEN (ms)
    // Cơ sở: bằng 5 × polling interval (5 × 1000ms) để đảm bảo ít nhất 5 chu kỳ
    // poll thất bại trước khi hệ thống thử kết nối lại
    private static final long OPEN_DURATION_MS = 5000L;

    // Số lần thành công liên tiếp trong HALF_OPEN để đóng circuit trở lại
    private static final int SUCCESS_THRESHOLD = 2;

    private record CircuitStatus(
        CircuitState state,
        int failureCount,
        int successCount,
        long openedAtMs
    ) {}

    private final ConcurrentHashMap<String, AtomicReference<CircuitStatus>> circuits =
        new ConcurrentHashMap<>();

    private AtomicReference<CircuitStatus> getOrCreate(String instanceId) {
        return circuits.computeIfAbsent(instanceId,
            k -> new AtomicReference<>(new CircuitStatus(CircuitState.CLOSED, 0, 0, 0L)));
    }

    /**
     * Gọi khi poll metrics thành công.
     * Nếu đang HALF_OPEN và đủ số lần thành công → chuyển về CLOSED.
     */
    public void recordSuccess(String instanceId) {
        AtomicReference<CircuitStatus> ref = getOrCreate(instanceId);
        ref.updateAndGet(current -> {
            if (current.state() == CircuitState.HALF_OPEN) {
                int newSuccess = current.successCount() + 1;
                if (newSuccess >= SUCCESS_THRESHOLD) {
                    log.info("Circuit CLOSED cho instance {} sau {} lần thành công",
                        instanceId, newSuccess);
                    return new CircuitStatus(CircuitState.CLOSED, 0, 0, 0L);
                }
                return new CircuitStatus(CircuitState.HALF_OPEN, 0, newSuccess, current.openedAtMs());
            }
            // CLOSED: reset failure count
            return new CircuitStatus(CircuitState.CLOSED, 0, 0, 0L);
        });
    }

    /**
     * Gọi khi poll metrics thất bại (timeout hoặc connection error).
     * Sau FAILURE_THRESHOLD lần thất bại liên tiếp → mở circuit.
     */
    public void recordFailure(String instanceId) {
        AtomicReference<CircuitStatus> ref = getOrCreate(instanceId);
        ref.updateAndGet(current -> {
            int newFailures = current.failureCount() + 1;
            if (current.state() == CircuitState.CLOSED && newFailures >= FAILURE_THRESHOLD) {
                log.warn("Circuit OPEN cho instance {} sau {} lần timeout liên tiếp",
                    instanceId, newFailures);
                return new CircuitStatus(CircuitState.OPEN, newFailures, 0,
                    System.currentTimeMillis());
            }
            return new CircuitStatus(current.state(), newFailures,
                current.successCount(), current.openedAtMs());
        });
    }

    /**
     * Kiểm tra xem instance có nên bị phạt penalty nặng không.
     * Đồng thời tự động chuyển OPEN → HALF_OPEN nếu đã hết thời gian chờ.
     */
    public CircuitState evaluateState(String instanceId) {
        AtomicReference<CircuitStatus> ref = getOrCreate(instanceId);
        CircuitStatus current = ref.get();

        if (current.state() == CircuitState.OPEN) {
            long elapsed = System.currentTimeMillis() - current.openedAtMs();
            if (elapsed >= OPEN_DURATION_MS) {
                // Chuyển sang HALF_OPEN để thử phục hồi
                CircuitStatus halfOpen = new CircuitStatus(
                    CircuitState.HALF_OPEN, current.failureCount(), 0,
                    current.openedAtMs()
                );
                if (ref.compareAndSet(current, halfOpen)) {
                    log.info("Circuit HALF_OPEN cho instance {} — thử phục hồi", instanceId);
                }
                return CircuitState.HALF_OPEN;
            }
            return CircuitState.OPEN;
        }
        return current.state();
    }

    public boolean isOpen(String instanceId) {
        return evaluateState(instanceId) == CircuitState.OPEN;
    }
    
 // Sau khi khởi tạo, đăng ký Gauge cho Prometheus
    @PostConstruct
    public void registerMetrics() {
        // Gauge này cần được cập nhật từng instance khi có dữ liệu
        // Giá trị: 0 = CLOSED, 1 = HALF_OPEN, 2 = OPEN
    }

    public int getStateValue(String instanceId) {
        return switch (evaluateState(instanceId)) {
            case CLOSED    -> 0;
            case HALF_OPEN -> 1;
            case OPEN      -> 2;
        };
    }
}