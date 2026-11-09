package com.iot.gas.domain;

import com.iot.gas.domain.enums.Role;
import com.iot.gas.domain.enums.UserStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(unique = true, length = 120)
    private String email;

    @Column(length = 100)
    private String fullName;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(nullable = false)
    private int failedLoginCount = 0;

    private Instant lockedUntil;

    @Column(nullable = false)
    private int tokenVersion = 0;

    private Instant lastLoginAt;

    /** Chat Telegram nhận cảnh báo (null = chưa liên kết). */
    private Long telegramChatId;

    private Instant telegramLinkedAt;

    /** Email nhận cảnh báo, đã xác nhận bằng mã (null = không nhận). */
    @Column(length = 120)
    private String alertEmail;

    private Instant alertEmailVerifiedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}