package com.iot.gas.service;

import com.iot.gas.domain.AuditLog;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.repository.AuditLogRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;

/**
 * Ghi audit log (mục 4.5). REQUIRES_NEW: bản ghi audit được lưu trong transaction riêng,
 * nên vẫn còn kể cả khi thao tác chính thất bại và bị rollback (vd. đăng nhập sai).
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long actorId, String actorUsername, String action, String targetType, String targetId,
                    AuditResult result, Map<String, Object> details) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorUsername(actorUsername);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setResult(result);
        entry.setDetails(details);
        entry.setIpAddress(currentIp());
        repo.save(entry);
    }

    /** Ghi audit với người thực hiện là user đang đăng nhập (nếu có). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logCurrent(String action, String targetType, String targetId,
                           AuditResult result, Map<String, Object> details) {
        Optional<AuthUser> user = CurrentUser.get();
        log(user.map(AuthUser::id).orElse(null), user.map(AuthUser::username).orElse(null),
                action, targetType, targetId, result, details);
    }

    private String currentIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            HttpServletRequest request = sra.getRequest();
            // Không tự đọc X-Forwarded-For (client giả mạo được). Tomcat (server.forward-headers-strategy=native)
            // chỉ nhận header này từ proxy nội bộ tin cậy (nginx trong mạng Docker) và đã đặt sẵn vào getRemoteAddr().
            String ip = request.getRemoteAddr();
            return ip != null && ip.length() > 45 ? ip.substring(0, 45) : ip;
        }
        return null;
    }
}
