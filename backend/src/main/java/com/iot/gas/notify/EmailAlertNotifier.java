package com.iot.gas.notify;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.AlertType;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Gửi cảnh báo qua email cho người có quyền xem thiết bị (admin, chủ sở hữu, người được chia sẻ)
 * và đã xác nhận email nhận cảnh báo. Chạy SAU KHI commit, trên luồng riêng: SMTP chậm không làm chậm
 * việc nhận dữ liệu. Mỗi người nhận 1 email riêng (không lộ địa chỉ của người khác).
 * Chống spam – mỗi cảnh báo gửi tối đa 2 email: 1 email khi XẢY RA (mất kết nối, vượt ngưỡng, lỗi cảm biến)
 * và 1 email khi TRỞ LẠI BÌNH THƯỜNG (kết nối lại, số đo hết vượt ngưỡng). Chập chờn (tái diễn rồi hết nhiều lần)
 * không gửi thêm cho đến khi cảnh báo được xử lý xong; sau đó sự cố mới là cảnh báo mới. Không gửi email
 * "đã xử lý xong" (người xử lý đã biết) và không gửi cho thiết bị của bộ kiểm thử tự động (mã e2e-*, perf-*;
 * cấu hình app.mail.skip-device-prefixes) – cảnh báo của chúng vẫn hiện trên web.
 */
@Component
@Slf4j
public class EmailAlertNotifier {

    static final Set<String> NOTIFY = Set.of("CREATED", "CLEARED");
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy").withZone(VN);

    private final EmailClient mail;
    private final UserRepository users;
    private final DeviceRepository devices;
    private final String publicUrl;
    private final List<String> skipPrefixes;
    /** Cảnh báo đã gửi email "trở lại bình thường" -> lần hết tiếp theo (sau tái diễn) không gửi nữa. */
    private final Set<Long> clearNotified = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500), r -> {
                Thread t = new Thread(r, "email-sender");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    public EmailAlertNotifier(EmailClient mail, UserRepository users, DeviceRepository devices,
                              @Value("${app.public-url:}") String publicUrl,
                              @Value("${app.mail.skip-device-prefixes:e2e-,perf-}") String skipPrefixes) {
        this.mail = mail;
        this.users = users;
        this.devices = devices;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
        this.skipPrefixes = Stream.of(skipPrefixes == null ? new String[0] : skipPrefixes.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlertChanged(AlertChangedEvent e) {
        AlertDto a = e.alert();
        if (a == null) {
            return;
        }
        if ("RESOLVED".equals(e.change())) {   // cảnh báo kết thúc -> sự cố sau sẽ là cảnh báo mới, gửi lại bình thường
            clearNotified.remove(a.id());
            return;
        }
        if (!shouldEmail(e.change(), a)) {
            return;
        }
        executor.execute(() -> deliver(e.change(), a));
    }

    /** Quy tắc chống spam (xem mô tả lớp). Tách riêng để kiểm thử. */
    boolean shouldEmail(String change, AlertDto a) {
        if (!mail.enabled() || !NOTIFY.contains(change) || isTestDevice(a.deviceCode())) {
            return false;
        }
        return !"CLEARED".equals(change) || clearNotified.add(a.id());
    }

    boolean isTestDevice(String deviceCode) {
        return deviceCode != null && skipPrefixes.stream().anyMatch(deviceCode::startsWith);
    }

    private void deliver(String change, AlertDto a) {
        Device d = devices.findById(a.deviceId()).orElse(null);
        if (d == null) {
            return;
        }
        List<User> recipients = users.findEmailRecipients(d.getId(), d.getOwnerId());
        if (recipients.isEmpty()) {
            return;
        }
        Mail m = compose(change, a, d);
        int ok = 0;
        for (User u : recipients) {
            try {
                mail.send(u.getAlertEmail(), m.subject, m.html, m.text);
                ok++;
            } catch (EmailException ex) {
                log.warn("Email cảnh báo tới {} ({}) thất bại: {}", u.getUsername(), EmailClient.mask(u.getAlertEmail()), ex.getMessage());
            }
        }
        log.info("[{}] Email {} cảnh báo #{}: gửi {}/{} người", d.getDeviceCode(), change, a.id(), ok, recipients.size());
    }

    record Mail(String subject, String html, String text) {
    }

    Mail compose(String change, AlertDto a, Device d) {
        String title = switch (a.type()) {
            case THRESHOLD -> a.ruleName() != null ? a.ruleName() : "Vượt ngưỡng";
            case OFFLINE -> "Thiết bị mất kết nối";
            case FAULT -> "Lỗi cảm biến";
        };
        String sev = switch (a.severity()) {
            case CRITICAL -> "NGHIÊM TRỌNG";
            case WARNING -> "CẢNH BÁO";
            case INFO -> "THÔNG TIN";
        };
        String color = switch (a.severity()) {
            case CRITICAL -> "#b91c1c";
            case WARNING -> "#b45309";
            case INFO -> "#1d4ed8";
        };
        String head = switch (change) {
            case "CREATED" -> (a.severity().name().equals("CRITICAL") ? "🔴 " : "🟠 ") + sev + ": " + title;
            case "RECURRED" -> "🔁 " + sev + " (tái diễn): " + title;
            case "CLEARED" -> a.type() == AlertType.OFFLINE ? "✅ Đã kết nối lại" : "✅ Đã trở lại bình thường: " + title;
            case "RESOLVED" -> "☑️ Đã xử lý xong: " + title;
            default -> title;
        };
        if ("CLEARED".equals(change) || "RESOLVED".equals(change)) {
            color = "#15803d";
        }
        Instant at = switch (change) {
            case "CLEARED" -> a.clearedAt();
            case "RESOLVED" -> a.resolvedAt();
            case "RECURRED" -> a.lastSeenAt();
            default -> a.createdAt();
        };
        String when = TIME.format(at != null ? at : Instant.now());
        String place = String.join(" / ", Stream.of(d.getLocation(), d.getZone()).filter(s -> s != null && !s.isBlank()).toList());

        List<String[]> rows = new ArrayList<>();
        if (!"RESOLVED".equals(change) && a.message() != null) {
            rows.add(new String[]{"Nội dung", a.message()});
        }
        rows.add(new String[]{"Thiết bị", d.getName() + " (" + d.getDeviceCode() + ")"});
        if (!place.isEmpty()) {
            rows.add(new String[]{"Vị trí", place});
        }
        rows.add(new String[]{"Mức độ", sev});
        rows.add(new String[]{"Thời gian", when});
        if ("CLEARED".equals(change)) {
            rows.add(new String[]{"Ghi chú", "Cảnh báo vẫn cần được xác nhận / xử lý trên web."});
        }
        if ("RESOLVED".equals(change)) {
            if (a.resolvedBy() != null) {
                rows.add(new String[]{"Người xử lý", a.resolvedBy()});
            }
            if (a.resolutionNote() != null && !a.resolutionNote().isBlank()) {
                rows.add(new String[]{"Ghi chú", a.resolutionNote()});
            }
        }
        boolean link = !publicUrl.isEmpty() && !publicUrl.contains("localhost") && !publicUrl.contains("127.0.0.1");

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:Segoe UI,Arial,sans-serif;max-width:560px\">")
                .append("<div style=\"background:").append(color).append(";color:#fff;padding:12px 16px;border-radius:8px 8px 0 0;font-size:17px;font-weight:bold\">")
                .append(esc(head)).append("</div>")
                .append("<table style=\"width:100%;border:1px solid #e5e7eb;border-top:none;border-collapse:collapse;font-size:14px\">");
        for (String[] r : rows) {
            html.append("<tr><td style=\"padding:8px 12px;color:#6b7280;width:120px;border-bottom:1px solid #f1f5f9\">").append(esc(r[0]))
                    .append("</td><td style=\"padding:8px 12px;border-bottom:1px solid #f1f5f9\">").append(esc(r[1])).append("</td></tr>");
        }
        html.append("</table>");
        if (link) {
            html.append("<p><a href=\"").append(esc(publicUrl)).append("/alerts\">Mở GasGuard để xem và xử lý</a></p>");
        }
        html.append("<p style=\"color:#9ca3af;font-size:12px\">Email tự động từ hệ thống GasGuard. Tắt nhận email trong trang Tài khoản.</p></div>");

        StringBuilder text = new StringBuilder(head).append("\n\n");
        rows.forEach(r -> text.append(r[0]).append(": ").append(r[1]).append('\n'));
        if (link) {
            text.append("\nMở GasGuard: ").append(publicUrl).append("/alerts\n");
        }
        String subject = "[GasGuard] " + head + " – " + d.getName();
        return new Mail(subject, html.toString(), text.toString());
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
