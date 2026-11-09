# Cấu trúc topic và payload MQTT – GasGuard

Thiết bị và backend trao đổi qua broker Mosquitto bằng **8 kênh** dưới gốc `iot/v1/devices/{deviceId}/`. Bản tin là
JSON UTF-8; thời gian là **epoch mili giây UTC** (thiết bị đồng bộ NTP); đơn vị ghi rõ trong từng chỉ số. Nguồn:
`firmware/gas-node/gas-node.ino`, `backend/src/main/java/com/iot/gas/mqtt/MqttTopics.java`,
`ingest/TelemetryValidator.java`, `service/CommandService.java`, `service/ConfigService.java`.

## 1. Danh sách topic

`{deviceId}` = mã thiết bị = MQTT username = clientId (chữ thường, số, `-`, 3–40 ký tự). `v1` là phiên bản giao thức,
để sau này đổi định dạng mà không phá thiết bị cũ.

| Topic `iot/v1/devices/{deviceId}/…` | Chiều | QoS | Retained | Khi nào gửi |
|---|---|---|---|---|
| `telemetry` | thiết bị → backend | 1 | Không | Mỗi chu kỳ đo (mặc định 5 s, cấu hình 1–3600 s); gửi bù bản đo lưu đệm khi có mạng lại |
| `heartbeat` | thiết bị → backend | 0 | Không | Mỗi 10 s, và ngay khi trạng thái quạt/còi thay đổi (sau khi đã đồng bộ NTP) |
| `status` | thiết bị → backend | 1 | **Có** | `ONLINE` ngay sau khi kết nối; `OFFLINE` do **broker** phát thay thiết bị (LWT) |
| `event` | thiết bị → backend | 1 | Không | Lỗi / hồi phục cảm biến; tự bật / tắt quạt |
| `cmd` | backend → thiết bị | 1 | Không | Người dùng gửi lệnh điều khiển |
| `cmd/ack` | thiết bị → backend | 1 | Không | Ngay sau khi thực hiện (hoặc từ chối) lệnh |
| `config` | backend → thiết bị | 1 | **Có** | Người dùng lưu cấu hình mới |
| `config/ack` | thiết bị → backend | 1 | Không | Sau khi kiểm tra cấu hình: áp dụng hoặc từ chối |

Backend subscribe một bộ lọc chung `iot/v1/devices/+/#` (QoS 1) rồi tách `{deviceId}` và kênh từ topic.

## 2. Payload

### telemetry

```json
{"deviceId": "esp32-001", "seq": 1024, "ts": 1794021884000, "fw": "1.0.0",
 "gas": {"value": 64.0, "unit": "ppm"}, "temp": {"value": 29.69, "unit": "C"}}
```

| Trường | Bắt buộc | Quy tắc backend kiểm tra |
|---|---|---|
| `deviceId` | Có | Phải trùng `{deviceId}` trên topic |
| `ts` | Có | Số nguyên epoch ms; không trước 2024 (thiết bị chưa đồng bộ NTP); không quá 2 phút trong tương lai; không cũ quá 7 ngày |
| `seq` | Không | Số nguyên ≥ 0, tăng dần, dùng phát hiện mất bản tin |
| `fw` | Không | Chuỗi 1–30 ký tự, cập nhật phiên bản firmware |
| `gas` | Ít nhất 1 chỉ số | `{"value": số, "unit": "ppm"}`, 0–10000 |
| `temp` | | `{"value": số, "unit": "C"}`, −55–125 |

Ngoài ra: payload tối đa 4096 byte; không được có trường lạ. Sai **bất kỳ** quy tắc nào thì **cả bản tin** bị từ chối,
không lưu một phần, và được đếm theo lý do (`MALFORMED_JSON`, `MISSING_TS`, `TS_IN_FUTURE`, `TS_TOO_OLD`,
`OUT_OF_RANGE`, `WRONG_UNIT`, `UNKNOWN_FIELD`, `DEVICE_ID_MISMATCH`, `PAYLOAD_TOO_LARGE`…).

### heartbeat

```json
{"deviceId": "esp32-001", "ts": 1794021884000, "uptimeSec": 3600, "rssi": -76, "fw": "1.0.0",
 "state": {"fan": false, "fanMode": "AUTO", "buzzer": false, "buzzerMode": "AUTO"}}
```

Backend dùng để biết thiết bị còn sống, cập nhật phiên bản và trạng thái quạt/còi hiển thị trên web.

### status (retained) và LWT

```json
{"deviceId": "esp32-001", "state": "ONLINE", "ts": 1794021884000, "fw": "1.0.0",
 "hw": "esp32-devkit-mq2-ds18b20", "ip": "192.168.5.125"}
```

LWT đăng ký lúc kết nối, broker phát khi thiết bị mất kết nối mà không ngắt đúng cách:

```json
{"deviceId": "esp32-001", "state": "OFFLINE", "reason": "LWT"}
```

### event

```json
{"deviceId": "esp32-001", "ts": 1794021884000, "type": "SENSOR_FAULT", "sensor": "temp", "message": "…"}
{"deviceId": "esp32-001", "ts": 1794021884000, "type": "AUTO_FAN_ON", "reason": "GAS", "gas": 1350.0, "threshold": 400.0}
```

`type`: `SENSOR_FAULT`, `SENSOR_OK` (kết nối chuyển FAULT / về ONLINE, sinh / đóng cảnh báo lỗi cảm biến),
`AUTO_FAN_ON`, `AUTO_FAN_OFF` (ghi nhận hành động tự động tại chỗ).

### cmd và cmd/ack

```json
{"commandId": "6f1c2a9e-…", "action": "FAN_SET", "params": {"on": true}, "ts": 1794021884000}
```

| `action` | `params` | Thiết bị làm gì |
|---|---|---|
| `FAN_SET` | `{"on": true/false}` | Bật/tắt quạt, giữ chế độ tay (MANUAL) đến khi đổi lại |
| `FAN_AUTO` | | Quạt trở về tự động theo báo động gas / quá nhiệt |
| `BUZZER_SET` | `{"on": true/false}` | Bật/tắt còi, giữ chế độ tay |
| `BUZZER_AUTO` | | Còi trở về tự động |
| `PING` | | Chỉ trả ACK, dùng đo độ trễ |

```json
{"commandId": "6f1c2a9e-…", "result": "SUCCESS", "message": "Quạt BẬT (giữ bật đến khi đổi lại)",
 "state": {"fan": true, "fanMode": "MANUAL", "buzzer": false, "buzzerMode": "AUTO"}, "ts": 1794021884068}
```

`result`: `SUCCESS` hoặc `FAILED` (kèm lý do, ví dụ lệnh không hỗ trợ).

### config (retained) và config/ack

```json
{"configVersion": 3, "telemetryIntervalSec": 3, "gasThreshold": 300, "autoFan": true}
```

```json
{"configVersion": 3, "result": "APPLIED", "message": "OK",
 "applied": {"configVersion": 3, "telemetryIntervalSec": 3, "gasThreshold": 300, "autoFan": true}, "ts": 1794021884000}
```

Thiết bị kiểm tra lại giá trị (chu kỳ 1–3600 s, ngưỡng 50–10000 ppm, `autoFan` true/false); sai thì trả
`"result": "REJECTED"` kèm lý do và giữ cấu hình cũ. Cấu hình đã áp dụng được lưu vào bộ nhớ flash (NVS) của ESP32.

## 3. Cơ chế tin cậy

| Vấn đề | Cách xử lý |
|---|---|
| **Xác nhận lệnh** | Mỗi lệnh có `commandId` (UUID, cũng là khóa của bảng `commands`). Thiết bị trả `cmd/ack` cùng `commandId`. Trạng thái lệnh: PENDING → SENT → SUCCESS / FAILED. |
| **Timeout lệnh** | Không có ACK sau **10 s** → `TIMEOUT`. ACK đến muộn vẫn được ghi nhận vào kết quả nhưng giữ trạng thái TIMEOUT để thống kê đúng. |
| **Thiết bị offline** | Backend từ chối gửi lệnh (HTTP 409 `DEVICE_OFFLINE`), vì lệnh QoS1 không retained sẽ không tới được thiết bị. |
| **Bản tin trùng (QoS1 giao lại)** | Dữ liệu đo: khóa duy nhất (thiết bị, chỉ số, `ts`) trong CSDL, bản trùng bị bỏ qua và đếm riêng. Lệnh: firmware nhớ 10 `commandId` gần nhất, lệnh trùng không thực hiện lại mà gửi lại ACK cũ. |
| **Mất mạng phía thiết bị** | Firmware vẫn đo, giữ tối đa **120 bản đo** trong bộ đệm, kết nối lại thì gửi bù (5 bản mỗi vòng lặp) với `ts` gốc; backend nhận dữ liệu cũ tới 7 ngày. |
| **Thiết bị tự kết nối lại** | WiFi và MQTT tự kết nối lại; thời gian chờ giữa các lần thử MQTT tăng gấp đôi từ 2 s, tối đa 30 s. |
| **Backend hoặc broker khởi động lại** | Backend dùng **phiên bền** (`cleanSession=false`, clientId cố định `iot-backend`): broker giữ đăng ký và xếp hàng bản tin QoS1 trong lúc backend ngắt; backend tự kết nối lại (chờ 1 → 30 s). Đo được (P4, lần đo 08/11): 50/50 bản tin gửi trong lúc backend khởi động lại và 24/24 bản tin trong lúc broker gián đoạn vẫn được lưu. |
| **Phát hiện offline** | LWT (broker phát khi thiết bị mất kết nối; ESP32 keepalive 15 s) và watchdog backend (30 s không có bản tin nào). Bản tin `status` ONLINE retained cũ không được coi là bằng chứng thiết bị đang sống. |
| **Cấu hình khi thiết bị offline** | Topic `config` retained: broker giữ bản mới nhất, thiết bị nhận ngay khi kết nối. Backend so `configVersion` trong ACK, bỏ qua ACK của phiên bản cũ. |
| **Thiết bị bị ngừng sử dụng** | Backend xóa cấu hình retained (gửi payload rỗng) để thiết bị cũ không nhận lại cấu hình. |

## 4. Luồng dữ liệu

| Luồng | Đường đi |
|---|---|
| Thiết bị → người dùng | Cảm biến → ESP32 → `telemetry` (QoS1, TLS) → Mosquitto → backend: kiểm tra, lưu PostgreSQL, áp luật cảnh báo → WebSocket (STOMP) tới dashboard; email nếu có cảnh báo |
| Người dùng → thiết bị (lệnh) | Web → REST `POST /api/devices/{code}/commands` → backend kiểm tra quyền và trạng thái, lưu lệnh → `cmd` → ESP32 thực hiện → `cmd/ack` → backend cập nhật lệnh → WebSocket tới web |
| Người dùng → thiết bị (cấu hình) | Web → REST `PUT /api/devices/{code}/config` → backend tăng `configVersion`, lưu → `config` (retained) → ESP32 kiểm tra, áp dụng, lưu flash → `config/ack` → backend ghi APPLIED / REJECTED |

Sơ đồ tuần tự chi tiết: `docs/design/sequence.md`.

## 5. Bảo mật kênh MQTT

- **Mã hóa:** thiết bị kết nối qua TLS (cổng 8883 trong container, mở ra LAN bằng `MQTT_TLS_PORT`, mặc định 8883) và
  kiểm tra chứng chỉ broker bằng CA của nhóm. Cổng 1883 không mã hóa chỉ mở cho mạng nội bộ Docker và chính máy chủ.
- **Xác thực riêng từng thiết bị:** username = clientId = mã thiết bị, mật khẩu riêng 24 ký tự; không có tài khoản dùng
  chung, không cho kết nối ẩn danh.
- **ACL theo topic:** thiết bị chỉ publish 6 kênh và subscribe 2 kênh (`cmd`, `config`) của **chính nó**; publish hay
  subscribe topic của thiết bị khác bị broker từ chối (mã 135). Chi tiết: `docs/design/roles.md`, mục 4.
- **Kiểm tra lại ở backend:** `deviceId` trong payload phải trùng topic; thiết bị bị tạm khóa hoặc ngừng sử dụng bị từ
  chối dữ liệu.

## 6. Kiểm thử chứng minh

| Ca | Nội dung |
|---|---|
| F3 | Telemetry được kiểm tra, lưu đúng giá trị và đơn vị, có `seq` |
| F5 | Lệnh FAN_SET, PING có ACK; lệnh không hỗ trợ bị từ chối; không ACK → TIMEOUT sau 10 s |
| F6 | Cấu hình retained tới thiết bị, thiết bị trả APPLIED; cấu hình sai bị từ chối |
| F7 | LWT phát hiện mất kết nối đột ngột |
| S4 | Broker từ chối sai topic, sai tài khoản, sai clientId, ẩn danh |
| S5 | 9 loại payload sai bị từ chối, không lưu vào CSDL |
| S9 | MQTT qua TLS; kết nối không mã hóa và chứng chỉ giả bị từ chối |
| P3, P4, P5 | QoS1 không mất bản tin; gửi bù sau mất mạng; khởi động lại broker/backend không mất dữ liệu; bản tin trùng bị loại |
