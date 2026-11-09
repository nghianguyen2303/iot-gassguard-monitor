package com.iot.gas.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "telemetry")
@Getter @Setter @NoArgsConstructor
public class Telemetry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deviceId;

    @Column(nullable = false, length = 20)
    private String metric;

    @Column(nullable = false)
    private double value;

    @Column(nullable = false, length = 10)
    private String unit;

    private Long seq;

    @Column(nullable = false)
    private Instant ts;

    @Column(nullable = false)
    private Instant receivedAt;
}
