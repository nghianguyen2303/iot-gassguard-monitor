package com.iot.gas.web.dto;

import java.time.Instant;
import java.util.List;

public record SeriesDto(String deviceCode, Instant from, Instant to, String bucket, List<SeriesPointDto> points) {
}
