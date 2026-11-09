package com.iot.gas.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Email(message = "Email không hợp lệ") @Size(max = 120) String email,
        @Size(max = 100) String fullName) {
}
