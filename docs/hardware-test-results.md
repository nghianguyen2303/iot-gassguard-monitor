# Kết quả kiểm thử với thiết bị thật (ESP32)

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
