package com.iot.gas.service.event;

import com.iot.gas.web.dto.DeviceConfigDto;

public record DeviceConfigUpdatedEvent(Long deviceId, DeviceConfigDto config) {
}
