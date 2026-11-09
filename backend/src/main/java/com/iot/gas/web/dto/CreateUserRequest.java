package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Admin tạo tài khoản và chọn vai trò. */
public record CreateUserRequest(
        @NotBlank @Pattern(regexp = PasswordPolicy.USERNAME_REGEX, message = PasswordPolicy.USERNAME_MESSAGE)
        String username,
        @Email(message = "Email không hợp lệ") @Size(max = 120)
        String email,
        @Size(max = 100)
        String fullName,
        @NotBlank @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        String password,
        @NotNull Role role) {
}
