package com.iot.gas.web.dto;

import java.time.Instant;

public record MetricStatsDto(String metric, String unit, long count, double min, double max, double avg,
                             double p95, Instant firstTs, Instant lastTs, double avgLatencyMs) {
}
