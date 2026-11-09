# Kiểm thử realtime (WebSocket/STOMP)

`ws_client.py` đóng vai **dashboard**: đăng nhập, kết nối `ws://localhost:8080/ws` bằng JWT,
subscribe `/topic/devices/{deviceCode}` và in mọi sự kiện realtime. Khi dừng in thống kê
**độ trễ cảm biến -> dashboard** (mục 7.2).

## Cài đặt
```powershell
cd tests\realtime
py -m pip install -r requirements.txt
```

## Dùng
| Mục đích | Lệnh |
|---|---|
| Xem realtime mọi thiết bị mình được xem | `py ws_client.py --username chu1 --password Chu12345` |
| Đo độ trễ 2 phút cho 1 thiết bị | `py ws_client.py --username chu1 --password Chu12345 --devices esp32-01 --duration 120 --quiet` |
| Kiểm thử bảo mật: token sai | `py ws_client.py --token abc.def.ghi --devices esp32-01` -> `TỪ CHỐI KẾT NỐI` |
| Kiểm thử bảo mật: xem thiết bị người khác | `py ws_client.py --username chu2 --password ... --devices esp32-01` -> `TỪ CHỐI` |

## Loại sự kiện
| type | Khi nào |
|---|---|
| `telemetry` | Telemetry mới đã lưu DB (có `ts` thời điểm đo, `readings`) |
| `connectivity` | ONLINE / OFFLINE / FAULT (kèm lý do: LWT, WATCHDOG, SHUTDOWN, SENSOR_FAULT...) |
| `lifecycle` | Đổi trạng thái vòng đời (ACTIVE, MAINTENANCE, SUSPENDED...) |
| `alert` | Cảnh báo CREATED / RECURRED / CLEARED / ACKNOWLEDGED / RESOLVED |
| `command` | Lệnh SENT / SUCCESS / FAILED / TIMEOUT (kèm độ trễ lệnh) |
| `config` | Cấu hình PENDING / APPLIED / REJECTED |
| `event` | Thiết bị tự báo (AUTO_FAN_ON, SENSOR_FAULT...) |

## Phương pháp đo độ trễ
Độ trễ = thời điểm client nhận bản tin - `ts` (thời điểm thiết bị đo, đồng bộ NTP).
Chạy simulator và client trên **cùng một máy** để dùng chung đồng hồ. Với ESP32 thật,
đồng hồ ESP32 đồng bộ NTP nên sai số đồng hồ cỡ vài chục ms; ghi rõ điều này trong báo cáo.
