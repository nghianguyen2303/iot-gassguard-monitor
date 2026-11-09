package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.ConfigStatus;

import java.time.Instant;
import java.util.Map;

/**
 * configVersion: phiên bản mới nhất backend đã gửi; appliedVersion: phiên bản thiết bị xác nhận đã áp dụng.
 * published=false: đã lưu nhưng chưa gửi được tới broker (broker mất kết nối) -> gửi lại sau.
 */
public record DeviceConfigDto(String deviceCode, Map<String, Object> config, int configVersion, int appliedVersion,
                              ConfigStatus status, String message, Instant ackedAt, boolean published) {
}
