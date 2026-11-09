package com.iot.gas.mqtt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cấu trúc topic MQTT của hệ thống:  iot/v1/devices/{deviceId}/{channel}
 * channel: telemetry | heartbeat | status | event | cmd | cmd/ack | config | config/ack
 */
public final class MqttTopics {
    private MqttTopics() {
    }

    public static final String ROOT = "iot/v1/devices";
    /** Backend subscribe 1 filter cho mọi thiết bị. */
    public static final String ALL_DEVICES = ROOT + "/+/#";

    public static final String TELEMETRY = "telemetry";
    public static final String HEARTBEAT = "heartbeat";
    public static final String STATUS = "status";
    public static final String EVENT = "event";
    public static final String CMD = "cmd";
    public static final String CMD_ACK = "cmd/ack";
    public static final String CONFIG = "config";
    public static final String CONFIG_ACK = "config/ack";

    /** deviceId: chữ thường, số, '-', 3-40 ký tự (khớp CHECK trong bảng devices). */
    private static final Pattern TOPIC = Pattern.compile("^iot/v1/devices/([a-z0-9][a-z0-9-]{2,39})/(.+)$");

    public static String of(String deviceCode, String channel) {
        return ROOT + "/" + deviceCode + "/" + channel;
    }

    /** Trả về null nếu topic không đúng cấu trúc. */
    public static Parsed parse(String topic) {
        Matcher m = TOPIC.matcher(topic);
        return m.matches() ? new Parsed(m.group(1), m.group(2)) : null;
    }

    public record Parsed(String deviceCode, String channel) {}
}
