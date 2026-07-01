# DynamicWeightEngine

## 1. Vai trò

`DynamicWeightEngine` tính trọng số cho ba tiêu chí MCDM trong `ScoreCalculator`:

```text
latency weight = alpha
queue weight   = beta
cpu weight     = gamma
```

Base score:

```text
baseScore = alpha * normLatency
          + beta  * normQueue
          + gamma * normCpu
```

---

## 2. AHP prior mặc định

Trọng số mặc định trong code:

```text
latency = 0.648
queue   = 0.230
cpu     = 0.122
```

Đây là **AHP prior**, tức bộ trọng số nền được thiết lập bằng Analytic Hierarchy Process. AHP prior được dùng khi Gateway mới khởi động, khi không có đủ traffic thật để cập nhật EWM, hoặc khi chạy ablation `fixed-weights`.

Tài liệu chi tiết: [`ahp-default-weight-rationale.md`](./ahp-default-weight-rationale.md).

### 2.1. Ma trận so sánh cặp

Thứ tự tiêu chí:

```text
C1 = latency
C2 = queue
C3 = cpu
```

Ma trận AHP dùng cho project:

```text
A = | 1    3    5 |
    | 1/3  1    2 |
    | 1/5  1/2  1 |
```

Diễn giải:

| So sánh | Giá trị | Lý do |
|---|---:|---|
| Latency > Queue | 3 | Latency phản ánh trực tiếp QoS/SLA; queue là tín hiệu nguyên nhân của độ trễ. |
| Latency > CPU | 5 | CPU là tín hiệu tài nguyên nhưng có thể bão hòa hoặc gây hiểu nhầm khi bottleneck nằm ở dependency, I/O hoặc thread pool. |
| Queue > CPU | 2 | Queue là leading indicator của quá tải và có quan hệ trực tiếp hơn với waiting delay; CPU vẫn được giữ vai trò phụ trợ. |

### 2.2. Trọng số suy ra

Chuẩn hóa tổng cột và lấy trung bình hàng cho kết quả:

```text
W = [0.648, 0.230, 0.122]
```

Nghĩa là trong health score nền, latency chiếm khoảng 64.8%, queue khoảng 23.0%, CPU khoảng 12.2%.

### 2.3. Kiểm tra nhất quán

Với ma trận trên:

```text
lambda_max ≈ 3.004
CI ≈ 0.002
CR ≈ 0.003  < 0.1
```

Do đó, ma trận so sánh cặp đạt mức nhất quán chấp nhận được theo tiêu chuẩn AHP. Tuy nhiên, cần trình bày đúng: AHP giúp hợp thức hóa bộ trọng số prior theo một quy trình ra quyết định đa tiêu chí, **không chứng minh đây là bộ trọng số tối ưu tuyệt đối cho mọi hệ thống**.

---

## 3. EWM trên normalized criteria

Engine không tính entropy trên raw latency/queue/cpu. Nó dùng:

```text
normLatency, normQueue, normCpu
```

đã được `ScoreCalculator` chuẩn hóa về [0,1]. Điều này giúp EWM nhìn đúng không gian dữ liệu mà base score sử dụng.

Entropy Weight Method được dùng theo ý tưởng:

```text
Tiêu chí nào phân biệt backend rõ hơn -> diversity cao hơn -> weight cao hơn.
```

Sau khi có EWM weights, code blend với AHP prior:

```text
target = 0.70 * EWM + 0.30 * AHP
```

Sau đó áp EMA để weight không nhảy quá nhanh.

---

## 4. Real-traffic gate

Một điểm quan trọng của phiên bản hiện tại: Dynamic MCDM chỉ cập nhật khi có đủ traffic nghiệp vụ thật.

Cấu hình:

```yaml
alb:
  weights:
    update-interval: 5000
    min-completed-requests: 20
    min-actual-rps: 5.0
    reset-to-ahp-when-idle: true
```

Cơ chế:

```text
MetricsPoller tính deltaCount từ Micrometer timer.
Nếu deltaCount > 0 -> ghi nhận completed request thật.
DynamicWeightEngine mỗi 5s đọc số completed request thật.
Nếu không đủ request/RPS -> không chạy EWM.
Nếu reset-to-ahp-when-idle=true -> quay về AHP prior.
```

Mục đích là tránh tình trạng weights thay đổi khi hệ thống idle chỉ vì Gateway vẫn poll `/api/alb-metrics`, Prometheus vẫn scrape, JVM vẫn có CPU nền.

---

## 5. Stable cluster guard

Ngay cả khi có traffic thật, nếu cụm rất ổn định:

```text
avgQueue < 0.08
avgCpu < 0.08
latencySpread < 0.12
```

engine giữ AHP prior thay vì cập nhật EWM. Điều này tránh khuếch đại nhiễu nhỏ thành thay đổi trọng số lớn.

---

## 6. Bound và smoothing

Sau khi tính target weight, code dùng:

- EMA alpha động từ 0.08 đến 0.22;
- clamp mềm:
  - `gamma` trong [0.08, 0.35];
  - `beta` trong [0.08, 0.45];
  - `alpha` trong [0.15, 0.70];
- normalize lại tổng weights = 1.

Những bound này giúp một tiêu chí không triệt tiêu hoàn toàn hoặc thống trị tuyệt đối.

---

## 7. Ablation

Variant liên quan:

| Variant | Ảnh hưởng |
|---|---|
| `full` | Dùng Dynamic MCDM bình thường |
| `fixed-weights` | Reset về AHP/fixed weights, không dùng EWM |

Ablation này dùng để trả lời câu hỏi: dynamic weight có cải thiện kết quả so với fixed weight hay không.

---

## 8. Metrics Prometheus

| Metric | Giá trị |
|---|---|
| `alb_mcdm_weight{criterion="latency"}` | alpha |
| `alb_mcdm_weight{criterion="queue"}` | beta |
| `alb_mcdm_weight{criterion="cpu"}` | gamma |
| `alb_mcdm_update_mode` | 0=frozen/idle/stable, 1=dynamic real traffic, 2=fixed-weights ablation |
| `alb_mcdm_recent_completed_requests` | completed requests trong cửa sổ MCDM gần nhất |
| `alb_mcdm_recent_actual_rps` | actual RPS trong cửa sổ MCDM gần nhất |

---

## 9. Cách diễn giải khi bảo vệ

Nên nói:

```text
MCDM weights gồm AHP prior và EWM adaptation. EWM chỉ cập nhật khi có đủ traffic thật để tránh học từ nhiễu idle. Vì chỉ có 3 backend, EWM được dùng như tín hiệu thích nghi nhẹ, có blend/clamp/EMA để hạn chế dao động.
```

Không nên nói:

```text
EWM chứng minh tiêu chí tối ưu hoặc tạo ra trọng số đúng tuyệt đối.
```

---

## 10. Rủi ro còn lại

- Chỉ có 3 backend nên entropy weight nhạy với outlier.
- AHP prior đã có ma trận so sánh cặp và CR trong `ahp-default-weight-rationale.md`, nhưng vẫn cần benchmark/ablation để chứng minh hiệu quả thực nghiệm.
- Các bound và blend factor vẫn là tham số thực nghiệm.
- Cần ablation và sensitivity analysis để chứng minh dynamic weight thật sự có ích.
