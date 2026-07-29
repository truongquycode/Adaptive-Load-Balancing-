# STRESS TEST FINAL DATASET - OFFICIAL FREEZE RECORD

- **Dataset Path**: `D:\eclipse-workspace\adaptive-load-balancer-parent\benchmark-raw-results\additional-tests\stress-test-final`
- **Date/Time of Audit & Freeze**: 2026-07-22 (UTC+7)
- **Total Validated Runs**: 12
- **Strategies Covered**: 
  1. `adaptive_full`
  2. `least_connect`
  3. `random`
  4. `round_robin`

## 1. Pipeline Verification
- **Parser script**: `process_stress_test_results.py`
- **Visualization script**: `generate_stress_test_visualizations.py`
- **Data Integrity Validation**: Verified 100% mathematical consistency (P50 < P95 < P99, Throughput > 0, No NaN/Inf).

## 2. Freeze Declaration
**Đây là bộ dữ liệu (Dataset) được sử dụng CHÍNH THỨC cho Luận văn.**
Kể từ thời điểm này:
1. KHÔNG thay đổi các file JTL thô (raw data).
2. KHÔNG chạy lại (re-run) benchmark cho kịch bản Stress Test 1200 RPS.
3. KHÔNG chỉnh sửa thủ công bất kỳ số liệu CSV hay Biểu đồ nào.

Mọi phân tích, nhận xét, và kết luận học thuật trong Chương 4 của luận văn phải được truy xuất trực tiếp và có tính kế thừa từ bộ dữ liệu đông lạnh (frozen) này.
