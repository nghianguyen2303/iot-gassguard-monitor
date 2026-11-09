package com.iot.gas.domain;

import com.iot.gas.domain.enums.CommandStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "commands")
@Getter @Setter @NoArgsConstructor
public class Command {
    @Id
    private UUID id;                    // = commandId gửi xuống thiết bị

    @Column(nullable = false)
    private Long deviceId;

    @Column(nullable = false, length = 30)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> params = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CommandStatus status = CommandStatus.PENDING;

    @Column(nullable = false)
    private Long requestedBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant sentAt;
    private Instant ackedAt;

    @Column(length = 255)
    private String resultMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> resultState;
}
