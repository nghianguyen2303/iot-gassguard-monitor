# Thiết bị mô phỏng (simulator)

Chương trình Python đóng vai **ESP32 gas node** (MQ-2 + DS18B20 + quạt), nói đúng giao thức MQTT
của hệ thống. Dùng để kiểm thử backend khi chưa ghép phần cứng và để **kiểm thử tải** (mục 5.3, 7.2).
Theo mục 4.2 của đề, thiết bị mô phỏng chỉ dùng để kiểm thử, **không thay thế** thiết bị thật khi demo.

## Cài đặt
```powershell
cd tests\simulator
py -m pip install -r requirements.txt
```

## Chạy 1 thiết bị
Thiết bị phải có tài khoản MQTT riêng (do backend cấp khi provisioning, hoặc tạo tay bằng `mosquitto_ctrl`).
```powershell
py simulator.py --device sim-001 --password <mat-khau>
```

## Các kịch bản kiểm thử

| Mục đích | Lệnh |
|---|---|
| Rò rỉ gas (gas tăng dần sau 20 s, tự bật quạt khi vượt ngưỡng) | `py simulator.py --device sim-001 --password X --scenario leak` |
| Lỗi cảm biến nhiệt (gửi event SENSOR_FAULT, 30 s sau SENSOR_OK) | `... --scenario fault --fault-after 10 --fault-duration 30` |
| Mất điện đột ngột -> broker phát LWT OFFLINE | `... --kill-after 15` |
| Gửi trùng 30% telemetry -> backend phải loại trùng | `... --duplicate-rate 0.3` |
| Thiết bị không phản hồi lệnh -> lệnh phải TIMEOUT | `... --no-ack` |
| Lệnh chậm 3 s (đo độ trễ lệnh) | `... --ack-delay 3` |
| Gửi 9 loại dữ liệu sai rồi thoát (kiểm thử bảo mật 4.5) | `... --send-invalid` |
| Nhiều thiết bị (kiểm thử tải) | `py simulator.py --devices-file devices.json --interval 2 --quiet` |
| Chạy cố định 10 phút rồi in thống kê | `... --duration 600` |

Xem đủ tham số: `py simulator.py --help`.

`devices.json` chứa mật khẩu thật nên **đã bị .gitignore**; chỉ commit `devices.example.json`.

## Hành vi giống firmware thật
- **ClientId = username = deviceId**, mỗi thiết bị một mật khẩu riêng.
- **LWT** trên `status` (retained): broker tự báo OFFLINE khi mất kết nối bất thường.
  - Tiến trình bị tắt đột ngột: socket đóng -> broker phát LWT gần như ngay lập tức.
  - Mất mạng (rút WiFi): broker chỉ phát hiện sau khoảng 1,5 x keepalive (mặc định 15 s -> ~22 s).
- **Tự kết nối lại** (giãn dần 1 -> 30 s), sau khi kết nối lại tự nhận lại cấu hình retained.
- **Lệnh**: nhớ 20 `commandId` gần nhất; lệnh bị giao trùng (QoS 1) không thực thi lại mà chỉ gửi lại ACK cũ.
- **Cấu hình**: kiểm tra hợp lệ, trả `APPLIED` hoặc `REJECTED` kèm lý do qua `config/ack`.
- **Luật tại thiết bị (edge)**: `autoFan=true` và gas > `gasThreshold` -> tự bật quạt, gửi event `AUTO_FAN_ON`,
  hoạt động cả khi mất kết nối backend.
- Khi offline: telemetry đến lượt gửi bị bỏ qua và được đếm (`bỏ lỡ khi offline`) để tính tỉ lệ mất bản tin.

## Thống kê khi dừng (Ctrl+C)
In số telemetry đã gửi, `seq` cuối, số bản gửi trùng, số lần kết nối... để so với số bản ghi backend lưu
được -> tính **tỉ lệ mất bản tin** và **khả năng xử lý trùng lặp** (mục 7.2).
