package com.iot.gas.ingest.event;

import java.util.Map;

/** Thiết bị báo trạng thái quạt/còi khác với trạng thái đang lưu -> đẩy realtime cho giao diện. */
public record ActuatorStateChangedEvent(Long deviceId, String deviceCode, Map<String, Object> state) {
}
