package com.iot.gas.domain;

import com.iot.gas.domain.enums.CredentialStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "device_credentials")
@Getter @Setter @NoArgsConstructor
public class DeviceCredential {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deviceId;

    @Column(nullable = false, length = 60)
    private String mqttUsername;

    @Column(nullable = false, length = 60)
    private String clientId;

    @Column(nullable = false, length = 64)
    private String secretFingerprint;   // SHA-256 của mật khẩu, không lưu mật khẩu

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CredentialStatus status = CredentialStatus.ACTIVE;

    @Column(nullable = false)
    private Instant issuedAt = Instant.now();

    private Long issuedBy;

    private Instant revokedAt;

    @Column(length = 255)
    private String revokedReason;
}
