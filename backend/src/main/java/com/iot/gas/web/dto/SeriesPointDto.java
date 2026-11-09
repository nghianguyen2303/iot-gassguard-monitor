package com.iot.gas.web.dto;

import java.time.Instant;

public record SeriesPointDto(Instant bucket, String metric, double avg, double min, double max, long count) {
}
