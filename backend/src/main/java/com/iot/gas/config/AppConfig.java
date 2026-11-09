package com.iot.gas.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling   // dùng cho kiểm tra TIMEOUT lệnh (Bước 8) và watchdog offline (Bước 9)
public class AppConfig {
}
