package com.iot.gas.realtime;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.domain.enums.UserStatus;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.security.JwtService;
import com.iot.gas.service.AccessService;
import com.iot.gas.service.AuditService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiểm soát truy cập WebSocket ở tầng STOMP (mục 4.5):
 * <ul>
 *   <li>CONNECT: bắt buộc JWT hợp lệ (đúng chữ ký, chưa hết hạn, tài khoản ACTIVE, đúng token_version).</li>
 *   <li>SUBSCRIBE: kiểm tra lại tài khoản + hạn token, rồi chỉ cho subscribe /topic/devices/{code}
 *       nếu người dùng có quyền xem thiết bị đó; mọi kênh khác bị từ chối.</li>
 *   <li>SEND: không cho client gửi lên (giao diện chỉ nhận; thao tác đi qua REST có kiểm tra quyền).</li>
 * </ul>
 * Từ chối -> ném MessagingException -> server gửi frame ERROR và đóng kết nối.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuthInterceptor implements ChannelInterceptor {

    static final String EXP_ATTR = "jwt_exp";
    private static final Pattern DEVICE_TOPIC = Pattern.compile("^/topic/devices/([a-z0-9][a-z0-9-]{2,39})$");

    private final JwtService jwtService;
    private final UserRepository users;
    private final DeviceRepository devices;
    private final AccessService access;
    private final AuditService audit;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) {
            return message;
        }
        switch (acc.getCommand()) {
            case CONNECT -> connect(acc);
            case SUBSCRIBE -> subscribe(acc);
            case SEND -> throw new MessagingException("Không hỗ trợ gửi bản tin qua WebSocket, hãy dùng REST API");
            default -> { }
        }
        return message;
    }

    private void connect(StompHeaderAccessor acc) {
        String header = acc.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingException("Thiếu header Authorization: Bearer <token>");
        }
        try {
            Claims claims = jwtService.parse(header.substring(7).trim());
            AuthUser user = loadActive(((Number) claims.get("uid")).longValue(), ((Number) claims.get("ver")).intValue());
            acc.setUser(new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
            Map<String, Object> session = acc.getSessionAttributes();
            if (session != null) {
                session.put(EXP_ATTR, claims.getExpiration().toInstant());
            }
            log.debug("WS CONNECT {} (session {})", user.username(), acc.getSessionId());
        } catch (ExpiredJwtException e) {
            throw new MessagingException("Token đã hết hạn");
        } catch (JwtException | IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw new MessagingException("Token không hợp lệ");
        }
    }

    private void subscribe(StompHeaderAccessor acc) {
        AuthUser user = principal(acc.getUser());
        Map<String, Object> session = acc.getSessionAttributes();
        Object exp = session == null ? null : session.get(EXP_ATTR);
        if (exp instanceof Instant e && e.isBefore(Instant.now())) {
            throw new MessagingException("Token đã hết hạn, hãy kết nối lại");
        }
        loadActiveById(user.id());                        // bị khóa sau khi kết nối -> không subscribe thêm được

        String dest = acc.getDestination() == null ? "" : acc.getDestination();
        Matcher m = DEVICE_TOPIC.matcher(dest);
        if (!m.matches()) {
            throw new MessagingException("Kênh không được phép: " + dest);
        }
        Device d = devices.findByDeviceCode(m.group(1)).orElse(null);
        if (d == null || !access.canView(user, d)) {
            audit.log(user.id(), user.username(), "ACCESS_DENIED", "WS_TOPIC", dest, AuditResult.DENIED, null);
            throw new MessagingException("Không có quyền xem thiết bị " + m.group(1));
        }
    }

    private AuthUser principal(Principal p) {
        if (p instanceof UsernamePasswordAuthenticationToken t && t.getPrincipal() instanceof AuthUser u) {
            return u;
        }
        throw new MessagingException("Chưa xác thực (cần CONNECT kèm JWT)");
    }

    private AuthUser loadActive(long userId, int tokenVersion) {
        User u = users.findById(userId).orElseThrow(() -> new MessagingException("Tài khoản không tồn tại"));
        if (u.getStatus() != UserStatus.ACTIVE) {
            throw new MessagingException("Tài khoản đã bị khóa");
        }
        if (u.getTokenVersion() != tokenVersion) {
            throw new MessagingException("Token đã bị thu hồi");
        }
        return new AuthUser(u.getId(), u.getUsername(), u.getRole());
    }

    private void loadActiveById(long userId) {
        User u = users.findById(userId).orElseThrow(() -> new MessagingException("Tài khoản không tồn tại"));
        if (u.getStatus() != UserStatus.ACTIVE) {
            throw new MessagingException("Tài khoản đã bị khóa");
        }
    }
}
