package com.iot.gas.ingest.event;

import com.iot.gas.domain.enums.Connectivity;

import java.time.Instant;

/** Phát khi thiết bị đổi ONLINE / OFFLINE / FAULT. reason: LWT, SHUTDOWN, TELEMETRY, SENSOR_FAULT... */
public record ConnectivityChangedEvent(Long deviceId, String deviceCode, Connectivity from, Connectivity to,
                                       String reason, Instant at) {
}
