# Triển khai GasGuard bằng Docker Compose

## Kiến trúc container

```
 Trình duyệt ──HTTPS :8443──► frontend (nginx)        (HTTP :8088 chỉ chuyển hướng sang HTTPS)
                               ├─ /           → file tĩnh React (SPA)
                               ├─ /api/*      → backend:8080 (REST)
                               └─ /ws         → backend:8080 (WebSocket STOMP, wss)
 ESP32 ──MQTT/TLS :8883──► mosquitto ◄─MQTT :1883 (mạng Docker)─ backend ──JDBC──► postgres
 Công cụ trên máy chủ (simulator, kiểm thử) ─MQTT 127.0.0.1:1883─► mosquitto
```

| Service | Image | Cổng trên máy | Ghi chú |
|---|---|---|---|
| mosquitto | eclipse-mosquitto:2.0.20 | 8883 (TLS, cho LAN), 127.0.0.1:1883 | Dynamic Security, không cho anonymous; 1883 không mã hóa chỉ mở cho chính máy chủ |
| postgres | postgres:16-alpine | 15432 | Dữ liệu trong volume `pg-data` |
| backend | gasguard/backend:1.0.0 (build từ `backend/`) | 127.0.0.1:8080 | Chỉ mở cho máy local, chạy user không phải root |
| frontend | gasguard/frontend:1.0.0 (build từ `frontend/`) | `${WEB_HTTPS_PORT}` (mặc định 8443), `${WEB_PORT}` (8088, chỉ chuyển hướng) | nginx HTTPS + reverse proxy |

`backend` và `frontend` nằm trong profile `app`: lệnh `up` thường chỉ bật hạ tầng (phục vụ dev với IntelliJ và `npm run dev`).

## Chuẩn bị

1. Docker Desktop đang chạy.
2. File `.env` ở thư mục gốc (tạo từ `.env.example`, **không commit**). Đặt `MQTT_DEVICE_URL=ssl://<IP-LAN-máy-bạn>:8883` để trang cấp thông tin xác thực hiện đúng địa chỉ broker cho ESP32.
3. Chứng chỉ TLS đã tạo (mục [Mã hóa TLS](#mã-hóa-tls-mqtt-8883-và-https)). Thiếu chứng chỉ thì Mosquitto và nginx không khởi động.
4. Tài khoản `backend` và quyền ACL trên Mosquitto đã tạo ở các bước trước. Volume `mosquitto-data` giữ lại cấu hình này. Nếu dựng trên máy mới thì phải tạo lại.

## Chạy toàn bộ hệ thống

```powershell
iex (Get-Content -Raw E:\IOT\iot-gas-monitor\deployment\dev-shell.ps1)
app-up          # build image + chạy mosquitto, postgres, backend, frontend
dc ps           # kiểm tra: backend, frontend phải là (healthy)
app-logs backend
```

Mở **https://localhost:8443** (gõ `http://localhost:8088` cũng được, tự chuyển sang HTTPS). Từ điện thoại hoặc máy khác cùng mạng: `https://<IP-LAN>:8443`.

Dừng phần ứng dụng (giữ hạ tầng và dữ liệu): `app-down`. Dừng tất cả: `dc --profile app down` (dữ liệu vẫn còn trong volume; chỉ mất khi thêm `-v`).

Không có dev-shell:

```powershell
cd E:\IOT\iot-gas-monitor\deployment
docker compose --env-file ..\.env --profile app up -d --build
```

## Mã hóa TLS (MQTT 8883 và HTTPS)

| Đường truyền | Cổng | Mã hóa |
|---|---|---|
| ESP32 ↔ broker | 8883 | MQTT qua TLS 1.2/1.3; ESP32 xác thực broker bằng CA của nhóm (`firmware/gas-node/ca_cert.h`) |
| Trình duyệt ↔ web | 8443 | HTTPS (TLS 1.2/1.3); REST và WebSocket (`wss://`) đi trong cùng kết nối |
| Trình duyệt → web | 8088 | Không phục vụ nội dung, chỉ trả 301 sang HTTPS |
| backend ↔ broker, nginx ↔ backend | 1883, 8080 | Không mã hóa nhưng chỉ đi trong mạng nội bộ Docker trên cùng máy, không ra LAN |
| Công cụ trên máy chủ ↔ broker | 127.0.0.1:1883 | Không mã hóa, chỉ trên chính máy chủ (simulator, kiểm thử, `mqctrl`) |

**1. Tạo chứng chỉ** (từ thư mục gốc repo; liệt kê mọi IP LAN mà máy chủ sẽ dùng: mạng nhà, hotspot khi demo…):

```powershell
docker run --rm -v "${PWD}:/repo" alpine:3.20 sh /repo/deployment/certs/gen-certs.sh 192.168.1.250 172.20.10.2
# hoặc sau khi nạp dev-shell:  gen-certs 192.168.1.250 172.20.10.2
```

Script tạo CA của nhóm (`certs/ca/`, chỉ tạo lần đầu), chứng chỉ máy chủ (`certs/server/`) có các IP trên cùng
`localhost`, `127.0.0.1`, `mosquitto`, và ghi `firmware/gas-node/ca_cert.h`. Container chỉ được gắn thư mục `server/`;
khóa bí mật của CA không vào container nào. Tất cả đều nằm trong `.gitignore`.

**2. Chạy lại hệ thống**: `app-up` (lần đầu sau khi bật TLS cần build lại frontend). Kiểm tra log broker có
`Opening ipv4 listen socket on port 8883`: `app-logs mosquitto`.

**3. Tường lửa Windows**: mở cổng 8883 (PowerShell **Run as Administrator**). Cổng 1883 không còn mở ra LAN nên có thể
xóa luật cũ:

```powershell
New-NetFirewallRule -DisplayName "GasGuard MQTT TLS 8883" -Direction Inbound -Protocol TCP -LocalPort 8883 -Action Allow -Profile Private
New-NetFirewallRule -DisplayName "GasGuard Web HTTPS 8443" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow -Profile Private
Remove-NetFirewallRule -DisplayName "GasGuard MQTT 1883"     # nếu đã tạo trước đây
```

**Cổng 8883 bị Windows giữ** (`app-up` báo `ports are not available: ... 8883 ... forbidden by its access
permissions`; kiểm tra bằng `netsh interface ipv4 show excludedportrange protocol=tcp`): thêm `MQTT_TLS_PORT=8884`
vào `.env`, sửa cổng trong `MQTT_DEVICE_URL` thành 8884, mở tường lửa cổng 8884 thay cho 8883 rồi `app-up`. Trong
container broker vẫn nghe 8883; chứng chỉ không phụ thuộc cổng nên không phải tạo lại. ESP32 dùng `MQTT_PORT 8884`
(đoạn sao chép trên web tự có cổng đúng).

**4. ESP32**: trong `secrets.h` đặt `MQTT_PORT 8883` và `MQTT_USE_TLS 1` (đoạn sao chép trên web đã có sẵn), có file
`ca_cert.h` cùng thư mục, rồi nạp lại firmware ([firmware/README.md](../firmware/README.md)).

**5. Trình duyệt**: cài CA vào Windows một lần để Chrome/Edge không cảnh báo:
`Import-Certificate -FilePath deployment\certs\server\ca.crt -CertStoreLocation Cert:\CurrentUser\Root`.
Máy chưa cài CA (điện thoại, máy giảng viên) sẽ thấy cảnh báo chứng chỉ; so vân tay CA mà script in ra rồi chọn
*Nâng cao → Tiếp tục*, kết nối vẫn được mã hóa.

**Đổi mạng / đổi IP** (vd. demo bằng hotspot): chạy lại bước 1 với danh sách IP mới rồi `dc restart mosquitto frontend`
(`app-up` / `up -d` không khởi động lại container đang chạy nên Mosquitto, nginx vẫn dùng chứng chỉ cũ). CA giữ nguyên nên
không phải cài lại CA và không phải thay `ca_cert.h` trên ESP32; chỉ sửa `MQTT_HOST` trong `secrets.h` nếu IP broker
mà ESP32 dùng thay đổi. Muốn tạo lại CA từ đầu: xóa `deployment/certs/ca/` rồi chạy lại (phải nạp lại ESP32).

## Cảnh báo qua email (Gmail)

Hệ thống dùng **một tài khoản Gmail làm hộp thư gửi** và gửi email thật tới hộp thư của từng người dùng.

1. Đăng nhập tài khoản Gmail dùng để gửi → **Google Account** → **Bảo mật** → bật **Xác minh 2 bước**.
2. Vẫn ở **Bảo mật**, tìm **Mật khẩu ứng dụng** (App passwords; hoặc vào thẳng https://myaccount.google.com/apppasswords) → đặt tên `GasGuard` → **Tạo**. Google hiện mật khẩu 16 ký tự dạng `abcd efgh ijkl mnop`.
3. Thêm vào `.env` (không commit):
   ```
   MAIL_USERNAME=gasguard.nhom5@gmail.com
   MAIL_PASSWORD=abcd efgh ijkl mnop
   ```
   Có dấu cách cũng được, backend tự bỏ.
4. `app-up`. Mỗi người dùng: web → **Tài khoản** → **Nhận cảnh báo qua email** → nhập email → **Gửi mã xác nhận** → nhập mã 6 số nhận trong hộp thư → **Xác nhận và bật** → **Gửi email thử**.
5. Người dùng nhận email cho thiết bị mình có quyền xem, mỗi người một email riêng (không lộ địa chỉ của người khác).
   Chống spam: mỗi sự cố **tối đa 2 email** – 1 khi xảy ra (mất kết nối, vượt ngưỡng gas/nhiệt độ, lỗi cảm biến) và
   1 khi trở lại bình thường (*Đã kết nối lại*, hết vượt ngưỡng). Chập chờn nhiều lần không gửi thêm cho đến khi cảnh
   báo được xử lý trên web; không gửi email "tái diễn" và "đã xử lý xong". Thiết bị của bộ kiểm thử tự động
   (mã bắt đầu `e2e-`, `perf-`) không gửi email, cảnh báo vẫn hiện trên web (danh sách tiền tố: thuộc tính
   `app.mail.skip-device-prefixes` của backend, mặc định `e2e-,perf-`). Thiết bị thật đặt mã khác các tiền tố này.

Vì sao phải xác nhận bằng mã: chứng minh người dùng là chủ hộp thư, để không ai lợi dụng hệ thống gửi thư hàng loạt tới email của người khác. Mã chỉ lưu dạng băm, hết hạn sau 10 phút, sai 5 lần thì hủy, xin mã mới cách nhau ít nhất 60 giây.

| Lỗi khi bấm *Gửi mã* / *Gửi email thử* | Cách xử lý |
|---|---|
| *Máy chủ email từ chối đăng nhập* | Sai `MAIL_USERNAME` hoặc đang dùng mật khẩu Gmail thường thay vì Mật khẩu ứng dụng |
| *Không kết nối được smtp.gmail.com:587* | Mạng chặn cổng 587 (thường gặp ở mạng trường): thử mạng khác / hotspot điện thoại |
| Không thấy email | Xem mục **Spam / Quảng cáo**, bấm "Không phải spam" một lần |
| Thẻ email báo *chưa cấu hình* | Chưa có `MAIL_USERNAME` / `MAIL_PASSWORD` trong `.env`, hoặc chưa chạy lại `app-up` |

Gmail cá nhân giới hạn khoảng 500 email/ngày, dư cho đồ án.

## Cảnh báo qua Telegram (tùy chọn)

Không cấu hình `TELEGRAM_BOT_TOKEN` thì Telegram tự tắt và không hiện trên giao diện.

1. Trong Telegram, nhắn cho **@BotFather**, gõ `/newbot`, đặt tên (vd. `GasGuard Nhóm 5`) và username kết thúc bằng `bot` (vd. `gasguard_nhom5_bot`). BotFather trả về **token** dạng `123456789:AA...`.
2. Thêm vào `.env` (không commit): `TELEGRAM_BOT_TOKEN=123456789:AA...`
3. `app-up`, rồi xem `app-logs backend` phải có dòng `Telegram: đã bật, bot @...`.
4. Mỗi người dùng tự liên kết: web → **Tài khoản** → **Liên kết Telegram** → **Mở Telegram** → bấm **Start**. Trang tự chuyển sang *Đã liên kết*; bấm **Gửi tin nhắn thử** để kiểm tra.
5. Người dùng nhận tin khi cảnh báo **mới**, **tái diễn**, **trở lại bình thường**, **đã xử lý xong**, chỉ với thiết bị mình có quyền xem (admin: tất cả; chủ sở hữu; người được chia sẻ). Gửi `/stop` cho bot để ngừng nhận.

**Nhà mạng chặn Telegram:** từ 2025 các nhà mạng Việt Nam được yêu cầu chặn Telegram. Backend đã dùng DNS công cộng (1.1.1.1, 8.8.8.8), thường đủ để gọi được API. Nếu nút *Gửi tin nhắn thử* vẫn báo *Không kết nối được tới Telegram*:
- bật VPN trên máy chạy Docker (Docker Desktop đi qua mạng của máy), hoặc
- đặt `TELEGRAM_API_BASE` trỏ tới một relay của bạn (vd. Cloudflare Worker chuyển tiếp tới `https://api.telegram.org`).

Bot token chỉ nằm trong `.env`, không xuất hiện trong log, giao diện hay audit log.

## Bảo mật khi triển khai (mục 4.5)

- Không có bí mật trong image hay trong `docker-compose.yml`. Mật khẩu chỉ đến từ `.env` lúc chạy. Compose dừng ngay nếu thiếu `POSTGRES_PASSWORD`, `MQTT_BACKEND_PASSWORD`, `JWT_SECRET`, `APP_ADMIN_PASSWORD`.
- Backend chỉ nhận đúng các biến nó cần (không nhận `MQTT_ADMIN_PASSWORD`).
- Container backend chạy bằng user `app` (không root). Cổng 8080 chỉ bind `127.0.0.1`.
- Mọi kết nối qua LAN đều mã hóa TLS (MQTT 8883, HTTPS 8443). nginx báo `X-Forwarded-Proto: https` và cổng gốc cho
  backend để kiểm tra cùng origin đúng với địa chỉ HTTPS mà trình duyệt dùng.
- nginx ghi đè `X-Forwarded-For` bằng IP thật của client. Backend dùng `server.forward-headers-strategy=native`, nên chỉ tin header này khi đến từ proxy nội bộ. Nhờ vậy audit log ghi đúng IP, không giả mạo được.
- nginx tắt hiển thị phiên bản, thêm `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, giới hạn body 1 MB.
- Request REST có Origin lạ bị backend trả 403 (CORS).

## Sự cố thường gặp

| Hiện tượng | Cách xử lý |
|---|---|
| `port is already allocated` 8080 | Tắt backend đang chạy trong IntelliJ trước khi `app-up` |
| `port is already allocated` 8088 | Thêm `WEB_PORT=8090` vào `.env` |
| backend `unhealthy`, log báo MQTT `Not authorized` | Kiểm tra `MQTT_BACKEND_PASSWORD` khớp với tài khoản `backend` trên broker |
| Build chậm lần đầu | Maven/npm tải thư viện (vài phút). Các lần sau dùng cache |
| Hết RAM | `.wslconfig` để `memory=3GB`. Backend đã giới hạn heap 384 MB |
| mosquitto hoặc frontend khởi động rồi dừng, log báo không đọc được `server.crt` / `server.key` | Chưa tạo chứng chỉ: chạy `gen-certs <IP-LAN>` rồi `app-up` |
| `ports are not available ... 8883 ... forbidden by its access permissions` | Windows đã giữ cổng 8883: đặt `MQTT_TLS_PORT=8884` trong `.env` (xem mục Mã hóa TLS) |
| ESP32 báo `Lỗi TLS: ... verification failed` / `CN mismatch` | `MQTT_HOST` không có trong chứng chỉ (chạy lại `gen-certs` thêm IP đó) hoặc `ca_cert.h` không khớp CA hiện tại (chép lại và nạp lại) |
| Trình duyệt báo *Kết nối không riêng tư* | Chưa cài CA vào máy: xem bước 5 mục Mã hóa TLS |
