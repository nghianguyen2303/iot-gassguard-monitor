// ============================================================================
// Cấu hình phần cứng + tham số mặc định của gas node.
// SỬA CÁC CHÂN (PIN_*) CHO ĐÚNG VỚI CÁCH BẠN NỐI DÂY. Xem hardware/README.md
// File này KHÔNG chứa bí mật (bí mật nằm trong secrets.h, không commit).
// ============================================================================
#pragma once

#define FW_VERSION "1.0.0"
#define HW_VERSION "esp32-devkit-mq2-ds18b20"

// ---------------------------------------------------------------- Chân nối
// Theo sơ đồ lắp ráp GasGuard (xem hardware/README.md)
// MQ-2 chân AO qua phân áp 10K/20K -> D34. PHẢI là chân ADC1 (GPIO 32..39): ADC2 không dùng được khi bật WiFi.
#define PIN_MQ2_AO        34
// DS18B20 sợi vàng DATA -> D4 (kéo lên 3.3V bằng 2 điện trở 10K song song ~5K)
#define PIN_DS18B20       4
// Module relay 1 kênh 5V, chân IN -> D33. Relay đóng: COM(5V) -> NO -> quạt
#define PIN_RELAY         33
// Module relay của nhóm kích mức CAO (đã thử: IN=HIGH -> relay đóng, quạt chạy).
// Nếu thay module kích mức THẤP thì đổi thành 1: khi đó firmware tắt bằng cách thả nổi chân (INPUT)
// vì relay nuôi 5V có thể không nhả với mức 3.3V.
#define RELAY_ACTIVE_LOW  0
// LED xanh lá (D27): trạng thái kết nối. Nháy nhanh = chưa có WiFi, nháy chậm = chưa vào broker, sáng = online
#define PIN_LED_GREEN     27
// LED đỏ (D26): báo động gas (sáng khi vượt ngưỡng), nháy khi cảm biến lỗi
#define PIN_LED_RED       26
// Còi chủ động 5V qua transistor 2N2222 (D25 -> 330R -> B). Mức HIGH = kêu
#define PIN_BUZZER        25
// 1 = còi kêu ngắt quãng khi gas vượt ngưỡng (kể cả khi mất mạng). 0 = tắt còi
#define BUZZER_ENABLED    1

// ---------------------------------------------------------------- Cảm biến MQ-2
// Module MQ-2 nuôi 5V -> chân AO ra tới 5V, ESP32 chỉ chịu 3.3V: DÙNG CẦU PHÂN ÁP.
// Sơ đồ GasGuard: AO -- 10K -- D34 -- 20K -- GND  => V(D34) = AO x 2/3, hệ số = (10+20)/20 = 1.5
// Nếu module nuôi 3.3V và nối thẳng AO -> GPIO thì đặt 1.0 và MQ2_VC = 3.3
#define MQ2_DIVIDER_RATIO 1.5f
#define MQ2_VC            5.0f     // điện áp nuôi cảm biến (V) = chân VN của ESP32 khi cắm USB
#define MQ2_RL_KOHM       1.0f     // điện trở tải trên module (đa số module FC-22 là 1k; xem mã in trên điện trở "102"=1k, "103"=10k)
// Đường đặc tuyến LPG trong datasheet MQ-2: ppm = A * (Rs/R0)^B
#define MQ2_CURVE_A       574.25f
#define MQ2_CURVE_B       -2.222f
#define MQ2_CLEAN_AIR_RATIO 9.83f  // Rs/R0 trong không khí sạch (datasheet)
// R0 (kΩ). 0 = tự hiệu chuẩn lần đầu trong không khí sạch, lưu vào bộ nhớ flash.
#define MQ2_R0_KOHM       0.0f
// 1 = bắt buộc hiệu chuẩn lại ở lần khởi động này (đặt lại 0 sau khi nạp xong)
#define MQ2_FORCE_CALIBRATE 0
// Thời gian làm nóng cảm biến trước khi đọc gas (giây). Datasheet khuyên lâu hơn; 60s đủ để demo.
#define MQ2_WARMUP_SEC    60
#define MQ2_MAX_PPM       10000.0f // backend chấp nhận 0..10000 ppm

// ---------------------------------------------------------------- Báo động quá nhiệt tại chỗ
// Bếp quá nóng -> LED đỏ + còi + tự bật quạt (nếu autoFan). Có trễ để không bật/tắt liên tục.
#define TEMP_ALARM_ON_C   45.0f
#define TEMP_ALARM_OFF_C  42.0f
// Chế độ AUTO: hết báo động bao lâu thì tự tắt quạt (giây) – để hút nốt khí còn lại
#define FAN_AUTO_OFF_DELAY_SEC 60

// ---------------------------------------------------------------- Mặc định (backend ghi đè bằng config)
#define DEFAULT_TELEMETRY_INTERVAL_SEC 5
#define DEFAULT_GAS_THRESHOLD_PPM      400.0f
#define DEFAULT_AUTO_FAN               true

// ---------------------------------------------------------------- Mạng / MQTT
#define HEARTBEAT_SEC        10    // backend đánh OFFLINE nếu > 30s không nhận gì
#define MQTT_KEEPALIVE_SEC   15    // broker phát LWT sau ~1.5 x keepalive khi mất kết nối đột ngột
#define MQTT_BUFFER_SIZE     1024
#define MQTT_TLS_HANDSHAKE_SEC 8   // thời gian tối đa bắt tay TLS với broker (cổng 8883)
#define OFFLINE_BUFFER_SIZE  120   // số bản đo giữ lại khi mất mạng, gửi bù khi kết nối lại
#define NTP_SERVER1          "pool.ntp.org"
#define NTP_SERVER2          "time.google.com"
#define NTP_SERVER3          "vn.pool.ntp.org"
#define WIFI_RESTART_AFTER_SEC 300 // mất WiFi quá 5 phút -> khởi động lại ESP32
#define SENSOR_FAIL_THRESHOLD  3   // đọc lỗi 3 lần liên tiếp -> báo SENSOR_FAULT
