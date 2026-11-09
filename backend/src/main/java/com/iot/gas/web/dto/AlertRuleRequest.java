package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.RuleOperator;
import com.iot.gas.domain.enums.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** deviceCode bỏ trống = luật chung cho mọi thiết bị (chỉ ADMIN). */
public record AlertRuleRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 40) String deviceCode,
        @NotBlank String metric,
        @NotNull RuleOperator operator,
        @NotNull Double threshold,
        @NotNull Severity severity,
        Boolean enabled) {
}
