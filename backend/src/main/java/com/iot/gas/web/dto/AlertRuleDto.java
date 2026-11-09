package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.RuleOperator;
import com.iot.gas.domain.enums.Severity;

import java.time.Instant;

public record AlertRuleDto(Long id, String name, Long deviceId, String deviceCode, boolean global, String metric,
                           RuleOperator operator, double threshold, String unit, Severity severity, boolean enabled,
                           Instant createdAt, boolean canEdit) {
}
