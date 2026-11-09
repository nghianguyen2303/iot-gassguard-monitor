package com.iot.gas.service.event;

import com.iot.gas.web.dto.CommandDto;

/** Phát khi lệnh đổi trạng thái (SENT, SUCCESS, FAILED, TIMEOUT) -> Bước 11 đẩy realtime lên giao diện. */
public record CommandUpdatedEvent(Long deviceId, CommandDto command) {
}
