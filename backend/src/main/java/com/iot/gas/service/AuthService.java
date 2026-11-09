package com.iot.gas.service;

import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.domain.enums.Role;
import com.iot.gas.domain.enums.UserStatus;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.security.JwtService;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** Sai mật khẩu 5 lần liên tiếp -> tạm khóa 15 phút (chống dò mật khẩu). */
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration TEMP_LOCK = Duration.ofMinutes(15);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;

    /** Tự đăng ký: luôn nhận vai trò thấp nhất VIEWER (quyền tối thiểu). Admin nâng quyền sau. */
    @Transactional
    public UserDto register(RegisterRequest req) {
        String username = req.username().trim();
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("USERNAME_TAKEN", "Tên đăng nhập đã tồn tại");
        }
        String email = normalizeEmail(req.email());
        if (email != null && users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "Email đã được sử dụng");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(blankToNull(req.fullName()));
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole(Role.VIEWER);
        users.save(user);
        // actorId = null: audit chạy trong transaction riêng (REQUIRES_NEW) nên chưa "thấy" user vừa tạo;
        // nếu ghi actorId sẽ vi phạm khóa ngoại audit_logs.actor_id -> users.id. Username vẫn được ghi lại.
        audit.log(null, user.getUsername(), "REGISTER", "USER", String.valueOf(user.getId()),
                AuditResult.SUCCESS, Map.of("role", Role.VIEWER.name()));
        return UserDto.from(user);
    }

    /**
     * Không đặt @Transactional ở đây: số lần sai phải được LƯU kể cả khi ta ném lỗi 401.
     * Nếu bọc transaction, việc ném lỗi sẽ rollback luôn bộ đếm -> không bao giờ khóa được.
     */
    public LoginResponse login(LoginRequest req) {
        String username = req.username().trim();
        User user = users.findByUsername(username).orElse(null);
        if (user == null) {
            audit.log(null, null, "LOGIN", "USER", null, AuditResult.FAILED,
                    Map.of("username", username, "reason", "UNKNOWN_USER"));
            throw badCredentials();
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            audit.log(user.getId(), user.getUsername(), "LOGIN", "USER", String.valueOf(user.getId()),
                    AuditResult.DENIED, Map.of("reason", "ACCOUNT_LOCKED"));
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "Tài khoản đã bị quản trị viên khóa");
        }
        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            audit.log(user.getId(), user.getUsername(), "LOGIN", "USER", String.valueOf(user.getId()),
                    AuditResult.DENIED, Map.of("reason", "TEMPORARILY_LOCKED"));
            throw tempLocked(user.getLockedUntil(), now);
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            int failed = user.getFailedLoginCount() + 1;
            boolean lockNow = failed >= MAX_FAILED_ATTEMPTS;
            if (lockNow) {
                user.setLockedUntil(now.plus(TEMP_LOCK));
                user.setFailedLoginCount(0);
            } else {
                user.setFailedLoginCount(failed);
            }
            users.save(user);
            audit.log(user.getId(), user.getUsername(), "LOGIN", "USER", String.valueOf(user.getId()),
                    AuditResult.FAILED, Map.of("reason", "BAD_PASSWORD", "attempt", failed));
            if (lockNow) {
                throw tempLocked(user.getLockedUntil(), now);
            }
            throw badCredentials();
        }
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        users.save(user);
        JwtService.IssuedToken token = jwtService.issue(user);
        audit.log(user.getId(), user.getUsername(), "LOGIN", "USER", String.valueOf(user.getId()),
                AuditResult.SUCCESS, null);
        return new LoginResponse(token.token(), "Bearer", token.expiresAt(), UserDto.from(user));
    }

    /** Đăng xuất: tăng token_version -> mọi JWT đã phát cho user này hết hiệu lực ngay. */
    @Transactional
    public void logout(AuthUser current) {
        User user = load(current.id());
        user.setTokenVersion(user.getTokenVersion() + 1);
        audit.log(user.getId(), user.getUsername(), "LOGOUT", "USER", String.valueOf(user.getId()),
                AuditResult.SUCCESS, null);
    }

    @Transactional(readOnly = true)
    public UserDto me(AuthUser current) {
        return UserDto.from(load(current.id()));
    }

    @Transactional
    public UserDto updateProfile(AuthUser current, UpdateProfileRequest req) {
        User user = load(current.id());
        String email = normalizeEmail(req.email());
        if (email != null && !email.equals(user.getEmail()) && users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "Email đã được sử dụng");
        }
        user.setEmail(email);
        user.setFullName(blankToNull(req.fullName()));
        audit.log(user.getId(), user.getUsername(), "UPDATE_PROFILE", "USER", String.valueOf(user.getId()),
                AuditResult.SUCCESS, null);
        return UserDto.from(user);
    }

    /** Đổi mật khẩu xong phải đăng nhập lại (token cũ bị thu hồi). */
    @Transactional
    public void changePassword(AuthUser current, ChangePasswordRequest req) {
        User user = load(current.id());
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            audit.log(user.getId(), user.getUsername(), "CHANGE_PASSWORD", "USER", String.valueOf(user.getId()),
                    AuditResult.FAILED, Map.of("reason", "WRONG_CURRENT_PASSWORD"));
            throw ApiException.badRequest("WRONG_PASSWORD", "Mật khẩu hiện tại không đúng");
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("SAME_PASSWORD", "Mật khẩu mới phải khác mật khẩu hiện tại");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        audit.log(user.getId(), user.getUsername(), "CHANGE_PASSWORD", "USER", String.valueOf(user.getId()),
                AuditResult.SUCCESS, null);
    }

    private User load(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng"));
    }

    /** Cùng một thông báo cho "sai user" và "sai mật khẩu" -> không lộ username nào tồn tại. */
    private static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Sai tên đăng nhập hoặc mật khẩu");
    }

    private static ApiException tempLocked(Instant until, Instant now) {
        long minutes = Math.max(1, (Duration.between(now, until).getSeconds() + 59) / 60);
        return new ApiException(HttpStatus.LOCKED, "TEMPORARILY_LOCKED",
                "Sai mật khẩu quá " + MAX_FAILED_ATTEMPTS + " lần. Thử lại sau " + minutes + " phút");
    }

    static String normalizeEmail(String email) {
        return (email == null || email.isBlank()) ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
