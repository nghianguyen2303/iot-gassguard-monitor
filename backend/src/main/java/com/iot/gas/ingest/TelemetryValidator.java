package com.iot.gas.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kiểm tra tính hợp lệ của telemetry (mục 4.4c "kiểm tra tính hợp lệ", 4.5 "kiểm tra và làm sạch dữ liệu đầu vào").
 * Định dạng hợp lệ:
 * <pre>{"deviceId":"esp32-001","seq":12,"ts":1762345678000,"fw":"1.0.0",
 *  "gas":{"value":412,"unit":"ppm"},"temp":{"value":29.5,"unit":"C"}}</pre>
 * Bản tin sai bất kỳ quy tắc nào -> từ chối CẢ bản tin (không lưu một phần).
 */
@Component
public class TelemetryValidator {

    public static final int MAX_PAYLOAD_BYTES = 4096;

    /** ts trước 2024-01-01 nghĩa là thiết bị chưa đồng bộ NTP (đồng hồ ESP32 bắt đầu từ 1970). */
    static final long MIN_VALID_TS = 1_704_067_200_000L;
    /** Cho phép đồng hồ thiết bị lệch tối đa 2 phút về tương lai. */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(2);
    /** Chấp nhận dữ liệu gửi bù (thiết bị lưu tạm khi mất mạng) trong tối đa 7 ngày. */
    static final Duration MAX_AGE = Duration.ofDays(7);

    static final Set<String> ALLOWED_FIELDS = Set.of("deviceId", "seq", "ts", "fw", "gas", "temp");

    /** Chỉ số được phép: đơn vị bắt buộc + dải đo vật lý của cảm biến. */
    static final Map<String, MetricSpec> METRICS = Map.of(
            "gas", new MetricSpec("ppm", 0, 10_000),       // MQ-2: 300-10000 ppm, cho phép từ 0
            "temp", new MetricSpec("C", -55, 125));        // DS18B20: -55..125 °C

    record MetricSpec(String unit, double min, double max) {}

    public Result validate(String topicDeviceCode, JsonNode root, Instant now) {
        if (root == null || !root.isObject()) {
            return Result.reject("NOT_OBJECT", "Payload phải là JSON object");
        }
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!ALLOWED_FIELDS.contains(field)) {
                return Result.reject("UNKNOWN_FIELD", "Trường không được phép: " + field);
            }
        }

        JsonNode idNode = root.get("deviceId");
        if (idNode == null || !idNode.isTextual() || !idNode.asText().equals(topicDeviceCode)) {
            return Result.reject("DEVICE_ID_MISMATCH",
                    "deviceId trong payload phải trùng với topic (" + topicDeviceCode + ")");
        }

        JsonNode tsNode = root.get("ts");
        if (tsNode == null || tsNode.isNull()) {
            return Result.reject("MISSING_TS", "Thiếu timestamp 'ts' (epoch milliseconds)");
        }
        if (!tsNode.isIntegralNumber() || !tsNode.canConvertToLong()) {
            return Result.reject("INVALID_TS", "'ts' phải là số nguyên epoch milliseconds");
        }
        long tsMs = tsNode.asLong();
        if (tsMs < MIN_VALID_TS) {
            return Result.reject("TS_NOT_SYNCED", "Timestamp " + tsMs + " trước 2024 -> thiết bị chưa đồng bộ NTP");
        }
        Instant ts = Instant.ofEpochMilli(tsMs);
        if (ts.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            return Result.reject("TS_IN_FUTURE", "Timestamp ở tương lai: " + ts);
        }
        if (ts.isBefore(now.minus(MAX_AGE))) {
            return Result.reject("TS_TOO_OLD", "Timestamp cũ hơn 7 ngày: " + ts);
        }

        Long seq = null;
        JsonNode seqNode = root.get("seq");
        if (seqNode != null && !seqNode.isNull()) {
            if (!seqNode.isIntegralNumber() || !seqNode.canConvertToLong() || seqNode.asLong() < 0) {
                return Result.reject("INVALID_SEQ", "'seq' phải là số nguyên >= 0");
            }
            seq = seqNode.asLong();
        }

        String fw = null;
        JsonNode fwNode = root.get("fw");
        if (fwNode != null && !fwNode.isNull()) {
            if (!fwNode.isTextual() || fwNode.asText().isBlank() || fwNode.asText().length() > 30) {
                return Result.reject("INVALID_FW", "'fw' phải là chuỗi 1-30 ký tự");
            }
            fw = fwNode.asText();
        }

        List<Reading> readings = new ArrayList<>();
        for (Map.Entry<String, MetricSpec> e : METRICS.entrySet()) {
            JsonNode m = root.get(e.getKey());
            if (m == null || m.isNull()) {
                continue;
            }
            String metric = e.getKey();
            MetricSpec spec = e.getValue();
            if (!m.isObject() || !m.has("value") || !m.has("unit")) {
                return Result.reject("INVALID_METRIC", "'" + metric + "' phải có dạng {\"value\":..., \"unit\":\"" + spec.unit() + "\"}");
            }
            JsonNode v = m.get("value");
            if (!v.isNumber() || !Double.isFinite(v.asDouble())) {
                return Result.reject("INVALID_METRIC", "'" + metric + ".value' phải là số");
            }
            String unit = m.get("unit").asText();
            if (!spec.unit().equals(unit)) {
                return Result.reject("WRONG_UNIT", "'" + metric + "' phải dùng đơn vị '" + spec.unit() + "', nhận '" + unit + "'");
            }
            double value = v.asDouble();
            if (value < spec.min() || value > spec.max()) {
                return Result.reject("OUT_OF_RANGE",
                        "'" + metric + "' = " + value + " ngoài dải " + spec.min() + ".." + spec.max() + " " + spec.unit());
            }
            readings.add(new Reading(metric, value, unit));
        }
        if (readings.isEmpty()) {
            return Result.reject("NO_METRIC", "Bản tin không có chỉ số nào (gas/temp)");
        }
        return new Result(true, null, null, ts, seq, fw, List.copyOf(readings));
    }

    public record Result(boolean ok, String reason, String detail, Instant ts, Long seq, String fw,
                         List<Reading> readings) {
        static Result reject(String reason, String detail) {
            return new Result(false, reason, detail, null, null, null, List.of());
        }
    }
}
