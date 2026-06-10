package com.truongquycode.apigatewayalb.dataplane;

import com.truongquycode.apigatewayalb.model.ScoreBreakdown;
import com.truongquycode.apigatewayalb.util.MetricsCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║               ADAPTIVE LOAD BALANCER — Bộ cân bằng tải thích nghi  ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * LUỒNG HOẠT ĐỘNG TỔNG QUAN:
 *
 *   [MetricsPoller]  poll /alb-metrics mỗi 200ms
 *       │  latency, cpu, queue
 *       ▼
 *   [ScoreCalculator]  EWMA → normalize → MCDM + PID → finalScore
 *       │  ScoreBreakdown(finalScore)
 *       ▼
 *   [MetricsCache]  lưu score theo instanceId
 *       │
 *       ▼
 *   [AdaptiveLoadBalancer.choose()]  ← được gọi mỗi khi có HTTP request mới đến Gateway
 *       │  đọc score từ cache + inflight từ InflightTracker
 *       ▼
 *   Chọn theo weighted routing: score thấp có xác suất cao hơn, nhưng không bỏ đói node khác
 *
 * Điểm quan trọng: score thấp = instance tốt (ít tải, nhanh, CPU thấp).
 * Load balancer KHÔNG tự tính score — nó chỉ ĐỌC score từ MetricsCache và
 * điều chỉnh thêm dựa trên số request đang bay (inflight) tại thời điểm hiện tại.
 */
@Slf4j
@RequiredArgsConstructor
public class AdaptiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    // ──────────────────────────────────────────────────────────────────────────
    // DEPENDENCY INJECTION
    // ──────────────────────────────────────────────────────────────────────────

    /** Cung cấp danh sách ServiceInstance đang UP từ Eureka. */
    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;

    /** Cache chứa ScoreBreakdown (do MetricsPoller cập nhật mỗi 200ms). */
    private final MetricsCache cache;

    /**
     * Tracker số request đang được xử lý (inflight) của từng instance.
     * Được tăng/giảm bởi InflightLifecycle khi request bắt đầu/kết thúc.
     */
    private final InflightTracker inflightTracker;


    // ══════════════════════════════════════════════════════════════════════════
    // HẰNG SỐ TUNING — Điều chỉnh hành vi của thuật toán chọn instance
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Ngưỡng hard-cap cho số request đang xử lý (inflight) của một instance.
     * Instance có inflight ≥ 200 bị loại khỏi danh sách ứng viên hoàn toàn.
     * Tác dụng: tránh "đổ" thêm request vào instance đã quá tải, dù score có tốt.
     */
    private static final int INFLIGHT_HARD_CAP = 200;

    /**
     * Hệ số phạt TƯƠNG ĐỐI theo inflight.
     * Công thức: relPenalty = OMEGA_REL × (inflight_node - inflight_min)
     * Ví dụ: node A có 10 inflight, node B có 5 inflight (min):
     *   relPenalty của A = 0.010 × (10 - 5) = 0.05
     * Mục đích: tránh gửi thêm request vào node đang bận hơn các node khác,
     * ngay cả khi MCDM score của nó vẫn thấp nhất.
     */
    private static final double OMEGA_REL = 0.010;

    /**
     * Hệ số phạt TUYỆT ĐỐI theo mức vượt quá fair share.
     * Công thức: absPenalty = OMEGA_ABS × (excessRatio ^ PENALTY_EXPONENT)
     * Trong đó: excessRatio = (inflight_node / expected_inflight) - 1.0
     *   expected_inflight = totalInflight × share[i]  (phần công bằng theo trọng số)
     * Mục đích: phạt nặng node đang nhận quá nhiều traffic so với "phần của nó".
     */
    private static final double OMEGA_ABS = 0.35;

    /**
     * Số mũ của hàm phạt tuyệt đối — làm cho đường cong phạt phi tuyến.
     * exponent = 1.3 > 1.0 → node vượt 50% fair share bị phạt nặng hơn
     * tỉ lệ thuận, node vượt 100% bị phạt nặng hơn nữa.
     * Nếu = 1.0 (tuyến tính): phạt đều → không ngăn được tập trung tải.
     */
    private static final double PENALTY_EXPONENT = 1.3;

    /**
     * Ở tải thấp, tổng inflight nhỏ nên chỉ lệch 1-2 request cũng có thể làm penalty quá lớn.
     * Vì vậy đặt expected tối thiểu để tránh phạt quá tay trong low-load.
     */
    private static final double MIN_EXPECTED_INFLIGHT = 3.0;

    /** Nếu tổng inflight còn thấp hơn ngưỡng này thì giảm penalty tuyệt đối. */
    private static final int LOW_INFLIGHT_THRESHOLD = 15;

    /** Hệ số giảm penalty khi hệ thống đang low-load. */
    private static final double LOW_INFLIGHT_PENALTY_FACTOR = 0.25;

    /**
     * Score sàn tối thiểu — tránh chia cho 0 trong công thức share[i] = 1/√score.
     * Nếu finalScore từ MCDM = 0.01 (rất nhỏ), sqrt(0.01) = 0.1, không có vấn đề.
     * Nhưng nếu = 0 → chia cho 0 → dùng SCORE_FLOOR = 0.05 làm giới hạn dưới.
     */
    private static final double SCORE_FLOOR = 0.05;

    /**
     * Score mặc định dùng khi instance chưa có dữ liệu trong MetricsCache.
     * Xảy ra lúc instance vừa đăng ký lên Eureka nhưng MetricsPoller chưa kịp poll.
     * 0.35 = mức trung bình — không ưu tiên cũng không tránh instance mới này.
     */
    private static final double DEFAULT_SCORE = 0.35;

    /**
     * Tỉ lệ tối đa giữa share của instance tốt nhất và instance tệ nhất.
     * Công thức: capFloor = maxShare / MAX_CAP_WEIGHT_RATIO = maxShare / 3.0
     * Ý nghĩa: giới hạn chênh lệch share dùng trong công thức fair-share.
     * Lưu ý: chống starvation thật sự nằm ở weighted routing phía dưới.
     */
    private static final double MAX_CAP_WEIGHT_RATIO = 3.0;

    /**
     * Tỉ lệ tối đa giữa trọng số chọn của instance tốt nhất và tệ nhất.
     * Dùng cho weighted routing để instance xấu không bị bỏ đói hoàn toàn.
     */
    private static final double MAX_SELECTION_WEIGHT_RATIO = 8.0;

    /** Điều chỉnh độ nhạy khi đổi routingScore thành trọng số chọn. */
    private static final double SELECTION_SCORE_POWER = 1.6;

    /**
     * Nếu score quá cao thì xem là lỗi thật sự, không probe nữa.
     * Trường hợp poll fail có thể đẩy score lên 2.5, 5.0, 10.0 nên cần chặn.
     */
    private static final double UNHEALTHY_SCORE_CUTOFF = 2.0;

    /**
     * Thời gian warmup cho instance mới (ms) = 5 giây.
     * Trong 5 giây đầu, instance dùng Round-Robin thay vì MCDM.
     * Lý do: instance mới chưa có đủ data trong MetricsCache để MCDM hoạt động chính xác.
     */
    private static final long WARMUP_MS = 5_000;


    // ══════════════════════════════════════════════════════════════════════════
    // STATIC STATE — Shared giữa tất cả request (tồn tại suốt vòng đời ứng dụng)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Thời điểm (epoch ms) mỗi instance được "nhìn thấy lần đầu" bởi load balancer.
     * Key = instanceId, Value = timestamp ms khi lần đầu xuất hiện trong danh sách Eureka.
     * Dùng để tính: (now - firstSeenMs) < WARMUP_MS → instance đang trong giai đoạn warmup.
     * Static vì nhiều request song song cùng chia sẻ thông tin warmup này.
     */
    private static final ConcurrentHashMap<String, Long> firstSeenMs = new ConcurrentHashMap<>();

    /**
     * Bộ đếm Round-Robin dùng trong giai đoạn warmup.
     * Mỗi request tăng lên 1, lấy modulo số instance → chọn lần lượt.
     * AtomicLong đảm bảo thread-safe khi nhiều request đọc/ghi đồng thời.
     * Static để counter tiếp tục đếm liên tục qua các request, không reset.
     */
    private static final AtomicLong rrCounter = new AtomicLong(0);

    /**
     * Cache Prometheus Counter theo instanceId.
     * Mục đích thuần về hiệu năng: tránh gọi Metrics.counter() (lookup tốn kém)
     * mỗi request. Thay vào đó, lần đầu tạo Counter, lưu vào đây, lần sau dùng lại.
     * Key = instanceId, Value = Counter object đã được đăng ký với Prometheus.
     */
    private static final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    /** Ghi nhận lần cuối mỗi instance được chọn, dùng để debug/chống starvation. */
    private static final ConcurrentHashMap<String, Long> lastSelectedMs = new ConcurrentHashMap<>();


    // ──────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Reset toàn bộ static state — được gọi bởi AdminController (POST /actuator/alb/reset)
     * trước mỗi benchmark để đảm bảo kết quả sạch, không bị ảnh hưởng bởi dữ liệu cũ.
     * - firstSeenMs bị xóa → tất cả instance vào lại warmup 5 giây
     * - rrCounter về 0 → round-robin bắt đầu lại từ đầu
     * - counterCache bị xóa → Prometheus Counter được tạo lại (không ảnh hưởng đến data)
     */
    public static void resetStaticState() {
        firstSeenMs.clear();
        rrCounter.set(0);
        counterCache.clear();
        lastSelectedMs.clear();
    }


    // ══════════════════════════════════════════════════════════════════════════
    // ENTRY POINT — Được Spring Cloud Gateway gọi khi có request cần routing
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Điểm vào chính của load balancer — trả về Mono để phù hợp với reactive pipeline.
     *
     * Luồng:
     *   1. Lấy ServiceInstanceListSupplier từ Spring (cung cấp danh sách instance từ Eureka)
     *   2. Lấy danh sách instance → gọi selectBestInstance() để chọn 1 instance
     *   3. Bọc instance đã chọn trong DefaultResponse và trả về
     *
     * Nếu supplier null (Spring chưa init xong) → trả về EmptyResponse để gateway xử lý lỗi.
     */
    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) return Mono.just(new EmptyResponse());
        return supplier.get(request).next().map(this::selectBestInstance);
    }


    // ══════════════════════════════════════════════════════════════════════════
    // CORE ALGORITHM — Thuật toán chọn instance tối ưu
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Chọn instance tốt nhất từ danh sách, dựa trên MCDM score + inflight penalty.
     *
     * ┌─ BƯỚC 1: Thu thập thông tin mỗi instance ─────────────────────────────┐
     * │  - firstSeenMs: phát hiện instance mới vào warmup                     │
     * │  - rawMcdm: score từ MetricsCache (thấp = tốt), fallback 0.35 nếu chưa│
     * │  - inflight: số request đang xử lý tại thời điểm này                  │
     * └───────────────────────────────────────────────────────────────────────┘
     *
     * ┌─ BƯỚC 2: Kiểm tra warmup ──────────────────────────────────────────────┐
     * │  Nếu TẤT CẢ instance đang trong warmup → Round-Robin đơn giản         │
     * │  (xảy ra lúc hệ thống mới khởi động, chưa instance nào có data)        │
     * └───────────────────────────────────────────────────────────────────────┘
     *
     * ┌─ BƯỚC 3: Tính capacity weight share[i] ───────────────────────────────┐
     * │  share[i] = 1 / √(rawMcdm)   → instance tốt (score thấp) = share cao │
     * │  Áp floor: share tối thiểu = maxShare / 3.0                           │
     * │  → Làm mềm fair-share, tránh penalty nghiêng quá mạnh về node tốt nhất     │
     * └───────────────────────────────────────────────────────────────────────┘
     *
     * ┌─ BƯỚC 4: Tính routingScore và chọn instance ──────────────────────────┐
     * │  routingScore = rawMcdm + relPenalty + absPenalty                     │
     * │  Weighted routing: score thấp có xác suất cao hơn, không chọn min tuyệt đối │
     * │  Bỏ qua instance có inflight ≥ INFLIGHT_HARD_CAP                      │
     * └───────────────────────────────────────────────────────────────────────┘
     */
    private Response<ServiceInstance> selectBestInstance(List<ServiceInstance> instances) {
        // Guard: không có instance nào UP
        if (instances == null || instances.isEmpty()) return new EmptyResponse();
        // Chỉ có 1 instance → chọn ngay, không cần tính toán
        if (instances.size() == 1) return new DefaultResponse(instances.get(0));

        int n = instances.size();

        // Snapshot toàn bộ inflight tại thời điểm này.
        // Dùng để tính "fair share" — mỗi instance nên nhận bao nhiêu % traffic.
        int totalInflight = inflightTracker.getTotalInflight();

        long now = System.currentTimeMillis();

        // NodeInfo: record tạm lưu thông tin đã tính toán của mỗi instance trong vòng lặp này.
        // Tránh tính lại nhiều lần: rawMcdm và inflight được đọc 1 lần, dùng lại ở bước 3 và 4.
        record NodeInfo(ServiceInstance inst, double rawMcdm, int inflight, boolean inWarmup) {}
        List<NodeInfo> nodes = new ArrayList<>(n);

        // inflight nhỏ nhất hiện tại — dùng trong relPenalty (phạt tương đối so với node nhàn nhất)
        int minCurrentInflight = Integer.MAX_VALUE;

        // ── BƯỚC 1: Thu thập thông tin mỗi instance ──────────────────────────
        for (ServiceInstance inst : instances) {
            String id = inst.getInstanceId();

            // Ghi nhận thời điểm "lần đầu thấy" instance này.
            // computeIfAbsent: chỉ ghi nếu chưa có — thread-safe, không override.
            long firstSeen = firstSeenMs.computeIfAbsent(id, k -> now);

            // Instance đang trong warmup nếu chưa đủ 5 giây kể từ lần đầu xuất hiện.
            boolean inWarmup = (now - firstSeen) < WARMUP_MS;

            // Đọc ScoreBreakdown từ cache (do MetricsPoller cập nhật mỗi 200ms).
            // Nếu cache chưa có data (instance mới) → dùng DEFAULT_SCORE = 0.35.
            // Math.max(SCORE_FLOOR, ...) đảm bảo score không bao giờ = 0 (tránh chia cho 0).
            ScoreBreakdown bd = cache.getScore(id);
            double rawMcdm = (bd != null) ? Math.max(SCORE_FLOOR, bd.finalScore()) : DEFAULT_SCORE;

            int inflight = inflightTracker.getInflight(id);
            if (inflight < minCurrentInflight) minCurrentInflight = inflight;

            nodes.add(new NodeInfo(inst, rawMcdm, inflight, inWarmup));
        }

        // ── BƯỚC 2: Kiểm tra warmup toàn hệ thống ────────────────────────────
        // Nếu TẤT CẢ instance đang trong warmup (mới khởi động) → Round-Robin.
        boolean allWarmup = true;
        for (NodeInfo node : nodes) {
            if (!node.inWarmup()) { allWarmup = false; break; }
        }
        if (allWarmup) {
            // rrCounter tăng dần, lấy modulo n → chọn lần lượt 0, 1, 2, 0, 1, 2, ...
        	int idx = (int) (rrCounter.getAndIncrement() % n);
        	ServiceInstance sel = nodes.get(idx).inst();
            emitMetric(sel);
            return new DefaultResponse(sel);
        }

        // ── BƯỚC 3: Tính capacity weight share[i] ────────────────────────────
        // share[i] đại diện cho "phần traffic công bằng" mà instance i xứng đáng nhận.
        // Instance tốt (score thấp) → share cao → được routing nhiều hơn.
        //
        // Công thức share[i] = 1/√(rawMcdm):
        //   - rawMcdm = 0.10 (rất tốt) → share = 1/√0.10 = 3.16
        //   - rawMcdm = 0.50 (trung bình) → share = 1/√0.50 = 1.41
        //   - rawMcdm = 1.00 (kém)      → share = 1/√1.00 = 1.00
        // Dùng √ thay vì 1/x để "làm mềm" sự chênh lệch — không để instance tốt
        // chiếm quá nhiều traffic khi score chênh lệch lớn.
        //
        // Instance đang warmup được gán share = 1.0 (trung bình) — chưa đủ data để tin.
        double[] share = new double[n];
        double maxCapW = 0;
        for (int i = 0; i < n; i++) {
            share[i] = nodes.get(i).inWarmup()
                    ? 1.0
                    : 1.0 / Math.sqrt(Math.max(SCORE_FLOOR, nodes.get(i).rawMcdm()));
            if (share[i] > maxCapW) maxCapW = share[i];
        }

        // Áp share floor: share tối thiểu = maxShare / 3.0
        // Ví dụ: instance tốt nhất share=3.0 → instance tệ nhất share ≥ 1.0 (không phải 0.3)
        // Mục đích: instance kém không bị "chết đói" → MetricsPoller vẫn đo được data thực.
        double sumCap = 0;
        double capFloor = maxCapW / MAX_CAP_WEIGHT_RATIO;
        for (int i = 0; i < n; i++) {
            if (share[i] < capFloor) share[i] = capFloor;
            sumCap += share[i];
        }
        // Normalize share về [0,1] để tổng = 1.0 (dùng làm xác suất phân bổ traffic)
        for (int i = 0; i < n; i++) share[i] /= sumCap;

        // ── BƯỚC 4: Tính routingScore và chọn instance ───────────────────────
        // Không chọn min-score tuyệt đối nữa, vì cách đó dễ làm 1 instance bị bỏ đói.
        // Ta đổi sang weighted routing: score thấp vẫn có xác suất cao hơn,
        // nhưng các instance khỏe khác vẫn có cơ hội nhận request để cập nhật metric.
        record Candidate(ServiceInstance inst, double routingScore, double selectionWeight, int inflight) {}
        List<Candidate> candidates = new ArrayList<>(n);

        // Fallback: nếu tất cả instance đều quá tải hoặc bị loại, chọn node ít inflight nhất.
        ServiceInstance leastLoadFb = null;
        int minInflFb = Integer.MAX_VALUE;

        double maxSelectionWeight = 0.0;

        for (int i = 0; i < n; i++) {
            NodeInfo node = nodes.get(i);
            int inf = node.inflight();

            if (inf < minInflFb) {
                minInflFb = inf;
                leastLoadFb = node.inst();
            }

            // Hard cap: node quá tải thì không nhận thêm request mới.
            if (inf >= INFLIGHT_HARD_CAP) continue;

            // Score quá cao thường là poll fail hoặc backend gần như lỗi, không probe nữa.
            if (node.rawMcdm() >= UNHEALTHY_SCORE_CUTOFF) continue;

            double relPenalty = OMEGA_REL * Math.max(0.0, inf - minCurrentInflight);

            double absPenalty = 0.0;
            if (totalInflight > 0) {
                // expected tối thiểu giúp low-load không bị phạt quá mạnh vì lệch 1-2 request.
                double expected = Math.max(MIN_EXPECTED_INFLIGHT, totalInflight * share[i]);
                double excessRatio = (double) inf / expected - 1.0;
                if (excessRatio > 0) {
                    double penaltyFactor = (totalInflight < LOW_INFLIGHT_THRESHOLD)
                            ? LOW_INFLIGHT_PENALTY_FACTOR
                            : 1.0;
                    absPenalty = OMEGA_ABS * penaltyFactor * Math.pow(excessRatio, PENALTY_EXPONENT);
                }
            }

            double routingScore = node.rawMcdm() + relPenalty + absPenalty;

            // Đổi score thành trọng số chọn. Score càng thấp thì trọng số càng cao.
            // Cộng SCORE_FLOOR để tránh trọng số quá lớn khi score rất nhỏ.
            double selectionWeight = 1.0 / Math.pow(routingScore + SCORE_FLOOR, SELECTION_SCORE_POWER);

            if (selectionWeight > maxSelectionWeight) maxSelectionWeight = selectionWeight;
            candidates.add(new Candidate(node.inst(), routingScore, selectionWeight, inf));
        }

        if (candidates.isEmpty()) {
            ServiceInstance fb = (leastLoadFb != null) ? leastLoadFb : instances.get(0);
            log.warn("[ALB] No eligible candidate, fallback to least-inflight instance");
            emitMetric(fb);
            return new DefaultResponse(fb);
        }

        // Áp floor cho trọng số chọn: node còn khỏe thì không bị xác suất 0.
        // Đây là phần chống starvation thật sự, khác với share chỉ dùng để tính penalty.
        double selectionFloor = maxSelectionWeight / MAX_SELECTION_WEIGHT_RATIO;
        double totalWeight = 0.0;
        List<Candidate> floored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            double w = Math.max(c.selectionWeight(), selectionFloor);
            floored.add(new Candidate(c.inst(), c.routingScore(), w, c.inflight()));
            totalWeight += w;
        }

        // Weighted random: giữ tính thích nghi nhưng không còn greedy tuyệt đối.
        double r = ThreadLocalRandom.current().nextDouble(totalWeight);
        ServiceInstance selected = floored.get(floored.size() - 1).inst();
        for (Candidate c : floored) {
            r -= c.selectionWeight();
            if (r <= 0.0) {
                selected = c.inst();
                break;
            }
        }

        emitMetric(selected);
        return new DefaultResponse(selected);
    }


    // ──────────────────────────────────────────────────────────────────────────
    // HELPER
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Ghi nhận việc instance được chọn lên Prometheus counter "alb.routing.selected".
     *
     * Dùng counterCache để tránh gọi Metrics.counter() (tra cứu trong registry)
     * mỗi lần request — thay vào đó tạo Counter 1 lần, tái sử dụng mãi.
     * Tag "backend" và "port" cho phép Grafana phân tách traffic theo từng instance.
     *
     * Ví dụ metric: alb.routing.selected{backend="REGISTRATION-SERVICE-ALB:8081", port="8081"}
     */
    private void emitMetric(ServiceInstance inst) {
        lastSelectedMs.put(inst.getInstanceId(), System.currentTimeMillis());
        counterCache.computeIfAbsent(
                inst.getInstanceId(),
                k -> Metrics.counter(
                        "alb.routing.selected",
                        "backend", inst.getInstanceId(),
                        "port", String.valueOf(inst.getPort())
                )
        ).increment();
    }
}