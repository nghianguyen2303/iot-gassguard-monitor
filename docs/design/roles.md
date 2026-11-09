# Ma trận vai trò và quyền – GasGuard

Quyền được xác định bởi **hai yếu tố**: **vai trò** của tài khoản (ADMIN, OWNER, VIEWER) và **quan hệ với từng thiết bị**
(chủ sở hữu, hoặc được chia sẻ quyền xem). Mọi quyền đều được kiểm tra **ở backend**; giao diện ẩn nút chỉ để tiện dùng.
Vi phạm trả về HTTP 403 và ghi audit log `DENIED`.

## 1. Ba vai trò

| Vai trò | Ai | Có được do |
|---|---|---|
| `ADMIN` | Người vận hành hệ thống | Tài khoản `admin` tạo tự động lần chạy đầu; hoặc ADMIN khác cấp |
| `OWNER` | Chủ hộ, chủ nhà trọ, quản lý bếp: sở hữu và quản lý thiết bị của mình | ADMIN tạo tài khoản hoặc đổi vai trò |
| `VIEWER` | Người thân, người thuê phòng: chỉ xem thiết bị được chia sẻ | Mặc định khi tự đăng ký |

Quyền **quản lý** một thiết bị = ADMIN, hoặc tài khoản vai trò OWNER **và** là chủ của thiết bị đó. Quyền **xem** = ADMIN,
chủ thiết bị, hoặc người được chia sẻ. Một OWNER được chia sẻ thiết bị của người khác chỉ được xem thiết bị đó.

## 2. Ma trận quyền trên một thiết bị

Ký hiệu: ✅ được phép · ❌ bị từ chối (HTTP 403). "Được chia sẻ" áp dụng cho người được chia sẻ quyền xem, bất kể vai
trò. Người không có quan hệ gì với thiết bị: không thấy thiết bị trong danh sách, mọi API của thiết bị trả 403.

| Chức năng | Được chia sẻ | OWNER (chủ thiết bị) | ADMIN | Kiểm tra trong code |
|---|---|---|---|---|
| Xem thông tin, trạng thái thiết bị | ✅ | ✅ | ✅ (mọi thiết bị) | `DeviceService.get` → `requireView` |
| Xem dữ liệu: mới nhất, bảng, biểu đồ, thống kê | ✅ | ✅ | ✅ | `TelemetryQueryService` → `loadForView` |
| Xuất CSV (ghi audit `DATA_EXPORT`) | ✅ | ✅ | ✅ | `TelemetryQueryService.exportCsv` |
| Nhận dữ liệu realtime (WebSocket) | ✅ | ✅ | ✅ | `StompAuthInterceptor` → `canView` khi đăng ký kênh |
| Xem cảnh báo | ✅ | ✅ | ✅ | `AlertService.list` |
| **Xác nhận, xử lý cảnh báo** | ❌ | ✅ | ✅ | `AlertService.loadForHandle` → `requireManage` |
| Xem cấu hình, lịch sử lệnh, nhật ký hoạt động | ✅ | ✅ | ✅ | `ConfigService.get`, `CommandService.history`, `AuditQueryService.deviceActivity` |
| **Gửi lệnh điều khiển** (quạt, còi) | ❌ | ✅ | ✅ | `CommandService.send` → `loadForManage` |
| **Đổi cấu hình từ xa** | ❌ | ✅ | ✅ | `ConfigService.update` → `loadForManage` |
| **Sửa thông tin** (tên, vị trí, khu vực) | ❌ | ✅ | ✅ | `DeviceService.update` → `loadForManage` |
| **Cấp, đổi mật khẩu MQTT; xem lịch sử xác thực** | ❌ | ✅ | ✅ | `DeviceService.provision`, `rotateCredentials`, `credentialHistory` |
| **Vòng đời:** kích hoạt, bảo trì, tạm khóa, mở khóa, ngừng sử dụng | ❌ | ✅ | ✅ | `DeviceService.changeLifecycle` → `loadForManage` |
| **Chia sẻ, thu hồi chia sẻ, xem danh sách chia sẻ** | ❌ | ✅ | ✅ | `DeviceService.addShare` / `removeShare` / `listShares` |
| Xem luật cảnh báo áp dụng cho thiết bị | ✅ | ✅ | ✅ | `AlertRuleService.list` → `requireView` |
| **Tạo, sửa, xóa luật cảnh báo riêng của thiết bị** | ❌ | ✅ | ✅ | `AlertRuleService` → `requireManage` |

## 3. Ma trận quyền chung của hệ thống

| Chức năng | Chưa đăng nhập | VIEWER | OWNER | ADMIN | Kiểm tra trong code |
|---|---|---|---|---|---|
| Đăng ký tài khoản (nhận vai trò VIEWER) | ✅ | | | | `/api/auth/register` công khai |
| Đăng nhập | ✅ | ✅ | ✅ | ✅ | `/api/auth/login` công khai, khóa tạm sau 5 lần sai |
| Mọi API còn lại | ❌ (401) | | | | `SecurityConfig`: `anyRequest().authenticated()` |
| Xem, sửa hồ sơ; đổi mật khẩu; đăng xuất | | ✅ | ✅ | ✅ | `/api/auth/me`, `/api/auth/password`, `/api/auth/logout` |
| Bật/tắt nhận cảnh báo qua email (của chính mình) | | ✅ | ✅ | ✅ | `/api/auth/me/email-alerts` |
| Dashboard | | ✅ thiết bị được xem | ✅ thiết bị được xem | ✅ mọi thiết bị | `DashboardService.overview` |
| **Đăng ký thiết bị mới** | | ❌ | ✅ (cho chính mình) | ✅ (gán chủ là OWNER/ADMIN bất kỳ) | `@PreAuthorize("hasAnyRole('ADMIN','OWNER')")`; gán cho người khác chỉ ADMIN |
| **Tạo, sửa, xóa luật cảnh báo chung** (mọi thiết bị) | | ❌ | ❌ | ✅ | `AlertRuleService`: chỉ ADMIN |
| **Quản lý người dùng:** danh sách, tạo, đổi vai trò, khóa, mở khóa | | ❌ | ❌ | ✅ (không tự đổi vai trò, không tự khóa mình) | `/api/admin/**` + `@PreAuthorize("hasRole('ADMIN')")` |
| **Audit log toàn hệ thống** | | ❌ | ❌ | ✅ | `/api/admin/audit-logs` |
| **Thống kê tiếp nhận MQTT** (bản tin bị từ chối) | | ❌ | ❌ | ✅ | `/api/system/**` |

Ô trống: không áp dụng. Đổi vai trò hoặc khóa tài khoản làm token cũ của người đó hết hiệu lực ngay (`token_version`).

## 4. Quyền ở broker MQTT

| Tài khoản MQTT | Được publish | Được subscribe | Ghi chú |
|---|---|---|---|
| Thiết bị `<mã>` (role `dev-<mã>`) | `iot/v1/devices/<mã>/` + `telemetry`, `heartbeat`, `status`, `event`, `cmd/ack`, `config/ack` | `iot/v1/devices/<mã>/cmd`, `…/config` | Chỉ topic của **chính nó**; clientId bắt buộc = mã thiết bị; mỗi thiết bị một mật khẩu riêng |
| `backend` | `iot/v1/devices/+/cmd`, `…/+/config`, `$CONTROL/dynamic-security/v1` | `iot/v1/devices/+/#`, phản hồi Dynamic Security | Nhận dữ liệu mọi thiết bị, gửi lệnh/cấu hình, tạo/khóa/xóa tài khoản thiết bị |
| `admin` | Quản trị Dynamic Security | | Chỉ dùng trên chính máy chủ (`mosquitto_ctrl`) |
| Ẩn danh | ❌ | ❌ | `allow_anonymous false` |

## 5. Kiểm tra quyền nằm ở đâu

1. **Theo URL** (`SecurityConfig`): chỉ đăng nhập, đăng ký là công khai; `/api/admin/**` chỉ ADMIN; còn lại phải có JWT hợp lệ.
2. **Theo vai trò trên từng API** (`@PreAuthorize`): đăng ký thiết bị, quản lý người dùng, thống kê hệ thống.
3. **Theo từng thiết bị** (`AccessService.requireView` / `requireManage`): mọi API có `{code}`, cảnh báo, luật cảnh báo.
4. **Kênh realtime** (`StompAuthInterceptor`): JWT khi kết nối WebSocket, quyền xem khi đăng ký kênh của một thiết bị.
5. **Broker** (Mosquitto Dynamic Security): ACL theo topic cho từng tài khoản thiết bị.

Bị từ chối ở lớp 1–4: HTTP 401/403 (WebSocket: khung ERROR) và audit log `DENIED` kèm người dùng, thao tác, IP. Bị từ
chối ở broker: PUBACK/SUBACK/CONNACK mã 135 (Not authorized).

## 6. Kiểm thử chứng minh

| Ca | Nội dung |
|---|---|
| F1 | Đăng nhập 3 vai trò; VIEWER và OWNER gọi API quản lý người dùng bị 403, ADMIN được; VIEWER đăng ký thiết bị bị 403; tự đăng ký nhận VIEWER |
| S1 | Người dùng không xem, không điều khiển, không đăng ký kênh realtime của thiết bị người khác (6 API đều 403); audit ghi các lần bị từ chối |
| S2 | Người được chia sẻ chỉ xem: gửi lệnh, đổi cấu hình, đổi mật khẩu thiết bị, đổi vòng đời đều 403 |
| S3 | Token thiếu, sai, giả mạo vai trò ADMIN, hết hạn, đã thu hồi đều bị từ chối (401) |
| S4 | Broker từ chối thiết bị publish/subscribe topic của thiết bị khác, subscribe wildcard toàn hệ thống, kết nối sai mật khẩu, sai clientId, ẩn danh |

Kết quả: `tests/reports/e2e-latest.md`.
