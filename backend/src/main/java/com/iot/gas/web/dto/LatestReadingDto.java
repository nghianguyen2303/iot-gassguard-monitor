package com.iot.gas.web.dto;

import java.time.Instant;

public record LatestReadingDto(String metric, double value, String unit, Instant ts) {
}
