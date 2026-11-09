package com.iot.gas.notify;

import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.service.AuditService;
import com.iot.gas.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Bật nhận cảnh báo qua email. Người dùng phải nhập MÃ 6 SỐ gửi tới hộp thư đó để chứng minh sở hữu,
 * nên không ai dùng hệ thống để gửi thư hàng loạt tới email của người khác.
 * Mã: hết hạn 10 phút, sai tối đa 5 lần, xin mã mới cách nhau ít nhất 60 giây; chỉ lưu dạng băm.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailAlertService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailClient mail;
    private final UserRepository users;
    private final AuditService audit;
    private final TransactionTemplate tx;

    private static final class Pending {
        final String email;
        final byte[] codeHash;
        final Instant createdAt = Instant.now();
        int attempts;

        Pending(String email, byte[] codeHash) {
            this.email = email;
            this.codeHash = codeHash;
        }
    }

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    public Map<String, Object> status(AuthUser current) {
        User u = users.findById(current.id()).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", mail.enabled());
        out.put("enabled", u.getAlertEmail() != null);
        out.put("alertEmail", u.getAlertEmail());
        out.put("verifiedAt", u.getAlertEmailVerifiedAt());
        out.put("profileEmail", u.getEmail());
        Pending p = pending.get(current.id());
        out.put("pendingEmail", p != null && p.createdAt.plus(CODE_TTL).isAfter(Instant.now()) ? p.email : null);
        return out;
    }

    public Map<String, Object> requestCode(AuthUser current, String rawEmail) {
        requireConfigured();
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        if (email.length() > 120 || !EMAIL.matcher(email).matches()) {
            throw ApiException.badRequest("INVALID_EMAIL", "Email không hợp lệ");
        }
        Pending old = pending.get(current.id());
        if (old != null && old.createdAt.plus(RESEND_AFTER).isAfter(Instant.now())) {
            long wait = Duration.between(Instant.now(), old.createdAt.plus(RESEND_AFTER)).toSeconds() + 1;
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_SOON", "Vui lòng đợi " + wait + " giây rồi gửi lại mã");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        try {
            mail.send(email, "[GasGuard] Mã xác nhận nhận cảnh báo: " + code,
                    "<p>Mã xác nhận để nhận cảnh báo GasGuard qua email cho tài khoản <b>" + EmailAlertNotifier.esc(current.username())
                            + "</b>:</p><p style=\"font-size:28px;font-weight:bold;letter-spacing:4px\">" + code
                            + "</p><p>Mã hết hạn sau 10 phút. Nếu bạn không yêu cầu, hãy bỏ qua email này.</p>",
                    "Mã xác nhận nhận cảnh báo GasGuard cho tài khoản " + current.username() + ": " + code
                            + "\nMã hết hạn sau 10 phút. Nếu bạn không yêu cầu, hãy bỏ qua email này.");
        } catch (EmailException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EMAIL_SEND_FAILED", e.getMessage());
        }
        pending.put(current.id(), new Pending(email, hash(code)));
        log.info("Đã gửi mã xác nhận email cảnh báo cho {} tới {}", current.username(), EmailClient.mask(email));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sentTo", email);
        out.put("expiresAt", Instant.now().plus(CODE_TTL));
        return out;
    }

    public Map<String, Object> confirm(AuthUser current, String code) {
        Pending p = pending.get(current.id());
        if (p == null || p.createdAt.plus(CODE_TTL).isBefore(Instant.now())) {
            pending.remove(current.id());
            throw ApiException.badRequest("CODE_EXPIRED", "Mã đã hết hạn hoặc chưa được gửi. Hãy bấm Gửi mã lại");
        }
        String c = code == null ? "" : code.trim();
        if (!MessageDigest.isEqual(p.codeHash, hash(c))) {
            p.attempts++;
            if (p.attempts >= MAX_ATTEMPTS) {
                pending.remove(current.id());
                audit.log(current.id(), current.username(), "EMAIL_ALERTS_ENABLE", "USER", String.valueOf(current.id()),
                        AuditResult.DENIED, Map.of("reason", "too many wrong codes"));
                throw ApiException.badRequest("CODE_LOCKED", "Nhập sai quá " + MAX_ATTEMPTS + " lần. Hãy gửi mã mới");
            }
            throw ApiException.badRequest("CODE_INVALID", "Mã không đúng (còn " + (MAX_ATTEMPTS - p.attempts) + " lần thử)");
        }
        pending.remove(current.id());
        tx.executeWithoutResult(s -> users.findById(current.id()).ifPresent(u -> {
            u.setAlertEmail(p.email);
            u.setAlertEmailVerifiedAt(Instant.now());
            users.save(u);
        }));
        audit.log(current.id(), current.username(), "EMAIL_ALERTS_ENABLE", "USER", String.valueOf(current.id()),
                AuditResult.SUCCESS, Map.of("email", EmailClient.mask(p.email)));
        return status(current);
    }

    public void disable(AuthUser current) {
        pending.remove(current.id());
        tx.executeWithoutResult(s -> users.findById(current.id()).ifPresent(u -> {
            u.setAlertEmail(null);
            u.setAlertEmailVerifiedAt(null);
            users.save(u);
        }));
        audit.log(current.id(), current.username(), "EMAIL_ALERTS_DISABLE", "USER", String.valueOf(current.id()),
                AuditResult.SUCCESS, Map.of());
    }

    public void sendTest(AuthUser current) {
        requireConfigured();
        User u = users.findById(current.id()).orElseThrow();
        if (u.getAlertEmail() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALERTS_OFF", "Chưa bật nhận cảnh báo qua email");
        }
        try {
            mail.send(u.getAlertEmail(), "[GasGuard] Email thử nghiệm",
                    "<p>🔔 Đây là email thử từ <b>GasGuard</b> cho tài khoản <b>" + EmailAlertNotifier.esc(u.getUsername())
                            + "</b>.</p><p>Bạn sẽ nhận cảnh báo của các thiết bị mình có quyền xem.</p>",
                    "Đây là email thử từ GasGuard cho tài khoản " + u.getUsername()
                            + ". Bạn sẽ nhận cảnh báo của các thiết bị mình có quyền xem.");
        } catch (EmailException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EMAIL_SEND_FAILED", e.getMessage());
        }
    }

    private void requireConfigured() {
        if (!mail.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DISABLED",
                    "Hệ thống chưa cấu hình email gửi (MAIL_USERNAME, MAIL_PASSWORD trong .env)");
        }
    }

    private static byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
