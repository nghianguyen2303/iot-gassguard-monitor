package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.Role;

import java.time.Instant;

public record ShareDto(Long userId, String username, String fullName, Role role, String permission, Instant sharedAt) {
}
