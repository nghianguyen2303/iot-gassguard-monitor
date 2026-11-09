package com.iot.gas.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.gas.ingest.Reading;
import com.iot.gas.ingest.event.ActuatorStateChangedEvent;
import com.iot.gas.ingest.event.ConnectivityChangedEvent;
import com.iot.gas.ingest.event.DeviceEventReceivedEvent;
import com.iot.gas.ingest.event.TelemetryReceivedEvent;
import com.iot.gas.service.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.MimeTypeUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Đẩy thay đổi lên giao diện qua /topic/devices/{deviceCode}.
 * Lắng nghe SAU KHI transaction commit: giao diện chỉ nhận dữ liệu đã thực sự lưu vào DB
 * (không bao giờ hiển thị dữ liệu bị rollback). Mỗi bản tin có "type" để giao diện phân loại:
 * telemetry | connectivity | lifecycle | alert | command | config | event
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimePublisher {

    public static final String TOPIC_PREFIX = "/topic/devices/";

    private final SimpMessagingTemplate broker;
    private final ObjectMapper mapper;

    @TransactionalEventListener(fallbackExecution = true)
    public void telemetry(TelemetryReceivedEvent e) {
        Map<String, Object> readings = new LinkedHashMap<>();
        for (Reading r : e.readings()) {
            readings.put(r.metric(), Map.of("value", r.value(), "unit", r.unit()));
        }
        Map<String, Object> body = base("telemetry", e.deviceCode());
        body.put("ts", e.measuredAt());                  // thời điểm đo -> client tính độ trễ cảm biến -> dashboard
        body.put("receivedAt", e.receivedAt());
        body.put("readings", readings);
        send(e.deviceCode(), body);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void state(ActuatorStateChangedEvent e) {
        Map<String, Object> body = base("state", e.deviceCode());
        body.put("state", e.state());
        send(e.deviceCode(), body);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void connectivity(ConnectivityChangedEvent e) {
        Map<String, Object> body = base("connectivity", e.deviceCode());
        body.put("from", e.from());
        body.put("to", e.to());
        body.put("reason", e.reason());
        body.put("at", e.at());
        send(e.deviceCode(), body);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void lifecycle(DeviceLifecycleChangedEvent e) {
        Map<String, Object> body = base("lifecycle", e.deviceCode());
        body.put("from", e.from());
        body.put("to", e.to());
        body.put("actor", e.actor());
        body.put("at", e.at());
        send(e.deviceCode(), body);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void alert(AlertChangedEvent e) {
        if (e.alert().deviceCode() == null) {
            return;
        }
        Map<String, Object> body = base("alert", e.alert().deviceCode());
        body.put("change", e.change());                  // CREATED | RECURRED | CLEARED | ACKNOWLEDGED | RESOLVED
        body.put("alert", e.alert());
        send(e.alert().deviceCode(), body);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void command(CommandUpdatedEvent e) {
        Map<String, Object> body = base("command", e.command().deviceCode());
        body.put("command", e.command());
        send(e.command().deviceCode(), body);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void config(DeviceConfigUpdatedEvent e) {
        Map<String, Object> body = base("config", e.config().deviceCode());
        body.put("config", e.config());
        send(e.config().deviceCode(), body);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void deviceEvent(DeviceEventReceivedEvent e) {
        Map<String, Object> body = base("event", e.deviceCode());
        body.put("eventType", e.type());
        body.put("data", e.body());
        body.put("at", e.at());
        send(e.deviceCode(), body);
    }

    private static Map<String, Object> base(String type, String deviceCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("deviceCode", deviceCode);
        m.put("serverTime", Instant.now());
        return m;
    }

    private void send(String deviceCode, Map<String, Object> body) {
        try {
            broker.convertAndSend(TOPIC_PREFIX + deviceCode, mapper.writeValueAsString(body),
                    Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON));
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("Không đẩy được realtime cho {}: {}", deviceCode, ex.getMessage());
        }
    }
}
