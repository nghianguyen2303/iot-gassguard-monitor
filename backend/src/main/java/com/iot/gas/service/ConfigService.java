package com.iot.gas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.domain.enums.ConfigStatus;
import com.iot.gas.domain.enums.LifecycleStatus;
import com.iot.gas.ingest.IngestStats;
import com.iot.gas.ingest.TelemetryValidator;
import com.iot.gas.mqtt.MqttGateway;
import com.iot.gas.mqtt.MqttTopics;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.service.event.DeviceConfigUpdatedEvent;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.DeviceConfigDto;
import com.iot.gas.web.dto.DeviceConfigRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cấu hình tham số hoạt động từ xa (mục 4.4b-5).
 * Gửi bằng bản tin RETAINED QoS 1: thiết bị đang offline sẽ nhận được ngay khi kết nối lại.
 * Mỗi lần đổi tăng configVersion; thiết bị trả config/ack kèm version -> biết chắc thiết bị đã áp dụng bản nào.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigService {

    /** Cấu hình mặc định của gas node. */
    public static final Map<String, Object> DEFAULTS = Map.of(
            "telemetryIntervalSec", 5,
            "gasThreshold", 400.0,
            "autoFan", true);

    private final DeviceRepository devices;
    private final DeviceService deviceService;
    private final MqttGateway gateway;
    private final AuditService audit;
    private final IngestStats stats;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public DeviceConfigDto get(AuthUser user, String code) {
        return toDto(deviceService.loadForView(user, code), true);
    }

    /** Không @Transactional: phải COMMIT version mới trước khi publish, để ACK về sớm vẫn khớp được version. */
    public DeviceConfigDto update(AuthUser user, String code, DeviceConfigRequest req) {
        Device checked = deviceService.loadForManage(user, code);
        if (checked.getLifecycleStatus() == LifecycleStatus.DECOMMISSIONED) {
            throw new ApiException(HttpStatus.CONFLICT, "DECOMMISSIONED", "Thiết bị đã ngừng sử dụng");
        }
        if (req.telemetryIntervalSec() == null && req.gasThreshold() == null && req.autoFan() == null) {
            throw ApiException.badRequest("EMPTY_CONFIG", "Cần ít nhất một tham số cấu hình");
        }

        Device saved = tx.execute(status -> {
            Device d = devices.findById(checked.getId()).orElseThrow();
            Map<String, Object> merged = new LinkedHashMap<>(DEFAULTS);
            merged.putAll(d.getConfig());
            if (req.telemetryIntervalSec() != null) {
                merged.put("telemetryIntervalSec", req.telemetryIntervalSec());
            }
            if (req.gasThreshold() != null) {
                merged.put("gasThreshold", req.gasThreshold());
            }
            if (req.autoFan() != null) {
                merged.put("autoFan", req.autoFan());
            }
            d.setConfig(merged);
            d.setConfigVersion(d.getConfigVersion() + 1);
            d.setConfigStatus(ConfigStatus.PENDING);
            d.setConfigMessage(null);
            return devices.save(d);
        });

        boolean published = publish(saved);
        audit.log(user.id(), user.username(), "DEVICE_CONFIG_UPDATE", "DEVICE", code,
                published ? AuditResult.SUCCESS : AuditResult.FAILED,
                Map.of("version", saved.getConfigVersion(), "config", saved.getConfig()));
        DeviceConfigDto dto = toDto(saved, published);
        events.publishEvent(new DeviceConfigUpdatedEvent(saved.getId(), dto));
        if (!published) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_UNAVAILABLE",
                    "Đã lưu cấu hình v" + saved.getConfigVersion() + " nhưng chưa gửi được tới broker. Hãy lưu lại sau");
        }
        return dto;
    }

    private boolean publish(Device d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configVersion", d.getConfigVersion());
        body.putAll(d.getConfig());
        try {
            gateway.publish(MqttTopics.of(d.getDeviceCode(), MqttTopics.CONFIG), mapper.writeValueAsBytes(body), 1, true);
            return true;
        } catch (Exception e) {
            log.warn("[{}] Không gửi được cấu hình: {}", d.getDeviceCode(), e.getMessage());
            return false;
        }
    }

    // ====================================================================== ACK từ thiết bị
    /** {"configVersion":3,"result":"APPLIED"|"REJECTED","message":"...","applied":{...}} */
    @Transactional
    public void onAck(String code, byte[] payload) {
        if (payload.length > TelemetryValidator.MAX_PAYLOAD_BYTES) {
            reject(code, "PAYLOAD_TOO_LARGE", payload.length + " bytes");
            return;
        }
        Device d = devices.findByDeviceCode(code).orElse(null);
        if (d == null || d.getLifecycleStatus() == LifecycleStatus.DECOMMISSIONED) {
            reject(code, "UNKNOWN_DEVICE", "Thiết bị không tồn tại hoặc đã ngừng sử dụng");
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (IOException e) {
            reject(code, "MALFORMED_JSON", "config/ack không phải JSON");
            return;
        }
        if (root == null || !root.isObject() || !root.path("configVersion").isIntegralNumber()) {
            reject(code, "INVALID_ACK", "Thiếu configVersion");
            return;
        }
        int version = root.path("configVersion").asInt();
        String result = root.path("result").asText("");
        String message = root.path("message").asText("");
        if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        if (version > d.getConfigVersion()) {
            reject(code, "UNKNOWN_VERSION", "Thiết bị báo v" + version + " nhưng backend mới gửi tới v" + d.getConfigVersion());
            return;
        }
        if (version < d.getConfigVersion()) {
            log.info("[{}] ACK cho cấu hình cũ v{} (mới nhất v{}) -> bỏ qua", code, version, d.getConfigVersion());
            return;
        }
        Instant now = Instant.now();
        switch (result) {
            case "APPLIED" -> {
                d.setConfigAppliedVersion(version);
                d.setConfigStatus(ConfigStatus.APPLIED);
                d.setConfigMessage(null);
                log.info("[{}] Đã áp dụng cấu hình v{}", code, version);
            }
            case "REJECTED" -> {
                d.setConfigStatus(ConfigStatus.REJECTED);
                d.setConfigMessage(message.isEmpty() ? "Thiết bị từ chối cấu hình" : message);
                log.warn("[{}] Thiết bị TỪ CHỐI cấu hình v{}: {}", code, version, message);
            }
            default -> {
                reject(code, "INVALID_RESULT", "result phải là APPLIED hoặc REJECTED");
                return;
            }
        }
        d.setConfigAckedAt(now);
        devices.save(d);
        events.publishEvent(new DeviceConfigUpdatedEvent(d.getId(), toDto(d, true)));
    }

    private DeviceConfigDto toDto(Device d, boolean published) {
        Map<String, Object> cfg = new LinkedHashMap<>(DEFAULTS);
        cfg.putAll(d.getConfig());
        return new DeviceConfigDto(d.getDeviceCode(), cfg, d.getConfigVersion(), d.getConfigAppliedVersion(),
                d.getConfigStatus(), d.getConfigMessage(), d.getConfigAckedAt(), published);
    }

    private void reject(String code, String reason, String detail) {
        stats.rejected("config/ack", code, reason, detail);
        log.warn("Từ chối config/ack từ [{}]: {} - {}", code, reason, detail);
    }
}
