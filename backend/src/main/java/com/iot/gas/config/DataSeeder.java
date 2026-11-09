package com.iot.gas.config;

import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.domain.enums.Role;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lần chạy đầu tiên: tạo tài khoản quản trị từ biến môi trường APP_ADMIN_PASSWORD.
 * Không có mật khẩu nào nằm trong mã nguồn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.BootstrapAdmin cfg = props.bootstrapAdmin();
        if (cfg == null || cfg.username() == null || cfg.password() == null || cfg.password().isBlank()) {
            log.warn("Chưa cấu hình app.bootstrap-admin -> bỏ qua tạo admin mặc định");
            return;
        }
        if (users.existsByUsername(cfg.username())) {
            return;
        }
        User admin = new User();
        admin.setUsername(cfg.username());
        admin.setFullName("Quản trị hệ thống");
        admin.setPasswordHash(passwordEncoder.encode(cfg.password()));
        admin.setRole(Role.ADMIN);
        users.save(admin);
        audit.log(null, "system", "BOOTSTRAP_ADMIN", "USER", String.valueOf(admin.getId()),
                AuditResult.SUCCESS, Map.of("username", admin.getUsername()));
        log.info("Đã tạo tài khoản quản trị mặc định '{}'", admin.getUsername());
    }
}
