package com.iot.gas.security;

import com.iot.gas.domain.enums.Role;

/** Người dùng đang đăng nhập, được đặt vào SecurityContext sau khi kiểm tra JWT. */
public record AuthUser(Long id, String username, Role role) {
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
