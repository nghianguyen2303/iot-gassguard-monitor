package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateRoleRequest(@NotNull Role role) {
}
