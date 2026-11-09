package com.iot.gas.domain;

import com.iot.gas.domain.enums.ConfigStatus;
import com.iot.gas.domain.enums.Connectivity;
import com.iot.gas.domain.enums.LifecycleStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "devices")
// Chỉ UPDATE các cột thực sự thay đổi: tránh ghi đè last_seen_at/connectivity do luồng MQTT cập nhật song song
@DynamicUpdate
@Getter @Setter @NoArgsConstructor
public class Device {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String deviceCode;          // chính là deviceId trong topic MQTT

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 40)
    private String deviceType = "GAS_NODE";

    @Column(length = 120)
    private String location;

    @Column(length = 60)
    private String zone;

    @Column(nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LifecycleStatus lifecycleStatus = LifecycleStatus.REGISTERED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Connectivity connectivity = Connectivity.OFFLINE;

    private Instant lastSeenAt;

    @Column(length = 30)
    private String hwVersion;

    @Column(length = 30)
    private String fwVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config = new HashMap<>();

    @Column(nullable = false)
    private int configVersion = 0;

    @Column(nullable = false)
    private int configAppliedVersion = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ConfigStatus configStatus = ConfigStatus.NONE;

    @Column(length = 255)
    private String configMessage;

    private Instant configAckedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> actuatorState = new HashMap<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private Instant decommissionedAt;
}
