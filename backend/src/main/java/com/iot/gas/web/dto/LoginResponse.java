package com.iot.gas.web.dto;

import java.time.Instant;

public record LoginResponse(String token, String tokenType, Instant expiresAt, UserDto user) {
}
