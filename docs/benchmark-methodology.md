# Quy trình benchmark ALB nghiêm ngặt

Tài liệu này mô tả quy trình benchmark được chuẩn hóa để giảm rủi ro gắn nhãn sai strategy, giảm bias do thứ tự chạy, và đảm bảo mỗi lần chạy bắt đầu từ trạng thái sạch.

## 1. Nguyên tắc bắt buộc

1. **Không chạy JMeter nếu chưa xác minh strategy đang chạy thật trên server.**  
   Gateway phải trả về đúng `strategy` và `ablationVariant` qua:

   ```http
   GET /actuator/alb/strategy
   ```

2. **Không dùng kết quả nếu script không strict check.**  
   Các script mới đặt mặc định:

   ```bat
   set "STRICT_SERVER_STRATEGY_CHECK=true"
   ```

3. **Reset trước mỗi lần chạy.**  
   Trước từng run JMeter, script gọi:

   ```http
   POST /actuator/alb/reset
   POST /api/chaos/reset trên 8081, 8082, 8083
   ```

4. **Randomize thứ tự chạy thuật toán.**  
   Script mới bật mặc định:

   ```bat
   set "RANDOMIZE_ORDER=true"
   ```

   Điều này tránh việc Adaptive luôn chạy sau cùng hoặc Round Robin luôn chạy đầu tiên.

5. **Lưu metadata cho từng run.**  
   Mỗi kết quả có file `*-metadata.txt` chứa:

   - scenario;
   - strategy;
   - ablation variant;
   - run index;
   - commit hash;
   - JMX file;
   - server URL;
   - timestamp.

## 2. Endpoint xác minh strategy

Gateway có endpoint:

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

Script benchmark chỉ chạy khi giá trị trả về khớp đúng với strategy/variant đang được gắn nhãn.

## 3. Các script benchmark chính

| Script | Mục đích |
|---|---|
| `scripts_run_jmeter/1-run_low_all_strategies.bat` | Low load, no chaos, so sánh 4 strategy |
| `scripts_run_jmeter/2-run_medium_chaos_all_strategies.bat` | Medium load + dependency slowdown, so sánh 4 strategy |
| `scripts_run_jmeter/3-run_high_all_strategies.bat` | High load staged + dependency slowdown, so sánh 4 strategy |
| `scripts_run_jmeter/4-run_stress-test_all_strategies.bat` | Stress/recovery, so sánh 4 strategy |
| `scripts_run_jmeter/5-run_adaptive_ablation_medium.bat` | Ablation study riêng cho Adaptive |
| `scripts_run_jmeter/_benchmark_common.bat` | Script lõi dùng chung, không chạy trực tiếp nếu không truyền tham số |

## 4. Cách chạy

Chạy toàn bộ benchmark chính:

```bat
scripts_run_jmeter\0-run_all_benchmark_scenarios.bat
```

Chạy riêng từng kịch bản:

```bat
scripts_run_jmeter\1-run_low_all_strategies.bat
scripts_run_jmeter\2-run_medium_chaos_all_strategies.bat
scripts_run_jmeter\3-run_high_all_strategies.bat
scripts_run_jmeter\4-run_stress-test_all_strategies.bat
```

Chạy Adaptive ablation:

```bat
scripts_run_jmeter\5-run_adaptive_ablation_medium.bat
```

## 5. Biến môi trường có thể chỉnh trước khi chạy script

| Biến | Mặc định | Ý nghĩa |
|---|---:|---|
| `JMETER_HOME` | `D:\Downloads\apache-jmeter-5.6.3` | Thư mục JMeter |
| `RESULT_ROOT` | `%JMETER_HOME%\bin\ALB_Test\results` | Thư mục lưu kết quả |
| `SERVER_BASE_URL` | `http://172.30.35.37:8080` | Gateway server |
| `BACKEND_BASE_URL` | `http://172.30.35.37` | Base URL để reset chaos backend |
| `BACKEND_PORTS` | `8081 8082 8083` | Danh sách backend cần reset chaos |
| `RUNS_PER_ITEM` | `5` | Số lần chạy mỗi strategy/variant |
| `WAIT_AFTER_PUSH` | `120` giây | Chờ CI/CD deploy sau khi push config |
| `WAIT_AFTER_RESET` | `20` giây | Chờ sau reset ALB/chaos |
| `WAIT_BETWEEN_RUNS` | `180` giây | Nghỉ giữa các run |
| `VERIFY_RETRIES` | `18` | Số lần thử xác minh strategy |
| `VERIFY_INTERVAL` | `10` giây | Khoảng cách giữa các lần verify |
| `RANDOMIZE_ORDER` | `true` | Xáo trộn thứ tự chạy |
| `STRICT_SERVER_STRATEGY_CHECK` | `true` | Dừng nếu không xác minh được strategy |

Ví dụ chạy nhanh để thử script:

```bat
set RUNS_PER_ITEM=1
set WAIT_BETWEEN_RUNS=30
set WAIT_AFTER_PUSH=90
set WAIT_AFTER_RESET=10
scripts_run_jmeter\2-run_medium_chaos_all_strategies.bat
```

## 6. Quy trình phân tích kết quả

Khi đưa vào luận văn, không nên chỉ lấy một run đẹp nhất. Quy trình đề xuất:

1. Chạy ít nhất 5 lần cho mỗi strategy.
2. Loại bỏ warmup phase khi tính số liệu chính.
3. Tách phase nếu JMX có baseline/degradation/recovery.
4. Tổng hợp các chỉ số:
   - average latency;
   - p90/p95/p99;
   - throughput thực tế;
   - error rate;
   - request distribution theo backend;
   - recovery time sau chaos;
   - CPU/memory container.
5. Tính mean, median, standard deviation và confidence interval nếu có thể.
6. So sánh trên cùng workload, cùng server, cùng thời điểm mạng ổn định.

## 7. Ghi chú để tránh phản biện

- Script đã randomize thứ tự strategy nhưng vẫn cần ghi rõ thứ tự thực tế trong `scenario-metadata.txt`.
- Endpoint `/actuator/alb/strategy` là nguồn xác minh chính, không tin vào tên thư mục kết quả.
- Nếu error rate tăng, không được chỉ báo cáo latency HTTP 200.
- Nếu actual RPS thấp hơn target RPS, phải ghi chú hệ thống hoặc JMeter client đã tới giới hạn.

## 8. Tổng hợp JTL sau khi chạy

Có thể dùng script Python không cần thư viện ngoài để tổng hợp kết quả JMeter:

```bat
python scripts_run_jmeter\summarize_jtl_results.py D:\Downloads\apache-jmeter-5.6.3\bin\ALB_Test\results\02_medium_dependency_slowdown_mixed_0600_tst
```

Script sẽ tạo file:

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
- `max_ms`;
- các dòng `AGGREGATE` tính mean/std theo từng strategy folder.
