-- GasGuard – XÓA DỮ LIỆU MẪU do database/sample-data.sql tạo (thiết bị demo-001, tài khoản sample_*).
-- Không đụng tới dữ liệu khác. Chạy từ thư mục gốc repo:
--   docker cp database/sample-data-cleanup.sql iot-postgres:/tmp/sample-data-cleanup.sql
--   docker exec iot-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -f /tmp/sample-data-cleanup.sql'
BEGIN;

DELETE FROM audit_logs
WHERE actor_id IN (SELECT id FROM users WHERE username IN ('sample_owner', 'sample_viewer'));

DELETE FROM telemetry          WHERE device_id IN (SELECT id FROM devices WHERE device_code = 'demo-001' AND owner_id IN (SELECT id FROM users WHERE username = 'sample_owner'));
DELETE FROM alerts             WHERE device_id IN (SELECT id FROM devices WHERE device_code = 'demo-001' AND owner_id IN (SELECT id FROM users WHERE username = 'sample_owner'));
DELETE FROM commands           WHERE device_id IN (SELECT id FROM devices WHERE device_code = 'demo-001' AND owner_id IN (SELECT id FROM users WHERE username = 'sample_owner'));
DELETE FROM device_credentials WHERE device_id IN (SELECT id FROM devices WHERE device_code = 'demo-001' AND owner_id IN (SELECT id FROM users WHERE username = 'sample_owner'));
DELETE FROM devices            WHERE device_code = 'demo-001' AND owner_id IN (SELECT id FROM users WHERE username = 'sample_owner');   -- device_shares, luật riêng: ON DELETE CASCADE
DELETE FROM users              WHERE username IN ('sample_owner', 'sample_viewer');

COMMIT;
