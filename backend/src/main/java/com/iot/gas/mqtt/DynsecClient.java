package com.iot.gas.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Điều khiển plugin Dynamic Security của Mosquitto qua MQTT (topic $CONTROL/dynamic-security/v1).
 * Mỗi thiết bị có: 1 client (username = clientId = deviceId, mật khẩu riêng) + 1 role "dev-{deviceId}"
 * chỉ cho publish 6 topic và subscribe 2 topic của CHÍNH nó (mục 4.5: thông tin xác thực riêng, ACL theo topic).
 * Thay đổi có hiệu lực ngay khi broker đang chạy: khóa/xóa client -> broker ngắt kết nối thiết bị lập tức.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DynsecClient {

    static final String REQUEST_TOPIC = "$CONTROL/dynamic-security/v1";
    static final String RESPONSE_TOPIC = REQUEST_TOPIC + "/response";
    static final long TIMEOUT_SECONDS = 5;

    static final List<String> DEVICE_PUBLISH = List.of(
            MqttTopics.TELEMETRY, MqttTopics.HEARTBEAT, MqttTopics.STATUS,
            MqttTopics.EVENT, MqttTopics.CMD_ACK, MqttTopics.CONFIG_ACK);
    static final List<String> DEVICE_SUBSCRIBE = List.of(MqttTopics.CMD, MqttTopics.CONFIG);

    private final MqttGateway gateway;
    private final ObjectMapper mapper;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        gateway.subscribe(RESPONSE_TOPIC, 1, this::onResponse);
    }

    public static String roleName(String deviceCode) {
        return "dev-" + deviceCode;
    }

    // ------------------------------------------------------------------ Thao tác mức cao
    /**
     * Tạo (hoặc tạo lại) tài khoản MQTT cho thiết bị. Idempotent: xóa client/role cũ nếu có rồi tạo mới,
     * nên dùng được cho cả cấp lần đầu lẫn đổi mật khẩu. Xóa client cũ cũng ngắt phiên đang kết nối
     * -> thiết bị buộc phải đăng nhập lại bằng mật khẩu mới.
     */
    public void createDeviceAccount(String deviceCode, String password, boolean disabled) {
        List<ObjectNode> cmds = new ArrayList<>();
        cmds.add(cmd("deleteClient").put("username", deviceCode));
        cmds.add(cmd("deleteRole").put("rolename", roleName(deviceCode)));

        ObjectNode createRole = cmd("createRole").put("rolename", roleName(deviceCode))
                .put("textdescription", "ACL cho thiết bị " + deviceCode);
        ArrayNode acls = createRole.putArray("acls");
        for (String ch : DEVICE_PUBLISH) {
            acls.addObject().put("acltype", "publishClientSend")
                    .put("topic", MqttTopics.of(deviceCode, ch)).put("priority", 0).put("allow", true);
        }
        for (String ch : DEVICE_SUBSCRIBE) {
            acls.addObject().put("acltype", "subscribeLiteral")
                    .put("topic", MqttTopics.of(deviceCode, ch)).put("priority", 0).put("allow", true);
        }
        cmds.add(createRole);

        ObjectNode createClient = cmd("createClient").put("username", deviceCode)
                .put("password", password).put("clientid", deviceCode);
        createClient.putArray("roles").addObject().put("rolename", roleName(deviceCode)).put("priority", -1);
        cmds.add(createClient);
        if (disabled) {
            cmds.add(cmd("disableClient").put("username", deviceCode));
        }

        List<JsonNode> res = execute(cmds);
        // Lệnh 0, 1 (xóa) được phép báo "not found"; các lệnh còn lại phải thành công
        requireOk(res.subList(2, res.size()));
        log.info("Dynsec: đã cấp tài khoản MQTT cho '{}'{}", deviceCode, disabled ? " (đang khóa)" : "");
    }

    /** Khóa: broker ngắt kết nối ngay và từ chối mọi lần kết nối lại. */
    public void disableDevice(String deviceCode) {
        JsonNode r = execute(List.of(cmd("disableClient").put("username", deviceCode))).get(0);
        if (hasError(r) && !isNotFound(r)) {
            throw new DynsecException("Không khóa được thiết bị trên broker: " + r.path("error").asText());
        }
        log.info("Dynsec: đã khóa '{}'", deviceCode);
    }

    public void enableDevice(String deviceCode) {
        requireOk(execute(List.of(cmd("enableClient").put("username", deviceCode))));
        log.info("Dynsec: đã mở khóa '{}'", deviceCode);
    }

    /** Thu hồi vĩnh viễn: xóa client + role. */
    public void deleteDeviceAccount(String deviceCode) {
        List<JsonNode> res = execute(List.of(
                cmd("deleteClient").put("username", deviceCode),
                cmd("deleteRole").put("rolename", roleName(deviceCode))));
        for (JsonNode r : res) {
            if (hasError(r) && !isNotFound(r)) {
                throw new DynsecException("Không thu hồi được tài khoản MQTT: " + r.path("error").asText());
            }
        }
        log.info("Dynsec: đã xóa tài khoản MQTT của '{}'", deviceCode);
    }

    // ------------------------------------------------------------------ Gửi lệnh và chờ phản hồi
    /** Gửi một lô lệnh trong 1 bản tin; trả về các phản hồi theo đúng thứ tự lệnh. */
    public List<JsonNode> execute(List<ObjectNode> commands) {
        if (!gateway.isConnected()) {
            throw new DynsecException("Backend chưa kết nối được MQTT broker");
        }
        String batch = UUID.randomUUID().toString();
        List<String> keys = new ArrayList<>();
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("commands");
        for (int i = 0; i < commands.size(); i++) {
            String key = batch + ":" + i;
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            pending.put(key, f);
            keys.add(key);
            futures.add(f);
            arr.add(commands.get(i).put("correlationData", key));
        }
        try {
            gateway.publish(REQUEST_TOPIC, mapper.writeValueAsBytes(body), 1, false);
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new DynsecException("Broker không phản hồi lệnh Dynamic Security sau " + TIMEOUT_SECONDS
                    + " s (kiểm tra role 'backend' có ACL publishClientReceive $CONTROL/dynamic-security/#)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DynsecException("Bị gián đoạn khi chờ broker");
        } catch (Exception e) {
            throw new DynsecException("Không gửi được lệnh tới broker: " + e.getMessage());
        } finally {
            keys.forEach(pending::remove);
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    void onResponse(String topic, MqttMessage message) {
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            for (JsonNode r : root.path("responses")) {
                String key = r.path("correlationData").asText(null);
                CompletableFuture<JsonNode> f = key == null ? null : pending.get(key);
                if (f != null) {
                    f.complete(r);
                }
            }
        } catch (IOException e) {
            log.warn("Phản hồi Dynamic Security không đọc được: {}", e.getMessage());
        }
    }

    private ObjectNode cmd(String name) {
        return mapper.createObjectNode().put("command", name);
    }

    private static boolean hasError(JsonNode r) {
        return r.hasNonNull("error");
    }

    private static boolean isNotFound(JsonNode r) {
        return r.path("error").asText("").toLowerCase().contains("not found");
    }

    private static void requireOk(List<JsonNode> responses) {
        for (JsonNode r : responses) {
            if (hasError(r)) {
                throw new DynsecException("Broker từ chối lệnh " + r.path("command").asText()
                        + ": " + r.path("error").asText());
            }
        }
    }
}
