package com.iot.gas.ingest;

import com.iot.gas.mqtt.MqttGateway;
import com.iot.gas.mqtt.MqttTopics;
import com.iot.gas.service.CommandService;
import com.iot.gas.service.ConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

/** Nhận mọi bản tin iot/v1/devices/{id}/{channel} và chuyển tới đúng hàm xử lý. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceMessageRouter {

    private final MqttGateway gateway;
    private final IngestService ingest;
    private final IngestStats stats;
    private final CommandService commandService;
    private final ConfigService configService;

    @PostConstruct
    void register() {
        gateway.subscribe(MqttTopics.ALL_DEVICES, 1, this::route);
    }

    void route(String topic, MqttMessage message) {
        MqttTopics.Parsed t = MqttTopics.parse(topic);
        if (t == null) {
            log.debug("Bỏ qua topic sai cấu trúc: {}", topic);
            return;
        }
        byte[] payload = message.getPayload();
        switch (t.channel()) {
            case MqttTopics.TELEMETRY -> {
                stats.messageReceived();
                ingest.onTelemetry(t.deviceCode(), payload);
            }
            case MqttTopics.HEARTBEAT -> {
                stats.messageReceived();
                ingest.onHeartbeat(t.deviceCode(), payload);
            }
            case MqttTopics.STATUS -> {
                stats.messageReceived();
                ingest.onStatus(t.deviceCode(), payload, message.isRetained());
            }
            case MqttTopics.EVENT -> {
                stats.messageReceived();
                ingest.onEvent(t.deviceCode(), payload);
            }
            case MqttTopics.CMD_ACK -> {
                stats.messageReceived();
                commandService.onAck(t.deviceCode(), payload);
            }
            case MqttTopics.CONFIG_ACK -> {
                stats.messageReceived();
                configService.onAck(t.deviceCode(), payload);
            }
            case MqttTopics.CMD, MqttTopics.CONFIG -> {
                // Bản tin do chính backend gửi xuống thiết bị (backend cũng subscribe "#") -> bỏ qua
            }
            default -> log.debug("[{}] channel không hỗ trợ: {}", t.deviceCode(), t.channel());
        }
    }
}
