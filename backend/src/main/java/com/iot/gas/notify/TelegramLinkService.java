package com.iot.gas.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.domain.enums.UserStatus;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.service.AuditService;
import com.iot.gas.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liên kết tài khoản GasGuard với Telegram mà KHÔNG phải nhập chat id bằng tay:
 * 1) người dùng bấm "Liên kết" trên web -> backend tạo mã dùng 1 lần (10 phút) + deep link t.me/&lt;bot&gt;?start=&lt;mã&gt;
 * 2) người dùng mở link, bấm Start -> Telegram gửi "/start &lt;mã&gt;" cho bot
 * 3) luồng long-polling getUpdates ở đây nhận tin, khớp mã -> lưu chat id của chính người đó.
 * Nhờ vậy chỉ chủ tài khoản Telegram mới liên kết được, không ai chuyển cảnh báo sang chat của người khác.
 * Gửi "/stop" cho bot để hủy liên kết.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramLinkService implements SmartLifecycle {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // bỏ ký tự dễ nhầm
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TelegramClient telegram;
    private final UserRepository users;
    private final AuditService audit;
    private final TransactionTemplate tx;

    private record Pending(Long userId, Instant expiresAt) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread poller;

    // ====================================================================== API cho người dùng
    public Map<String, Object> status(AuthUser current) {
        User u = users.findById(current.id()).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botConfigured", telegram.enabled());
        out.put("botUsername", telegram.botUsername());
        out.put("linked", u.getTelegramChatId() != null);
        out.put("linkedAt", u.getTelegramLinkedAt());
        return out;
    }

    public Map<String, Object> createLink(AuthUser current) {
        requireBot();
        String bot = telegram.botUsername();
        if (bot == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TELEGRAM_UNREACHABLE",
                    "Không gọi được Telegram để lấy tên bot. Kiểm tra token, Internet hoặc nhà mạng có chặn Telegram");
        }
        pending.values().removeIf(p -> p.userId().equals(current.id()) || p.expiresAt().isBefore(Instant.now()));
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        Instant expires = Instant.now().plus(CODE_TTL);
        pending.put(code.toString(), new Pending(current.id(), expires));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code.toString());
        out.put("botUsername", bot);
        out.put("deepLink", "https://t.me/" + bot + "?start=" + code);
        out.put("expiresAt", expires);
        return out;
    }

    public void unlink(AuthUser current) {
        tx.executeWithoutResult(s -> users.findById(current.id()).ifPresent(u -> {
            u.setTelegramChatId(null);
            u.setTelegramLinkedAt(null);
            users.save(u);
        }));
        audit.log(current.id(), current.username(), "TELEGRAM_UNLINK", "USER", String.valueOf(current.id()),
                AuditResult.SUCCESS, Map.of("via", "web"));
    }

    public void sendTest(AuthUser current) {
        requireBot();
        User u = users.findById(current.id()).orElseThrow();
        if (u.getTelegramChatId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "TELEGRAM_NOT_LINKED", "Tài khoản chưa liên kết Telegram");
        }
        try {
            telegram.sendMessage(u.getTelegramChatId(), "🔔 <b>GasGuard</b>: tin nhắn thử cho tài khoản <b>"
                    + TelegramText.escape(u.getUsername()) + "</b>. Bạn sẽ nhận cảnh báo của các thiết bị mình có quyền xem.");
        } catch (TelegramException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "TELEGRAM_SEND_FAILED", e.getMessage());
        }
    }

    private void requireBot() {
        if (!telegram.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TELEGRAM_DISABLED",
                    "Hệ thống chưa cấu hình Telegram bot (TELEGRAM_BOT_TOKEN trong .env)");
        }
    }

    // ====================================================================== Nhận tin nhắn gửi tới bot
    void handleUpdate(JsonNode update) {
        JsonNode msg = update.path("message");
        JsonNode chat = msg.path("chat");
        if (!"private".equals(chat.path("type").asText()) || !chat.path("id").isIntegralNumber()) {
            return;                                     // chỉ nhận chat 1-1 với bot
        }
        long chatId = chat.path("id").asLong();
        String text = msg.path("text").asText("").trim();
        if (text.startsWith("/start")) {
            String code = text.length() > 6 ? text.substring(6).trim().toUpperCase() : "";
            if (code.isEmpty()) {
                reply(chatId, "Chào bạn! Để nhận cảnh báo, đăng nhập GasGuard → <b>Tài khoản</b> → <b>Liên kết Telegram</b>.");
                return;
            }
            Pending p = pending.remove(code);
            if (p == null || p.expiresAt().isBefore(Instant.now())) {
                reply(chatId, "Mã liên kết không đúng hoặc đã hết hạn. Hãy bấm <b>Liên kết Telegram</b> trên web để lấy mã mới.");
                return;
            }
            User linked = tx.execute(s -> {
                User u = users.findById(p.userId()).orElse(null);
                if (u == null || u.getStatus() != UserStatus.ACTIVE) {
                    return null;
                }
                u.setTelegramChatId(chatId);
                u.setTelegramLinkedAt(Instant.now());
                return users.save(u);
            });
            if (linked == null) {
                reply(chatId, "Tài khoản không còn hoạt động, không thể liên kết.");
                return;
            }
            audit.log(linked.getId(), linked.getUsername(), "TELEGRAM_LINK", "USER", String.valueOf(linked.getId()),
                    AuditResult.SUCCESS, Map.of("via", "telegram"));
            log.info("Tài khoản {} đã liên kết Telegram", linked.getUsername());
            reply(chatId, "✅ Đã liên kết với tài khoản GasGuard <b>" + TelegramText.escape(linked.getUsername())
                    + "</b>. Bạn sẽ nhận cảnh báo của các thiết bị mình có quyền xem.\nGửi /stop để ngừng nhận.");
        } else if (text.startsWith("/stop")) {
            List<User> list = tx.execute(s -> {
                List<User> us = users.findByTelegramChatId(chatId);
                us.forEach(u -> {
                    u.setTelegramChatId(null);
                    u.setTelegramLinkedAt(null);
                });
                return users.saveAll(us);
            });
            list.forEach(u -> audit.log(u.getId(), u.getUsername(), "TELEGRAM_UNLINK", "USER", String.valueOf(u.getId()),
                    AuditResult.SUCCESS, Map.of("via", "telegram")));
            reply(chatId, list.isEmpty() ? "Chat này chưa liên kết tài khoản nào."
                    : "Đã hủy liên kết. Bạn sẽ không nhận cảnh báo GasGuard nữa.");
        } else {
            reply(chatId, "Bot chỉ gửi cảnh báo GasGuard. Lệnh: /stop để ngừng nhận.");
        }
    }

    private void reply(long chatId, String html) {
        try {
            telegram.sendMessage(chatId, html);
        } catch (TelegramException e) {
            log.warn("Không trả lời được Telegram: {}", e.getMessage());
        }
    }

    private void pollLoop() {
        long offset = 0;
        long backoffMs = 2000;
        while (running) {
            try {
                JsonNode updates = telegram.getUpdates(offset, 25);
                backoffMs = 2000;
                for (JsonNode u : updates) {
                    offset = Math.max(offset, u.path("update_id").asLong() + 1);
                    try {
                        handleUpdate(u);
                    } catch (RuntimeException e) {
                        log.warn("Lỗi xử lý tin nhắn Telegram: {}", e.getMessage());
                    }
                }
                pending.values().removeIf(p -> p.expiresAt().isBefore(Instant.now()));
            } catch (TelegramException e) {
                log.warn("Telegram getUpdates lỗi: {} (thử lại sau {} s)", e.getMessage(), backoffMs / 1000);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 60_000);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ====================================================================== Vòng đời luồng nhận tin
    @Override
    public void start() {
        if (!telegram.enabled()) {
            log.info("Telegram: chưa cấu hình TELEGRAM_BOT_TOKEN -> tắt thông báo Telegram");
            return;
        }
        running = true;
        poller = new Thread(this::pollLoop, "telegram-poller");
        poller.setDaemon(true);
        poller.start();
        log.info("Telegram: đã bật, bot @{}", telegram.botUsername());
    }

    @Override
    public void stop() {
        running = false;
        if (poller != null) {
            poller.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
