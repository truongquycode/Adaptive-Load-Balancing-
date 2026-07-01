# Adaptive Ablation Study

## 1. Mục đích

Ablation study dùng để trả lời câu hỏi phản biện:

```text
Các thành phần trong Adaptive Load Balancer có thật sự đóng góp vào kết quả không,
hay chỉ làm thuật toán phức tạp hơn?
```

Nếu không làm ablation, rất khó bảo vệ việc dùng nhiều kỹ thuật cùng lúc như MCDM, AHP/EWM, EWMA, PID-inspired penalty, capacity weight, P2C, probe recovery và low-load fallback.

---

## 2. Cách bật variant

Cấu hình:

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

Response phải có đúng:

```json
{
  "strategy": "adaptive",
  "ablationVariant": "full"
}
```

Script benchmark strict mode sẽ dừng nếu response không khớp.

---

## 3. Các variant hiện có

| Variant | Thành phần bị tắt/thay đổi | File/class liên quan | Mục đích kiểm chứng |
|---|---|---|---|
| `full` | Không tắt gì | Toàn bộ Adaptive | Baseline đầy đủ |
| `no-pid` | PID penalty = 0 | `ScoreCalculator`, `PIDController` | PID-inspired penalty có giúp node chậm kéo dài bị giảm traffic không |
| `fixed-weights` | Không dùng dynamic EWM, dùng AHP fixed weights | `DynamicWeightEngine`, `ScoreCalculator` | Dynamic weights có tạo khác biệt không |
| `no-ewma-latency` | Dùng latency thô thay vì EWMA | `ScoreCalculator`, `EwmaSmoother` | EWMA có giảm nhiễu/flapping không |
| `no-score-ema` | Không làm mượt finalScore | `MetricsPoller` | Score EMA có giảm dao động routing không |
| `no-capacity` | Xem mọi backend capacity = 1.0 | `RoutingCostCalculator` | Capacity weight có giúp tận dụng instance mạnh hơn không |
| `no-p2c` | Chọn min cost toàn cục | `AdaptiveLoadBalancer` | P2C có giảm herd effect/dao động không |
| `no-probe` | Tắt probe recovery | `AdaptiveLoadBalancer` | Probe có giúp backend hồi phục nhận traffic trở lại không |
| `no-low-load-rr` | Tắt low-load fallback | `RoutingCostCalculator`, `AdaptiveLoadBalancer` | Low-load RR có tránh phản ứng quá mức khi tải thấp không |

---

## 4. Script chạy

```bat
scripts_run_jmeter\5-run_adaptive_ablation_medium.bat
```

Script dùng JMX:

```text
jmeter/02_medium_dependency_slowdown_mixed_0600_tst.jmx
```

Lý do chọn medium dependency slowdown:

- đủ tải để thấy khác biệt;
- có degradation rõ;
- chưa quá nặng đến mức toàn hệ thống sập;
- phù hợp để quan sát routing shift, tail latency và recovery.

---

## 5. Chỉ số cần so sánh

| Nhóm | Chỉ số |
|---|---|
| Latency | avg, p90, p95, p99 |
| Throughput | actual RPS trên JMeter và Grafana |
| Error | error rate, non-2xx rate |
| Routing | request distribution theo backend |
| Reason | `alb_routing_selected_total{reason=...}` |
| Score/cost | `alb_final_score`, `alb_routing_score`, `alb_routing_weight` |
| MCDM | `alb_mcdm_weight`, `alb_mcdm_update_mode` |
| Resource | cAdvisor CPU/memory |
| Recovery | thời gian traffic quay lại backend hồi phục |

---

## 6. Mẫu kết luận

Không nên kết luận bằng một chỉ số duy nhất. Mẫu nên dùng:

```text
Khi tắt [thành phần], p95/p99 thay đổi ..., error rate ..., actual RPS ..., routing distribution ...
Điều này cho thấy [thành phần] có/không có đóng góp rõ trong kịch bản medium dependency slowdown.
```

Ví dụ:

```text
Nếu `no-capacity` làm 8083 nhận tải gần ngang 8081 và p99 tăng, có thể kết luận capacity weight giúp Adaptive phù hợp hơn với cụm backend không đồng nhất.
```

---

## 7. Cách diễn giải trung thực

- Nếu variant không kém hơn `full`, không được che giấu.
- Nếu `full` chỉ tốt hơn trong một kịch bản, cần nói rõ phạm vi kết luận.
- Nếu latency giảm nhưng error rate tăng, không được xem là cải thiện rõ ràng.
- Nếu actual RPS thấp hơn target RPS, so sánh latency có thể không công bằng.
- Nếu một thành phần chưa chứng minh được lợi ích, có thể trình bày là hướng cải tiến/tối giản trong tương lai.

---

## 8. Ablation nên có trong luận văn

Tối thiểu nên có:

1. `full` vs `fixed-weights` để kiểm tra Dynamic MCDM.
2. `full` vs `no-pid` để kiểm tra PID-inspired penalty.
3. `full` vs `no-capacity` để kiểm tra capacity awareness.
4. `full` vs `no-p2c` để kiểm tra P2C.
5. `full` vs `no-low-load-rr` trong low load để kiểm tra stability guard.

Nếu thời gian hạn chế, ưu tiên 1-3 vì đây là các điểm dễ bị phản biện nhất.
