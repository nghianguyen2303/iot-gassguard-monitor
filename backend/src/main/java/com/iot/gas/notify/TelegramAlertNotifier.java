package com.iot.gas.notify;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.User;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.service.event.AlertChangedEvent;
import com.iot.gas.web.dto.AlertDto;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Gửi cảnh báo qua Telegram cho người có quyền xem thiết bị (admin, chủ sở hữu, người được chia sẻ)
 * và đã liên kết Telegram. Chạy SAU KHI commit, trên luồng riêng: Telegram chậm/bị chặn không làm
 * chậm việc nhận dữ liệu. Chống spam: tái diễn / trở lại bình thường của cùng 1 cảnh báo tối đa 1 tin mỗi 2 phút.
 */
@Component
@Slf4j
public class TelegramAlertNotifier {

    static final Set<String> NOTIFY = Set.of("CREATED", "RECURRED", "CLEARED", "RESOLVED");
    static final Duration FLAP_COOLDOWN = Duration.ofMinutes(2);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy").withZone(VN);

    private final TelegramClient telegram;
    private final UserRepository users;
    private final DeviceRepository devices;
    private final String publicUrl;
    private final Map<Long, Instant> lastFlapSent = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500), r -> {
                Thread t = new Thread(r, "telegram-sender");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    public TelegramAlertNotifier(TelegramClient telegram, UserRepository users, DeviceRepository devices,
                                 @Value("${app.public-url:}") String publicUrl) {
        this.telegram = telegram;
        this.users = users;
        this.devices = devices;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlertChanged(AlertChangedEvent e) {
        if (!telegram.enabled() || !NOTIFY.contains(e.change()) || e.alert() == null) {
            return;
        }
        AlertDto a = e.alert();
        if ("RECURRED".equals(e.change()) || "CLEARED".equals(e.change())) {
            Instant now = Instant.now();
            Instant last = lastFlapSent.get(a.id());
            if (last != null && last.plus(FLAP_COOLDOWN).isAfter(now)) {
                log.debug("Bỏ qua Telegram {} cho cảnh báo {} (chống spam)", e.change(), a.id());
                return;
            }
            lastFlapSent.put(a.id(), now);
        }
        executor.execute(() -> deliver(e.change(), a));
    }

    private void deliver(String change, AlertDto a) {
        Device d = devices.findById(a.deviceId()).orElse(null);
        if (d == null) {
            return;
        }
        List<User> recipients = users.findTelegramRecipients(d.getId(), d.getOwnerId());
        if (recipients.isEmpty()) {
            return;
        }
        String text = format(change, a, d);
        int ok = 0;
        for (User u : recipients) {
            try {
                telegram.sendMessage(u.getTelegramChatId(), text);
                ok++;
            } catch (TelegramException ex) {
                log.warn("Telegram tới {} thất bại: {}", u.getUsername(), ex.getMessage());
            }
        }
        log.info("[{}] Telegram {} cảnh báo #{}: gửi {}/{} người", d.getDeviceCode(), change, a.id(), ok, recipients.size());
    }

    String format(String change, AlertDto a, Device d) {
        String title = switch (a.type()) {
            case THRESHOLD -> a.ruleName() != null ? a.ruleName() : "Vượt ngưỡng";
            case OFFLINE -> "Thiết bị mất kết nối";
            case FAULT -> "Lỗi cảm biến";
        };
        String sev = switch (a.severity()) {
            case CRITICAL -> "🔴 NGHIÊM TRỌNG";
            case WARNING -> "🟠 CẢNH BÁO";
            case INFO -> "🔵 THÔNG TIN";
        };
        StringBuilder sb = new StringBuilder();
        switch (change) {
            case "CREATED" -> sb.append("<b>").append(sev).append(": ").append(esc(title)).append("</b>\n");
            case "RECURRED" -> sb.append("<b>").append(sev).append(" (tái diễn): ").append(esc(title)).append("</b>\n");
            case "CLEARED" -> sb.append("<b>✅ Đã trở lại bình thường: ").append(esc(title)).append("</b>\n")
                    .append("Cảnh báo vẫn cần được xử lý trên web.\n");
            case "RESOLVED" -> {
                sb.append("<b>☑️ Đã xử lý xong: ").append(esc(title)).append("</b>\n");
                if (a.resolvedBy() != null) {
                    sb.append("Người xử lý: ").append(esc(a.resolvedBy())).append('\n');
                }
                if (a.resolutionNote() != null && !a.resolutionNote().isBlank()) {
                    sb.append("Ghi chú: ").append(esc(a.resolutionNote())).append('\n');
                }
            }
            default -> sb.append(esc(title)).append('\n');
        }
        if (!"RESOLVED".equals(change) && a.message() != null) {
            sb.append(esc(a.message())).append('\n');
        }
        sb.append("\n📟 ").append(esc(d.getName())).append(" (").append(esc(d.getDeviceCode())).append(")\n");
        String place = String.join(" / ", java.util.stream.Stream.of(d.getLocation(), d.getZone())
                .filter(s -> s != null && !s.isBlank()).toList());
        if (!place.isEmpty()) {
            sb.append("📍 ").append(esc(place)).append('\n');
        }
        Instant at = switch (change) {
            case "CLEARED" -> a.clearedAt();
            case "RESOLVED" -> a.resolvedAt();
            case "RECURRED" -> a.lastSeenAt();
            default -> a.createdAt();
        };
        sb.append("🕒 ").append(TIME.format(at != null ? at : Instant.now()));
        if (!publicUrl.isEmpty() && !publicUrl.contains("localhost") && !publicUrl.contains("127.0.0.1")) {
            sb.append("\n\n<a href=\"").append(esc(publicUrl)).append("/alerts\">Mở GasGuard</a>");
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return TelegramText.escape(s);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
