# Firmware gas node (ESP32)

Firmware cho bộ GasGuard: ESP32-WROOM-32 + MQ-2 + DS18B20 + relay quạt + LED + còi.
Giao thức MQTT giống hệt `tests/simulator/simulator.py` (đã kiểm thử với backend).

## Thiết bị làm gì

| Chức năng | Chi tiết |
|---|---|
| Đo | Gas (MQ-2 → ppm theo đường đặc tuyến LPG, có hiệu chuẩn R0), nhiệt độ (DS18B20). Chu kỳ mặc định 5 s, đổi từ xa được |
| Gửi | `telemetry` QoS1 (có `seq`, `ts` UTC từ NTP), `heartbeat` 10 s, `status` ONLINE retained, LWT OFFLINE |
| Mất mạng | Vẫn đo, giữ tối đa 120 bản đo trong RAM, kết nối lại thì gửi bù đúng thứ tự (backend tự loại trùng) |
| Luật tại thiết bị | Gas > ngưỡng hoặc nhiệt độ > 45 °C (hết báo khi < 42 °C): LED đỏ luôn sáng. Ở chế độ **Tự động**: còi kêu, quạt tự bật (nếu `autoFan`) và tự tắt sau khi hết báo động 60 s. Chạy cả khi mất mạng; báo sự kiện `AUTO_FAN_ON` (lý do `GAS`/`TEMP`) / `AUTO_FAN_OFF` |
| Lỗi cảm biến | Đọc lỗi 3 lần liên tiếp → `SENSOR_FAULT` (backend chuyển FAULT + cảnh báo); hồi phục → `SENSOR_OK` |
| Lệnh | `FAN_SET {on}` / `BUZZER_SET {on}`: người dùng giữ quạt / còi **BẬT hoặc TẮT** (chế độ *Giữ bằng tay*, kể cả khi có báo động, lưu flash) đến khi đổi lại. `FAN_AUTO` / `BUZZER_AUTO`: về **Tự động**. `PING`. Trả `cmd/ack` kèm `state {fan, fanMode, buzzer, buzzerMode}`; lệnh giao trùng (QoS1) không chạy lại mà gửi lại ACK cũ |
| Cấu hình từ xa | `telemetryIntervalSec`, `gasThreshold`, `autoFan`. Kiểm tra hợp lệ, lưu vào flash, trả `config/ack` APPLIED/REJECTED |
| Báo hiệu | LED xanh: nháy nhanh = chưa có WiFi, nháy chậm = chưa vào broker, sáng = online. LED đỏ: sáng = gas cao / quá nhiệt, nháy = cảm biến lỗi |

## 1. Cài phần mềm (một lần)

1. **Arduino IDE 2.x**.
2. File → Preferences → *Additional boards manager URLs*:
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`
3. Tools → Board → Boards Manager → cài **esp32 by Espressif Systems** (bản 3.x).
4. Tools → Manage Libraries → cài:
   - **MQTT** by Joel Gaehwiler (2.5.x). Không phải PubSubClient: thư viện đó không gửi được QoS1.
   - **ArduinoJson** by Benoit Blanchon (7.x)
   - **OneWire** by Paul Stoffregen
   - **DallasTemperature** by Miles Burton
5. Cắm ESP32 mà không thấy cổng COM: cài driver **CH340** hoặc **CP2102** (xem chữ trên con chip cạnh cổng USB).

## 2. Chuẩn bị mạng trên máy chạy Docker

1. ESP32 và máy tính phải chung một mạng WiFi **2.4 GHz** (ESP32 không bắt được 5 GHz).
2. Lấy IP LAN của máy: `ipconfig`, xem dòng *IPv4 Address* của card WiFi, ví dụ `192.168.1.10`.
3. Mở cổng **8883** (MQTT qua TLS) trên tường lửa Windows (PowerShell **Run as Administrator**):
   ```powershell
   New-NetFirewallRule -DisplayName "GasGuard MQTT TLS 8883" -Direction Inbound -Protocol TCP -LocalPort 8883 -Action Allow -Profile Private
   Get-NetConnectionProfile   # NetworkCategory phải là Private; nếu Public:
   Set-NetConnectionProfile -InterfaceAlias "Wi-Fi" -NetworkCategory Private
   ```
4. Trong `.env` đặt `MQTT_DEVICE_URL=ssl://192.168.1.10:8883`, rồi khởi động lại backend. Giá trị này chỉ dùng để hiện đúng địa chỉ broker khi cấp thông tin xác thực.
5. Tạo chứng chỉ TLS cho IP này (một lần; xem [deployment/README.md](../deployment/README.md#mã-hóa-tls-mqtt-8883-và-https)):
   `docker run --rm -v "${PWD}:/repo" alpine:3.20 sh /repo/deployment/certs/gen-certs.sh 192.168.1.10`.
   Script ghi luôn `firmware/gas-node/ca_cert.h` (chứng chỉ CA để ESP32 xác thực broker, không commit).

## 3. Đăng ký thiết bị và lấy mật khẩu

1. Web → **Thiết bị** → *Đăng ký thiết bị*, mã ví dụ `esp32-001`.
2. Mở thiết bị → tab **Xác thực MQTT** → *Cấp thông tin xác thực* → bấm sao chép đoạn `secrets.h`.
   Mật khẩu **chỉ hiện một lần**. Lỡ mất thì bấm *Đổi mật khẩu* và nạp lại.
3. Trong `firmware/gas-node/`, copy `secrets.h.example` thành `secrets.h`. Dán đoạn vừa sao chép, rồi điền `WIFI_SSID` và `WIFI_PASSWORD`.
   `secrets.h` đã nằm trong `.gitignore`: **không commit**.
   Đoạn sao chép có `MQTT_PORT 8883` và `MQTT_USE_TLS 1`: firmware kết nối bằng TLS và chỉ tin broker có chứng chỉ do CA
   trong `ca_cert.h` ký (chống broker giả, nghe lén). `secrets.h` cũ không có `MQTT_USE_TLS` thì firmware kết nối không
   mã hóa như trước, nhưng cổng 1883 không còn mở ra LAN nên phải chuyển sang TLS.

## 4. Nạp firmware

1. Mở `firmware/gas-node/gas-node.ino` (Arduino IDE tự mở luôn `config.h`).
2. Kiểm tra chân trong `config.h` khớp với cách nối dây (mặc định theo `hardware/README.md`).
3. Tools → Board: **ESP32 Dev Module**, Port: cổng COM của ESP32.
4. Bấm **Upload**. Nếu kẹt ở `Connecting....` thì giữ nút **BOOT** trên board tới khi bắt đầu nạp.
5. Tools → **Serial Monitor**, tốc độ **115200**. Log đúng sẽ như sau:
   ```
   GasGuard gas node 1.0.0 | thiết bị esp32-001
   WiFi OK, IP 192.168.1.23, RSSI -55 dBm
   Đã đồng bộ giờ NTP: 2026-11-08 02:15:30 UTC   (TLS cần giờ đúng để kiểm tra hạn chứng chỉ)
   Kết nối MQTT (TLS) 192.168.1.10:8883 với tài khoản esp32-001 ...
   Đã kết nối broker
   Áp dụng cấu hình v1: ...            (nếu đã lưu cấu hình trên web)
   MQ-2 đang làm nóng 60 giây...
   Hiệu chuẩn MQ-2 (để cảm biến trong KHÔNG KHÍ SẠCH)...
   R0 = 1.59 kΩ (đã lưu vào flash)
   gas=3.6 ppm  temp=29.50 C  quạt=TẮT
   ```

**Hiệu chuẩn:** lần chạy đầu tiên, MQ-2 cần làm nóng 60 giây rồi tự đo R0 trong 5 giây. Lúc này phải để cảm biến ở **không khí sạch**. R0 được lưu vào flash và dùng lại cho các lần sau. Muốn hiệu chuẩn lại thì đặt `MQ2_FORCE_CALIBRATE 1`, nạp, rồi đặt về `0` và nạp lại.

## 5. Kiểm tra theo kịch bản demo

| Thử | Kết quả mong đợi |
|---|---|
| Cắm điện | LED xanh sáng; Dashboard: thiết bị **Trực tuyến**, số đo cập nhật theo thời gian thực |
| Xịt gas bật lửa (KHÔNG đánh lửa) gần MQ-2 | Gas tăng; vượt ngưỡng → LED đỏ + còi + quạt tự bật; web hiện cảnh báo |
| Web → Điều khiển → Quạt: Bật / Tắt / Tự động | Relay đóng/nhả; chế độ hiện *Giữ bằng tay* hoặc *Tự động*; lịch sử lệnh ghi *Thành công* + độ trễ |
| Web → Điều khiển → Còi: Tắt, rồi xịt gas | Còi im, LED đỏ vẫn sáng, web + Telegram vẫn cảnh báo. Bấm *Tự động* để còi kêu lại |
| Web → Cấu hình (chu kỳ 3 s, ngưỡng 500) | Thiết bị log "Áp dụng cấu hình"; web hiện *Đã áp dụng* |
| Rút sợi vàng DS18B20 | Sau ~3 chu kỳ: LED đỏ nháy, thiết bị chuyển **Lỗi**, cảnh báo lỗi cảm biến |
| Rút điện ESP32 | Sau ~20 giây: **Ngoại tuyến** (LWT) + cảnh báo mất kết nối |
| Tắt WiFi phát rồi bật lại | Thiết bị tự kết nối lại, gửi bù dữ liệu; biểu đồ không bị hụt |
| Web → Vòng đời → Tạm ngưng | Broker ngắt thiết bị; log "Broker TỪ CHỐI (mã 5)" |

An toàn: chỉ xịt lượng gas rất nhỏ, ở nơi thoáng, tuyệt đối không có lửa.

## Sự cố thường gặp

| Log / hiện tượng | Nguyên nhân / cách xử lý |
|---|---|
| `Broker TỪ CHỐI (mã 5)` hoặc `(mã 4)` | Sai `DEVICE_ID`/`MQTT_PASSWORD`, hoặc thiết bị đang tạm ngưng / đã đổi mật khẩu. Cấp lại trên web |
| `Không kết nối được broker (lỗi -3 ...)` | Sai `MQTT_HOST`, tường lửa chặn cổng 8883, mạng đang Public, Docker chưa chạy. Nếu máy chủ dùng cổng khác (`MQTT_TLS_PORT` trong `.env`, vd 8884) thì `MQTT_PORT` và luật tường lửa phải theo cổng đó |
| `Lỗi TLS: ... verification failed` hoặc `CN mismatch` | `MQTT_HOST` không có trong chứng chỉ (chạy lại `gen-certs.sh` thêm IP đó, rồi `app-up`), hoặc `ca_cert.h` không khớp CA hiện tại (chép lại `ca_cert.h` và nạp lại) |
| `Thieu ca_cert.h` khi biên dịch | Chưa chạy `gen-certs.sh` (file được ghi vào `firmware/gas-node/ca_cert.h`) |
| Không thấy dòng `Kết nối MQTT` | TLS chờ đồng bộ giờ NTP trước; xem dòng `Chưa đồng bộ giờ NTP` |
| LED xanh nháy nhanh mãi | Sai tên/mật khẩu WiFi hoặc WiFi 5 GHz |
| `Chưa đồng bộ giờ NTP` | Mạng không ra được Internet. Backend bắt buộc timestamp đúng |
| Quạt chạy ngược (bảo tắt lại bật) | Đổi `RELAY_ACTIVE_LOW` trong `config.h` |
| ESP32 tự khởi động lại khi quạt bật | Sụt áp nguồn USB: dùng cổng USB/cục sạc khỏe hơn (≥1 A) hoặc cáp ngắn |
| Gas luôn `--` sau khi làm nóng / `AO ~0V` | Kiểm tra dây AO → phân áp → D34, nguồn 5V của MQ-2 |
| Nhiệt độ `-127` | Sai chân DATA hoặc thiếu điện trở kéo lên |
| Gas không khí sạch mà cao bất thường | Hiệu chuẩn lại trong không khí sạch, kiểm tra `MQ2_RL_KOHM` (xem mã điện trở trên module) |
