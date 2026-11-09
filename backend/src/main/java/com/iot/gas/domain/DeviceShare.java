package com.iot.gas.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "device_shares")
@IdClass(DeviceShare.Pk.class)
@Getter @Setter @NoArgsConstructor
public class DeviceShare {
    @Id
    private Long deviceId;

    @Id
    private Long userId;

    @Column(nullable = false, length = 10)
    private String permission = "VIEW";

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public DeviceShare(Long deviceId, Long userId) {
        this.deviceId = deviceId;
        this.userId = userId;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Pk implements Serializable {
        private Long deviceId;
        private Long userId;
    }
}
