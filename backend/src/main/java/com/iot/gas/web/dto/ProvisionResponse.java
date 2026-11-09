package com.iot.gas.web.dto;

import java.util.Map;

/**
 * Thông tin để nạp vào firmware (secrets.h). Mật khẩu CHỈ trả về 1 lần ở đây,
 * DB chỉ lưu SHA-256 -> mất thì phải cấp lại (ROTATE_CREDENTIALS).
 */
public record ProvisionResponse(String deviceId, String mqttUrl, String username, String clientId, String password,
                                Map<String, String> topics, String note) {
}
