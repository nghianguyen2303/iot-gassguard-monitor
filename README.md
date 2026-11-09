# GasGuard – Hệ thống IoT giám sát và cảnh báo rò rỉ khí gas

Đồ án kết thúc học phần **IoT và ứng dụng**. GasGuard là nguyên mẫu hoàn chỉnh gồm thiết bị ESP32 đo nồng độ khí gas
và nhiệt độ, broker MQTT, backend quản lý người dùng và vòng đời thiết bị, cơ sở dữ liệu chuỗi thời gian và giao diện
web giám sát, điều khiển, cảnh báo theo thời gian thực.

> Thành viên nhóm, phân công và đường dẫn GitHub: xem [Nhóm thực hiện](#11-nhóm-thực-hiện).

---

## 1. Bài toán

**Bối cảnh.** Rò rỉ khí gas (LPG) trong bếp gia đình, quán ăn, phòng trọ là nguyên nhân thường gặp của cháy nổ và ngộ
độc. Báo động gas thông thường chỉ kêu tại chỗ: khi không có ai ở nhà thì không ai biết, không có lịch sử để kiểm tra,
không điều khiển được quạt thông gió từ xa, và người quản lý nhiều điểm (chủ nhà trọ, quản lý bếp) không theo dõi
được tập trung.

**Đối tượng sử dụng.**

| Vai trò | Ai | Cần gì |
|---|---|---|
| Quản trị viên (`ADMIN`) | Người vận hành hệ thống | Quản lý tài khoản, toàn bộ thiết bị, luật cảnh báo chung, audit log, tình trạng hệ thống |
| Chủ thiết bị (`OWNER`) | Chủ hộ, chủ nhà trọ, quản lý bếp | Đăng ký và cấu hình thiết bị của mình, điều khiển quạt/còi, xử lý cảnh báo, chia sẻ quyền xem |
| Người xem (`VIEWER`) | Người thân, người thuê phòng | Xem dữ liệu và cảnh báo của thiết bị được chia sẻ, nhận email cảnh báo |

**Vì sao cần IoT mà không chỉ là một ứng dụng web.** Dữ liệu cần giám sát (nồng độ gas, nhiệt độ) chỉ có tại hiện
trường và thay đổi theo từng giây, nên phải có thiết bị đo đặt tại chỗ, gửi dữ liệu liên tục qua mạng. Phản ứng cũng
phải xảy ra tại chỗ (bật quạt, kêu còi) ngay cả khi mất mạng, đồng thời được báo về người dùng ở xa và điều khiển được
từ xa. Một phần mềm quản lý thông thường không thu thập được dữ liệu và không tác động được vào môi trường vật lý.

**Phạm vi nguyên mẫu.** Một hoặc nhiều node ESP32 trong cùng mạng LAN với máy chủ chạy Docker; dùng trong nhà/bếp nhỏ.
Ngoài phạm vi: thay thế thiết bị báo cháy đạt chuẩn PCCC, triển khai ra Internet công cộng (xem
[Hạn chế](#10-hạn-chế-và-hướng-phát-triển)).

## 2. Chức năng chính

| Nhóm (đề bài) | Chức năng |
|---|---|
| Thiết bị (4.2) | ESP32 đo gas (MQ-2, hiệu chuẩn R0) và nhiệt độ (DS18B20); mã định danh riêng; gửi telemetry QoS1 có `seq` và `ts` UTC (NTP); heartbeat 10 s; LWT báo offline; nhận lệnh và cấu hình từ xa; điều khiển quạt (relay) và còi; LED trạng thái; mất mạng vẫn đo, đệm 120 bản và gửi bù; tự kết nối lại WiFi/MQTT |
| Giao thức (4.3) | MQTT qua TLS (Mosquitto 2, Dynamic Security) cho thiết bị; REST + WebSocket (STOMP) qua HTTPS cho web |
| Người dùng (4.4a) | Đăng ký, đăng nhập/đăng xuất (JWT, thu hồi token), đổi mật khẩu và thông tin, khóa/mở khóa tài khoản, khóa tạm sau 5 lần sai mật khẩu, 3 vai trò |
| Vòng đời thiết bị (4.4b) | Đăng ký → cấp thông tin xác thực MQTT riêng → tự kích hoạt khi gửi bản tin đầu → bảo trì → tạm khóa/mở khóa → ngừng sử dụng; chia sẻ thiết bị cho người dùng khác; theo dõi online/offline/lỗi, firmware; đổi mật khẩu thiết bị |
| Dữ liệu (4.4c) | Kiểm tra hợp lệ (định dạng, đơn vị, khoảng giá trị, thời gian), loại trùng, lưu theo thời gian; tra cứu theo thiết bị và khoảng thời gian; biểu đồ, thống kê (min/max/TB/P95, ước lượng mất bản tin theo `seq`); xuất CSV |
| Giám sát, cảnh báo (4.4d) | Dashboard realtime; luật ngưỡng cấu hình được (chung hoặc theo thiết bị); cảnh báo offline, lỗi cảm biến; lịch sử, xác nhận, xử lý; gửi email (Gmail) |
| Điều khiển (4.4e) | Bật/Tắt/Tự động quạt và còi, PING; kiểm tra quyền ở backend; thiết bị trả ACK kèm trạng thái; lưu lịch sử lệnh, độ trễ; quá hạn sau 10 s |
| Giao diện (4.6) | Web React: đăng nhập, quản lý người dùng, danh sách và chi tiết thiết bị, dữ liệu và biểu đồ, cảnh báo, cấu hình, điều khiển, lịch sử hoạt động, audit log |
| Nâng cao (5.3) | Triển khai bằng Docker Compose; rule engine cảnh báo; xử lý realtime; xử lý mất mạng và đồng bộ lại; đánh giá mở rộng bằng thiết bị mô phỏng (100 thiết bị) |

## 3. Kiến trúc

```
 ┌──────────────── Lớp thiết bị ───────────────┐      ┌──────── Lớp kết nối ────────┐
 │ ESP32 + MQ-2 + DS18B20 + relay/quạt + còi   │      │ Mosquitto 2.0 (Docker)      │
 │ firmware C++ (Arduino)                      │─MQTT►│ :8883 TLS, Dynamic Security │
 │ đo, luật tại chỗ, đệm khi mất mạng          │◄─────│ mỗi thiết bị 1 tài khoản+ACL│
 └─────────────────────────────────────────────┘ WiFi └──────────────┬──────────────┘
                                                  (TLS)              │ MQTT :1883 trong mạng Docker (phiên bền, QoS1)
 ┌──────────────── Lớp ứng dụng ───────────────┐      ┌──────────────▼──────────────┐      ┌──── Lớp dữ liệu ────┐
 │ Trình duyệt: React SPA                      │      │ Backend Spring Boot 3.5     │      │ PostgreSQL 16       │
 │ qua nginx HTTPS :8443 (:8088 → chuyển HTTPS)│─REST►│ tiếp nhận, kiểm tra, cảnh   │─JDBC►│ users, devices,     │
 │ (/api → REST, /ws → WebSocket STOMP, wss)   │◄─WS──│ báo, lệnh, vòng đời, JWT    │      │ telemetry, alerts,  │
 └─────────────────────────────────────────────┘      │ ──SMTP──► Gmail (email)     │      │ commands, audit_log │
                                                      └─────────────────────────────┘      └─────────────────────┘
```

Không dùng gateway/edge riêng: ESP32 có WiFi nên kết nối thẳng tới broker. Phần xử lý tại biên nằm ngay trên thiết bị
(luật báo động tại chỗ, đệm dữ liệu khi mất mạng).

Mọi đường truyền đi qua mạng LAN/WiFi đều mã hóa TLS: ESP32 ↔ broker (MQTT 8883) và trình duyệt ↔ web (HTTPS 8443).
Cổng MQTT 1883 không mã hóa chỉ dùng giữa các container và trên chính máy chủ (bind `127.0.0.1`), máy khác trong LAN
không kết nối được.

| Lựa chọn | Lý do |
|---|---|
| **ESP32-WROOM-32** | Có sẵn WiFi, đủ RAM để đệm dữ liệu và xử lý JSON, rẻ, hệ sinh thái Arduino lớn |
| **MQ-2 + DS18B20** | MQ-2 nhạy với LPG/khí gas bếp; DS18B20 số (1-Wire), chính xác ±0,5 °C, đầu inox chịu nhiệt; nhiệt độ cao là dấu hiệu cháy đi kèm |
| **MQTT** | Nhẹ cho thiết bị nhúng, mô hình publish/subscribe hợp với dữ liệu liên tục; có sẵn QoS1 (đảm bảo tới nơi), LWT (phát hiện offline), retained (cấu hình), phiên bền (không mất bản tin khi backend khởi động lại) |
| **Mosquitto + Dynamic Security** | Broker mã nguồn mở nhẹ; Dynamic Security cho phép backend tạo/khóa/xóa tài khoản từng thiết bị và ACL theo topic **lúc đang chạy**, có hiệu lực ngay (khóa thiết bị → broker ngắt kết nối lập tức) |
| **Spring Boot (Java 21)** | Spring Security cho JWT và phân quyền, JPA + Flyway quản lý schema theo phiên bản, WebSocket STOMP có sẵn |
| **PostgreSQL** | Dữ liệu quan hệ (người dùng, thiết bị, quyền) và chuỗi thời gian trong cùng một CSDL, có chỉ mục theo (thiết bị, chỉ số, thời điểm) và ràng buộc duy nhất để chống trùng |
| **React + Vite, WebSocket STOMP** | Giao diện một trang, cập nhật realtime không cần tải lại; STOMP cho phép đăng ký kênh theo từng thiết bị và kiểm tra quyền khi đăng ký |
| **Docker Compose** | Chạy toàn bộ hệ thống bằng một lệnh, giống nhau trên mọi máy; bí mật chỉ đến từ `.env` |
| **TLS với CA riêng của nhóm** | Mạng LAN/hotspot không có tên miền công khai nên không xin được chứng chỉ công cộng; nhóm tự tạo CA (script `deployment/certs/gen-certs.sh`), ESP32 nhúng chứng chỉ CA để xác thực broker (chống broker giả, nghe lén), trình duyệt cài CA một lần. Chứng chỉ ghi nhiều IP (nhà, hotspot) để đổi mạng khi demo |
| **Email (Gmail SMTP)** | Gửi được tới hộp thư thật của người dùng mà không cần ứng dụng riêng; Telegram có sẵn nhưng tùy chọn vì bị chặn ở Việt Nam |

### Giao tiếp MQTT

Gốc topic: `iot/v1/devices/{deviceId}/…` với `deviceId` = mã thiết bị = username = clientId MQTT.

| Topic | Chiều | QoS | Nội dung |
|---|---|---|---|
| `telemetry` | thiết bị → backend | 1 | Số đo gas, nhiệt độ |
| `heartbeat` | thiết bị → backend | 0 | 10 s/lần: uptime, RSSI, firmware, trạng thái quạt |
| `status` | thiết bị → backend | 1, retained | `ONLINE` khi kết nối; **LWT** `OFFLINE` do broker gửi khi thiết bị mất kết nối đột ngột |
| `event` | thiết bị → backend | 1 | `SENSOR_FAULT`, `SENSOR_OK`, `AUTO_FAN_ON`, `AUTO_FAN_OFF` |
| `cmd` | backend → thiết bị | 1 | Lệnh `FAN_SET`, `FAN_AUTO`, `BUZZER_SET`, `BUZZER_AUTO`, `PING` |
| `cmd/ack` | thiết bị → backend | 1 | Kết quả lệnh + trạng thái quạt/còi |
| `config` | backend → thiết bị | 1, retained | Cấu hình có số phiên bản (chu kỳ đo, ngưỡng gas, tự bật quạt) |
| `config/ack` | thiết bị → backend | 1 | `APPLIED` / `REJECTED` |

Ví dụ bản tin telemetry (thời gian là mili giây UTC, đơn vị ghi rõ trong từng chỉ số):

```json
{"deviceId": "esp32-001", "seq": 1024, "ts": 1794021884000, "fw": "1.0.0",
 "gas": {"value": 64.0, "unit": "ppm"}, "temp": {"value": 29.5, "unit": "C"}}
```

Lệnh: `{"commandId": "<uuid>", "action": "FAN_SET", "params": {"on": true}, "ts": …}`. Thiết bị trả `cmd/ack` cùng
`commandId`; không có ACK sau 10 s thì lệnh chuyển `TIMEOUT`. Bản tin trùng (QoS1 gửi lại, gửi bù) bị loại nhờ khóa duy
nhất (thiết bị, chỉ số, thời điểm đo) và `commandId`. Thiết bị offline khi broker gửi LWT, hoặc backend không nhận bản
tin nào trong 30 s (watchdog).

### Vòng đời thiết bị và phân quyền

```
REGISTERED ──cấp xác thực──► PROVISIONED ──bản tin đầu tiên (hoặc ACTIVATE)──► ACTIVE ◄──START/END_MAINTENANCE──► MAINTENANCE
PROVISIONED / ACTIVE / MAINTENANCE ──SUSPEND──► SUSPENDED ──RESUME──► ACTIVE
Mọi trạng thái (trừ DECOMMISSIONED) ──DECOMMISSION──► DECOMMISSIONED (chỉ xem lịch sử, không quay lại)
Kết nối (độc lập với vòng đời): ONLINE / OFFLINE / FAULT
```

Chuyển trạng thái sai (ví dụ kích hoạt lại thiết bị đã ngừng sử dụng) bị từ chối với HTTP 409; tạm khóa và ngừng sử dụng bắt buộc ghi lý do. `SUSPENDED` vô hiệu hóa tài khoản MQTT (broker ngắt ngay), `DECOMMISSIONED` xóa tài khoản MQTT vĩnh viễn nhưng giữ dữ
liệu lịch sử. Quyền được kiểm tra ở backend cho từng API và cả khi đăng ký kênh WebSocket: `ADMIN` quản lý tất cả;
`OWNER` quản lý thiết bị mình sở hữu; người được chia sẻ chỉ được xem.

## 4. Cấu trúc repository

| Thư mục | Nội dung |
|---|---|
| [`firmware/`](firmware/) | Mã nguồn ESP32 (`gas-node/`), hướng dẫn cài Arduino IDE, nạp và kiểm tra |
| [`backend/`](backend/) | Spring Boot: REST API, tiếp nhận telemetry MQTT, vòng đời thiết bị, cảnh báo, lệnh, JWT, audit log. Migration CSDL (Flyway) ở `backend/src/main/resources/db/migration/` |
| [`frontend/`](frontend/) | Giao diện web React + Vite, Dockerfile và cấu hình nginx |
| [`database/`](database/) | Tài liệu CSDL: schema, dữ liệu mẫu. Migration mà backend thực sự chạy (Flyway V1–V5) nằm ở `backend/src/main/resources/db/migration/` |
| [`deployment/`](deployment/) | Docker Compose, cấu hình Mosquitto, lệnh tắt `dev-shell.ps1`, [hướng dẫn triển khai](deployment/README.md) |
| [`hardware/`](hardware/) | Danh sách linh kiện, sơ đồ nối dây, hình ảnh prototype |
| [`tests/`](tests/) | Kiểm thử tự động chức năng/bảo mật (`e2e/`) và phi chức năng (`perf/`), thiết bị mô phỏng (`simulator/`), báo cáo kết quả (`reports/`) |
| [`docs/`](docs/) | Báo cáo, slide, tài liệu thiết kế, kế hoạch và kết quả kiểm thử |
| `.env.example` | Danh sách biến môi trường, **không chứa bí mật thật** |

## 5. Cài đặt và chạy

### 5.1. Yêu cầu

| Phần mềm | Phiên bản | Dùng cho |
|---|---|---|
| Docker Desktop (Windows/macOS) hoặc Docker Engine + Compose v2 | 24+ | Chạy toàn bộ hệ thống |
| Arduino IDE | 2.x, lõi esp32 3.x | Nạp firmware (xem [`firmware/README.md`](firmware/README.md)) |
| Python | 3.10+ | Chạy kiểm thử và thiết bị mô phỏng (tùy chọn) |
| JDK 21, Node.js 20 | | Chỉ khi chạy backend/frontend ngoài Docker để phát triển |

Phần cứng: xem [`hardware/README.md`](hardware/README.md). Máy chủ và ESP32 phải cùng mạng WiFi 2,4 GHz.

Các lệnh dưới đây viết cho **PowerShell trên Windows**, chạy từ thư mục gốc repo.

### 5.2. Lấy mã nguồn và tạo file `.env`

```powershell
git clone https://github.com/nghianguyen2303/iot-gassguard-monitor.git iot-gas-monitor
cd iot-gas-monitor
Copy-Item .env.example .env
notepad .env
```

Trong `.env`, thay mọi giá trị `change_me` bằng mật khẩu riêng:

| Biến | Ý nghĩa |
|---|---|
| `POSTGRES_PASSWORD` | Mật khẩu CSDL |
| `MQTT_ADMIN_PASSWORD` | Tài khoản `admin` của broker (chỉ dùng để quản trị Dynamic Security) |
| `MQTT_BACKEND_PASSWORD` | Tài khoản `backend` của broker |
| `JWT_SECRET` | Khóa ký JWT, **tối thiểu 32 ký tự ngẫu nhiên** |
| `APP_ADMIN_PASSWORD` | Mật khẩu tài khoản web `admin` tạo tự động lần chạy đầu |
| `MQTT_DEVICE_URL` | Địa chỉ broker mà ESP32 dùng: `ssl://<IP-LAN-của-máy>:8883` (MQTT qua TLS; xem IP bằng `ipconfig`) |
| `MQTT_TLS_PORT` | Cổng MQTT/TLS mở ra LAN, mặc định 8883. Đặt 8884 nếu Windows đã giữ cổng 8883 (lỗi `ports are not available` khi chạy), khi đó sửa cổng trong `MQTT_DEVICE_URL` và tường lửa theo |
| `WEB_PORT`, `WEB_HTTPS_PORT` | Cổng web: 8088 (chỉ chuyển hướng) và 8443 (HTTPS) |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Tùy chọn: Gmail và Mật khẩu ứng dụng để gửi email cảnh báo ([hướng dẫn](deployment/README.md#cảnh-báo-qua-email-gmail)) |

Tạo chuỗi ngẫu nhiên cho `JWT_SECRET`:
`-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 48 | ForEach-Object {[char]$_})`

`.env` đã nằm trong `.gitignore`, **không bao giờ commit file này**.

### 5.3. Tạo chứng chỉ TLS

Broker (MQTT 8883) và web (HTTPS 8443) dùng chứng chỉ do **CA riêng của nhóm** ký. Script chạy trong Docker nên
không cần cài OpenSSL. Ghi **mọi IP LAN** mà máy chủ sẽ dùng (mạng nhà, hotspot khi demo…), cách nhau bởi dấu cách:

```powershell
docker run --rm -v "${PWD}:/repo" alpine:3.20 sh /repo/deployment/certs/gen-certs.sh 192.168.1.250 172.20.10.2
```

Kết quả (đều **không commit**, đã có trong `.gitignore`):

| File | Dùng cho |
|---|---|
| `deployment/certs/ca/ca.key`, `ca.crt` | CA của nhóm. **`ca.key` là bí mật**, không gắn vào container nào |
| `deployment/certs/server/server.crt`, `server.key` | Chứng chỉ máy chủ cho Mosquitto và nginx |
| `deployment/certs/server/ca.crt` | Bản sao CA để cài vào máy tính và cho bộ kiểm thử |
| `firmware/gas-node/ca_cert.h` | CA nhúng vào firmware để ESP32 xác thực broker |

Đổi mạng (IP mới): chạy lại lệnh với danh sách IP mới rồi khởi động lại Mosquitto và nginx
(`dc restart mosquitto frontend`; `up -d` không khởi động lại container đang chạy). CA giữ nguyên nên **không phải nạp lại
CA vào ESP32** và không phải cài lại CA vào máy; chỉ sửa `MQTT_HOST` trong `secrets.h` nếu ESP32 kết nối tới IP khác.

Cài CA vào Windows để trình duyệt (Chrome, Edge) tin trang web, không còn cảnh báo (làm một lần, bấm **Yes** khi hỏi):

```powershell
Import-Certificate -FilePath deployment\certs\server\ca.crt -CertStoreLocation Cert:\CurrentUser\Root
```

Chưa cài CA (hoặc mở từ điện thoại) thì trình duyệt cảnh báo *"Kết nối không riêng tư"*: kiểm tra vân tay CA mà script
in ra rồi chọn **Nâng cao → Tiếp tục**. Kết nối vẫn được mã hóa.

### 5.4. Khởi tạo broker MQTT (chỉ làm một lần trên máy mới)

Broker không cho kết nối ẩn danh; mọi tài khoản nằm trong file Dynamic Security lưu ở volume Docker `mosquitto-data`.
Lần đầu cần tạo tài khoản `admin` và tài khoản `backend` (tài khoản của từng thiết bị do backend tự tạo khi cấp xác
thực).

```powershell
cd deployment
# Đọc mật khẩu từ .env vào biến (không in ra màn hình)
$ADMIN_PASS   = (Select-String ..\.env -Pattern '^MQTT_ADMIN_PASSWORD=(.+)$').Matches[0].Groups[1].Value.Trim()
$BACKEND_PASS = (Select-String ..\.env -Pattern '^MQTT_BACKEND_PASSWORD=(.+)$').Matches[0].Groups[1].Value.Trim()
function mqctrl { docker compose --env-file ..\.env exec mosquitto mosquitto_ctrl -u admin -P $ADMIN_PASS dynsec @args }

# 1) Bật hạ tầng, tạo file Dynamic Security với tài khoản admin, khởi động lại broker để nạp file
docker compose --env-file ..\.env up -d mosquitto postgres
docker compose --env-file ..\.env exec mosquitto mosquitto_ctrl dynsec init /mosquitto/data/dynamic-security.json admin $ADMIN_PASS
docker compose --env-file ..\.env restart mosquitto

# 2) Tài khoản backend: chỉ được nhận bản tin thiết bị, gửi lệnh/cấu hình, và gọi API Dynamic Security
mqctrl createRole backend
mqctrl addRoleACL backend subscribePattern 'iot/v1/devices/+/#' allow
mqctrl addRoleACL backend publishClientSend 'iot/v1/devices/+/cmd' allow
mqctrl addRoleACL backend publishClientSend 'iot/v1/devices/+/config' allow
mqctrl addRoleACL backend publishClientSend '$CONTROL/dynamic-security/v1' allow
mqctrl addRoleACL backend subscribeLiteral '$CONTROL/dynamic-security/v1/response' allow
mqctrl addRoleACL backend publishClientReceive '$CONTROL/dynamic-security/v1/response' allow
mqctrl createClient backend -c iot-backend -p $BACKEND_PASS
mqctrl addClientRole backend backend
mqctrl getClient backend          # kiểm tra: roles = backend, clientid = iot-backend
cd ..
```

Câu cảnh báo *"running mosquitto_ctrl without encryption"* là bình thường (lệnh chạy bên trong container). Nếu bước 1
báo file đã tồn tại thì broker đã được khởi tạo trước đó, bỏ qua bước 1.

### 5.5. Chạy toàn bộ hệ thống

```powershell
cd deployment
docker compose --env-file ..\.env --profile app up -d --build    # lần đầu mất vài phút để build
docker compose --env-file ..\.env ps                             # backend phải (healthy)
cd ..
```

| Thành phần | Địa chỉ |
|---|---|
| Giao diện web | **https://localhost:8443** (máy khác trong LAN: `https://<IP-LAN>:8443`). `http://…:8088` tự chuyển sang HTTPS |
| REST API backend | http://127.0.0.1:8080 (chỉ mở cho máy local, phục vụ kiểm thử) |
| Broker MQTT cho thiết bị | `<IP-LAN>:8883` (TLS) |
| Broker MQTT không mã hóa | `127.0.0.1:1883` (chỉ trên máy chủ: công cụ quản trị, kiểm thử) |
| PostgreSQL | `localhost:15432` |

Đăng nhập web bằng tài khoản **`admin`** / mật khẩu `APP_ADMIN_PASSWORD`. Người tự đăng ký có vai trò `VIEWER`;
admin đổi vai trò thành `OWNER` ở trang **Người dùng**.

Dừng: `docker compose --env-file ..\.env --profile app down` (trong `deployment/`). Dữ liệu vẫn giữ trong volume; chỉ
mất khi thêm `-v`.

**Lệnh tắt.** [`deployment/dev-shell.ps1`](deployment/dev-shell.ps1) nạp sẵn các lệnh `app-up`, `app-down`,
`app-logs`, `dc`, `mqctrl`, `psql`. Nạp khi đang đứng ở thư mục gốc repo, script tự lấy thư mục
hiện tại làm `$IOT_ROOT` (hoặc đặt biến môi trường `IOT_ROOT` trước khi nạp):
`iex (Get-Content -Raw .\deployment\dev-shell.ps1)`.

### 5.6. Kết nối thiết bị

**ESP32 thật** – làm theo [`firmware/README.md`](firmware/README.md), tóm tắt:

1. Mở cổng **8883** trên tường lửa Windows (mạng Private).
2. Web → **Thiết bị** → *Đăng ký thiết bị* (ví dụ `esp32-001`) → tab **Xác thực MQTT** → *Cấp thông tin xác thực*.
   Mật khẩu thiết bị chỉ hiện **một lần**.
3. Copy `firmware/gas-node/secrets.h.example` thành `secrets.h`, dán đoạn vừa sao chép (có `MQTT_PORT 8883`,
   `MQTT_USE_TLS 1`), điền WiFi (`secrets.h` không được commit). Kiểm tra có file `firmware/gas-node/ca_cert.h`
   (tạo ở bước 5.3).
4. Nạp `firmware/gas-node/gas-node.ino` bằng Arduino IDE (board *ESP32 Dev Module*). Serial Monitor 115200 phải hiện
   `Kết nối MQTT (TLS) …` rồi `Đã kết nối broker`; web hiện thiết bị **ACTIVE**, **Trực tuyến**.

**Thiết bị mô phỏng** (khi chưa có phần cứng, hoặc để thử nhiều thiết bị) – xem
[`tests/simulator/README.md`](tests/simulator/README.md).

### 5.7. Chạy để phát triển (không đóng gói Docker)

Chỉ chạy hạ tầng bằng Docker (`docker compose --env-file ..\.env up -d` trong `deployment/`), sau đó:

- Backend: mở `backend/` bằng IntelliJ và chạy `BackendApplication` (tự đọc `../.env`), hoặc `.\mvnw spring-boot:run`.
- Frontend: `cd frontend; npm install; npm run dev` → http://localhost:5173 (xem [`frontend/README.md`](frontend/README.md)).

## 6. Kiểm thử

Kế hoạch: [`docs/test-plan.md`](docs/test-plan.md). Kết quả tổng hợp: [`docs/test-report.md`](docs/test-report.md).

```powershell
pip install -r tests\requirements.txt
python tests\e2e\run_e2e.py       # 18 ca chức năng (7.1) và bảo mật (4.5)
python tests\perf\run_perf.py     # 8 chỉ tiêu phi chức năng (7.2), ~40 phút
python tests\make_report.py       # gộp kết quả -> docs\test-report.md
```

| Bộ | Nội dung |
|---|---|
| Chức năng F1–F9 | Đăng nhập và phân quyền; đăng ký, kích hoạt thiết bị; telemetry; dashboard realtime; lệnh và ACK; cấu hình từ xa; phát hiện offline; cảnh báo; khóa và ngừng sử dụng thiết bị |
| Bảo mật S1–S9 | Không truy cập thiết bị người khác; người xem không điều khiển được; token sai/hết hạn/thu hồi; broker chặn sai topic và sai tài khoản; từ chối dữ liệu không hợp lệ; khóa tạm khi dò mật khẩu; mật khẩu thiết bị cũ hết hiệu lực; audit log và BCrypt; kết nối mã hóa TLS (MQTT 8883, HTTPS, từ chối kết nối không mã hóa và chứng chỉ giả) |
| Phi chức năng P1–P8 | Độ trễ cảm biến → dashboard; độ trễ lệnh; tỷ lệ mất bản tin; mất/khôi phục mạng, broker, backend; bản tin trùng; CPU/RAM; chạy liên tục; số thiết bị đồng thời |
| Thiết bị thật H1–H10 | Làm thủ công với ESP32, ghi ở [`docs/hardware-test-results.md`](docs/hardware-test-results.md) |

Báo cáo từng lần chạy lưu trong [`tests/reports/`](tests/reports/) (`.md` để đọc, `.json` số liệu thô). Chi tiết:
[`tests/README.md`](tests/README.md).

## 7. Bảo mật và quyền riêng tư

| Yêu cầu (4.5) | Cách làm |
|---|---|
| Mật khẩu băm an toàn | BCrypt (cost 12) cho mật khẩu người dùng; mã xác nhận email lưu dạng SHA-256 |
| API yêu cầu xác thực, kiểm tra quyền ở backend | JWT HS256 (hết hạn 60 phút, thu hồi khi đăng xuất/đổi mật khẩu); kiểm tra vai trò và quyền trên từng thiết bị cho mọi API và kênh WebSocket |
| Định danh và xác thực riêng cho thiết bị | Mỗi thiết bị một tài khoản MQTT (username = clientId = mã thiết bị), mật khẩu ngẫu nhiên 24 ký tự, ACL chỉ cho đúng topic của chính nó |
| Không lộ bí mật lên GitHub | Bí mật chỉ ở `.env` và `firmware/gas-node/secrets.h` (đều trong `.gitignore`); repo chỉ có `.env.example`, `secrets.h.example` |
| Kiểm tra dữ liệu đầu vào | Telemetry: JSON, trường bắt buộc, đơn vị, khoảng giá trị, thời gian, kích thước; REST: Bean Validation |
| Khóa, thu hồi thiết bị | Tạm khóa, đổi mật khẩu, ngừng sử dụng → broker ngắt kết nối ngay |
| Audit log | Đăng nhập, quản lý người dùng, vòng đời thiết bị, lệnh, cấu hình, chia sẻ, xuất dữ liệu, truy cập bị từ chối (kèm IP) |
| Mã hóa đường truyền | TLS 1.2/1.3: ESP32 ↔ broker qua MQTT 8883 (ESP32 xác thực broker bằng CA của nhóm), trình duyệt ↔ web qua HTTPS 8443 (HTTP chỉ chuyển hướng). Cổng 1883 không mã hóa chỉ mở trong máy chủ. Khóa CA và khóa máy chủ không commit |
| Dữ liệu cá nhân | Thu thập: tên đăng nhập, họ tên, email nhận cảnh báo (tùy chọn, phải xác nhận), IP trong audit log. Không thu thập vị trí, hình ảnh, âm thanh. Người dùng chỉ thấy thiết bị và dữ liệu trong phạm vi được cấp quyền; mỗi email cảnh báo gửi riêng từng người, không lộ địa chỉ người khác; người dùng tự tắt nhận email bất kỳ lúc nào |

## 8. Tài liệu

| Tài liệu | Đường dẫn |
|---|---|
| Hướng dẫn triển khai, email, xử lý sự cố | [`deployment/README.md`](deployment/README.md) |
| Phần cứng: linh kiện, nối dây | [`hardware/README.md`](hardware/README.md) |
| Firmware: cài đặt, nạp, kiểm tra | [`firmware/README.md`](firmware/README.md) |
| Giao diện web: các màn hình | [`frontend/README.md`](frontend/README.md) |
| Kế hoạch và kết quả kiểm thử | [`docs/test-plan.md`](docs/test-plan.md), [`docs/test-report.md`](docs/test-report.md) |
| Báo cáo, slide, tài liệu thiết kế | [`docs/`](docs/) |

## 9. Thư viện và tài nguyên bên thứ ba

| Thành phần | Thư viện / phần mềm (giấy phép) |
|---|---|
| Firmware | Arduino-ESP32 3.x (LGPL-2.1), MQTT by Joel Gaehwiler 2.5 (MIT), ArduinoJson 7 (MIT), OneWire (MIT-style), DallasTemperature (LGPL-2.1) |
| Backend | Spring Boot 3.5.16, Spring Security, Spring Data JPA, Spring WebSocket, Flyway, PostgreSQL JDBC, jjwt 0.12.6, Eclipse Paho MQTT 1.2.5, Eclipse Angus Mail, Lombok (Apache-2.0 / EPL / MIT / BSD) |
| Frontend | React 18.3, React Router 6.28, Recharts 2.13, Vite 5.4 (MIT); @stomp/stompjs 7.0 (Apache-2.0) |
| Hạ tầng | Eclipse Mosquitto 2.0.20 (EPL/EDL), PostgreSQL 16, nginx 1.27 (Docker image chính thức) |
| Kiểm thử | paho-mqtt 2.1 (EPL/EDL), websocket-client 1.x (Apache-2.0) |

Công thức quy đổi MQ-2 sang ppm dùng đường đặc tuyến LPG trong datasheet MQ-2 (Hanwei Electronics).

**Sử dụng công cụ AI.** Nhóm có dùng trợ lý AI (Claude) để hỗ trợ viết mã, kiểm thử và tài liệu. Toàn bộ nội dung đã
được nhóm đọc hiểu, chạy kiểm thử trên hệ thống thật và chịu trách nhiệm về kết quả.

## 10. Hạn chế và hướng phát triển

- TLS dùng CA tự tạo của nhóm (không có tên miền công khai): máy chưa cài CA sẽ thấy cảnh báo chứng chỉ. Thiết bị xác thực bằng mật khẩu riêng; hướng phát triển: chứng chỉ client cho từng thiết bị (mTLS), triển khai lên máy chủ có tên miền và chứng chỉ công cộng.
- MQ-2 chỉ cho nồng độ ước lượng (đã hiệu chuẩn R0 trong không khí sạch), không thay thế đầu báo gas đạt chuẩn.
- Chưa có cập nhật firmware từ xa (OTA) và chức năng AI.
- Số liệu tải đo trên một laptop; mỗi bản tin ghi CSDL một giao dịch, có thể ghi theo lô khi cần nhiều thiết bị hơn.

## 11. Nhóm thực hiện

| Thành viên | MSSV | GitHub | Phụ trách |
|---|---|---|---|
| Đỗ Bình Minh | B23DCCN542 | [DoBinhMinh](https://github.com/DoBinhMinh) | Phân tích bài toán, yêu cầu; phần cứng (linh kiện, lắp mạch, sơ đồ – `hardware/`); firmware ESP32 (`firmware/`); giao diện web React (`frontend/`); kiểm thử với ESP32 thật H1–H10 |
| Nguyễn Đại Nghĩa | B23DCCN598 | [nghianguyen2303](https://github.com/nghianguyen2303) | Backend Spring Boot, cơ sở dữ liệu, API (`backend/`, `database/`); broker Mosquitto, TLS, Docker Compose (`deployment/`); kiểm thử tự động, thiết bị mô phỏng (`tests/`) |

Cả hai thành viên phối hợp: báo cáo, slide, video demo (`docs/`). Chi tiết mức tham gia từng hạng mục: Bảng 2 trong báo cáo.

Repository: <https://github.com/nghianguyen2303/iot-gassguard-monitor> · Phiên bản phát hành: `v1.0.0`
