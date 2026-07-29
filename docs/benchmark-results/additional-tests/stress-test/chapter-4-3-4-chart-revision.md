# Sửa đổi Mục 4.3.4 (Nhận xét tổng hợp biểu đồ 300-900 RPS)
Dựa trên nhận xét của Claude và việc đọc kỹ cấu trúc quyển luận văn (từ trang 53 đến trang 57), tôi hoàn toàn đồng ý với góc nhìn của người phản biện. 

### Điểm chưa tốt hiện tại trong PDF:
1. **Tính lặp lại (Redundancy):** Các Bảng 4.7, 4.8, 4.9 đã liệt kê rõ ràng chỉ số của từng mức tải (300, 600, 900 RPS), và các đoạn ngay bên dưới bảng đã tóm tắt đặc điểm đó. Khi đến phần 4.3.4 (trang 55), việc bạn liệt kê lại "Tại mức 300 RPS... Khi tải đạt 600 RPS... Tại 900 RPS..." cho từng biểu đồ khiến luận văn bị loãng và mang tính "kể chuyện" (narrative) hơn là "phân tích" (analytical).
2. **Thiếu sự liên kết chéo (Cross-metric Analysis):** Sức mạnh của việc đặt 3 biểu đồ (Average, P95, P99) cạnh nhau không phải là để xem tải nào cao hơn, mà là để thấy **sự biến thiên nội tại** của từng thuật toán khi đi từ mức trung bình (Average) đến nhóm yêu cầu chậm nhất (P99). Least Connections trông khá ổn ở Average Latency (1504ms), nhưng lại bộc lộ yếu điểm chí mạng ở P99 (13625ms).

### Hướng giải quyết & Cấu trúc mới:
- Chúng ta sẽ **gộp chung** sự phân tích của Hình 4.2 (Avg), Hình 4.3 (P95) và Hình 4.4 (P99) vào một đoạn so sánh chéo duy nhất.
- Bỏ hẳn văn phong liệt kê theo dòng thời gian (300 -> 600 -> 900). Tập trung phân tích trạng thái bão hòa (900 RPS) để thấy rõ sự khác biệt giữa xu hướng chung (Avg) và độ trễ đuôi (P99).

---

## DƯỚI ĐÂY LÀ ĐOẠN VIẾT LẠI HOÀN CHỈNH CHO MỤC 4.3.4
*(Bạn có thể thay thế toàn bộ văn bản từ dưới Hình 4.2 ở trang 55 cho đến hết Hình 4.4 ở trang 56 bằng đoạn dưới đây)*

### 4.3.4 Nhận xét tổng hợp qua biểu đồ phân vị độ trễ
Sự phân hóa hiệu năng giữa các chiến lược định tuyến được thể hiện rõ nét nhất khi đối chiếu chéo giữa xu hướng độ trễ trung bình (Hình 4.2) và độ trễ phân vị đuôi (Hình 4.3 và Hình 4.4) tại mức tải cao 900 RPS. 

Trong khi Average Latency chỉ phản ánh bức tranh tổng thể, P95 và P99 đóng vai trò như một lăng kính phóng đại khả năng kiểm soát điểm nghẽn của thuật toán. Tại 900 RPS, chiến lược Least Connections thể hiện độ trễ trung bình ở mức có thể chấp nhận được (1504.05 ms), tuy nhiên, khi quan sát ở phân vị đuôi, giá trị P95 (4968.82 ms) đã vọt lên đến P99 (13625.42 ms) — tương đương mức tăng xấp xỉ 2.7 lần. Hiện tượng này minh chứng cho đặc tính dễ bị mắc kẹt (hold-and-wait) của Least Connections: một lượng nhỏ yêu cầu bị dồn vào các backend đang suy thoái ngầm, gây bùng nổ độ trễ cực đoan dù tải trung bình trông có vẻ ổn định. Tương tự, Round Robin và Random cũng nhanh chóng đánh mất sự kiểm soát, đẩy toàn bộ phân vị đuôi vượt ngưỡng 11.000 ms.

Ngược lại, thuật toán Adaptive chứng minh khả năng kháng nhiễu và bảo vệ độ trễ đuôi vượt trội. Mức tăng từ P95 (2237.65 ms) lên P99 (3803.56 ms) của Adaptive chỉ xấp xỉ 1.7 lần. Sự co hẹp biên độ giữa P95 và P99 chỉ ra rằng cơ chế tự động điều chỉnh trọng số (EWM) và bộ lọc làm mượt (EWMA) đã phân tán rủi ro đồng đều, phản ứng kịp thời trước khi một node chuyển sang trạng thái bão hòa sâu. Điều này trực tiếp làm giảm độ phân tán của thời gian chờ (variability), giúp hệ thống duy trì được tính tất định (deterministic) ngay cả khi bị đẩy đến sát ngưỡng chịu đựng.

*(Lưu ý cho bạn: Biểu đồ P95 và P99 ở Hình 4.3 và Hình 4.4 hiện tại sử dụng thang đo tuyến tính nhưng chênh lệch quá lớn, bạn có thể chú thích thêm về việc các thuật toán tĩnh tạo ra cột P99 quá cao làm lấp đi cột của Adaptive).*

---

### Ghi chú về Cấu trúc (Dành riêng cho bạn)
Hiện tại trong quyển PDF của bạn, mục **4.3.4** đang là phần "Nhận xét thông qua biểu đồ" cho các mức tải 300, 600, 900 RPS. 
Tuy nhiên ở phiên làm việc trước, tôi đã cung cấp cho bạn một bài viết RẤT LỚN về **Stress Test 1200 RPS** và đặt tên nó là mục **4.3.4**. 
Do đó, khi lắp ráp vào file Word/LaTeX, bạn nên đánh số lại như sau để mạch văn logic nhất:

- **4.3.1** Kịch bản tải thấp – 300 RPS
- **4.3.2** Kịch bản tải vừa – 600 RPS
- **4.3.3** Kịch bản tải cao – 900 RPS
- **4.3.4 Nhận xét tổng hợp phân vị độ trễ (300 - 900 RPS)** *(Chính là nội dung tôi vừa viết lại ở trên)*
- **4.3.5 Kịch bản Stress Test – 1200 RPS kết hợp Graceful Degradation** *(Chính là file `chapter-4-stress-test-analysis.md` dài 2 trang tôi đã viết cho bạn ở lần trước)*

Việc cấu trúc như vậy sẽ giúp Luận văn cực kỳ chặt chẽ: Dẫn dắt từ từ (tải tăng dần) -> Chốt lại điểm yếu của baseline bằng so sánh chéo biểu đồ -> Tung "Đòn quyết định" bằng bài Stress Test 1200 RPS cực độ. Hội đồng đọc vào sẽ thấy một sự tăng tiến logic hoàn hảo!
