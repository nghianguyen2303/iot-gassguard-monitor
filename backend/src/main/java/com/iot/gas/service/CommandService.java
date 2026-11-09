package com.iot.gas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.gas.domain.Command;
import com.iot.gas.domain.Device;
import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.domain.enums.CommandStatus;
import com.iot.gas.domain.enums.Connectivity;
import com.iot.gas.domain.enums.LifecycleStatus;
import com.iot.gas.ingest.ActuatorState;
import com.iot.gas.ingest.IngestStats;
import com.iot.gas.ingest.TelemetryValidator;
import com.iot.gas.mqtt.MqttGateway;
import com.iot.gas.mqtt.MqttTopics;
import com.iot.gas.repository.CommandRepository;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.service.event.CommandUpdatedEvent;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.CommandDto;
import com.iot.gas.web.dto.SendCommandRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Lệnh điều khiển (mục 4.4e): kiểm tra quyền -> lưu lệnh -> gửi MQTT (QoS 1) -> chờ ACK -> lưu kết quả.
 * <pre>
 *  PENDING --publish OK--> SENT --ACK SUCCESS/FAILED--> SUCCESS / FAILED
 *     |                      \--quá 10 s không ACK--> TIMEOUT (ACK đến muộn vẫn được ghi lại)
 *     \--publish lỗi--> FAILED
 * </pre>
 * Lệnh được LƯU VÀ COMMIT trước khi publish: thiết bị có thể trả ACK chỉ sau vài ms, nếu lúc đó lệnh
 * còn chưa commit thì backend sẽ không tìm thấy lệnh để ghi ACK.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommandService {

    public static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_WAIT_MS = 12_000;
    /** Thiết bị nhận lệnh khi đang hoạt động hoặc bảo trì (bảo trì vẫn cần thử cơ cấu chấp hành). */
    private static final Set<LifecycleStatus> CONTROLLABLE = EnumSet.of(LifecycleStatus.ACTIVE, LifecycleStatus.MAINTENANCE);
    private static final Set<String> RESULTS = Set.of("SUCCESS", "FAILED");

    private final CommandRepository commands;
    private final DeviceRepository devices;
    private final UserRepository users;
    private final DeviceService deviceService;
    private final MqttGateway gateway;
    private final AuditService audit;
    private final IngestStats stats;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    // ====================================================================== Gửi lệnh
    /** waitMs > 0: chờ tối đa waitMs (≤ 12 s) để trả về luôn kết quả ACK. */
    public CommandDto send(AuthUser user, String code, SendCommandRequest req, long waitMs) {
        Device device = deviceService.loadForManage(user, code);       // VIEWER / người ngoài -> 403
        Map<String, Object> params = validate(req);
        if (!CONTROLLABLE.contains(device.getLifecycleStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NOT_CONTROLLABLE",
                    "Thiết bị đang " + device.getLifecycleStatus() + ", không nhận lệnh");
        }
        Instant now = Instant.now();
        Command c = new Command();
        c.setId(UUID.randomUUID());
        c.setDeviceId(device.getId());
        c.setAction(req.action());
        c.setParams(params);
        c.setRequestedBy(user.id());
        c.setCreatedAt(now);

        if (device.getConnectivity() == Connectivity.OFFLINE) {
            // Vẫn lưu lại lần thử để có lịch sử, nhưng không gửi (lệnh QoS1 không retained sẽ mất khi thiết bị offline)
            c.setStatus(CommandStatus.FAILED);
            c.setResultMessage("Thiết bị đang OFFLINE, lệnh không được gửi");
            commands.save(c);
            audit.log(user.id(), user.username(), "COMMAND_SEND", "DEVICE", code, AuditResult.FAILED,
                    Map.of("action", req.action(), "reason", "DEVICE_OFFLINE"));
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_OFFLINE", "Thiết bị đang OFFLINE, không thể gửi lệnh");
        }

        c.setStatus(CommandStatus.PENDING);
        c.setSentAt(now);
        commands.save(c);                                                // commit ngay (không có @Transactional)

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("commandId", c.getId().toString());
            body.put("action", c.getAction());
            body.put("params", params);
            body.put("ts", now.toEpochMilli());
            gateway.publish(MqttTopics.of(code, MqttTopics.CMD), mapper.writeValueAsBytes(body), 1, false);
            commands.transition(c.getId(), CommandStatus.PENDING, CommandStatus.SENT, null);
        } catch (Exception e) {
            commands.transition(c.getId(), CommandStatus.PENDING, CommandStatus.FAILED,
                    "Không gửi được tới broker: " + e.getMessage());
            log.warn("[{}] Gửi lệnh {} thất bại: {}", code, c.getAction(), e.getMessage());
        }
        audit.log(user.id(), user.username(), "COMMAND_SEND", "DEVICE", code, AuditResult.SUCCESS,
                Map.of("commandId", c.getId().toString(), "action", c.getAction(), "params", params));
        CommandDto dto = load(c.getId(), code);
        events.publishEvent(new CommandUpdatedEvent(device.getId(), dto));

        long deadline = System.currentTimeMillis() + Math.min(Math.max(waitMs, 0), MAX_WAIT_MS);
        while (isWaiting(dto.status()) && System.currentTimeMillis() < deadline) {
            sleep(100);
            dto = load(c.getId(), code);
        }
        return dto;
    }

    /**
     * Danh sách lệnh hợp lệ và tham số bắt buộc. Thiết bị cũng tự kiểm tra lại (phòng thủ 2 lớp).
     *   FAN_SET {on} / BUZZER_SET {on}: người dùng giữ quạt / còi BẬT hoặc TẮT (chế độ MANUAL, kể cả khi có báo động)
     *   FAN_AUTO / BUZZER_AUTO       : trả về tự động theo báo động gas / quá nhiệt
     */
    private Map<String, Object> validate(SendCommandRequest req) {
        Map<String, Object> params = req.params() == null ? Map.of() : req.params();
        switch (req.action()) {
            case "FAN_SET", "BUZZER_SET" -> {
                if (params.size() != 1 || !(params.get("on") instanceof Boolean)) {
                    throw ApiException.badRequest("INVALID_PARAMS",
                            req.action() + " cần đúng 1 tham số: {\"on\": true|false}");
                }
            }
            case "FAN_AUTO", "BUZZER_AUTO", "PING" -> {
                if (!params.isEmpty()) {
                    throw ApiException.badRequest("INVALID_PARAMS", req.action() + " không có tham số");
                }
            }
            default -> throw ApiException.badRequest("UNSUPPORTED_ACTION", "Lệnh không được hỗ trợ: " + req.action()
                    + ". Hỗ trợ: FAN_SET, FAN_AUTO, BUZZER_SET, BUZZER_AUTO, PING");
        }
        return new LinkedHashMap<>(params);
    }

    // ====================================================================== Đọc
    @Transactional(readOnly = true)
    public List<CommandDto> history(AuthUser user, String code, int limit) {
        Device device = deviceService.loadForView(user, code);
        List<Command> list = commands.findByDeviceIdOrderByCreatedAtDesc(device.getId(),
                PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
        Map<Long, String> names = new HashMap<>();
        users.findAllById(list.stream().map(Command::getRequestedBy).distinct().toList())
                .forEach(u -> names.put(u.getId(), u.getUsername()));
        return list.stream().map(c -> CommandDto.from(c, code, names.get(c.getRequestedBy()))).toList();
    }

    @Transactional(readOnly = true)
    public CommandDto get(AuthUser user, String code, UUID id) {
        Device device = deviceService.loadForView(user, code);
        Command c = commands.findById(id).filter(x -> x.getDeviceId().equals(device.getId()))
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy lệnh"));
        return CommandDto.from(c, code, users.findById(c.getRequestedBy()).map(User::getUsername).orElse(null));
    }

    // ====================================================================== ACK từ thiết bị
    /** {"commandId","result":"SUCCESS"|"FAILED","message","state":{"fan":true},"ts"} trên topic .../cmd/ack */
    @Transactional
    public void onAck(String code, byte[] payload) {
        if (payload.length > TelemetryValidator.MAX_PAYLOAD_BYTES) {
            reject(code, "PAYLOAD_TOO_LARGE", payload.length + " bytes");
            return;
        }
        Device device = devices.findByDeviceCode(code).orElse(null);
        if (device == null || device.getLifecycleStatus() == LifecycleStatus.DECOMMISSIONED) {
            reject(code, "UNKNOWN_DEVICE", "Thiết bị không tồn tại hoặc đã ngừng sử dụng");
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (IOException e) {
            reject(code, "MALFORMED_JSON", "ACK không phải JSON");
            return;
        }
        if (root == null || !root.isObject()) {
            reject(code, "NOT_OBJECT", "ACK phải là JSON object");
            return;
        }
        // Chỉ chính thiết bị mới publish được lên .../{code}/cmd/ack (ACL broker); nếu có deviceId thì phải khớp
        if (root.has("deviceId") && !code.equals(root.path("deviceId").asText())) {
            reject(code, "DEVICE_ID_MISMATCH", "deviceId trong ACK không khớp topic");
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(root.path("commandId").asText(""));
        } catch (IllegalArgumentException e) {
            reject(code, "INVALID_COMMAND_ID", "commandId phải là UUID");
            return;
        }
        String result = root.path("result").asText("");
        if (!RESULTS.contains(result)) {
            reject(code, "INVALID_RESULT", "result phải là SUCCESS hoặc FAILED");
            return;
        }
        Command c = commands.findById(id).orElse(null);
        if (c == null) {
            reject(code, "UNKNOWN_COMMAND", "Không có lệnh " + id);
            return;
        }
        if (!c.getDeviceId().equals(device.getId())) {
            reject(code, "COMMAND_DEVICE_MISMATCH", "Lệnh " + id + " không thuộc thiết bị này");
            return;
        }
        String message = truncate(root.path("message").asText(""), 200);
        Map<String, Object> state = sanitizeState(root.get("state"));
        Instant now = Instant.now();

        switch (c.getStatus()) {
            case PENDING, SENT -> {
                c.setStatus(CommandStatus.valueOf(result));
                c.setAckedAt(now);
                c.setResultMessage(message.isEmpty() ? result : message);
                c.setResultState(state);
                log.info("[{}] ACK lệnh {} {} -> {} ({} ms)", code, c.getAction(), id.toString().substring(0, 8),
                        result, Duration.between(c.getSentAt(), now).toMillis());
            }
            case TIMEOUT -> {
                // ACK đến muộn: giữ TIMEOUT để thống kê đúng, nhưng ghi nhận thiết bị đã thực hiện
                c.setAckedAt(now);
                c.setResultMessage(truncate("ACK đến muộn (" + result + "): " + message, 255));
                c.setResultState(state);
                log.info("[{}] ACK muộn cho lệnh {} đã TIMEOUT", code, id.toString().substring(0, 8));
            }
            default -> {
                log.debug("[{}] ACK trùng cho lệnh {} -> bỏ qua", code, id);   // QoS1 có thể giao lại ACK
                return;
            }
        }
        if ("SUCCESS".equals(result) && state != null && !state.isEmpty()) {
            try {
                devices.mergeActuatorState(device.getId(), mapper.writeValueAsString(state));
            } catch (Exception e) {
                log.debug("Không cập nhật được actuator state: {}", e.getMessage());
            }
        }
        commands.save(c);
        events.publishEvent(new CommandUpdatedEvent(device.getId(),
                CommandDto.from(c, code, users.findById(c.getRequestedBy()).map(User::getUsername).orElse(null))));
    }

    // ====================================================================== Hết hạn chờ ACK
    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void expireCommands() {
        Instant cutoff = Instant.now().minus(ACK_TIMEOUT);
        for (Command c : commands.findByStatusInAndSentAtBefore(
                List.of(CommandStatus.PENDING, CommandStatus.SENT), cutoff)) {
            String msg = "Không nhận được ACK sau " + ACK_TIMEOUT.toSeconds() + " giây";
            if (commands.transition(c.getId(), c.getStatus(), CommandStatus.TIMEOUT, msg) == 1) {
                String code = devices.findById(c.getDeviceId()).map(Device::getDeviceCode).orElse("?");
                log.warn("[{}] Lệnh {} {} -> TIMEOUT", code, c.getAction(), c.getId().toString().substring(0, 8));
                c.setStatus(CommandStatus.TIMEOUT);
                c.setResultMessage(msg);
                events.publishEvent(new CommandUpdatedEvent(c.getDeviceId(),
                        CommandDto.from(c, code, users.findById(c.getRequestedBy()).map(User::getUsername).orElse(null))));
            }
        }
    }

    // ====================================================================== Tiện ích
    private CommandDto load(UUID id, String code) {
        Command c = commands.findById(id).orElseThrow();
        return CommandDto.from(c, code, users.findById(c.getRequestedBy()).map(User::getUsername).orElse(null));
    }

    private static boolean isWaiting(CommandStatus s) {
        return s == CommandStatus.PENDING || s == CommandStatus.SENT;
    }

    /** Chỉ giữ trạng thái đã biết, đúng kiểu (xem ActuatorState) -> không cho thiết bị ghi dữ liệu tùy ý vào DB. */
    private static Map<String, Object> sanitizeState(JsonNode state) {
        if (state == null || !state.isObject()) {
            return null;
        }
        return ActuatorState.sanitize(state);
    }

    private void reject(String code, String reason, String detail) {
        stats.rejected("cmd/ack", code, reason, detail);
        log.warn("Từ chối cmd/ack từ [{}]: {} - {}", code, reason, detail);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
