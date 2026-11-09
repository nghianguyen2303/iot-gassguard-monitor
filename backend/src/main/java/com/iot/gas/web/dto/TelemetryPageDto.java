package com.iot.gas.web.dto;

import java.time.Instant;
import java.util.List;

/** truncated=true: có nhiều điểm hơn limit -> nên dùng /series (dữ liệu gộp) để vẽ biểu đồ. */
public record TelemetryPageDto(String deviceCode, Instant from, Instant to, int count, boolean truncated,
                               List<TelemetryPointDto> points) {
}
