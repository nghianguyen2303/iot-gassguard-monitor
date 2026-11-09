package com.iot.gas.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShareRequest(@NotBlank @Size(max = 50) String username) {
}
