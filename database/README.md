# Cơ sở dữ liệu – GasGuard

PostgreSQL 16 (container `iot-postgres`, volume `pg-data`). Thư mục này gom **schema, migration và dữ liệu mẫu**
theo mục 8.1 của đề bài. Mô hình dữ liệu và giải thích thiết kế: [`docs/design/erd.md`](../docs/design/erd.md).

## 1. Nội dung thư mục

| File | Nội dung | Dùng khi |
|---|---|---|
| [`schema.sql`](schema.sql) | Toàn bộ schema (9 bảng, ràng buộc, chỉ mục) sau migration V1–V5, sinh bằng `pg_dump --schema-only` | Đọc, tham khảo cấu trúc ở một chỗ; tạo CSDL độc lập để thử truy vấn |
| [`sample-data.sql`](sample-data.sql) | Dữ liệu mẫu: 1 thiết bị `demo-001`, 24 giờ số đo, 1 đợt rò rỉ gas, cảnh báo, lệnh, audit log | Trình bày biểu đồ, cảnh báo, lịch sử khi chưa có dữ liệu thật |
| [`sample-data-cleanup.sql`](sample-data-cleanup.sql) | Xóa đúng dữ liệu mẫu ở trên | Sau khi trình bày xong |

**Migration** (nguồn gốc duy nhất của schema) nằm cùng mã backend:
[`backend/src/main/resources/db/migration/`](../backend/src/main/resources/db/migration/).

## 2. Migration (Flyway)

Backend tự chạy Flyway mỗi lần khởi động: tạo bảng khi CSDL trống, áp dụng migration mới, ghi lịch sử vào bảng
`flyway_schema_history`. Hibernate chạy `ddl-auto: validate`, chỉ kiểm tra entity khớp schema, **không tự sửa** schema.
Nhờ vậy mọi máy (của nhóm, của giảng viên) có cùng một schema, thay đổi nào cũng có file và có trong lịch sử Git.

| Phiên bản | File | Nội dung |
|---|---|---|
| V1 | `V1__init_schema.sql` | 9 bảng: `users`, `devices`, `device_shares`, `device_credentials`, `telemetry`, `alert_rules`, `alerts`, `commands`, `audit_logs`; ràng buộc CHECK cho mọi trạng thái; chỉ mục |
| V2 | `V2__device_config_status.sql` | Theo dõi áp dụng cấu hình từ xa: `devices.config_status` (NONE / PENDING / APPLIED / REJECTED), `config_message`, `config_acked_at` |
| V3 | `V3__default_alert_rules.sql` | 3 luật cảnh báo chung: gas > 400 ppm (WARNING), gas > 1000 ppm (CRITICAL), nhiệt độ > 50 °C (WARNING) |
| V4 | `V4__telegram.sql` | Liên kết Telegram (tùy chọn): `users.telegram_chat_id`, `telegram_linked_at` |
| V5 | `V5__email_alerts.sql` | Email nhận cảnh báo phải xác nhận: `users.alert_email`, `alert_email_verified_at` |

Quy tắc: **không sửa** migration đã chạy; mọi thay đổi schema là một file mới `V6__mo_ta.sql`, sau đó sinh lại
`schema.sql` (mục 5).

## 3. Các bảng

| Bảng | Lưu gì | Điểm thiết kế chính |
|---|---|---|
| `users` | Tài khoản, vai trò (ADMIN / OWNER / VIEWER), trạng thái | Mật khẩu BCrypt; `failed_login_count`, `locked_until` khóa tạm; `token_version` thu hồi JWT |
| `devices` | Thiết bị, chủ sở hữu, vị trí, khu vực, vòng đời, kết nối, phiên bản | `device_code` duy nhất, kiểm tra định dạng; vòng đời và kết nối là 2 cột riêng; cấu hình và trạng thái quạt/còi dạng JSONB |
| `device_shares` | Chia sẻ quyền xem | Khóa chính (thiết bị, người dùng) |
| `device_credentials` | Lịch sử thông tin xác thực MQTT | Chỉ lưu SHA-256, không lưu mật khẩu; mỗi thiết bị tối đa 1 thông tin xác thực chưa thu hồi |
| `telemetry` | Số đo, mỗi chỉ số một dòng | `ts` (thiết bị đo) và `received_at` (backend nhận); `UNIQUE (device_id, metric, ts)` chống trùng |
| `alert_rules` | Luật ngưỡng | `device_id` NULL = áp dụng mọi thiết bị |
| `alerts` | Cảnh báo ngưỡng, offline, lỗi cảm biến | OPEN → ACKNOWLEDGED → RESOLVED; mỗi (thiết bị, loại, luật) tối đa 1 cảnh báo chưa xử lý, lặp lại thì tăng `occurrence_count` |
| `commands` | Lệnh điều khiển và kết quả | Khóa chính là `commandId` (UUID) gửi xuống thiết bị |
| `audit_logs` | Ai làm gì, kết quả, IP | Kết quả SUCCESS / DENIED / FAILED, chi tiết JSONB |

Mọi thời gian lưu kiểu `TIMESTAMPTZ` theo UTC; giao diện web hiển thị theo giờ Việt Nam (`Asia/Ho_Chi_Minh`).

## 4. Truy cập CSDL

Thông tin đăng nhập lấy từ `.env` (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`), không ghi vào repo.

```powershell
# Mở psql trong container (biến môi trường có sẵn trong container, không cần gõ mật khẩu)
docker exec -it iot-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

# Hoặc sau khi nạp deployment/dev-shell.ps1:
psql "select device_code, lifecycle_status, connectivity from devices"
```

Công cụ quản trị trên máy chủ (DBeaver, pgAdmin): `localhost:15432`.

## 5. Các thao tác thường dùng

Chạy từ thư mục gốc repo, khi hệ thống đang chạy. Dùng `docker cp` rồi `psql -f` để giữ đúng tiếng Việt UTF-8 (chuyển
hướng `<` không dùng được trong PowerShell).

**Nạp dữ liệu mẫu** (sau khi backend đã khởi động ít nhất 1 lần):

```powershell
docker cp database/sample-data.sql iot-postgres:/tmp/sample-data.sql
docker exec iot-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -f /tmp/sample-data.sql'
```

Kết quả cuối: `demo-001 | ACTIVE | 578 | 1200 | 2 | 1` (578 dòng số đo, gas cao nhất 1200 ppm, 2 cảnh báo, 1 lệnh).
Đăng nhập web bằng `admin`, mở thiết bị **demo-001**: tab *Dữ liệu & biểu đồ* (chọn 24 giờ, thấy đợt rò rỉ),
*Cảnh báo*, *Điều khiển* (lịch sử lệnh), *Chia sẻ*, *Lịch sử hoạt động*.

**Xóa dữ liệu mẫu:**

```powershell
docker cp database/sample-data-cleanup.sql iot-postgres:/tmp/sample-data-cleanup.sql
docker exec iot-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -f /tmp/sample-data-cleanup.sql'
```

**Sao lưu và khôi phục:**

```powershell
# Sao lưu -> file gasguard.dump ở thư mục hiện tại (không commit file này: chứa dữ liệu thật)
docker exec iot-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/gasguard.dump'
docker cp iot-postgres:/tmp/gasguard.dump ./gasguard.dump

# Khôi phục (dừng backend trước: app-down)
docker cp ./gasguard.dump iot-postgres:/tmp/gasguard.dump
docker exec iot-postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists /tmp/gasguard.dump'
```

**Sinh lại `schema.sql`** sau khi thêm migration (backend đã chạy migration mới):

```powershell
docker exec iot-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --schema-only --no-owner --no-privileges --exclude-table=flyway_schema_history -f /tmp/schema.sql'
docker cp iot-postgres:/tmp/schema.sql database/schema.sql
```

Sau đó giữ lại phần chú thích đầu file từ bản cũ và xóa các dòng `\restrict` / `\unrestrict` nếu có.

**Xóa toàn bộ dữ liệu, làm lại từ đầu:** `docker compose ... down -v` xóa cả volume `pg-data` và `mosquitto-data`
(mất hết dữ liệu và tài khoản MQTT). Lần chạy sau Flyway tạo lại bảng, backend tạo lại tài khoản `admin`; phải khởi
tạo lại broker theo `README.md` mục 5.4 và cấp lại xác thực cho thiết bị.

## 6. Dữ liệu mẫu an toàn thế nào

- Không có mật khẩu hay dữ liệu cá nhân thật. Tài khoản `sample_owner`, `sample_viewer` bị khóa (LOCKED) và không có
  mật khẩu hợp lệ nên **không đăng nhập được**; không có email; IP trong audit log thuộc dải dành cho tài liệu
  `192.0.2.0/24`.
- Thiết bị `demo-001` không có tài khoản trên broker nên không kết nối MQTT được; dấu SHA-256 trong
  `device_credentials` tính từ một chuỗi ngẫu nhiên không lưu lại.
- Chạy lại nhiều lần không sinh bản trùng. Nếu đã có thiết bị thật tên `demo-001` của người khác, script không ghi gì
  vào thiết bị đó; script dọn chỉ xóa thiết bị `demo-001` của `sample_owner`.
- Toàn bộ nằm trong một transaction: lỗi ở bất kỳ câu lệnh nào thì không có gì được ghi.
