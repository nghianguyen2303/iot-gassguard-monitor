-- =========================================================
-- V3: Luật cảnh báo mặc định (áp dụng cho MỌI thiết bị: device_id = NULL)
-- Ngưỡng tham khảo cho cảm biến MQ-2 trong nhà ở; ADMIN có thể sửa qua API/giao diện.
--   gas > 400 ppm  : WARNING  - nồng độ gas cao, cần kiểm tra
--   gas > 1000 ppm : CRITICAL - nghi rò rỉ, cần xử lý ngay
--   temp > 50 °C   : WARNING  - nhiệt độ bất thường (nguy cơ cháy)
-- =========================================================
INSERT INTO alert_rules (name, device_id, metric, operator, threshold, severity, enabled)
VALUES ('Nồng độ gas cao',         NULL, 'gas',  'GT', 400,  'WARNING',  TRUE),
       ('Rò rỉ gas nguy hiểm',     NULL, 'gas',  'GT', 1000, 'CRITICAL', TRUE),
       ('Nhiệt độ cao bất thường', NULL, 'temp', 'GT', 50,   'WARNING',  TRUE);
