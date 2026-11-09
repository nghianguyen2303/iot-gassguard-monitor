# Sơ đồ tuần tự – GasGuard

Ba quy trình quan trọng (đề bài yêu cầu tối thiểu hai): **(1)** thiết bị gửi số đo, hệ thống lưu, cập nhật dashboard
và sinh cảnh báo; **(2)** người dùng gửi lệnh điều khiển và nhận xác nhận; **(3)** phát hiện thiết bị mất kết nối.
Tên lớp trong sơ đồ là lớp thật trong `backend/src/main/java/com/iot/gas/`.

## 1. Telemetry → lưu → dashboard → cảnh báo → email

```mermaid
sequenceDiagram
    autonumber
    participant ESP as ESP32 (gas-node.ino)
    participant MQ as Mosquitto
    participant IN as Backend: IngestService
    participant DB as PostgreSQL
    participant AL as Backend: AlertService
    participant WS as Backend: RealtimePublisher
    participant WEB as Dashboard (trình duyệt)
    participant EM as Backend: EmailAlertNotifier
    participant G as Gmail SMTP

    ESP->>ESP: Đo MQ-2, DS18B20 theo chu kỳ (mặc định 5 s)
    alt Gas > ngưỡng cấu hình hoặc nhiệt độ > 45 °C
        ESP->>ESP: Báo động tại chỗ: LED đỏ, còi, tự bật quạt (không cần mạng)
    end
    ESP->>MQ: PUBLISH telemetry (QoS1, TLS) {deviceId, seq, ts, gas, temp}
    MQ-->>ESP: PUBACK
    MQ->>IN: Chuyển bản tin (backend subscribe iot/v1/devices/+/#)
    IN->>IN: Kiểm tra trạng thái thiết bị và payload (TelemetryValidator)
    alt Bản tin không hợp lệ
        IN->>IN: Từ chối cả bản tin, đếm theo lý do (không lưu)
    else Hợp lệ
        IN->>DB: INSERT từng chỉ số, bỏ qua bản trùng (khóa thiết bị + chỉ số + ts)
        IN->>DB: Cập nhật last_seen, OFFLINE → ONLINE, PROVISIONED → ACTIVE (lần đầu)
        Note over IN,WS: Sau khi giao dịch commit, phát sự kiện TelemetryReceivedEvent
        IN->>WS: TelemetryReceivedEvent
        WS->>WEB: STOMP /topic/devices/{code} {type: telemetry}
        IN->>AL: TelemetryReceivedEvent
        AL->>DB: Lấy luật áp dụng (luật chung + luật riêng của thiết bị)
        alt Thiết bị ACTIVE và số đo vượt ngưỡng
            AL->>DB: Tạo cảnh báo OPEN (hoặc cập nhật cảnh báo đang mở)
            AL->>WS: AlertChangedEvent (CREATED)
            WS->>WEB: STOMP {type: alert}
            AL->>EM: AlertChangedEvent (CREATED)
            EM->>DB: Người nhận: chủ, người được chia sẻ, ADMIN đã xác nhận email
            EM->>G: Gửi 1 email riêng cho mỗi người (luồng nền)
        else Hết vượt ngưỡng
            AL->>DB: Đánh dấu cảnh báo đã hết điều kiện (cleared_at)
            AL->>EM: AlertChangedEvent (CLEARED) → email "trở lại bình thường" (1 lần)
        end
    end
```

Điểm cần nắm:

- **Báo động tại chỗ không phụ thuộc mạng:** ESP32 tự bật còi, LED, quạt; backend chỉ ghi nhận và báo cho người ở xa.
- **QoS1 + chống trùng:** broker có thể giao lại bản tin; khóa duy nhất trong CSDL bỏ bản trùng, nên không lưu hai lần.
- **Dashboard và cảnh báo chạy sau khi lưu thành công** (`@TransactionalEventListener`): không bao giờ hiện số liệu
  chưa được lưu.
- **Email không làm chậm luồng chính:** gửi trên luồng nền, mỗi người nhận một email riêng; mỗi cảnh báo tối đa 1 email
  khi xảy ra và 1 email khi trở lại bình thường.

## 2. Gửi lệnh điều khiển và nhận xác nhận (ACK)

```mermaid
sequenceDiagram
    autonumber
    actor U as Người dùng
    participant WEB as Web (ControlTab)
    participant API as Backend: JwtAuthFilter + CommandService
    participant DB as PostgreSQL
    participant MQ as Mosquitto
    participant ESP as ESP32

    U->>WEB: Bấm "Bật" quạt
    WEB->>API: POST /api/devices/{code}/commands?waitMs=5000 {action: FAN_SET, params: {on: true}} + JWT
    API->>API: Xác thực JWT (sai / hết hạn / đã thu hồi → 401)
    API->>API: Quyền quản lý thiết bị? (người được chia sẻ → 403 + audit DENIED)
    API->>API: Lệnh, tham số hợp lệ? (sai → 400), thiết bị ACTIVE/MAINTENANCE? (không → 409)
    alt Thiết bị OFFLINE
        API->>DB: Lưu lệnh FAILED, audit COMMAND_SEND (FAILED)
        API-->>WEB: 409 DEVICE_OFFLINE
    else Thiết bị ONLINE
        API->>DB: Lưu lệnh PENDING (id = commandId, UUID)
        API->>MQ: PUBLISH .../cmd (QoS1) {commandId, action, params, ts}
        API->>DB: PENDING → SENT, audit COMMAND_SEND
        MQ->>ESP: Giao lệnh (chỉ thiết bị này được subscribe topic cmd của nó)
        alt commandId đã thực hiện (QoS1 giao lại)
            ESP->>MQ: Gửi lại ACK cũ, không thực hiện lần hai
        else Lệnh mới
            ESP->>ESP: Bật relay quạt, chế độ MANUAL, lưu flash
            ESP->>MQ: PUBLISH .../cmd/ack (QoS1) {commandId, result: SUCCESS, state}
            ESP->>MQ: heartbeat ngay với trạng thái quạt mới (web nhận STOMP {type: state})
        end
        MQ->>API: ACK
        API->>DB: SENT → SUCCESS, acked_at (độ trễ = acked_at − sent_at), cập nhật trạng thái quạt/còi
        API-->>WEB: STOMP {type: command}
        API-->>WEB: HTTP 201 (trạng thái SUCCESS, chờ tối đa 5 s)
        WEB-->>U: "Thành công · độ trễ … ms", nút quạt đổi trạng thái
    end
    opt Không có ACK sau 10 s
        API->>DB: SENT → TIMEOUT (bộ quét chạy mỗi 2 s), ACK đến muộn vẫn được ghi nhận
    end
```

Điểm cần nắm:

- **Kiểm tra quyền ở backend**, trước khi lệnh rời khỏi hệ thống; mọi lệnh đều được lưu lịch sử kèm người gửi và kết quả.
- **`commandId` ghép lệnh với ACK** và giúp thiết bị bỏ qua lệnh bị giao trùng.
- **Không gửi lệnh khi thiết bị OFFLINE:** lệnh QoS1 không retained sẽ không tới được, nên trả lỗi ngay thay vì chờ.

## 3. Phát hiện thiết bị mất kết nối

```mermaid
sequenceDiagram
    autonumber
    participant ESP as ESP32
    participant MQ as Mosquitto
    participant IN as Backend: IngestService / OfflineWatchdog
    participant DB as PostgreSQL
    participant AL as Backend: AlertService
    participant WEB as Dashboard
    participant EM as Email

    ESP->>MQ: CONNECT (TLS, keepalive 15 s, LWT = status OFFLINE retained)
    ESP->>MQ: PUBLISH status ONLINE (retained)
    Note over ESP: Rút điện / mất WiFi
    alt Kết nối TCP đóng đột ngột hoặc hết 1,5 × keepalive (~22 s) không có tín hiệu
        MQ->>IN: LWT: status {state: OFFLINE, reason: LWT}
    else Thiết bị treo, kết nối chưa đứt (không có LWT)
        IN->>IN: Watchdog (mỗi 5 s): 30 s không có bản tin nào
    end
    IN->>DB: connectivity ONLINE → OFFLINE
    IN->>WEB: STOMP {type: connectivity, OFFLINE}
    IN->>AL: ConnectivityChangedEvent
    AL->>DB: Thiết bị ACTIVE → tạo cảnh báo OFFLINE
    AL->>EM: Email "mất kết nối" (1 lần)
    Note over ESP: Cấp nguồn lại
    ESP->>MQ: CONNECT, status ONLINE, gửi bù bản đo đã lưu đệm
    MQ->>IN: status ONLINE / telemetry
    IN->>DB: OFFLINE → ONLINE, lưu dữ liệu gửi bù với ts gốc
    IN->>WEB: STOMP {type: connectivity, ONLINE}
    IN->>AL: ConnectivityChangedEvent
    AL->>DB: Cảnh báo OFFLINE: đã hết điều kiện
    AL->>EM: Email "Đã kết nối lại" (1 lần)
```

Điểm cần nắm:

- **Hai cơ chế bổ sung nhau:** LWT do broker phát (nhanh, không cần backend dò), watchdog bắt trường hợp thiết bị treo
  mà kết nối chưa đứt.
- **Không mất dữ liệu khi mất mạng ngắn:** thiết bị vẫn đo, lưu đệm tối đa 120 bản, kết nối lại thì gửi bù.

## Kiểm thử chứng minh

| Quy trình | Ca kiểm thử |
|---|---|
| 1 | F3 (telemetry lưu và truy vấn), F4 (dashboard realtime, độ trễ đo được), F8 (sinh và xử lý cảnh báo), S5 (từ chối payload sai), P1, P5 |
| 2 | F5 (lệnh, ACK, TIMEOUT), S2 (người được chia sẻ không gửi được lệnh), P2 |
| 3 | F7 (offline bằng LWT, cảnh báo, online lại), P4 (gửi bù sau mất mạng) |
