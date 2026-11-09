package com.iot.gas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Ánh xạ khối "app:" trong application.yml. Giá trị bí mật lấy từ biến môi trường (.env). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, BootstrapAdmin bootstrapAdmin, Mqtt mqtt) {

    public record Jwt(String secret, long expirationMinutes) {}

    public record BootstrapAdmin(String username, String password) {}

    public record Mqtt(String url, String username, String password, String clientId) {}
}
