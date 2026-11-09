package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.AlertStatus;
import com.iot.gas.domain.enums.AlertType;
import com.iot.gas.domain.enums.Severity;

import java.time.Instant;

/** clearedAt != null: điều kiện đã trở lại bình thường nhưng người dùng chưa xử lý xong cảnh báo. */
public record AlertDto(Long id, Long deviceId, String deviceCode, String deviceName, Long ruleId, String ruleName,
                       AlertType type, Severity severity, String message, String metric, Double value,
                       AlertStatus status, int occurrenceCount, Instant createdAt, Instant lastSeenAt,
                       Instant clearedAt, String acknowledgedBy, Instant acknowledgedAt, String resolvedBy,
                       Instant resolvedAt, String resolutionNote, boolean canHandle) {
}
