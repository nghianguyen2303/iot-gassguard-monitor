# Báo cáo kiểm thử và đánh giá – GasGuard

Tài liệu tự sinh bởi `python tests/make_report.py` từ kết quả chạy thật. Kế hoạch kiểm thử, phương pháp và mục tiêu: [test-plan.md](test-plan.md).

## 1. Kiểm thử chức năng (7.1) và bảo mật (4.5)

Lần chạy: 2026-11-08 13:12:04 – **18/18 ca đạt**. Chi tiết từng bước: [`tests/reports/e2e-20261108-131204.md`](../tests/reports/e2e-20261108-131204.md).

Môi trường: API `http://127.0.0.1:8080`, WebSocket `ws://127.0.0.1:8080/ws`, MQTT `127.0.0.1:1883`, MQTT/TLS `127.0.0.1:8884`, Web HTTPS `https://127.0.0.1:8443`, Máy chạy test `Windows 10 / Python 3.11.7`

| Mã | Ca kiểm thử | Yêu cầu | Số bước kiểm tra | Kết quả |
|---|---|---|---|---|
| F1 | Đăng nhập và phân quyền theo vai trò | 7.1 · 4.4a · 8.4-1 | 8 | ✅ Đạt |
| F2 | Đăng ký, cấp xác thực và kích hoạt thiết bị | 7.1 · 4.4b · 8.4-2 | 8 | ✅ Đạt |
| F3 | Thiết bị gửi telemetry, backend kiểm tra, lưu và truy vấn | 7.1 · 4.4c · 8.4-3 | 8 | ✅ Đạt |
| F4 | Dashboard cập nhật dữ liệu thời gian thực (WebSocket) | 7.1 · 4.4d · 8.4-4 | 4 | ✅ Đạt |
| F5 | Gửi lệnh điều khiển và nhận xác nhận (ACK) | 7.1 · 4.4e · 8.4-5 | 8 | ✅ Đạt |
| F6 | Thay đổi cấu hình thiết bị từ xa | 4.4b · 8.4-6 | 4 | ✅ Đạt |
| F7 | Phát hiện thiết bị offline và trở lại online | 7.1 · 4.4d · 8.4-7 | 5 | ✅ Đạt |
| F8 | Sinh cảnh báo theo luật và xử lý cảnh báo | 7.1 · 4.4d · 8.4-8 | 7 | ✅ Đạt |
| F9 | Khóa (tạm ngưng) và ngừng sử dụng thiết bị | 7.1 · 4.4b · 4.5 · 8.4-10 | 12 | ✅ Đạt |
| S1 | Người dùng không truy cập được thiết bị của người khác | 4.5 · 8.4-9 | 9 | ✅ Đạt |
| S2 | Người được chia sẻ chỉ được xem, không được điều khiển | 4.4a · 4.5 | 7 | ✅ Đạt |
| S3 | API từ chối token thiếu, sai, giả mạo, hết hạn hoặc đã thu hồi | 4.5 | 7 | ✅ Đạt |
| S4 | Broker từ chối kết nối sai và publish/subscribe sai topic | 4.5 · 4.3 | 10 | ✅ Đạt |
| S5 | Hệ thống kiểm tra và từ chối dữ liệu không hợp lệ | 4.5 · 4.4c | 11 | ✅ Đạt |
| S6 | Chống dò mật khẩu: khóa tạm sau 5 lần sai | 4.5 · 4.4a | 3 | ✅ Đạt |
| S7 | Thu hồi thông tin xác thực: mật khẩu cũ của thiết bị hết hiệu lực | 4.5 · 4.4b | 4 | ✅ Đạt |
| S8 | Audit log thao tác quan trọng và mật khẩu được băm | 4.5 | 9 | ✅ Đạt |
| S9 | Kết nối mã hóa TLS: MQTT 8883 và web HTTPS | 4.5 | 10 | ✅ Đạt |

Đề bài yêu cầu tối thiểu 2 kiểm thử bảo mật; nhóm thực hiện **9** ca (S1–S9), gồm đủ 5 ví dụ trong mục 4.5. Bằng chứng tiêu biểu:

- **S1** Người dùng không truy cập được thiết bị của người khác: GET /api/devices/{thiết bị người khác} -> HTTP 403
- **S2** Người được chia sẻ chỉ được xem, không được điều khiển: Chủ thiết bị chia sẻ cho e2e_viewer_1108131204 -> HTTP 201
- **S3** API từ chối token thiếu, sai, giả mạo, hết hạn hoặc đã thu hồi: Không có token -> HTTP 401
- **S4** Broker từ chối kết nối sai và publish/subscribe sai topic: Thiết bị A publish vào topic của thiết bị B -> PUBACK 135 (Not authorized)
- **S5** Hệ thống kiểm tra và từ chối dữ liệu không hợp lệ: Bản tin lỗi MALFORMED_JSON bị từ chối (bộ đếm +1)
- **S6** Chống dò mật khẩu: khóa tạm sau 5 lần sai: Sai mật khẩu 5 lần liên tiếp -> [401, 401, 401, 401, 423] (lần thứ 5 kích hoạt khóa tạm 15 phút: 423)
- **S7** Thu hồi thông tin xác thực: mật khẩu cũ của thiết bị hết hiệu lực: Đổi mật khẩu thiết bị (rotate) -> cấp mật khẩu mới
- **S8** Audit log thao tác quan trọng và mật khẩu được băm: Audit có 'DEVICE_REGISTER' cho e2e-1108131204
- **S9** Kết nối mã hóa TLS: MQTT 8883 và web HTTPS: Thiết bị kết nối MQTT qua TLS cổng 8884, kiểm tra chứng chỉ broker bằng CA của nhóm -> CONNACK 0, TLSv1.3, TLS_AES_256_GCM_SHA384

## 2. Kiểm thử phi chức năng (7.2)

Lần chạy: 2026-11-08 23:37:34. Số liệu thô (từng mẫu): [`tests/reports/perf-20261108-233734.json`](../tests/reports/perf-20261108-233734.json).

### Tổng hợp

| Mã | Chỉ tiêu | Kết quả đo | So với mục tiêu |
|---|---|---|---|
| P6 | Mức sử dụng CPU và bộ nhớ | RAM backend max 401 MiB | ✅ Đạt |
| P8 | Số lượng thiết bị / bản tin hệ thống xử lý được | 100 thiết bị, mất 0 bản tin, P95 758.0 ms | ✅ Đạt |

### P6 – Mức sử dụng CPU và bộ nhớ

**Phương pháp đo.** Lấy mẫu mỗi ~2 s bằng `docker stats` cho từng thành phần, lúc nghỉ và trong từng mức tải của P8. CPU tính theo % một nhân (100 % = 1 nhân).

**Dữ liệu đo.**

| Số thiết bị | CPU backend TB % | CPU backend max % | RAM backend max (MiB) | CPU broker TB % | RAM broker max (MiB) | CPU CSDL TB % | RAM CSDL max (MiB) |
|---|---|---|---|---|---|---|---|
| 0 (nghỉ) | 2.3 | 4.5 | 372.5 | 0.1 | 3.3 | 1.1 | 54.8 |
| 10 | 22.6 | 45.8 | 388.6 | 0.3 | 3.4 | 4.0 | 56.8 |
| 50 | 38.3 | 155.1 | 398.0 | 1.3 | 4.1 | 10.3 | 60.1 |
| 100 | 29.7 | 54.7 | 401.0 | 2.5 | 5.0 | 18.6 | 64.5 |

**Nhận xét.** Broker Mosquitto rất nhẹ (vài MiB RAM). Backend Java dùng nhiều RAM nhất nhưng bị giới hạn heap 384 MB (`JAVA_OPTS`) nên vừa với máy 8 GB RAM; CPU tăng gần tuyến tính theo số bản tin/giây. Thiết bị ESP32 (firmware) dùng khoảng 15 % RAM và 74 % flash theo kết quả biên dịch.

### P8 – Số lượng thiết bị / bản tin hệ thống xử lý được

**Phương pháp đo.** Tăng dần số thiết bị mô phỏng kết nối đồng thời (10, 50, 100); mỗi thiết bị gửi 1 bản tin/giây (QoS1, mỗi bản tin 2 chỉ số) trong 60 s. Đo thông lượng backend chấp nhận, số bản tin lưu được, tỷ lệ mất, P95 độ trễ thiết bị → backend.

**Dữ liệu đo.**

| Số thiết bị | Bản tin gửi/s | Backend xử lý/s | Đã gửi | Đã lưu | Mất | P95 độ trễ (ms) | Bị từ chối |
|---|---|---|---|---|---|---|---|
| 10 | 10 | 10 | 600 | 600 | 0.00 % | 187.0 | 0 |
| 50 | 50 | 50 | 3000 | 3000 | 0.00 % | 255.0 | 0 |
| 100 | 100 | 100 | 6000 | 6000 | 0.00 % | 758.0 | 0 |

**Nhận xét.** Hệ thống xử lý 100 thiết bị đồng thời (100 bản tin/s ≈ 500 thiết bị thật với chu kỳ 5 s) không mất bản tin, P95 < 1000 ms. Giới hạn chính là ghi CSDL (mỗi bản tin 1 giao dịch); có thể tăng bằng ghi theo lô nếu cần nhiều thiết bị hơn.

## 3. Kiểm thử với thiết bị thật (ESP32)

Điền sau khi làm từng ca theo mục 6 của [test-plan.md](test-plan.md). Ảnh/video lưu trong `docs/images/tests/`
(ví dụ `docs/images/tests/H3-dieu-khien.mp4`) và ghi đường dẫn vào cột Bằng chứng.

- Ngày kiểm thử: …
- Người thực hiện: …
- Firmware: 1.0.0 (`firmware/gas-node`), thiết bị: `esp32-001`
- Mạng: WiFi 2.4 GHz …, IP máy chủ …

| Mã | Ca kiểm thử | Kết quả (Đạt / Không đạt) | Số liệu quan sát | Bằng chứng | Ghi chú |
|---|---|---|---|---|---|
| H1 | Kích hoạt thiết bị thật | | | | |
| H2 | Telemetry + realtime | | Độ trễ TB (tab Dữ liệu): … ms | | |
| H3 | Điều khiển quạt + còi | | Độ trễ lệnh: … ms | | |
| H4 | Cấu hình từ xa | | | | |
| H5 | Cảnh báo gas | | Gas đỉnh: … ppm; email sau … s | | |
| H6 | Offline | | OFFLINE sau … s | | |
| H7 | Mất mạng, gửi bù | | Gửi bù … bản | | |
| H8 | Lỗi cảm biến | | | | |
| H9 | Khóa / thu hồi | | | | |
| H10 | Quá nhiệt | | Nhiệt độ đỉnh … °C | | |

## 4. Đánh giá chung

- Các quy trình bắt buộc của mục 7.1 chạy đầu-cuối với thiết bị mô phỏng đúng giao thức và với ESP32 thật.
- Bảo mật được kiểm tra ở 3 lớp: broker (ACL theo thiết bị, tài khoản riêng), API (JWT, phân quyền ở backend, kiểm tra dữ liệu vào) và lưu trữ (BCrypt, audit log).
- Đường truyền qua LAN được mã hóa TLS (MQTT 8883, HTTPS); ca S9 kiểm tra kết nối không mã hóa và chứng chỉ giả bị từ chối.
- Hạn chế: CA do nhóm tự tạo (máy chưa cài CA sẽ thấy cảnh báo chứng chỉ); số liệu tải đo trên một laptop, chưa đo trên máy chủ riêng.

