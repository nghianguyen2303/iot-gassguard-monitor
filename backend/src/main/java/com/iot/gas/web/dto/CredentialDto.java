package com.iot.gas.web.dto;

import com.iot.gas.domain.DeviceCredential;
import com.iot.gas.domain.enums.CredentialStatus;

import java.time.Instant;

/** Lịch sử cấp/thu hồi thông tin xác thực. KHÔNG có mật khẩu, chỉ có 8 ký tự đầu của dấu vân tay SHA-256. */
public record CredentialDto(Long id, String mqttUsername, String clientId, String fingerprint, CredentialStatus status,
                            Instant issuedAt, Long issuedBy, Instant revokedAt, String revokedReason) {

    public static CredentialDto from(DeviceCredential c) {
        return new CredentialDto(c.getId(), c.getMqttUsername(), c.getClientId(),
                c.getSecretFingerprint().substring(0, 8), c.getStatus(), c.getIssuedAt(), c.getIssuedBy(),
                c.getRevokedAt(), c.getRevokedReason());
    }
}
