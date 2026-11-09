package com.iot.gas.domain;

import com.iot.gas.domain.enums.RuleOperator;
import com.iot.gas.domain.enums.Severity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "alert_rules")
@Getter @Setter @NoArgsConstructor
public class AlertRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    private Long deviceId;              // null = áp dụng cho mọi thiết bị

    @Column(nullable = false, length = 20)
    private String metric;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private RuleOperator operator;

    @Column(nullable = false)
    private double threshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(nullable = false)
    private boolean enabled = true;

    private Long createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
