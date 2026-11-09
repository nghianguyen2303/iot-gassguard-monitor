-- =====================================================================================
-- GasGuard – DỮ LIỆU MẪU (chỉ dùng cho CSDL thử nghiệm / trình bày, không dùng khi vận hành thật)
--
-- Tạo: 2 người dùng mẫu, 1 thiết bị "demo-001" có chia sẻ, 24 giờ số đo gas + nhiệt độ (5 phút/lần)
-- với 1 đợt gas tăng cao cách đây 6 giờ, 2 cảnh báo đã xử lý, 1 lệnh bật quạt và audit log tương ứng.
-- Mốc thời gian tính theo now() lúc chạy, nên biểu đồ "24 giờ qua" trên web luôn có dữ liệu.
--
-- An toàn:
--   * Không có mật khẩu hay dữ liệu cá nhân thật: tài khoản mẫu ở trạng thái LOCKED, password_hash không phải
--     BCrypt nên KHÔNG đăng nhập được; không có email; IP thuộc dải tài liệu 192.0.2.0/24 (RFC 5737).
--   * Thiết bị mẫu không có tài khoản trên broker nên không kết nối MQTT được; chỉ để xem lịch sử, biểu đồ.
--   * Chạy lại nhiều lần không sinh bản trùng (kiểm tra tồn tại / ON CONFLICT).
--
-- Cách chạy (sau khi backend đã khởi động ít nhất 1 lần để Flyway tạo bảng và 3 luật cảnh báo mặc định),
-- từ thư mục gốc repo:
--   docker cp database/sample-data.sql iot-postgres:/tmp/sample-data.sql
--   docker exec iot-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -f /tmp/sample-data.sql'
-- Xem dữ liệu: đăng nhập web bằng tài khoản admin -> thiết bị "demo-001".
-- Xóa dữ liệu mẫu: database/sample-data-cleanup.sql
-- =====================================================================================
BEGIN;

-- ---------- Người dùng mẫu (không đăng nhập được) ----------
INSERT INTO users (username, full_name, password_hash, role, status)
VALUES ('sample_owner',  'Chủ hộ mẫu',      '!sample-account-no-login', 'OWNER',  'LOCKED'),
       ('sample_viewer', 'Người thân mẫu',  '!sample-account-no-login', 'VIEWER', 'LOCKED')
ON CONFLICT (username) DO NOTHING;

-- ---------- Thiết bị mẫu ----------
INSERT INTO devices (device_code, name, device_type, location, zone, owner_id, lifecycle_status, connectivity,
                     last_seen_at, hw_version, fw_version, config, config_version, config_applied_version,
                     config_status, config_acked_at, actuator_state, created_at)
SELECT 'demo-001', 'Bếp nhà mẫu', 'GAS_NODE', 'Bếp, cạnh bình gas', 'Tầng 1', u.id, 'ACTIVE', 'OFFLINE',
       date_trunc('minute', now()), 'v1', '1.0.0',
       '{"telemetryIntervalSec": 5, "gasThreshold": 400.0, "autoFan": true}'::jsonb, 1, 1,
       'APPLIED', now() - interval '25 hours',
       '{"fan": false, "buzzer": false, "fanMode": "AUTO", "buzzerMode": "AUTO"}'::jsonb,
       now() - interval '26 hours'
FROM users u WHERE u.username = 'sample_owner'
ON CONFLICT (device_code) DO NOTHING;

-- Chia sẻ quyền xem cho người thân
INSERT INTO device_shares (device_id, user_id)
SELECT d.id, u.id FROM devices d, users u
WHERE d.device_code = 'demo-001' AND d.owner_id = (SELECT id FROM users WHERE username = 'sample_owner') AND u.username = 'sample_viewer'
ON CONFLICT DO NOTHING;

-- Lịch sử cấp xác thực: chỉ lưu dấu SHA-256 của một chuỗi ngẫu nhiên, không có mật khẩu dùng được
INSERT INTO device_credentials (device_id, mqtt_username, client_id, secret_fingerprint, status, issued_at, issued_by)
SELECT d.id, 'demo-001', 'demo-001', encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'),
       'ACTIVE', now() - interval '26 hours', d.owner_id
FROM devices d
WHERE d.device_code = 'demo-001' AND d.owner_id = (SELECT id FROM users WHERE username = 'sample_owner')
  AND NOT EXISTS (SELECT 1 FROM device_credentials c WHERE c.device_id = d.id);

-- ---------- Telemetry: 24 giờ, 5 phút một bản, mỗi bản 2 chỉ số ----------
-- Gas nền 110-150 ppm; đợt rò rỉ cách đây 6 giờ kéo dài 20 phút, đỉnh 1200 ppm. Nhiệt độ 27-31 °C theo ngày.
WITH pts AS (
    SELECT g AS n,
           date_trunc('minute', now()) - (288 - g) * interval '5 minutes' AS ts,
           (288 - g) * 5 AS minutes_ago
    FROM generate_series(0, 288) AS g
), vals AS (
    SELECT n, ts,
           CASE
               WHEN minutes_ago BETWEEN 340 AND 360
                   THEN round((1200 - abs(minutes_ago - 350) * 75)::numeric, 1)
               ELSE round((130 + 15 * sin(n / 7.0) + 5 * cos(n / 3.0))::numeric, 1)
           END AS gas,
           round((29 + 2 * sin((n - 72) * 2 * pi() / 288))::numeric, 2) AS temp
    FROM pts
)
INSERT INTO telemetry (device_id, metric, value, unit, seq, ts, received_at)
SELECT d.id, m.metric, m.value, m.unit, v.n, v.ts, v.ts + interval '40 milliseconds'
FROM vals v
CROSS JOIN LATERAL (VALUES ('gas', v.gas, 'ppm'), ('temp', v.temp, 'C')) AS m(metric, value, unit)
JOIN devices d ON d.device_code = 'demo-001' AND d.owner_id = (SELECT id FROM users WHERE username = 'sample_owner')
WHERE NOT EXISTS (SELECT 1 FROM telemetry t WHERE t.device_id = d.id)
ON CONFLICT ON CONSTRAINT uq_telemetry_dedup DO NOTHING;

-- ---------- Cảnh báo của đợt rò rỉ (đã xử lý) ----------
INSERT INTO alerts (device_id, rule_id, type, severity, message, metric, value, status, occurrence_count,
                    created_at, last_seen_at, cleared_at, acknowledged_by, acknowledged_at,
                    resolved_by, resolved_at, resolution_note)
SELECT d.id, r.id, 'THRESHOLD', r.severity,
       'Khí gas ' || a.peak || ' ppm > ' || r.threshold::int || ' ppm (' || r.name || ')',
       'gas', a.peak, 'RESOLVED', a.cnt,
       date_trunc('minute', now()) - a.start_min * interval '1 minute',
       date_trunc('minute', now()) - 350 * interval '1 minute',
       date_trunc('minute', now()) - (a.start_min - 25) * interval '1 minute',
       d.owner_id, date_trunc('minute', now()) - 345 * interval '1 minute',
       d.owner_id, date_trunc('minute', now()) - 300 * interval '1 minute',
       'Khóa van bình gas, mở cửa thông gió. Đã kiểm tra lại dây dẫn gas.'
FROM devices d
JOIN (VALUES ('Nồng độ gas cao', 1200, 5, 360), ('Rò rỉ gas nguy hiểm', 1200, 3, 355))
         AS a(rule_name, peak, cnt, start_min) ON TRUE
JOIN alert_rules r ON r.name = a.rule_name AND r.device_id IS NULL
WHERE d.device_code = 'demo-001' AND d.owner_id = (SELECT id FROM users WHERE username = 'sample_owner')
  AND NOT EXISTS (SELECT 1 FROM alerts x WHERE x.device_id = d.id AND x.rule_id = r.id);

-- ---------- Lệnh bật quạt trong đợt rò rỉ (thành công) ----------
INSERT INTO commands (id, device_id, action, params, status, requested_by, created_at, sent_at, acked_at,
                      result_message, result_state)
SELECT gen_random_uuid(), d.id, 'FAN_SET', '{"on": true}'::jsonb, 'SUCCESS', d.owner_id,
       now() - interval '348 minutes',
       now() - interval '348 minutes' + interval '12 milliseconds',
       now() - interval '348 minutes' + interval '160 milliseconds',
       'Quạt BẬT (giữ bật đến khi đổi lại)',
       '{"fan": true, "buzzer": true, "fanMode": "MANUAL", "buzzerMode": "AUTO"}'::jsonb
FROM devices d
WHERE d.device_code = 'demo-001' AND d.owner_id = (SELECT id FROM users WHERE username = 'sample_owner')
  AND NOT EXISTS (SELECT 1 FROM commands c WHERE c.device_id = d.id);

-- ---------- Audit log tương ứng ----------
INSERT INTO audit_logs (actor_id, actor_username, action, target_type, target_id, result, details, ip_address, created_at)
SELECT u.id, u.username, e.action, e.target_type, e.target_id, 'SUCCESS', e.details::jsonb, '192.0.2.10',
       now() - e.ago * interval '1 minute'
FROM users u
JOIN (VALUES
        ('DEVICE_REGISTER',   'DEVICE', 'demo-001', '{"name": "Bếp nhà mẫu"}', 1560),
        ('DEVICE_PROVISION',  'DEVICE', 'demo-001', NULL,                     1559),
        ('DEVICE_SHARE_ADD',  'DEVICE', 'demo-001', '{"username": "sample_viewer", "permission": "VIEW"}', 1550),
        ('COMMAND_SEND',      'DEVICE', 'demo-001', '{"action": "FAN_SET", "params": {"on": true}}', 348),
        ('ALERT_ACK',         'ALERT',  (SELECT max(x.id)::text FROM alerts x JOIN devices dd ON dd.id = x.device_id WHERE dd.device_code = 'demo-001' AND dd.owner_id = (SELECT id FROM users WHERE username = 'sample_owner')), NULL, 345),
        ('ALERT_RESOLVE',     'ALERT',  (SELECT max(x.id)::text FROM alerts x JOIN devices dd ON dd.id = x.device_id WHERE dd.device_code = 'demo-001' AND dd.owner_id = (SELECT id FROM users WHERE username = 'sample_owner')), '{"note": "Khóa van bình gas, mở cửa thông gió."}', 300)
     ) AS e(action, target_type, target_id, details, ago) ON TRUE
WHERE u.username = 'sample_owner'
  AND NOT EXISTS (SELECT 1 FROM audit_logs a WHERE a.actor_id = u.id);

COMMIT;

-- Kiểm tra nhanh
SELECT d.device_code, d.lifecycle_status,
       (SELECT count(*) FROM telemetry t WHERE t.device_id = d.id)               AS telemetry_rows,
       (SELECT max(value) FROM telemetry t WHERE t.device_id = d.id AND metric = 'gas') AS gas_max_ppm,
       (SELECT count(*) FROM alerts a WHERE a.device_id = d.id)                  AS alerts,
       (SELECT count(*) FROM commands c WHERE c.device_id = d.id)                AS commands
FROM devices d WHERE d.device_code = 'demo-001' AND d.owner_id = (SELECT id FROM users WHERE username = 'sample_owner');
