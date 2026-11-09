package com.iot.gas.domain;

import com.iot.gas.domain.enums.AlertStatus;
import com.iot.gas.domain.enums.AlertType;
import com.iot.gas.domain.enums.Severity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "alerts")
@Getter @Setter @NoArgsConstructor
public class Alert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deviceId;

    private Long ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AlertType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(nullable = false, length = 255)
    private String message;

    @Column(length = 20)
    private String metric;

    private Double value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AlertStatus status = AlertStatus.OPEN;

    @Column(nullable = false)
    private int occurrenceCount = 1;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant lastSeenAt = Instant.now();

    private Instant clearedAt;

    private Long acknowledgedBy;
    private Instant acknowledgedAt;

    private Long resolvedBy;
    private Instant resolvedAt;

    @Column(length = 500)
    private String resolutionNote;
}
