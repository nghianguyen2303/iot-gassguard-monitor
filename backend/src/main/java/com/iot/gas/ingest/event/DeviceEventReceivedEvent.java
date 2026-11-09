package com.iot.gas.ingest.event;

import java.time.Instant;
import java.util.Map;

/** Sự kiện thiết bị tự báo qua topic "event" (SENSOR_FAULT, SENSOR_OK, AUTO_FAN_ON...). */
public record DeviceEventReceivedEvent(Long deviceId, String deviceCode, String type,
                                       Map<String, Object> body, Instant at) {
}
