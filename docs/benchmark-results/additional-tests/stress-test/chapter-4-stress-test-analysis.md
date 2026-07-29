### 4.3.4 Kịch bản Stress Test – 1200 RPS kết hợp Graceful Degradation

Sau khi đánh giá phản ứng của hệ thống dưới các mức tải tăng dần (300, 600 và 900 RPS), kịch bản Stress Test (1200 RPS) được thiết kế nhằm đẩy hệ thống vượt qua ngưỡng bão hòa lý thuyết. Bên cạnh áp lực về lưu lượng, thực nghiệm này còn tiêm vào hệ thống các điểm nghẽn (dependency slowdown) nhằm mô phỏng trạng thái suy thoái cục bộ ở các dịch vụ backend. Mục tiêu trọng tâm của kịch bản không đơn thuần là đánh giá tốc độ xử lý trung bình, mà là kiểm tra tính sẵn sàng, khả năng kiểm soát độ trễ đuôi (Tail Latency), và cơ chế suy giảm có kiểm soát (Graceful Degradation) của các chiến lược định tuyến khi hạ tầng gặp sự cố.

**Bảng 4.4:** Kết quả trung bình của 3 lần chạy ở kịch bản Stress Test 1200 RPS

| Thuật toán | Avg Latency (ms) | P50 (ms) | P95 (ms) | P99 (ms) | Error Rate (%) | Throughput (RPS) | Goodput (RPS)* |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Adaptive Full** | 530.6 | 252.6 | 1905.3 | 3890.9 | 0.06 | 763.5 | 763.0 |
| **Least Connections**| 949.7 | 341.0 | 3470.0 | 10177.4 | 0.33 | 734.6 | 732.1 |
| **Round Robin** | 2255.1 | 2074.6 | 9936.3 | 15147.0 | 43.27 | 744.5 | 422.3 |
| **Random** | 3695.6 | 1893.0 | 15006.6 | 15272.0 | 27.71 | 609.6 | 440.5 |

*(Ghi chú: Giá trị Goodput trong thực nghiệm này là giá trị ước tính dẫn xuất, được tính bằng công thức: Goodput = Throughput × (1 − Error Rate))*

#### Phân tích Average Latency
Dữ liệu từ Bảng 4.4 cho thấy sự phân hóa rõ rệt về độ trễ trung bình khi hệ thống đối mặt với 1200 RPS. Thuật toán Adaptive duy trì mức Average Latency thấp nhất (530.6 ms), thể hiện hiệu suất điều tiết luồng tốt hơn 44% so với thuật toán theo sát phía sau là Least Connections (949.7 ms). Các chiến lược phân phối tải không dựa trên trạng thái (stateless) như Round Robin và Random có mức độ trễ trung bình tăng vọt lên tương ứng 2255.1 ms và 3695.6 ms. Mặc dù Average Latency cho thấy xu hướng phản hồi chung, chỉ số này chưa thể hiện đầy đủ tác động của các điểm nghẽn cục bộ đối với nhóm yêu cầu bị trì hoãn lâu nhất.

*[Đề xuất chèn biểu đồ `fig_01_avg_latency.png` tại đây để trực quan hóa sự chênh lệch độ trễ trung bình]*

#### Phân tích P95/P99 Tail Latency
Khía cạnh quan trọng nhất của kịch bản Stress Test nằm ở khả năng kiểm soát độ trễ phân vị đuôi (Tail Latency). Trong khi Least Connections cố gắng duy trì tỷ lệ lỗi thấp, hệ lụy của nó là hiện tượng kẹt yêu cầu (hold and wait) tại các node đang suy giảm, dẫn đến giá trị P99 vọt lên đến 10177.4 ms (hơn 10 giây). Điều này đồng nghĩa với việc 1% lượng người dùng có trải nghiệm cực kỳ kém hoặc đối mặt với rủi ro vượt quá thời gian chờ (timeout) của ứng dụng khách. 

Ngược lại, thuật toán Adaptive kiểm soát giá trị P99 ở ngưỡng 3890.9 ms, giảm 61.7% so với Least Connections. Mặc dù 3.8 giây vẫn là một độ trễ cao trong điều kiện bình thường, sự chênh lệch này cung cấp bằng chứng cho thấy cơ chế phản hồi động đã hạn chế thành công việc tích tụ yêu cầu vào các node đang gặp sự cố chậm chạp, ngăn chặn sự kéo giãn vô tận của độ trễ. 

*[Đề xuất chèn biểu đồ `fig_02_tail_latency.png` tại đây. Chú ý: Biểu đồ có thể sử dụng trục Logarit (Log Scale) nhằm biểu diễn trực quan khoảng cách biên độ lớn giữa các giá trị P50 và P99]*

#### Phân tích Error Rate và Goodput
Sự khác biệt giữa Throughput (Thông lượng tổng thể) và Goodput (Thông lượng thành công hữu ích) làm nổi bật nhược điểm của các thuật toán tĩnh trong điều kiện tải bão hòa. Round Robin ghi nhận mức Throughput khá cao (744.5 RPS), tuy nhiên tỷ lệ lỗi lên tới 43.27% khiến giá trị Goodput thực tế chỉ còn 422.3 RPS. Lượng Throughput này đại diện cho sự lãng phí tài nguyên mạng và băng thông để xử lý các yêu cầu cuối cùng vẫn dẫn đến lỗi.

Least Connections và Adaptive có tỷ lệ lỗi thấp hơn đáng kể (lần lượt là 0.33% và 0.06%). Thuật toán Adaptive không chỉ duy trì tỷ lệ từ chối dịch vụ tiệm cận mức 0, mà còn đạt mức Goodput cao nhất (763.0 RPS). Sự khác biệt siêu nhỏ giữa Throughput và Goodput ở thuật toán Adaptive (763.5 vs 763.0) cho thấy lượng tài nguyên lãng phí cho các kết nối thất bại là không đáng kể. 

*[Đề xuất chèn biểu đồ `fig_04_throughput.png` hoặc `fig_03_error_rate.png` tại đây để so sánh Throughput/Goodput và Tỷ lệ lỗi]*

#### Phân tích Stability (Mức độ ổn định giữa các lần chạy)
Trong thực nghiệm tính toán, độ ổn định của hệ thống đóng vai trò quan trọng ngang với tốc độ xử lý trung bình. Độ lệch chuẩn của độ trễ trung bình giữa 3 lần chạy liên tiếp cho thấy thuật toán Adaptive (dao động ~67 ms) ổn định hơn rõ rệt so với Least Connections (dao động ~481 ms). Sự dao động lớn của Least Connections gợi ý rằng hiệu năng của nó phụ thuộc nhiều vào thời điểm các điểm nghẽn (chaos) phát sinh, trong khi cơ chế vòng lặp điều khiển phản hồi của Adaptive có khả năng dập tắt nhiễu ngoại vi một cách nhất quán hơn.

#### Phân tích Graceful Degradation (Suy giảm có kiểm soát)
Dữ liệu thu thập được từ thực nghiệm này phù hợp với giả thuyết về cơ chế Graceful Degradation của thuật toán Adaptive. Khi lượng tải vượt ngưỡng thiết kế và các node backend gặp trục trặc, hệ thống sẽ đối diện với hai rủi ro: từ chối dịch vụ hàng loạt (Round Robin/Random) hoặc treo yêu cầu vô hạn (Least Connections). Thay vì rơi vào các kịch bản cực đoan đó, Adaptive có xu hướng chuyển tiếp các yêu cầu sang các node khả dụng hơn thông qua cơ chế cập nhật điểm số (Scoring). Hệ quả là một phần các yêu cầu buộc phải đợi lâu hơn ở các node khỏe (thể hiện qua việc P99 tăng lên mức 3.8s), nhưng đổi lại, hệ thống bảo vệ được tính sẵn sàng (duy trì Error Rate ở mức 0.06%). Quá trình hy sinh một phần tốc độ cục bộ để đảm bảo khả năng phục vụ tổng thể này chính là mục tiêu thiết kế cốt lõi của nguyên lý Graceful Degradation.

#### Phân tích Trade-off (Sự đánh đổi)
Mặc dù Adaptive xếp hạng ưu thế ở đa số các thông số đo lường End-to-End, chiến lược này không thể được xem là một công cụ hoàn hảo không có độ trễ. Sự đánh đổi lớn nhất nằm ở việc hệ thống phải liên tục vận hành các thuật toán định tuyến phức tạp (tính toán hàm trọng số, thăm dò trạng thái) ở tầng Gateway. Dù mức chi phí tài nguyên này không làm giảm Goodput tổng thể trong bài kiểm tra này, nó vẫn yêu cầu sức mạnh xử lý CPU lớn hơn so với chi phí O(1) của Round Robin.

### Tiểu kết 4.3.4
Sự leo thang cường độ kiểm thử từ 300, 600, 900 RPS cho đến ngưỡng bão hòa 1200 RPS (kết hợp với sự cố cục bộ) đã minh họa rõ nét ranh giới hiệu năng của các thuật toán cân bằng tải. Nếu ở mức tải thấp, sự chênh lệch giữa các phương pháp gần như không đáng kể, thì khi hệ thống đối diện với tình trạng cạn kiệt tài nguyên, khả năng định tuyến dựa trên trạng thái đóng vai trò quyết định. Kịch bản Stress Test đã cung cấp bằng chứng cho thấy các cơ chế tĩnh không đủ khả năng đảm bảo tính sẵn sàng (thể hiện qua tỷ lệ lỗi cao). Việc áp dụng mô hình phản hồi tự động (Adaptive) có thể giúp duy trì Goodput cao và ngăn chặn tình trạng kẹt cổ chai (hạ nhiệt Tail Latency), từ đó đáp ứng được yêu cầu về độ bền bỉ (resilience) trong môi trường phân tán.
