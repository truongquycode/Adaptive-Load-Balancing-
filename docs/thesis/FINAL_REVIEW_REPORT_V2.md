# FINAL REVIEW REPORT V2

## 1. Tổng quan
- **Số lỗi đã kiểm tra:** 8
- **Số lỗi đã PASS:** 2
- **Số lỗi còn cần sửa:** 6
- **Đánh giá tổng thể:** Luận văn đang ở trạng thái kỹ thuật xuất sắc. Các chỉnh sửa quan trọng nhất của tác giả (về cấu hình CPU Quota và Bảng 4.8) đã giải quyết triệt để các mâu thuẫn số liệu. Các vấn đề còn lại hiện tại chỉ tập trung vào việc tinh chỉnh văn phong học thuật, diễn đạt toán học và tính nhất quán của thuật ngữ. Sau khi cập nhật các đề xuất dưới đây, luận văn có thể coi là hoàn thiện.

## 2. Xác nhận Lỗi 1 – CPU Quota
- **Trạng thái:** **PASS**
- **Nhận xét ngắn gọn:** Nội dung mới "tỷ lệ 2x (2.0 vCPU so với 1.0 vCPU)" hoàn toàn khớp với thiết kế thực nghiệm trong Bảng 4.4. Đã quét lại toàn bộ tài liệu, không còn bất kỳ đoạn nào nhắc đến tỷ lệ "4x" hay xem 0.5 CPU của Eureka Server như một backend xử lý tải. Chỉnh sửa đã chính xác.

## 3. Xác nhận Lỗi 2 – Error Rate Bảng 4.8
- **Trạng thái:** **PASS**
- **Nhận xét ngắn gọn:** Ghi nhận tác giả đã trực tiếp sửa số liệu Error Rate của thuật toán Round Robin trong Bảng 4.8. Việc chỉnh sửa này giúp dữ liệu trong bảng nhất quán hoàn toàn với Hình 4.5 và nội dung phân tích (mức 0.71%) ở phần phân tích kịch bản tải vừa.

## 4. Các lỗi còn lại

### Lỗi 3 – Lý thuyết hàng đợi Kingman G/G/1
- **Vị trí:** Chương 2 / Mục 2.2.1 (Trang 9).
- **Mức độ:** Quan trọng.
- **Kết quả kiểm tra:** Công thức Kingman được tác giả trình bày là dành riêng cho hệ thống 1 máy chủ (mô hình G/G/1). Việc sử dụng G/G/1 để giải thích nguyên lý quá tải tại *từng instance đơn lẻ* là hoàn toàn hợp lý, nhưng văn bản hiện tại đang gọi nhầm đây là công thức của G/G/c. 
- **Trạng thái:** **CẦN SỬA**
- **Nội dung đề xuất sửa:** Xem Bảng rà soát văn phong (Mục 5).

### Lỗi 4 – Diễn đạt hàm Logarit
- **Vị trí:** Chương 3 / Mục 3.3.3.4 (Trang 41).
- **Mức độ:** Cần cải thiện.
- **Kết quả kiểm tra:** Câu văn "đảm bảo hàm logarit tự nhiên không bao giờ bằng 0" là chưa chuẩn xác về toán học. Hàm $\ln(x) = 0$ khi đối số $x = 1$. Việc hệ thống bị lỗi tràn số (NaN/-Infinity) xảy ra khi đối số $x = 0$. Do đó, hằng số Epsilon được thêm vào là để đảm bảo *đối số* của hàm logarit luôn lớn hơn 0.
- **Trạng thái:** **CẦN SỬA**
- **Nội dung đề xuất sửa:** Xem Bảng rà soát văn phong (Mục 5).

### Lỗi 5 – Danh mục từ viết tắt
- **Vị trí:** Danh mục từ viết tắt (Trang 13).
- **Mức độ:** Nhỏ.
- **Kết quả kiểm tra:** Phát hiện 4 thuật ngữ chuyên ngành xuất hiện dày đặc nhưng chưa được liệt kê.
- **Trạng thái:** **CẦN SỬA**
- **Nội dung đề xuất sửa:** Bổ sung vào danh mục:

| Từ viết tắt | Tiếng Anh | Tiếng Việt |
|---|---|---|
| CAS | Compare-And-Set | Thuật toán so sánh và hoán đổi nguyên tử |
| HDR | High Dynamic Range | Dải động cao |
| RPS | Requests Per Second | Số yêu cầu trên mỗi giây |
| SLA | Service Level Agreement | Thỏa thuận mức dịch vụ |

### Lỗi 6 – Nhất quán tên gọi EWMA / EMA
- **Vị trí:** Hình 3.11 (Trang 37) và Mục 3.3.5.2 (Trang 42, 45).
- **Mức độ:** Nhỏ.
- **Kết quả kiểm tra:** Cùng là kỹ thuật trung bình trượt mũ, nhưng khi làm mượt độ trễ thì dùng "EWMA", còn khi làm mượt trọng số điểm sức khỏe lại dùng "EMA". Cần đồng nhất thành EWMA để giữ tính chuyên nghiệp và tránh người đọc nhầm lẫn đây là hai cơ chế khác nhau.
- **Trạng thái:** **CẦN SỬA**
- **Nội dung đề xuất sửa:** Sửa tiêu đề Mục 3.3.5.2 thành "Làm mượt bằng EWMA". Thay thế các chữ "EMA" trong nội dung phần đó thành "EWMA". Giữ nguyên sơ đồ/code nếu không tiện sửa lại ảnh.

### Lỗi 7 – Diễn đạt Throughput và Goodput
- **Vị trí:** Chương 4 / Trang 57.
- **Mức độ:** Cần cải thiện.
- **Kết quả kiểm tra:** Đoạn văn phân tích định nghĩa Throughput và Goodput hiện tại bị lặp từ "thông lượng" nhiều lần, cấu trúc câu chưa được gãy gọn.
- **Trạng thái:** **CẦN SỬA**
- **Nội dung đề xuất sửa:** Xem Bảng rà soát văn phong (Mục 5).

### Lỗi 8 – Lỗi trình bày khoảng trắng
- **Vị trí:** Chương 4 / Trang 50.
- **Mức độ:** Nhỏ.
- **Kết quả kiểm tra:** Chú thích "Hình 4. 1: Sơ đồ kiến trúc..." bị dư một khoảng trắng.
- **Trạng thái:** **CẦN SỬA**
- **Nội dung đề xuất sửa:** Sửa thành "Hình 4.1: Sơ đồ kiến trúc...". Không cần sửa lại hệ thống đánh số tự động của Word, chỉ cần gõ lại đoạn text chú thích.

## 5. Rà soát văn phong các đoạn sửa

| Nội dung | Trạng thái | Đánh giá | Phiên bản cuối cùng |
|---|---|---|---|
| **Đoạn 1 (CPU Quota)** | **PASS** | Chính xác, rõ ràng, đã được tác giả xác nhận sửa. | *Giữ nguyên nội dung đã sửa của tác giả.* |
| **Đoạn 2 (Kingman)** | **NÊN SỬA** | Đã xử lý được nhầm lẫn giữa G/G/c và G/G/1, giải thích đúng ngữ cảnh quá tải của từng instance, văn phong khách quan. | "Dưới góc nhìn của lý thuyết hàng đợi, các hệ thống truyền thống thường giả định luồng yêu cầu tuân theo mô hình lý tưởng $M/M/c$. Tuy nhiên, thực tế đám mây mang đặc trưng của một mạng lưới hàng đợi phức tạp. Khi xét tại cấp độ một máy chủ (instance) đơn lẻ, hiện tượng quá tải có thể được giải thích một cách chặt chẽ thông qua mô hình hàng đợi $G/G/1$ kết hợp với Công thức xấp xỉ Kingman [14]:" |
| **Đoạn 3 (Logarit)** | **NÊN SỬA** | Chuẩn xác về mặt toán học, giải thích đúng lý do tại sao phải dùng Epsilon. | "Tránh điểm kỳ dị: Mọi điểm số đưa vào EWM đều được cộng thêm một hằng số rất nhỏ ($\epsilon = 1e-9$), đảm bảo giá trị truyền vào (đối số) của hàm logarit tự nhiên luôn lớn hơn 0, loại bỏ rủi ro phát sinh giá trị không xác định hoặc âm vô cực (NaN/-Infinity)." |
| **Đoạn 4 (EWMA)** | **NÊN SỬA** | Hợp lý, giúp đồng nhất được thuật ngữ chuyên ngành xuyên suốt tài liệu. | **3.3.5.2 Làm mượt bằng EWMA**<br><br>Trọng số $W_{health}$ qua hàm EWMA để tránh hiện tượng dao động nhanh:<br><br>$W_{health}(t) = W_{health}(t - 1) + \alpha \cdot (W_{target\_health} - W_{health}(t - 1))$ |
| **Đoạn 5 (Throughput)** | **NÊN SỬA** | Phiên bản đề xuất trước đây hơi dài. Phiên bản này đã được rút gọn, không lặp từ và dễ hiểu hơn. | "Tỷ lệ lỗi cao dẫn đến sự chênh lệch lớn giữa thông lượng tổng thể (Throughput - tổng số yêu cầu đã xử lý) và thông lượng hữu ích (Goodput - số yêu cầu xử lý thành công). Sự chênh lệch này phản ánh lượng tài nguyên bị lãng phí cho các yêu cầu thất bại." |

## 6. Các vấn đề mới phát hiện
Không có vấn đề mới. Toàn bộ các công thức toán học tính toán ma trận AHP, Entropy, PID trong hệ thống đều đã được rà soát sâu và hoàn toàn chính xác.

## 7. Final Cross-check
Kiểm tra chéo lại toàn bộ tài liệu hiện tại:
- **Chương 1 ↔ Chương 2 ↔ Chương 3:** Khái niệm "Tail Latency", "Noisy Neighbor" và kiến trúc giải pháp đồng nhất hoàn toàn.
- **Chương 3 ↔ Chương 4:** (Đã PASS lỗi số liệu) Mô hình tiêm lỗi (Chaos Engineering) mô tả ở Chương 3 khớp hoàn hảo với các kịch bản kiểm thử (Load, Medium, High, Stress) ở Chương 4.
- **Bảng ↔ Hình ↔ Nội dung:** (Đã PASS lỗi Round Robin). Dữ liệu P95/P99, Latency, và Throughput trong mọi biểu đồ đều ánh xạ chính xác 100% từ bảng số liệu.
- **Thuật ngữ:** (Cần sửa danh mục viết tắt và EWMA/EMA như đã nêu). Các thuật ngữ khác (MCDM, P2C, PID) hoàn toàn nhất quán.

## 8. Kết luận cuối cùng
Luận văn đã tiến rất sát đến mức hoàn thiện để in ấn.
- **[PASS]** Nội dung thực nghiệm (CPU Quota, Số liệu Bảng biểu) đã chính xác, không cần chỉnh sửa thêm.
- **[CẦN SỬA]** Chỉ cần tác giả cập nhật 5 nội dung đề xuất cuối cùng ở **Mục 5** (Kingman, Logarit, EWMA, Tóm tắt Throughput và Khoảng trắng tiêu đề Hình) cùng với việc bổ sung 4 từ viết tắt là có thể chốt phiên bản cuối (Final) để nộp.
- **[CẦN XÁC MINH]** Không còn nội dung nào cần xác minh lại với dữ liệu gốc. Mọi thứ đã khớp.
