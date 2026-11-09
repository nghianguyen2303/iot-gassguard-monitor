package com.iot.gas.notify;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Properties;

/**
 * Gửi email qua SMTP (mặc định Gmail: smtp.gmail.com:587 + STARTTLS) bằng Jakarta Mail.
 * Gmail yêu cầu "Mật khẩu ứng dụng" 16 ký tự (bật xác minh 2 bước), KHÔNG dùng mật khẩu đăng nhập.
 * Mật khẩu chỉ lấy từ biến môi trường, không bao giờ ghi ra log.
 */
@Component
@Slf4j
public class EmailClient {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final String fromName;
    private final boolean auth;
    private final boolean starttls;
    private final boolean ssl;
    private final Session session;

    public EmailClient(@Value("${app.mail.host:smtp.gmail.com}") String host,
                       @Value("${app.mail.port:587}") int port,
                       @Value("${app.mail.username:}") String username,
                       @Value("${app.mail.password:}") String password,
                       @Value("${app.mail.from:}") String from,
                       @Value("${app.mail.from-name:GasGuard}") String fromName,
                       @Value("${app.mail.auth:true}") boolean auth,
                       @Value("${app.mail.starttls:true}") boolean starttls,
                       @Value("${app.mail.ssl:false}") boolean ssl) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.replace(" ", "");   // App Password Gmail hay có dấu cách
        this.from = from == null || from.isBlank() ? this.username : from.trim();
        this.fromName = fromName;
        this.auth = auth;
        this.starttls = starttls;
        this.ssl = ssl;

        Properties p = new Properties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.host", this.host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.auth", String.valueOf(auth));
        p.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        p.put("mail.smtp.starttls.required", String.valueOf(starttls));
        p.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        String user = this.username;
        String pass = this.password;
        this.session = Session.getInstance(p, auth ? new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        } : null);
    }

    public boolean enabled() {
        return !host.isEmpty() && !from.isEmpty() && (!auth || (!username.isEmpty() && !password.isEmpty()));
    }

    public String sender() {
        return from;
    }

    /** Gửi 1 email HTML (kèm bản text thuần cho ứng dụng không hiển thị HTML). */
    public void send(String to, String subject, String html, String text) throws EmailException {
        if (!enabled()) {
            throw new EmailException("Chưa cấu hình email gửi (MAIL_USERNAME / MAIL_PASSWORD trong .env)");
        }
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from, fromName, "UTF-8"));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to, true));
            msg.setSubject(subject, "UTF-8");
            msg.setSentDate(new Date());
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(text, "UTF-8");
            MimeBodyPart rich = new MimeBodyPart();
            rich.setContent(html, "text/html; charset=UTF-8");
            MimeMultipart alt = new MimeMultipart("alternative");
            alt.addBodyPart(plain);
            alt.addBodyPart(rich);
            msg.setContent(alt);
            Transport.send(msg);
        } catch (jakarta.mail.AuthenticationFailedException e) {
            throw new EmailException("Máy chủ email từ chối đăng nhập: kiểm tra MAIL_USERNAME và Mật khẩu ứng dụng (App Password) Gmail");
        } catch (MessagingException | UnsupportedEncodingException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String detail = root instanceof java.net.UnknownHostException ? "không phân giải được " + host
                    : root instanceof java.net.ConnectException || root instanceof java.net.SocketTimeoutException
                    ? "không kết nối được " + host + ":" + port + " (Internet / tường lửa)"
                    : root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + firstLine(root.getMessage()));
            throw new EmailException("Gửi email thất bại – " + detail);
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String line = i < 0 ? s : s.substring(0, i);
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    /** Che bớt email khi ghi log: nguyenvana@gmail.com -> ng***@gmail.com */
    public static String mask(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 2) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
