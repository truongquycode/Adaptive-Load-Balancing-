# Quy trình benchmark ALB nghiêm ngặt

## 1. Mục tiêu

Tài liệu này chuẩn hóa quy trình benchmark để giảm rủi ro:

- gắn nhãn sai strategy;
- thứ tự chạy gây bias;
- run sau bị nhiễm state của run trước;
- chỉ báo cáo latency 200 mà bỏ qua lỗi;
- không đủ thống kê để bảo vệ kết quả luận văn.

---

## 2. Nguyên tắc bắt buộc

1. **Không chạy JMeter nếu chưa xác minh strategy đang chạy thật.**

   ```http
   GET /actuator/alb/strategy
   ```

2. **Strict check phải bật.**

   ```bat
   set "STRICT_SERVER_STRATEGY_CHECK=true"
   ```

3. **Reset trước từng run.**

   ```http
   POST /actuator/alb/reset
   POST /api/chaos/reset trên 8081, 8082, 8083
   ```

4. **Randomize thứ tự strategy.**

   ```bat
   set "RANDOMIZE_ORDER=true"
   ```

5. **Không dùng một run đẹp nhất.**  
   Cần chạy nhiều lần, tổng hợp mean/median/std/CI và ghi rõ môi trường.

6. **Không chỉ đọc latency của HTTP 200.**  
   Phải đọc kèm all-status latency, error rate và actual throughput.

---

## 3. Endpoint xác minh strategy

Gateway cung cấp:

```http
GET /actuator/alb/strategy
```

Ví dụ response:

```json
{
  "strategy": "adaptive",
  "ablationVariant": "full",
  "supportedStrategies": ["adaptive", "round-robin", "random", "least-connections"],
  "supportedAblationVariants": ["full", "no-pid", "fixed-weights", "no-ewma-latency", "no-score-ema", "no-capacity", "no-p2c", "no-probe", "no-low-load-rr"],
  "timestamp": "2026-06-30T...Z"
}
```

Script benchmark chỉ chạy nếu response khớp với strategy/variant đang được gắn nhãn.

---

## 4. Script benchmark

| Script | Mục đích |
|---|---|
| `0-run_all_benchmark_scenarios.bat` | Chạy toàn bộ benchmark chính |
| `1-run_low_all_strategies.bat` | Low load, no chaos |
| `2-run_medium_chaos_all_strategies.bat` | Medium load + dependency slowdown |
| `3-run_high_all_strategies.bat` | High load staged + dependency slowdown |
| `4-run_stress-test_all_strategies.bat` | Stress/recovery |
| `5-run_adaptive_ablation_medium.bat` | Adaptive ablation study |
| `_benchmark_common.bat` | Script lõi dùng chung |
| `summarize_jtl_results.py` | Tổng hợp JTL thành CSV |

---

## 5. JMX benchmark

| JMX | Mục tiêu |
|---|---|
| `00_rsat_discovery_mixed_1000plus_600_1600_tst.jmx` | Tìm vùng bão hòa R_sat |
| `00b_rsat_confirm_mixed_1000plus_1000_1250_tst.jmx` | Xác nhận R_sat |
| `01_low_baseline_mixed_0300_nochaos_tst.jmx` | Low load, no chaos |
| `02_medium_dependency_slowdown_mixed_0600_tst.jmx` | Medium load + dependency slowdown |
| `03_high_dependency_slowdown_mixed_0900_tst.jmx` | High load + dependency slowdown |
| `03_high_dependency_slowdown_mixed_0900_staged_tst.jmx` | High load staged |
| `04_stress_recovery_mixed_1200_to_0600_nochaos_tst.jmx` | Stress/recovery |
| `04_stress_recovery_mixed_1200_to_0600_staged_nochaos_tst.jmx` | Stress/recovery staged |

---

## 6. Cách chạy nhanh để kiểm tra script

```bat
set RUNS_PER_ITEM=1
set WAIT_BETWEEN_RUNS=30
set WAIT_AFTER_PUSH=90
set WAIT_AFTER_RESET=10
scripts_run_jmeter\2-run_medium_chaos_all_strategies.bat
```

Chạy chính thức nên dùng mặc định 5 run hoặc hơn.

---

## 7. Biến môi trường chính

| Biến | Mặc định | Ý nghĩa |
|---|---:|---|
| `JMETER_HOME` | `D:\Downloads\apache-jmeter-5.6.3` | Thư mục JMeter |
| `RESULT_ROOT` | `%JMETER_HOME%\bin\ALB_Test\results` | Thư mục kết quả |
| `SERVER_BASE_URL` | `http://172.30.35.37:8080` | Gateway |
| `BACKEND_BASE_URL` | `http://172.30.35.37` | Base URL backend |
| `BACKEND_PORTS` | `8081 8082 8083` | Backend cần reset chaos |
| `RUNS_PER_ITEM` | `5` | Số run mỗi strategy/variant |
| `WAIT_AFTER_PUSH` | `120` | Chờ CI/CD deploy sau push |
| `WAIT_AFTER_RESET` | `20` | Chờ sau reset ALB/chaos |
| `WAIT_BETWEEN_RUNS` | `180` | Nghỉ giữa các run |
| `VERIFY_RETRIES` | `18` | Số lần verify strategy |
| `VERIFY_INTERVAL` | `10` | Khoảng cách verify |
| `RANDOMIZE_ORDER` | `true` | Xáo trộn thứ tự |
| `STRICT_SERVER_STRATEGY_CHECK` | `true` | Dừng nếu verify sai |

---

## 8. Quy trình chuẩn trước khi benchmark

1. Build/deploy hệ thống.
2. Kiểm tra Docker containers.
3. Kiểm tra Eureka có đủ 3 backend.
4. Kiểm tra `/actuator/health` của Gateway và backend.
5. Kiểm tra `/actuator/alb/strategy`.
6. Reset ALB.
7. Reset chaos trên 8081/8082/8083.
8. Chạy JMeter non-GUI.
9. Lưu `.jtl`, HTML report, metadata.
10. Lưu Grafana/Prometheus snapshot nếu dùng trong luận văn.

---

## 9. Phân tích kết quả

Không dùng một bảng HTML JMeter duy nhất. Quy trình đề xuất:

1. Chạy ít nhất 5 run mỗi strategy.
2. Loại bỏ warmup phase khi tính kết quả chính.
3. Nếu JMX có phase baseline/degradation/recovery, tách riêng từng phase.
4. Tính:
   - samples;
   - error rate;
   - throughput thực tế;
   - average;
   - p50/p90/p95/p99;
   - max;
   - mean/std/CI qua nhiều run.
5. Đối chiếu Grafana:
   - actual RPS;
   - all-status latency;
   - error rate;
   - routing distribution;
   - CPU/memory;
   - MCDM/routing score.

---

## 10. Tổng hợp JTL

Chạy:

```bat
python scripts_run_jmeter\summarize_jtl_results.py D:\Downloads\apache-jmeter-5.6.3\bin\ALB_Test\results\02_medium_dependency_slowdown_mixed_0600_tst
```

Script tạo:

```text
jtl-summary.csv
```

Các cột chính:

- `samples`;
- `errors`;
- `error_rate_percent`;
- `throughput_rps`;
- `avg_ms`;
- `p50_ms`;
- `p90_ms`;
- `p95_ms`;
- `p99_ms`;
- `max_ms`.

---

## 11. Vấn đề còn cần ghi rõ

Script hiện tại đổi strategy bằng cách sửa `application.yml`, commit/push và chờ CI/CD deploy. Strict verification giúp tránh gắn nhãn sai, nhưng quy trình này vẫn có thể gây nhiễu thời gian deploy và phụ thuộc GitHub runner.

Khi viết luận văn, cần ghi rõ:

- strategy được verify bằng endpoint Gateway;
- thứ tự strategy được randomize;
- state được reset trước mỗi run;
- có thời gian chờ sau deploy/reset;
- kết quả được lấy từ nhiều run;
- môi trường mạng, client/server, JMeter path và server IP được cố định trong quá trình đo.
