package com.iot.gas.domain;

import com.iot.gas.domain.enums.AuditResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actorId;

    @Column(length = 50)
    private String actorUsername;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(length = 30)
    private String targetType;

    @Column(length = 60)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuditResult result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(length = 45)
    private String ipAddress;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
