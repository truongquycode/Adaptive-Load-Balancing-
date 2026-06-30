# Kế hoạch Adaptive Ablation Study

Ablation study dùng để trả lời câu hỏi phản biện: từng thành phần trong Adaptive Load Balancer có thật sự đóng góp vào kết quả hay chỉ làm thuật toán phức tạp hơn.

## 1. Cách bật ablation variant

Gateway đọc cấu hình:

```yaml
alb:
  strategy: adaptive
  ablation:
    variant: full
```

Endpoint xác minh:

```http
GET /actuator/alb/strategy
```

Response phải có:

```json
{
  "strategy": "adaptive",
  "ablationVariant": "full"
}
```

## 2. Các variant hiện có

| Variant | Thành phần bị tắt/thay đổi | File/class liên quan | Mục đích kiểm chứng |
|---|---|---|---|
| `full` | Không tắt gì | Toàn bộ Adaptive | Baseline thuật toán đầy đủ |
| `no-pid` | PID penalty = 0 | `ScoreCalculator.java`, `PIDController.java` | PID-inspired penalty có giúp giảm traffic khỏi node chậm kéo dài không |
| `fixed-weights` | Không dùng dynamic EWM, dùng AHP fixed weights | `ScoreCalculator.java`, `DynamicWeightEngine.java` | Dynamic weight có tạo khác biệt so với trọng số cố định không |
| `no-ewma-latency` | Dùng latency thô thay vì EWMA latency | `ScoreCalculator.java`, `EwmaSmoother.java` | EWMA có giảm nhiễu/flapping không |
| `no-score-ema` | Không làm mượt finalScore | `MetricsPoller.java` | Score EMA có giảm dao động routing không |
| `no-capacity` | Xem mọi backend có capacity = 1.0 | `RoutingCostCalculator.java` | Capacity weight có giúp tận dụng instance mạnh hơn không |
| `no-p2c` | Chọn backend có routing cost thấp nhất toàn cục | `AdaptiveLoadBalancer.java` | P2C có giảm herd effect/dao động không |
| `no-probe` | Tắt probe recovery | `AdaptiveLoadBalancer.java` | Probe có giúp backend hồi phục nhận traffic trở lại không |
| `no-low-load-rr` | Tắt low-load fallback về Round Robin | `RoutingCostCalculator.java` | Low-load fallback có tránh phản ứng quá mức khi tải thấp không |

## 3. Script chạy ablation

Chạy:

```bat
scripts_run_jmeter\5-run_adaptive_ablation_medium.bat
```

Script này dùng JMX:

```text
jmeter/02_medium_dependency_slowdown_mixed_0600_tst.jmx
```

Lý do chọn medium dependency slowdown:

- đủ tải để thấy khác biệt;
- có degradation rõ;
- chưa quá nặng tới mức toàn hệ thống sập;
- phù hợp để quan sát routing shift, latency tail và recovery.

## 4. Chỉ số cần so sánh

| Nhóm chỉ số | Metric/JMeter | Ý nghĩa |
|---|---|---|
| Latency | avg, p90, p95, p99 | Trải nghiệm người dùng |
| Throughput | actual RPS | Hệ thống có giữ được target RPS không |
| Error rate | non-2xx % | Có đổi latency thấp bằng lỗi không |
| Routing distribution | `alb_routing_selected_total` | Traffic có chuyển khỏi backend xấu không |
| Routing reason | `reason` label | Adaptive đang warmup, low-load, probe hay normal |
| Score/cost | `alb_final_score`, `alb_routing_score` | Thành phần nào làm thay đổi quyết định |
| Resource | cAdvisor CPU/memory | Backend có bị quá tải tài nguyên không |

## 5. Cách kết luận ablation

Không nên kết luận chỉ dựa vào một chỉ số. Mẫu kết luận nên theo cấu trúc:

```text
Khi tắt [thành phần], p95/p99 tăng/giảm ..., error rate ..., routing distribution ...
Điều này cho thấy [thành phần] có/không có đóng góp rõ trong kịch bản medium dependency slowdown.
```

Ví dụ:

```text
Nếu `no-capacity` làm 8083 nhận tải gần ngang 8081 và p99 tăng, có thể kết luận capacity weight giúp thuật toán phù hợp hơn với cụm backend không đồng nhất.
```

## 6. Rủi ro khi diễn giải

- Nếu một variant không kém hơn `full`, không nên che giấu. Cần ghi nhận rằng thành phần đó chưa chứng minh được lợi ích trong kịch bản hiện tại.
- Nếu `full` chỉ tốt hơn trong một kịch bản, cần thêm low/high/stress ablation để tránh overfitting.
- Nếu error rate tăng, không được chỉ nói latency giảm.
- Nếu throughput thực tế thấp hơn target RPS, so sánh latency có thể không công bằng.
