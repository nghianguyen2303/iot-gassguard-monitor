# Kết quả kiểm thử với thiết bị thật (ESP32)

Điền sau khi làm từng ca theo mục 6 của [test-plan.md](test-plan.md); phiếu làm từng bước:
[hardware-test-checklist.md](hardware-test-checklist.md). Ảnh/video lưu trong `docs/images/tests/`
(ví dụ `docs/images/tests/H3-dieu-khien.mp4`) và ghi đường dẫn vào cột Bằng chứng.

- Ngày kiểm thử: 09/11/2026 (giờ Việt Nam, UTC+7)
- Người thực hiện: Đỗ Bình Minh
- Firmware: 1.0.0 (`firmware/gas-node`), thiết bị: `esp32-001` (hw `esp32-devkit-mq2-ds18b20`)
- Mạng: WiFi 2.4 GHz …, IP máy chủ …
- Nguồn số liệu: bảng `telemetry`, `commands`, `alerts`, `audit_logs` (PostgreSQL) và log `iot-backend`

| Mã | Ca kiểm thử | Kết quả (Đạt / Không đạt) | Số liệu quan sát | Bằng chứng | Ghi chú |
|---|---|---|---|---|---|
| H1 | Kích hoạt thiết bị thật | Đạt | Tự kích hoạt 11:23:58 (PROVISIONED → ACTIVE, trigger `STATUS_ONLINE`); web: ACTIVE, ONLINE, fw 1.0.0 | | |
| H2 | Telemetry + realtime | Đạt | Độ trễ TB (tab Dữ liệu): ≈ 57 ms (1.432 bản ghi) | | Giá trị thô −238 ms do đồng hồ máy chủ (Windows/Docker) chậm 295 ms so với NTP; đã hiệu chỉnh |
| H3 | Điều khiển quạt + còi | Đạt | Độ trễ lệnh: TB 86 ms (52–135 ms); 14/14 lệnh "Thành công" | | FAN_SET, BUZZER_SET, FAN_AUTO, BUZZER_AUTO |
| H4 | Cấu hình từ xa | Đạt | Cấu hình v2 gửi 12:16:35.708 → "Đã áp dụng" 12:16:35.779 (71 ms); chu kỳ gửi 5,0 s → 3,0 s từ seq 486 | | Ngưỡng thử là 390 ppm (không phải 300 ppm) |
| H5 | Cảnh báo gas | Đạt | Gas đỉnh: 10000 ppm (trần thang đo, 11:39:07); email sau … s | | Cảnh báo CRITICAL 11:37:36 ("3909,4 ppm > 1000 ppm"). Log gửi email bị mất do backend khởi động lại lúc 12:02 |
| H6 | Offline | Đạt | OFFLINE sau 27 s (bản tin cuối 12:19:14 → OFFLINE/LWT 12:19:41) | | ONLINE lại 12:20:51, cảnh báo OFFLINE tự xóa |
| H7 | Mất mạng, gửi bù | Đạt | Gửi bù 8 bản (seq 176–183, đo 11:49:04–11:50:31, nhận dồn 11:50:33) | | Không trùng seq |
| H8 | Lỗi cảm biến | Chưa kiểm thử | | | Chưa có cảnh báo FAULT/SENSOR_FAULT trong DB |
| H9 | Khóa / thu hồi | Chưa kiểm thử | | | Chưa có thao tác khóa thiết bị trong audit log |
| H10 | Quá nhiệt | Chưa kiểm thử | Nhiệt độ đỉnh 29,19 °C | | Chưa đạt 45 °C |
