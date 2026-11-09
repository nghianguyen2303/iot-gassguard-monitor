-- Cảnh báo qua email: chỉ gửi tới địa chỉ người dùng ĐÃ XÁC NHẬN bằng mã (chứng minh sở hữu hộp thư),
-- tránh việc nhập email người khác để gửi spam. NULL = không nhận email cảnh báo.
ALTER TABLE users ADD COLUMN alert_email             VARCHAR(120);
ALTER TABLE users ADD COLUMN alert_email_verified_at TIMESTAMPTZ;
