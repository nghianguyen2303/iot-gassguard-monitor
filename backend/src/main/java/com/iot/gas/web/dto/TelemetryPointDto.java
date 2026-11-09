package com.iot.gas.web.dto;

import java.time.Instant;

/** latencyMs = receivedAt - ts (độ trễ thiết bị -> backend). */
public record TelemetryPointDto(Instant ts, String metric, double value, String unit, Long seq,
                                Instant receivedAt, long latencyMs) {
}
