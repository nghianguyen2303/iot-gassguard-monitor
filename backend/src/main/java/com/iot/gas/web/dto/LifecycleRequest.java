package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.LifecycleAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** reason bắt buộc với SUSPEND và DECOMMISSION (ghi vào audit). */
public record LifecycleRequest(@NotNull LifecycleAction action, @Size(max = 255) String reason) {
}
