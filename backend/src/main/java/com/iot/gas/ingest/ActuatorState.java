package com.iot.gas.ingest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Trạng thái cơ cấu chấp hành thiết bị được phép báo về. Chỉ nhận khóa đã biết, đúng kiểu
 * -> thiết bị không ghi được dữ liệu tùy ý vào DB.
 *   fan / buzzer        : boolean – quạt đang chạy / còi đang kêu (báo động)
 *   fanMode / buzzerMode: "AUTO" (theo báo động) | "MANUAL" (người dùng giữ bật/tắt)
 */
public final class ActuatorState {
    private static final Set<String> BOOL_KEYS = Set.of("fan", "buzzer");
    private static final Set<String> MODE_KEYS = Set.of("fanMode", "buzzerMode");
    private static final Set<String> MODES = Set.of("AUTO", "MANUAL");

    private ActuatorState() {
    }

    public static Map<String, Object> sanitize(JsonNode state) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (state == null || !state.isObject()) {
            return out;
        }
        for (String k : BOOL_KEYS) {
            JsonNode v = state.get(k);
            if (v != null && v.isBoolean()) {
                out.put(k, v.asBoolean());
            }
        }
        for (String k : MODE_KEYS) {
            JsonNode v = state.get(k);
            if (v != null && v.isTextual() && MODES.contains(v.asText())) {
                out.put(k, v.asText());
            }
        }
        return out;
    }
}
