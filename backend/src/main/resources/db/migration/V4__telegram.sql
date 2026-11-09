-- Bước 16: mỗi người dùng tự liên kết Telegram để nhận cảnh báo của các thiết bị mình có quyền xem.
-- chat_id do Telegram cấp khi người dùng bấm Start với bot (deep link /start <mã liên kết>), không nhập tay.
ALTER TABLE users ADD COLUMN telegram_chat_id   BIGINT;
ALTER TABLE users ADD COLUMN telegram_linked_at TIMESTAMPTZ;
CREATE INDEX idx_users_telegram ON users (telegram_chat_id) WHERE telegram_chat_id IS NOT NULL;
