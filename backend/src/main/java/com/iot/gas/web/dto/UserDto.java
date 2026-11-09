package com.iot.gas.web.dto;

import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.Role;
import com.iot.gas.domain.enums.UserStatus;

import java.time.Instant;

/** Dữ liệu user trả về cho client. Không bao giờ chứa passwordHash. */
public record UserDto(Long id, String username, String email, String fullName, Role role, UserStatus status,
                      Instant lockedUntil, Instant lastLoginAt, Instant createdAt) {

    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.getRole(), u.getStatus(),
                u.getLockedUntil(), u.getLastLoginAt(), u.getCreatedAt());
    }
}
