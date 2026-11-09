-- =========================================================
-- V2: Theo dõi trạng thái áp dụng cấu hình từ xa (Bước 8)
--   NONE     : chưa từng gửi cấu hình
--   PENDING  : đã gửi (retained), chờ thiết bị xác nhận
--   APPLIED  : thiết bị đã áp dụng đúng phiên bản mới nhất
--   REJECTED : thiết bị từ chối (kèm lý do trong config_message)
-- =========================================================
ALTER TABLE devices
    ADD COLUMN config_status   VARCHAR(10) NOT NULL DEFAULT 'NONE'
        CHECK (config_status IN ('NONE', 'PENDING', 'APPLIED', 'REJECTED')),
    ADD COLUMN config_message  VARCHAR(255),
    ADD COLUMN config_acked_at TIMESTAMPTZ;
