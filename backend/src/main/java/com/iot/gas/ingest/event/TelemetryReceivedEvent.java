package com.iot.gas.ingest.event;

import com.iot.gas.ingest.Reading;

import java.time.Instant;
import java.util.List;

/** Phát sau khi lưu telemetry mới (không phát với bản trùng). Bước 9 (cảnh báo) và 11 (realtime) lắng nghe. */
public record TelemetryReceivedEvent(Long deviceId, String deviceCode, List<Reading> readings,
                                     Instant measuredAt, Instant receivedAt) {
}
