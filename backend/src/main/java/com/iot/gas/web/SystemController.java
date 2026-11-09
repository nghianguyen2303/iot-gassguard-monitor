package com.iot.gas.web;

import com.iot.gas.ingest.IngestStats;
import com.iot.gas.mqtt.MqttGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thông tin vận hành cho quản trị viên và cho kiểm thử phi chức năng (mục 7.2). */
@RestController
@RequestMapping("/api/system")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SystemController {

    private final IngestStats stats;
    private final MqttGateway gateway;

    @GetMapping("/ingest-stats")
    public Map<String, Object> ingestStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mqttConnected", gateway.isConnected());
        m.putAll(stats.snapshot());
        return m;
    }
}
