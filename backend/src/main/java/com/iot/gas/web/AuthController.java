package com.iot.gas.web;

import com.iot.gas.security.CurrentUser;
import com.iot.gas.service.AuthService;
import com.iot.gas.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/logout")
    public Map<String, String> logout() {
        authService.logout(CurrentUser.require());
        return Map.of("message", "Đã đăng xuất");
    }

    @GetMapping("/me")
    public UserDto me() {
        return authService.me(CurrentUser.require());
    }

    @PutMapping("/me")
    public UserDto updateProfile(@Valid @RequestBody UpdateProfileRequest req) {
        return authService.updateProfile(CurrentUser.require(), req);
    }

    @PutMapping("/password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(CurrentUser.require(), req);
        return Map.of("message", "Đã đổi mật khẩu. Vui lòng đăng nhập lại");
    }
}
