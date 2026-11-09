package com.iot.gas.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Đăng ký (onboarding) thiết bị. deviceCode cũng là username/clientId MQTT và là phần {deviceId} của topic. */
public record RegisterDeviceRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,39}$",
                message = "Mã thiết bị 3-40 ký tự: chữ thường, số, '-' (vd. esp32-001)")
        String deviceCode,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 40) String deviceType,
        @Size(max = 120) String location,
        @Size(max = 60) String zone,
        @Size(max = 30) String hwVersion,
        /** Chỉ ADMIN được gán chủ sở hữu khác; bỏ trống = chính người đăng ký. */
        @Size(max = 50) String ownerUsername) {
}
