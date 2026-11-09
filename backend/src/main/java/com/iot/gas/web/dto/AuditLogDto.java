package com.iot.gas.web.dto;

import com.iot.gas.domain.AuditLog;
import com.iot.gas.domain.enums.AuditResult;

import java.time.Instant;
import java.util.Map;

public record AuditLogDto(Long id, Long actorId, String actorUsername, String action, String targetType,
                          String targetId, AuditResult result, Map<String, Object> details, String ipAddress,
                          Instant createdAt) {
    public static AuditLogDto from(AuditLog a) {
        return new AuditLogDto(a.getId(), a.getActorId(), a.getActorUsername(), a.getAction(), a.getTargetType(),
                a.getTargetId(), a.getResult(), a.getDetails(), a.getIpAddress(), a.getCreatedAt());
    }
}
