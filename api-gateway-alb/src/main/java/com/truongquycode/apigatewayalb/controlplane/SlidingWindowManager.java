package com.truongquycode.apigatewayalb.controlplane;

import com.truongquycode.apigatewayalb.model.PercentileSnapshot;
import org.HdrHistogram.Histogram;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SlidingWindowManager {

	// ═══════════════════════════════════════════════════════════════════════════
	// HẰNG SỐ CẤU HÌNH HDR HISTOGRAM
	//
	// HDR Histogram (High Dynamic Range Histogram) lưu phân phối giá trị
	// với độ chính xác cố định, không phụ thuộc vào khoảng giá trị.
	// Phù hợp hơn moving-average vì giữ lại hình dạng phân phối (đuôi dài).
	// ═══════════════════════════════════════════════════════════════════════════

	// Giới hạn trên của HDR Histogram cho latency: 60 giây = 60000ms.
	// Histogram sẽ reject giá trị vượt quá ngưỡng này → addMetrics() phải clamp.
	// 60s đủ để bắt mọi timeout thực tế trong microservice.
	private static final long MAX_LATENCY_MS = 60000L;

	// Giới hạn trên cho queue size: 10000 request đang xử lý đồng thời.
	// Trong thực tế container 1-2 CPU thường không vượt quá vài trăm,
	// nhưng đặt cao để tránh histogram bỏ sót giá trị khi burst bất ngờ.
	private static final long MAX_QUEUE_SIZE = 10000L;

	// Số chữ số có nghĩa (significant digits) của HDR Histogram.
	// SIGNIFICANT_DIGITS = 2 → sai số tối đa ~1% so với giá trị thực.
	// Ví dụ: giá trị 100ms có thể được lưu là 99-101ms, chấp nhận được.
	// Tăng lên 3 → chính xác hơn nhưng tốn RAM gấp ~10x.
	private static final int SIGNIFICANT_DIGITS = 2;

	// Số sample tối đa trong mỗi cửa sổ trượt (sliding window) của TỪNG instance.
	// Khi histogram active vượt 100 sample → flip sang histogram còn lại và reset.
	// 100 sample với polling 200ms ≈ cửa sổ thời gian ~20 giây.
	// Đủ để bắt xu hướng ngắn hạn mà không giữ data lỗi thời quá lâu.
	private static final int WINDOW_SIZE = 100;

	// Số sample tối đa trong cửa sổ toàn hệ thống (global histogram).
	// Lớn hơn WINDOW_SIZE vì global ghi nhận latency của TẤT CẢ instance,
	// nên traffic gấp N lần per-instance → cần window lớn hơn để không flip quá
	// nhanh.
	// 160 sample ≈ toàn hệ thống ~10-13 giây với 3 instance polling 200ms.
	private static final int GLOBAL_WIN_SIZE = 160;

	// Lock dành riêng cho thao tác trên global histogram.
	// Dùng Object lock thay vì synchronized method để scope lock nhỏ nhất có thể:
	// chỉ lock khi đọc/ghi globalPair, không block các thao tác per-instance.
	private final Object globalLock = new Object();

	// ═══════════════════════════════════════════════════════════════════════════
	// DOUBLE-BUFFER (PING-PONG) PATTERN cho Global Histogram
	//
	// Kỹ thuật: dùng 2 histogram luân phiên thay vì 1.
	// - globalPair[active]: đang nhận data mới (ghi vào đây)
	// - globalPair[1-active]: histogram cũ, vẫn đang phục vụ đọc percentile
	//
	// Khi histogram active đủ GLOBAL_WIN_SIZE sample:
	// 1. Chuyển globalActiveIdx sang index kia (1→0 hoặc 0→1)
	// 2. Reset histogram cũ (sẽ trở thành active mới)
	//
	// Lợi ích so với single histogram:
	// - Không bao giờ có window trống hoàn toàn (luôn có data để đọc)
	// - Không phải xóa từng element cũ (chi phí O(1) thay vì O(n))
	// - Thread đọc percentile không bị block khi flip xảy ra
	// ═══════════════════════════════════════════════════════════════════════════
	private final Histogram[] globalPair = { new Histogram(1, MAX_LATENCY_MS, SIGNIFICANT_DIGITS),
			new Histogram(1, MAX_LATENCY_MS, SIGNIFICANT_DIGITS) };
	// Con trỏ (0 hoặc 1) chỉ vào histogram đang active trong globalPair.
	// AtomicInteger để flip an toàn giữa các thread mà không cần lock riêng.
	// Chỉ thay đổi trong synchronized(globalLock) block nên thực ra không cần
	// atomic,
	// nhưng AtomicInteger vẫn dùng để ý định rõ ràng (thread-visible read).
	private final AtomicInteger globalActiveIdx = new AtomicInteger(0);

	// ═══════════════════════════════════════════════════════════════════════════
	// InstanceState: trạng thái HDR Histogram riêng cho từng instance backend.
	//
	// Mỗi instance có 2 cặp histogram (double-buffer):
	// - latHists[0], latHists[1]: lưu phân phối LATENCY (ms)
	// - qHists[0], qHists[1]: lưu phân phối QUEUE LENGTH (số request)
	//
	// latIdx/qIdx: con trỏ chỉ vào histogram đang active (0 hoặc 1) của cặp tương
	// ứng.
	// AtomicInteger cho phép flip index bằng CAS mà không cần synchronized.
	//
	// Dùng Java record (immutable) để đảm bảo không ai thay thế toàn bộ
	// InstanceState
	// sau khi đã computeIfAbsent — chỉ có thể thay đổi nội dung histogram bên
	// trong.
	// ═══════════════════════════════════════════════════════════════════════════
	private record InstanceState(Histogram[] latHists, AtomicInteger latIdx, Histogram[] qHists, AtomicInteger qIdx) {
	}

	// ═══════════════════════════════════════════════════════════════════════════
	// SystemSnapshot: snapshot hệ thống toàn cục tại một thời điểm.
	//
	// Chứa 3 percentile latency tổng hợp của TẤT CẢ instance:
	// p5 → ngưỡng dưới: latency của 5% request nhanh nhất (baseline tốt nhất)
	// p75 → trung vị cao: dùng làm setpoint cho PID controller trong
	// ScoreCalculator
	// p95 → ngưỡng trên: latency của 95% request (biên trên để chuẩn hóa về [0,1])
	//
	// ScoreCalculator dùng p5 và p95 để min-max normalize latency của từng
	// instance:
	// normLatency = (ewmaLat - p5) / (p95 - p5)
	// → instance có latency = p5 cho normLatency = 0 (tốt nhất)
	// → instance có latency = p95 cho normLatency = 1 (tệ nhất)
	// ═══════════════════════════════════════════════════════════════════════════
	public record SystemSnapshot(double p5, double p75, double p95) {
	}

	/**
	 * Lấy snapshot percentile toàn hệ thống (gộp tất cả instance).
	 *
	 * Được gọi bởi ScoreCalculator.calculateScore() để lấy p5/p95 làm biên
	 * chuẩn hóa latency theo công thức:
	 *   normLatency = (ewmaLat - sysP5) / (sysP95 - sysP5)
	 *
	 * Dùng system-wide p5/p95 thay vì per-instance để:
	 *   - Tạo một thước đo chung: instance nào nhanh nhất so với TOÀN HỆ THỐNG?
	 *   - Tránh lệch khi một instance bị cô lập (chỉ nhìn vào mình không đủ context)
	 *
	 * Default khi chưa có data: (5ms, 50ms, 200ms) — con số thực tế thường thấy
	 * trong microservice Java với 50ms I/O delay.
	 */
	public SystemSnapshot getSystemSnapshot() {
		synchronized (globalLock) {
			Histogram h = getSafeGlobalHistogram();
			if (h.getTotalCount() == 0)
				return new SystemSnapshot(5.0, 50.0, 200.0);
			return new SystemSnapshot(h.getValueAtPercentile(5.0), h.getValueAtPercentile(75.0),
					h.getValueAtPercentile(95.0));
		}
	}

	// Map từ instanceId → InstanceState.
	// ConcurrentHashMap cho phép nhiều thread ghi/đọc đồng thời (mỗi entry độc
	// lập).
	// computeIfAbsent() đảm bảo InstanceState chỉ được tạo 1 lần dù nhiều thread
	// cùng gọi.
	private final ConcurrentHashMap<String, InstanceState> instanceStates = new ConcurrentHashMap<>();

	public void addMetrics(String instanceId, double lat, double queue) {

		// Clamp latency vào [1, MAX_LATENCY_MS]:
		// - Min 1 (không phải 0) vì HDR Histogram yêu cầu giá trị > 0 (lowerBound=1)
		// - Max 60000ms để không vượt upperBound của histogram (sẽ throw exception)
		// Cast sang long vì HDR Histogram chỉ nhận integer value (độ phân giải 1ms là
		// đủ)
		long latVal = Math.min(Math.max(1, (long) lat), MAX_LATENCY_MS);

		// Clamp queue tương tự, dùng MAX_QUEUE_SIZE riêng vì đơn vị khác (số request)
		long qVal = Math.min(Math.max(1, (long) queue), MAX_QUEUE_SIZE);

		// Lấy hoặc tạo mới InstanceState cho instance này.
		// computeIfAbsent(): thread-safe, chỉ tạo mới nếu key chưa tồn tại.
		// Mỗi instance được cấp phát 4 histogram (2 cho latency, 2 cho queue)
		// ngay lần đầu tiên xuất hiện → chi phí cấp phát chỉ xảy ra 1 lần.
		InstanceState s = instanceStates.computeIfAbsent(instanceId,
				k -> new InstanceState(
						new Histogram[] { new Histogram(1, MAX_LATENCY_MS, SIGNIFICANT_DIGITS),
								new Histogram(1, MAX_LATENCY_MS, SIGNIFICANT_DIGITS) },
						new AtomicInteger(0), new Histogram[] { new Histogram(1, MAX_QUEUE_SIZE, SIGNIFICANT_DIGITS),
								new Histogram(1, MAX_QUEUE_SIZE, SIGNIFICANT_DIGITS) },
						new AtomicInteger(0)));

		// ── CẬP NHẬT LATENCY HISTOGRAM (double-buffer) ─────────────────────────
		int lActive = s.latIdx().get(); // Đọc index histogram đang active
		s.latHists()[lActive].recordValue(latVal); // Ghi sample vào histogram active

		// Kiểm tra xem histogram active đã đủ WINDOW_SIZE sample chưa.
		// Nếu đủ → flip sang histogram còn lại để bắt đầu cửa sổ mới.
		if (s.latHists()[lActive].getTotalCount() > WINDOW_SIZE) {
			int lNext = 1 - lActive; // Toggle: 0→1 hoặc 1→0

			// CAS (Compare-And-Set): chỉ flip nếu index vẫn là lActive.
			// Tránh race condition: nếu 2 thread cùng vào đây, chỉ 1 thread flip thành
			// công,
			// thread còn lại thấy CAS fail và bỏ qua (histogram đã được flip rồi).
			if (s.latIdx().compareAndSet(lActive, lNext)) {
				// Reset histogram cũ (sẽ trở thành active mới trong lần flip tiếp theo)
				// Lưu ý: histogram cũ vẫn đang được đọc bởi getSnapshot() ở thread khác,
				// nhưng vì chúng ta đã flip index trước khi reset, reader sẽ không bị ảnh
				// hưởng.
				s.latHists()[lNext].reset();
			}
		}

		// ── CẬP NHẬT QUEUE HISTOGRAM (logic tương tự latency) ─────────────────
		int qActive = s.qIdx().get();
		s.qHists()[qActive].recordValue(qVal);
		if (s.qHists()[qActive].getTotalCount() > WINDOW_SIZE) {
			int qNext = 1 - qActive;
			if (s.qIdx().compareAndSet(qActive, qNext)) {
				s.qHists()[qNext].reset();
			}
		}

		// ── CẬP NHẬT GLOBAL HISTOGRAM ──────────────────────────────────────────
		// Global histogram tổng hợp latency của TẤT CẢ instance để tính system-wide
		// percentile.
		// Cần synchronized vì globalPair và globalActiveIdx không dùng CAS mà dùng lock
		// (flip global cần đảm bảo reset và set index xảy ra trong cùng 1 transaction).
		synchronized (globalLock) {
			int gi = globalActiveIdx.get();
			globalPair[gi].recordValue(latVal);

			// Flip logic tương tự per-instance nhưng dùng GLOBAL_WIN_SIZE lớn hơn
			if (globalPair[gi].getTotalCount() > GLOBAL_WIN_SIZE) {
				int next = 1 - gi;
				globalPair[next].reset(); // Reset trước khi flip để tránh đọc data cũ
				globalActiveIdx.set(next); // Flip: từ đây sample mới vào histogram kia
			}
		}
	}

	public PercentileSnapshot getSnapshot(String instanceId) {
		InstanceState s = instanceStates.get(instanceId);

		// Instance chưa có data (chưa được poll lần nào) → trả về fallback.
		// Giá trị fallback (p5=0, p50=50ms, p95=100ms, qP99=10) đủ thực tế
		// để ScoreCalculator hoạt động được trong giai đoạn warm-up đầu tiên.
		if (s == null) {
			return new PercentileSnapshot(0.0, 50.0, 100.0, 10.0);
		}

		// Lấy histogram latency đang active (chứa data gần nhất)
		int lIdx = s.latIdx().get();
		Histogram lh = s.latHists()[lIdx];

		// Histogram active chưa có sample nào (vừa bị reset trong quá trình flip)
		// → fallback tương tự trường hợp null để tránh getValueAtPercentile() throw
		// exception
		if (lh.getTotalCount() == 0) {
			return new PercentileSnapshot(0.0, 50.0, 100.0, 10.0);
		}

		// Lấy p99 của queue làm ngưỡng chuẩn hóa trong
		// NormalizationFunctions.normalizeQueue().
		// Fallback 10.0 khi chưa có data (tránh chia cho 0 trong normalizeQueue).
		double qP99 = 10.0;
		int qIdx = s.qIdx().get();
		Histogram qh = s.qHists()[qIdx];
		if (qh.getTotalCount() > 0) {
			qP99 = qh.getValueAtPercentile(99.0);
		}

		// Trả về snapshot với:
		// p5 → EWMA fallback khi instance chưa có latency đo được (ScoreCalculator)
		// p50 → dùng làm fallback latency khi instance idle
		// (MetricsPoller.calculateDeltaLatency)
		// p95 → ngưỡng trên cho min-max normalization trong ScoreCalculator
		// qP99 → ngưỡng max queue cho NormalizationFunctions.normalizeQueue()
		return new PercentileSnapshot(lh.getValueAtPercentile(5.0), lh.getValueAtPercentile(50.0),
				lh.getValueAtPercentile(95.0), qP99);
	}

	private Histogram getSafeGlobalHistogram() {
	    int gi = globalActiveIdx.get(); // Index của histogram đang active

	    // Ưu tiên histogram active nếu đủ 20 sample (đủ để percentile có ý nghĩa thống kê).
	    // 20 sample là ngưỡng tối thiểu thực nghiệm cho HDR Histogram ở SIGNIFICANT_DIGITS=2.
	    if (globalPair[gi].getTotalCount() >= 20) {
	        return globalPair[gi];
	    }

	    // Histogram active chưa đủ sample (vừa flip, data đang tích lũy) →
	    // dùng histogram cũ (index 1-gi) nếu vẫn còn data.
	    // Đây là điểm mấu chốt của double-buffer: không bao giờ trả về histogram rỗng
	    // nếu còn histogram nào có data, tránh percentile bị lệch về 0.
	    else if (globalPair[1 - gi].getTotalCount() > 0) {
	        return globalPair[1 - gi];
	    }

	    // Cả hai histogram đều chưa có data (hệ thống vừa start hoặc vừa reset).
	    // Trả về histogram active — caller sẽ xử lý trường hợp getTotalCount() == 0.
	    return globalPair[gi];
	}

	/**
	 * Trả về latency P5 toàn hệ thống (ms).
	 * Dùng làm cận dưới (best case) khi chuẩn hóa latency trong ScoreCalculator.
	 * Default 5ms khi chưa có data (latency tối thiểu thực tế của JVM + network stack).
	 *
	 * Lưu ý: 3 method P5/P75/P95 có logic giống nhau nhưng tách riêng
	 * vì caller (ScoreCalculator) đôi khi chỉ cần 1 giá trị,
	 * tránh tạo SystemSnapshot object không cần thiết.
	 */
	public double getSystemP5() {
		synchronized (globalLock) {
			Histogram safeHist = getSafeGlobalHistogram();
			if (safeHist.getTotalCount() == 0)
				return 5.0;
			return safeHist.getValueAtPercentile(5.0);
		}
	}
	
	/**
	 * Trả về latency P95 toàn hệ thống (ms).
	 * Dùng làm cận trên (worst accepted) khi chuẩn hóa latency.
	 * Instance có ewmaLatency = P95 → normLatency = 1.0 (tệ nhất có thể).
	 * Default 200ms (P95 điển hình khi registration service ở trạng thái bình thường).
	 */
	public double getSystemP95() {
		synchronized (globalLock) {
			Histogram safeHist = getSafeGlobalHistogram();
			if (safeHist.getTotalCount() == 0)
				return 200.0;
			return safeHist.getValueAtPercentile(95.0);
		}
	}

	/**
	 * Trả về latency P75 toàn hệ thống (ms).
	 * Dùng làm SETPOINT cho PID controller: khi một instance vượt quá P75 hệ thống,
	 * PID bắt đầu tích lũy penalty → giảm xác suất được chọn.
	 * Default 50ms (trung vị thực tế trong môi trường microservice Docker).
	 */
	public double getSystemP75() {
		synchronized (globalLock) {
			Histogram safeHist = getSafeGlobalHistogram();
			if (safeHist.getTotalCount() == 0)
				return 50.0;
			return safeHist.getValueAtPercentile(75.0);
		}
	}

	public void resetAll() {
	    // Xóa toàn bộ per-instance state.
	    // Lần addMetrics() tiếp theo sẽ computeIfAbsent() tạo lại InstanceState mới.
	    instanceStates.clear();

	    // Reset cả 2 histogram trong global pair về 0 sample.
	    // Không cần synchronized ở đây vì AdminController đã đảm bảo
	    // gọi reset khi hệ thống không đang chạy benchmark.
	    globalPair[0].reset();
	    globalPair[1].reset();

	    // Đưa con trỏ về index 0 (histogram 0 sẽ là active sau reset).
	    globalActiveIdx.set(0);
	}
}