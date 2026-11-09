# Mô tả API – GasGuard

Backend cung cấp **REST API** (JSON) cho giao diện web và **WebSocket STOMP** cho cập nhật thời gian thực. Có 53
endpoint trong 13 controller (`backend/src/main/java/com/iot/gas/web/`). Thiết bị không gọi REST; thiết bị dùng MQTT
(xem `docs/design/mqtt.md`).

## 1. Quy ước chung

| Mục | Quy ước |
|---|---|
| Địa chỉ | Qua web: `https://<máy chủ>:8443/api/...` (nginx chuyển tiếp). Trực tiếp backend: `http://127.0.0.1:8080/api/...` (chỉ trên máy chủ) |
| Xác thực | Header `Authorization: Bearer <JWT>`; JWT HS256, hết hạn sau **60 phút**, nhận khi đăng nhập. Chỉ `POST /api/auth/login`, `POST /api/auth/register` không cần token |
| Định dạng | Request/response JSON UTF-8 (riêng xuất CSV trả `text/csv`) |
| Thời gian | Response: ISO-8601 UTC (`2026-11-08T05:55:50Z`). Tham số `from`/`to`: ISO-8601 có múi giờ, hoặc giờ địa phương không múi giờ (hiểu là giờ Việt Nam) |
| `{code}` | Mã thiết bị, ví dụ `esp32-001` |
| Phân trang | `page` (từ 0), `size`; response `PageResponse` = `{items, total, page, size}` |
| Quyền | **Xem** = ADMIN, chủ thiết bị, người được chia sẻ. **Quản lý** = ADMIN, hoặc OWNER là chủ thiết bị. Chi tiết: `docs/design/roles.md` |

### Định dạng lỗi

Mọi lỗi trả cùng một dạng:

```json
{"timestamp": "2026-11-08T05:55:50Z", "status": 409, "error": "DEVICE_OFFLINE",
 "message": "Thiết bị đang OFFLINE, không thể gửi lệnh", "path": "/api/devices/esp32-001/commands",
 "fieldErrors": null}
```

| HTTP | Ý nghĩa | Ví dụ `error` |
|---|---|---|
| 400 | Dữ liệu không hợp lệ (có `fieldErrors` theo từng trường) | `VALIDATION_ERROR`, `MALFORMED_JSON`, `BAD_PARAMETER`, `INVALID_PARAMS`, `UNSUPPORTED_ACTION`, `REASON_REQUIRED`, `RANGE_TOO_LARGE`, `NOTE_REQUIRED` |
| 401 | Chưa đăng nhập; token thiếu, sai, giả mạo, hết hạn, đã thu hồi; sai tên đăng nhập/mật khẩu | `UNAUTHORIZED`, `BAD_CREDENTIALS` |
| 403 | Không có quyền (ghi audit `DENIED`); tài khoản bị admin khóa | `FORBIDDEN`, `ACCOUNT_LOCKED` |
| 404 | Không tìm thấy | `NOT_FOUND` |
| 409 | Xung đột trạng thái hoặc trùng dữ liệu | `DEVICE_EXISTS`, `INVALID_TRANSITION`, `DEVICE_OFFLINE`, `DEVICE_NOT_CONTROLLABLE`, `ALREADY_PROVISIONED`, `USERNAME_TAKEN`, `INVALID_STATE` |
| 423 | Khóa tạm do sai mật khẩu 5 lần (15 phút) | `TEMPORARILY_LOCKED` |
| 500 | Lỗi hệ thống (không lộ chi tiết bên trong) | `INTERNAL_ERROR` |

## 2. Xác thực và tài khoản – `/api/auth`

| Method | Đường dẫn | Quyền | Request | Response |
|---|---|---|---|---|
| POST | `/api/auth/register` | Công khai | `{username, password, email?, fullName?}` | 201 `UserDto` (vai trò VIEWER) |
| POST | `/api/auth/login` | Công khai | `{username, password}` | 200 `{token, tokenType: "Bearer", expiresAt, user}` |
| POST | `/api/auth/logout` | Đã đăng nhập | | 200; mọi token cũ của người này hết hiệu lực |
| GET | `/api/auth/me` | Đã đăng nhập | | `UserDto` |
| PUT | `/api/auth/me` | Đã đăng nhập | `{email?, fullName?}` | `UserDto` |
| PUT | `/api/auth/password` | Đã đăng nhập | `{currentPassword, newPassword}` | 200; phải đăng nhập lại |
| GET | `/api/auth/me/email-alerts` | Đã đăng nhập | | Trạng thái nhận cảnh báo email |
| POST | `/api/auth/me/email-alerts/request` | Đã đăng nhập | `{email}` | Gửi mã 6 số tới email (hết hạn 10 phút) |
| POST | `/api/auth/me/email-alerts/confirm` | Đã đăng nhập | `{code}` | Xác nhận email nhận cảnh báo |
| POST | `/api/auth/me/email-alerts/test` | Đã đăng nhập | | Gửi email thử |
| DELETE | `/api/auth/me/email-alerts` | Đã đăng nhập | | Tắt nhận cảnh báo email |
| GET / POST / DELETE | `/api/auth/me/telegram`, `…/telegram/link`, `…/telegram/test` | Đã đăng nhập | | Liên kết Telegram (tùy chọn; giao diện ẩn khi chưa cấu hình bot) |

Ràng buộc: tên đăng nhập 3–50 ký tự (chữ, số, `_`, `.`); mật khẩu 8–72 ký tự, có ít nhất 1 chữ cái và 1 chữ số. Đăng
nhập sai báo cùng một thông báo cho "sai tên" và "sai mật khẩu" (không lộ tài khoản nào tồn tại).

`UserDto`: `id, username, email, fullName, role, status, lockedUntil, lastLoginAt, createdAt` (không bao giờ có mật
khẩu hay mã băm).

## 3. Thiết bị và vòng đời – `/api/devices`

| Method | Đường dẫn | Quyền | Request | Response |
|---|---|---|---|---|
| GET | `/api/devices?includeDecommissioned=false` | Đã đăng nhập | | Danh sách `DeviceDto` mình được xem (ADMIN: tất cả) |
| POST | `/api/devices` | ADMIN, OWNER | `{deviceCode, name, deviceType?, location?, zone?, hwVersion?, ownerUsername?}` | 201 `DeviceDto` (REGISTERED). `ownerUsername` chỉ ADMIN dùng được |
| GET | `/api/devices/{code}` | Xem | | `DeviceDto` |
| PUT | `/api/devices/{code}` | Quản lý | `{name, deviceType?, location?, zone?, hwVersion?}` | `DeviceDto` |
| POST | `/api/devices/{code}/provision` | Quản lý | | 201 `ProvisionResponse` (REGISTERED → PROVISIONED) |
| POST | `/api/devices/{code}/credentials/rotate` | Quản lý | | `ProvisionResponse` (mật khẩu mới, mật khẩu cũ bị thu hồi) |
| GET | `/api/devices/{code}/credentials` | Quản lý | | Lịch sử thông tin xác thực (chỉ vân tay SHA-256) |
| POST | `/api/devices/{code}/lifecycle` | Quản lý | `{action, reason?}` | `DeviceDto` |
| GET | `/api/devices/{code}/shares` | Quản lý | | Danh sách người được chia sẻ |
| POST | `/api/devices/{code}/shares` | Quản lý | `{username}` | 201 `ShareDto` |
| DELETE | `/api/devices/{code}/shares/{username}` | Quản lý | | 204 |
| GET | `/api/devices/{code}/activity?page&size` | Xem | | Nhật ký hoạt động của thiết bị (audit) |

- `deviceCode`: 3–40 ký tự, chữ thường, số, `-`; không trùng kể cả với thiết bị đã ngừng sử dụng (409 `DEVICE_EXISTS`).
- `action`: `ACTIVATE`, `START_MAINTENANCE`, `END_MAINTENANCE`, `SUSPEND`, `RESUME`, `DECOMMISSION`. `SUSPEND` và
  `DECOMMISSION` bắt buộc `reason`. Chuyển sai → 409 `INVALID_TRANSITION` (kèm các thao tác hợp lệ).
- `ProvisionResponse`: `deviceId, mqttUrl, username, clientId, password, topics, note`. **Mật khẩu chỉ trả về một lần**,
  CSDL chỉ lưu SHA-256.
- `DeviceDto` gồm: thông tin, chủ sở hữu, `lifecycleStatus`, `connectivity`, `lastSeenAt`, `hwVersion`, `fwVersion`,
  cấu hình và trạng thái áp dụng, trạng thái quạt/còi, và `canManage` (người gọi có được quản lý không).

## 4. Dữ liệu đo – `/api/devices/{code}/telemetry` (quyền Xem)

| Method | Đường dẫn | Tham số | Response |
|---|---|---|---|
| GET | `/telemetry/latest` | | Giá trị mới nhất mỗi chỉ số `{gas: {metric, value, unit, ts}, temp: {…}}` |
| GET | `/telemetry` | `metric?`, `from?`, `to?` (mặc định 1 giờ gần nhất, tối đa 31 ngày), `limit` (mặc định 1000, tối đa 20000) | Danh sách điểm đo thô, `truncated` nếu bị cắt |
| GET | `/telemetry/series` | `metric?`, `from?`, `to?` (mặc định 24 giờ, tối đa 366 ngày), `bucket` = `auto`, `1m`, `5m`, `15m`, `30m`, `1h`, `6h`, `1d` | Dữ liệu gộp theo khoảng cho biểu đồ: mỗi điểm `{bucket, metric, avg, min, max, count}`; mốc ngày theo giờ Việt Nam |
| GET | `/telemetry/stats` | `from?`, `to?` (mặc định 24 giờ) | Thống kê từng chỉ số, số cảnh báo trong khoảng, ước lượng tỷ lệ mất bản tin theo `seq` |
| GET | `/telemetry/export.csv` | `metric?`, `from?`, `to?` (mặc định 24 giờ, tối đa 31 ngày) | File CSV; ghi audit `DATA_EXPORT` |

## 5. Điều khiển và cấu hình

| Method | Đường dẫn | Quyền | Request | Response |
|---|---|---|---|---|
| POST | `/api/devices/{code}/commands?waitMs=0` | Quản lý | `{action, params}` | 201 `CommandDto`. `waitMs` (tối đa 12000): chờ ACK rồi mới trả |
| GET | `/api/devices/{code}/commands?limit=50` | Xem | | Lịch sử lệnh, mới nhất trước (tối đa 500) |
| GET | `/api/devices/{code}/commands/{id}` | Xem | | `CommandDto` |
| GET | `/api/devices/{code}/config` | Xem | | `DeviceConfigDto` |
| PUT | `/api/devices/{code}/config` | Quản lý | `{telemetryIntervalSec?, gasThreshold?, autoFan?}` | `DeviceConfigDto` (tăng `configVersion`, gửi xuống thiết bị) |

- `action` / `params`: `FAN_SET {on}`, `BUZZER_SET {on}`, `FAN_AUTO`, `BUZZER_AUTO`, `PING` (không tham số). Sai →
  400. Thiết bị không ở ACTIVE/MAINTENANCE → 409 `DEVICE_NOT_CONTROLLABLE`; OFFLINE → 409 `DEVICE_OFFLINE`.
- `CommandDto`: `id` (= `commandId`), `action, params, status` (PENDING, SENT, SUCCESS, FAILED, TIMEOUT),
  `requestedByUsername, createdAt, sentAt, ackedAt, latencyMs, resultMessage, resultState`.
- Cấu hình: `telemetryIntervalSec` 1–3600, `gasThreshold` 50–10000 ppm, `autoFan` true/false; ít nhất một tham số.
  `DeviceConfigDto` có `configVersion`, `appliedVersion`, `status` (NONE, PENDING, APPLIED, REJECTED), `message`.

## 6. Cảnh báo và luật cảnh báo

| Method | Đường dẫn | Quyền | Request / tham số | Response |
|---|---|---|---|---|
| GET | `/api/alerts` | Đã đăng nhập (chỉ thiết bị mình xem được) | `deviceCode?, status?` (OPEN, ACKNOWLEDGED, RESOLVED, ACTIVE = chưa xử lý xong), `severity?, type?, from?, to?, page, size` (mặc định 20) | `PageResponse<AlertDto>` |
| GET | `/api/alerts/summary` | Đã đăng nhập | | Số cảnh báo đang mở theo mức độ |
| POST | `/api/alerts/{id}/ack` | Quản lý thiết bị | `{note?}` | `AlertDto` (OPEN → ACKNOWLEDGED; trạng thái khác → 409) |
| POST | `/api/alerts/{id}/resolve` | Quản lý thiết bị | `{note}` (ít nhất 3 ký tự) | `AlertDto` (→ RESOLVED; đã RESOLVED → 409) |
| GET | `/api/alert-rules?deviceCode=` | Đã đăng nhập | | Luật chung + luật của thiết bị mình xem được (`canEdit` cho biết được sửa không) |
| POST | `/api/alert-rules` | Luật chung: ADMIN. Luật thiết bị: Quản lý | `{name, deviceCode?, metric, operator, threshold, severity, enabled?}` | 201 `AlertRuleDto` |
| PUT | `/api/alert-rules/{id}` | Như trên | như trên (không đổi phạm vi chung/riêng) | `AlertRuleDto` |
| DELETE | `/api/alert-rules/{id}` | Như trên | | 204 |

`metric`: `gas` (0–10000) hoặc `temp` (−55–125); `operator`: `GT, GTE, LT, LTE`; `severity`: `INFO, WARNING, CRITICAL`.
`AlertDto` gồm thiết bị, luật, loại (THRESHOLD, OFFLINE, FAULT), mức độ, nội dung, giá trị, trạng thái, số lần lặp,
thời điểm tạo / hết điều kiện / xác nhận / xử lý, người xác nhận / xử lý, ghi chú, `canHandle`.

## 7. Dashboard và quản trị

| Method | Đường dẫn | Quyền | Response |
|---|---|---|---|
| GET | `/api/dashboard` | Đã đăng nhập | Tổng số thiết bị, số theo kết nối và vòng đời, tóm tắt cảnh báo, thẻ từng thiết bị (giá trị mới nhất) |
| GET | `/api/admin/users` | ADMIN | Danh sách `UserDto` |
| POST | `/api/admin/users` | ADMIN | `{username, password, role, email?, fullName?}` → 201 `UserDto` |
| PUT | `/api/admin/users/{id}/role` | ADMIN | `{role}` → `UserDto` (không tự đổi vai trò mình) |
| POST | `/api/admin/users/{id}/lock` | ADMIN | `UserDto` (không tự khóa mình; token của người bị khóa hết hiệu lực) |
| POST | `/api/admin/users/{id}/unlock` | ADMIN | `UserDto` (xóa cả khóa tạm do sai mật khẩu) |
| GET | `/api/admin/audit-logs` | ADMIN | Lọc `actor, action, result, targetType, targetId, from, to, page, size` → `PageResponse<AuditLogDto>` |
| GET | `/api/system/ingest-stats` | ADMIN | Thống kê tiếp nhận MQTT: số bản tin nhận / lưu / trùng / bị từ chối theo lý do, độ trễ |

## 8. WebSocket (STOMP) – cập nhật thời gian thực

| Mục | Giá trị |
|---|---|
| Endpoint | `wss://<máy chủ>:8443/ws` (WebSocket thuần, không SockJS) |
| Xác thực | Khung `CONNECT` có header `Authorization: Bearer <JWT>`; token sai → khung `ERROR` |
| Kênh | `SUBSCRIBE /topic/devices/{code}` — chỉ được nếu có quyền **xem** thiết bị; không có quyền → `ERROR` |
| Nội dung | JSON có trường `type`: `telemetry`, `state` (quạt/còi), `connectivity`, `lifecycle`, `alert`, `command`, `config`, `event` |

## 9. Ví dụ

Đăng nhập rồi gửi lệnh bật quạt (PowerShell, gọi thẳng backend trên máy chủ):

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/auth/login `
  -ContentType 'application/json' -Body '{"username":"admin","password":"<mật khẩu>"}'
$h = @{ Authorization = "Bearer $($login.token)" }
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/devices/esp32-001/commands?waitMs=5000" `
  -Headers $h -ContentType 'application/json' -Body '{"action":"FAN_SET","params":{"on":true}}'
```

Kết quả: `status` = `SUCCESS`, `latencyMs` là độ trễ từ lúc gửi đến lúc nhận ACK.

## 10. Kiểm thử chứng minh

Bộ `tests/e2e/run_e2e.py` gọi trực tiếp các API trên: F1 (auth, phân quyền), F2 (đăng ký, cấp xác thực), F3 (truy vấn,
thống kê, CSV), F5 (lệnh), F6 (cấu hình), F8 (cảnh báo), F9 (vòng đời), S1–S3 (403/401), S5 (400 với `fieldErrors`),
S6 (423), S8 (audit, API không trả mật khẩu). Kết quả: `tests/reports/e2e-latest.md`.
