package com.iot.gas.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.domain.enums.Connectivity;
import com.iot.gas.domain.enums.LifecycleStatus;
import com.iot.gas.ingest.event.ActuatorStateChangedEvent;
import com.iot.gas.ingest.event.ConnectivityChangedEvent;
import com.iot.gas.ingest.event.DeviceEventReceivedEvent;
import com.iot.gas.ingest.event.TelemetryReceivedEvent;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.repository.TelemetryRepository;
import com.iot.gas.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Xử lý bản tin thiết bị gửi lên (thiết bị -> backend).
 * Thứ tự kiểm tra cho mọi bản tin: kích thước -> thiết bị có tồn tại -> trạng thái vòng đời cho phép -> JSON hợp lệ
 * -> deviceId khớp topic -> nội dung hợp lệ. Sai ở bước nào thì từ chối ở bước đó và ghi lý do.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestService {

    /** Chỉ nhận dữ liệu khi thiết bị đã được cấp quyền và chưa bị khóa/ngừng sử dụng. */
    static final Set<LifecycleStatus> ACCEPTING_DATA =
            EnumSet.of(LifecycleStatus.PROVISIONED, LifecycleStatus.ACTIVE, LifecycleStatus.MAINTENANCE);

    private static final Duration BLOCKED_AUDIT_INTERVAL = Duration.ofMinutes(1);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DeviceRepository devices;
    private final TelemetryRepository telemetry;
    private final TelemetryValidator validator;
    private final IngestStats stats;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final ObjectMapper mapper;

    /** Chống spam audit: mỗi thiết bị bị chặn chỉ ghi audit tối đa 1 lần/phút. */
    private final Map<Long, Instant> lastBlockedAudit = new ConcurrentHashMap<>();

    // ====================================================================== TELEMETRY
    @Transactional
    public void onTelemetry(String code, byte[] payload) {
        Instant now = Instant.now();
        stats.telemetryReceived();
        Device device = acceptDevice("telemetry", code, payload);
        if (device == null) {
            return;
        }
        JsonNode root = parse("telemetry", code, payload);
        if (root == null) {
            return;
        }
        TelemetryValidator.Result r = validator.validate(code, root, now);
        if (!r.ok()) {
            reject("telemetry", code, r.reason(), r.detail());
            return;
        }

        List<Reading> stored = new ArrayList<>();
        int duplicates = 0;
        for (Reading reading : r.readings()) {
            int inserted = telemetry.insertIgnoreDuplicate(device.getId(), reading.metric(), reading.value(),
                    reading.unit(), r.seq(), r.ts(), now);
            if (inserted == 1) {
                stored.add(reading);
            } else {
                duplicates++;
            }
        }
        stats.telemetryAccepted(stored.size(), duplicates);
        if (stored.isEmpty()) {
            log.debug("[{}] telemetry seq={} trùng -> bỏ qua", code, r.seq());
            return;
        }
        stats.recordLatency(Duration.between(r.ts(), now).toMillis());

        markAlive(device, now, "TELEMETRY");
        if (r.fw() != null) {
            devices.updateFirmware(device.getId(), r.fw());
        }
        events.publishEvent(new TelemetryReceivedEvent(device.getId(), code, List.copyOf(stored), r.ts(), now));
    }

    // ====================================================================== HEARTBEAT
    /** {"deviceId","ts","uptimeSec","rssi","fw","state":{"fan":true}} */
    @Transactional
    public void onHeartbeat(String code, byte[] payload) {
        Instant now = Instant.now();
        Device device = acceptDevice("heartbeat", code, payload);
        if (device == null) {
            return;
        }
        JsonNode root = parse("heartbeat", code, payload);
        if (root == null || !checkDeviceId("heartbeat", code, root)) {
            return;
        }
        markAlive(device, now, "HEARTBEAT");
        updateVersions(device, root);
        updateActuatorState(device, root.get("state"));
    }

    // ====================================================================== STATUS / LWT
    /** {"deviceId","state":"ONLINE"|"OFFLINE","reason":"LWT"|"SHUTDOWN","fw","hw"} - retained. */
    @Transactional
    public void onStatus(String code, byte[] payload, boolean retained) {
        Instant now = Instant.now();
        if (payload.length == 0) {
            return;                                     // retained status bị xóa
        }
        if (payload.length > TelemetryValidator.MAX_PAYLOAD_BYTES) {
            reject("status", code, "PAYLOAD_TOO_LARGE", payload.length + " bytes");
            return;
        }
        Device device = devices.findByDeviceCode(code).orElse(null);
        if (device == null) {
            reject("status", code, "UNKNOWN_DEVICE", "Thiết bị chưa đăng ký");
            return;
        }
        JsonNode root = parse("status", code, payload);
        if (root == null || !checkDeviceId("status", code, root)) {
            return;
        }
        String state = root.path("state").asText("");
        String reason = root.path("reason").asText("");
        switch (state) {
            case "OFFLINE" -> {
                // Luôn chấp nhận OFFLINE (kể cả thiết bị bị khóa): broker phát LWT khi ngắt thiết bị bị thu hồi.
                Connectivity from = device.getConnectivity();
                if (from != Connectivity.OFFLINE
                        && devices.setConnectivity(device.getId(), Connectivity.OFFLINE, now) == 1) {
                    String why = reason.isBlank() ? "STATUS_OFFLINE" : reason;
                    log.info("[{}] {} -> OFFLINE ({})", code, from, why);
                    events.publishEvent(new ConnectivityChangedEvent(device.getId(), code, from,
                            Connectivity.OFFLINE, why, now));
                }
            }
            case "ONLINE" -> {
                if (!ACCEPTING_DATA.contains(device.getLifecycleStatus())) {
                    blocked("status", device);
                    return;
                }
                // Bản tin retained có thể đã cũ (gửi từ trước khi backend chạy) -> không coi là bằng chứng
                // thiết bị đang sống; watchdog (Bước 9) sẽ xác định dựa trên heartbeat thật.
                if (!retained) {
                    markAlive(device, now, "STATUS_ONLINE");
                }
                updateVersions(device, root);
            }
            default -> reject("status", code, "INVALID_STATE", "state phải là ONLINE hoặc OFFLINE");
        }
    }

    // ====================================================================== EVENT
    /** {"deviceId","ts","type":"SENSOR_FAULT"|"SENSOR_OK"|"AUTO_FAN_ON"|"AUTO_FAN_OFF", ...} */
    @Transactional
    public void onEvent(String code, byte[] payload) {
        Instant now = Instant.now();
        Device device = acceptDevice("event", code, payload);
        if (device == null) {
            return;
        }
        JsonNode root = parse("event", code, payload);
        if (root == null || !checkDeviceId("event", code, root)) {
            return;
        }
        String type = root.path("type").asText("");
        if (!type.matches("^[A-Z][A-Z0-9_]{1,39}$")) {
            reject("event", code, "INVALID_TYPE", "type phải là chữ IN HOA, vd. SENSOR_FAULT");
            return;
        }
        devices.touchLastSeen(device.getId(), now);
        switch (type) {
            case "SENSOR_FAULT" -> {
                Connectivity from = device.getConnectivity();
                if (devices.setConnectivity(device.getId(), Connectivity.FAULT, now) == 1) {
                    log.warn("[{}] {} -> FAULT: {}", code, from, root.path("message").asText());
                    events.publishEvent(new ConnectivityChangedEvent(device.getId(), code, from,
                            Connectivity.FAULT, "SENSOR_FAULT", now));
                }
            }
            case "SENSOR_OK" -> {
                if (devices.transitionConnectivity(device.getId(), Connectivity.FAULT, Connectivity.ONLINE, now) == 1) {
                    log.info("[{}] FAULT -> ONLINE (cảm biến hoạt động lại)", code);
                    events.publishEvent(new ConnectivityChangedEvent(device.getId(), code, Connectivity.FAULT,
                            Connectivity.ONLINE, "SENSOR_OK", now));
                }
            }
            case "AUTO_FAN_ON" -> devices.mergeActuatorState(device.getId(), "{\"fan\":true}");
            case "AUTO_FAN_OFF" -> devices.mergeActuatorState(device.getId(), "{\"fan\":false}");
            default -> log.info("[{}] event {}", code, type);
        }
        Map<String, Object> body;
        try {
            body = mapper.convertValue(root, MAP_TYPE);
        } catch (IllegalArgumentException e) {
            body = Map.of();
        }
        events.publishEvent(new DeviceEventReceivedEvent(device.getId(), code, type, body, now));
    }

    // ====================================================================== Hàm dùng chung
    /** Kiểm tra kích thước, thiết bị tồn tại và được phép gửi dữ liệu. Trả null nếu từ chối. */
    private Device acceptDevice(String channel, String code, byte[] payload) {
        if (payload.length > TelemetryValidator.MAX_PAYLOAD_BYTES) {
            reject(channel, code, "PAYLOAD_TOO_LARGE",
                    payload.length + " bytes > " + TelemetryValidator.MAX_PAYLOAD_BYTES);
            return null;
        }
        Device device = devices.findByDeviceCode(code).orElse(null);
        if (device == null) {
            reject(channel, code, "UNKNOWN_DEVICE", "Thiết bị chưa đăng ký trong hệ thống");
            return null;
        }
        if (!ACCEPTING_DATA.contains(device.getLifecycleStatus())) {
            blocked(channel, device);
            return null;
        }
        return device;
    }

    private JsonNode parse(String channel, String code, byte[] payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isObject()) {
                reject(channel, code, "NOT_OBJECT", "Payload phải là JSON object");
                return null;
            }
            return root;
        } catch (IOException e) {
            reject(channel, code, "MALFORMED_JSON", "Payload không phải JSON hợp lệ");
            return null;
        }
    }

    private boolean checkDeviceId(String channel, String code, JsonNode root) {
        JsonNode id = root.get("deviceId");
        if (id == null || !id.isTextual() || !id.asText().equals(code)) {
            reject(channel, code, "DEVICE_ID_MISMATCH", "deviceId trong payload phải trùng với topic");
            return false;
        }
        return true;
    }

    /** Thiết bị có dấu hiệu sống: cập nhật lastSeen, OFFLINE->ONLINE, PROVISIONED->ACTIVE (lần đầu). */
    private void markAlive(Device device, Instant now, String reason) {
        devices.touchLastSeen(device.getId(), now);
        // Kiểm tra trạng thái đã đọc trước rồi mới UPDATE: tránh 2 câu UPDATE thừa cho mỗi bản tin
        if (device.getConnectivity() == Connectivity.OFFLINE
                && devices.transitionConnectivity(device.getId(), Connectivity.OFFLINE, Connectivity.ONLINE, now) == 1) {
            log.info("[{}] OFFLINE -> ONLINE ({})", device.getDeviceCode(), reason);
            events.publishEvent(new ConnectivityChangedEvent(device.getId(), device.getDeviceCode(),
                    Connectivity.OFFLINE, Connectivity.ONLINE, reason, now));
        }
        if (device.getLifecycleStatus() == LifecycleStatus.PROVISIONED
                && devices.transitionLifecycle(device.getId(), LifecycleStatus.PROVISIONED, LifecycleStatus.ACTIVE, now) == 1) {
            log.info("[{}] PROVISIONED -> ACTIVE (nhận dữ liệu đầu tiên)", device.getDeviceCode());
            audit.log(null, "system", "DEVICE_AUTO_ACTIVATED", "DEVICE", device.getDeviceCode(),
                    AuditResult.SUCCESS, Map.of("trigger", reason));
        }
    }

    private void updateVersions(Device device, JsonNode root) {
        String fw = root.path("fw").asText("");
        if (!fw.isBlank() && fw.length() <= 30) {
            devices.updateFirmware(device.getId(), fw);
        }
        String hw = root.path("hw").asText("");
        if (!hw.isBlank() && hw.length() <= 30) {
            devices.updateHardware(device.getId(), hw);
        }
    }

    /** Chỉ chấp nhận khóa đã biết, đúng kiểu (xem ActuatorState) -> không cho thiết bị ghi dữ liệu tùy ý vào DB. */
    private void updateActuatorState(Device device, JsonNode state) {
        Map<String, Object> clean = ActuatorState.sanitize(state);
        if (clean.isEmpty()) {
            return;
        }
        Map<String, Object> current = device.getActuatorState() == null ? Map.of() : device.getActuatorState();
        boolean changed = clean.entrySet().stream().anyMatch(en -> !en.getValue().equals(current.get(en.getKey())));
        if (!changed) {
            return;
        }
        try {
            devices.mergeActuatorState(device.getId(), mapper.writeValueAsString(clean));
        } catch (JsonProcessingException e) {
            log.debug("Không ghi được actuator state: {}", e.getMessage());
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(current);
        merged.putAll(clean);
        events.publishEvent(new ActuatorStateChangedEvent(device.getId(), device.getDeviceCode(), merged));
    }

    private void blocked(String channel, Device device) {
        reject(channel, device.getDeviceCode(), "DEVICE_NOT_ACCEPTING",
                "Thiết bị đang ở trạng thái " + device.getLifecycleStatus());
        Instant now = Instant.now();
        Instant last = lastBlockedAudit.get(device.getId());
        if (last == null || last.plus(BLOCKED_AUDIT_INTERVAL).isBefore(now)) {
            lastBlockedAudit.put(device.getId(), now);
            audit.log(null, "system", "DEVICE_DATA_REJECTED", "DEVICE", device.getDeviceCode(),
                    AuditResult.DENIED, Map.of("channel", channel, "lifecycle", device.getLifecycleStatus().name()));
        }
    }

    private void reject(String channel, String code, String reason, String detail) {
        stats.rejected(channel, code, reason, detail);
        log.warn("Từ chối {} từ [{}]: {} - {}", channel, code, reason, detail);
    }
}
