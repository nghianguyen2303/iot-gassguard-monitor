package com.iot.gas.web.dto;

import java.util.Map;

/** Số cảnh báo chưa xử lý xong (OPEN + ACKNOWLEDGED) theo mức độ và trạng thái, cho dashboard. */
public record AlertSummaryDto(long active, long open, long acknowledged, Map<String, Long> bySeverity) {
}
