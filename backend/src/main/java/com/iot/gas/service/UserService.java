package com.iot.gas.service;

import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.domain.enums.Role;
import com.iot.gas.domain.enums.UserStatus;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.CreateUserRequest;
import com.iot.gas.web.dto.UserDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Quản lý người dùng dành cho ADMIN (mục 4.4a: quản lý trạng thái tài khoản, phân quyền). */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<UserDto> list() {
        return users.findAll(Sort.by("id")).stream().map(UserDto::from).toList();
    }

    @Transactional
    public UserDto create(AuthUser admin, CreateUserRequest req) {
        String username = req.username().trim();
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("USERNAME_TAKEN", "Tên đăng nhập đã tồn tại");
        }
        String email = AuthService.normalizeEmail(req.email());
        if (email != null && users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "Email đã được sử dụng");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(AuthService.blankToNull(req.fullName()));
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole(req.role());
        users.save(user);
        audit.log(admin.id(), admin.username(), "CREATE_USER", "USER", String.valueOf(user.getId()),
                AuditResult.SUCCESS, Map.of("username", username, "role", req.role().name()));
        return UserDto.from(user);
    }

    @Transactional
    public UserDto changeRole(AuthUser admin, Long userId, Role role) {
        if (admin.id().equals(userId)) {
            throw ApiException.badRequest("SELF_ROLE_CHANGE", "Không thể tự đổi vai trò của chính mình");
        }
        User user = load(userId);
        Role old = user.getRole();
        user.setRole(role);
        user.setTokenVersion(user.getTokenVersion() + 1);   // buộc đăng nhập lại với quyền mới
        audit.log(admin.id(), admin.username(), "CHANGE_ROLE", "USER", String.valueOf(userId),
                AuditResult.SUCCESS, Map.of("from", old.name(), "to", role.name()));
        return UserDto.from(user);
    }

    /** Khóa: chặn đăng nhập + thu hồi mọi token đang dùng. */
    @Transactional
    public UserDto lock(AuthUser admin, Long userId) {
        if (admin.id().equals(userId)) {
            throw ApiException.badRequest("SELF_LOCK", "Không thể tự khóa tài khoản của chính mình");
        }
        User user = load(userId);
        user.setStatus(UserStatus.LOCKED);
        user.setTokenVersion(user.getTokenVersion() + 1);
        audit.log(admin.id(), admin.username(), "LOCK_USER", "USER", String.valueOf(userId),
                AuditResult.SUCCESS, null);
        return UserDto.from(user);
    }

    @Transactional
    public UserDto unlock(AuthUser admin, Long userId) {
        User user = load(userId);
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        audit.log(admin.id(), admin.username(), "UNLOCK_USER", "USER", String.valueOf(userId),
                AuditResult.SUCCESS, null);
        return UserDto.from(user);
    }

    private User load(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng"));
    }
}
