package com.iot.gas.service.event;

import com.iot.gas.web.dto.AlertDto;

/** Phát khi cảnh báo được tạo / tái diễn / trở lại bình thường / xác nhận / xử lý -> Bước 11 đẩy realtime. */
public record AlertChangedEvent(Long deviceId, String change, AlertDto alert) {
}
