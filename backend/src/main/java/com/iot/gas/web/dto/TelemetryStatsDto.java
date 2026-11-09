package com.iot.gas.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * expectedMessages: số bản tin gas lẽ ra phải nhận (tính từ seq), estimatedLossRate: % bản tin bị mất (mục 7.2).
 */
public record TelemetryStatsDto(String deviceCode, Instant from, Instant to, List<MetricStatsDto> metrics,
                                long alertsInRange, Long expectedMessages, Double estimatedLossRate) {
}
