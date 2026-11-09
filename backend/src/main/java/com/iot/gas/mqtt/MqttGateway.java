package com.iot.gas.mqtt;

import com.iot.gas.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * Kết nối duy nhất của backend tới Mosquitto (tài khoản "backend", quyền tối thiểu theo ACL).
 * <ul>
 *   <li>Phiên BỀN (cleanSession=false, clientId cố định): khi backend hoặc broker khởi động lại, broker giữ
 *       đăng ký topic và XẾP HÀNG bản tin QoS 1 cho backend -> không mất dữ liệu thiết bị gửi trong lúc gián đoạn.</li>
 *   <li>Mất kết nối -> tự kết nối lại bằng một MqttClient MỚI (giãn dần 1 s → 30 s). Không dùng
 *       automaticReconnect của Paho 1.2.5 vì có lỗi treo CommsSender.start khi kết nối lại với phiên bền.</li>
 *   <li>Mọi bản tin đi qua messageArrived rồi chuyển cho handler có topic filter khớp: bản tin xếp hàng
 *       broker giao ngay sau CONNACK (trước khi subscribe lại) vẫn được xử lý.</li>
 *   <li>Xử lý bản tin xong mới trả về -> Paho mới gửi PUBACK (QoS 1). Nếu backend chết giữa chừng,
 *       broker sẽ giao lại bản tin: không mất dữ liệu (at-least-once), bản trùng do DB loại.</li>
 * </ul>
 * Kiểm chứng: tests/perf P4 (khởi động lại broker khi thiết bị đang gửi).
 */
@Component
@Slf4j
public class MqttGateway implements SmartLifecycle {

    private static final long MIN_RETRY_MS = 1_000;
    private static final long MAX_RETRY_MS = 30_000;

    private final AppProperties.Mqtt cfg;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final ExecutorService subscriber = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mqtt-subscriber");
        t.setDaemon(true);
        return t;
    });
    /** Một luồng duy nhất lo kết nối / kết nối lại -> không bao giờ có 2 lần kết nối song song. */
    private final ExecutorService connector = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mqtt-connector");
        t.setDaemon(true);
        return t;
    });

    private volatile MqttClient client;
    private volatile boolean running;

    public MqttGateway(AppProperties props) {
        this.cfg = props.mqtt();
        if (cfg == null || cfg.url() == null || cfg.password() == null || cfg.password().isBlank()) {
            throw new IllegalStateException("Thiếu cấu hình app.mqtt (MQTT_BACKEND_PASSWORD trong .env?)");
        }
    }

    /** Đăng ký nhận bản tin theo topic filter. Gọi được trước hoặc sau khi kết nối. */
    public void subscribe(String filter, int qos, BiConsumer<String, MqttMessage> handler) {
        Subscription sub = new Subscription(filter, qos, handler);
        subscriptions.add(sub);
        if (isConnected()) {
            subscriber.submit(() -> doSubscribe(client, sub));
        }
    }

    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    public void publish(String topic, String payload, int qos, boolean retained) throws MqttException {
        publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, retained);
    }

    public void publish(String topic, byte[] payload, int qos, boolean retained) throws MqttException {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        c.publish(topic, payload, qos, retained);
    }

    // ------------------------------------------------------------------ SmartLifecycle
    @Override
    public void start() {
        running = true;
        connector.submit(this::connectLoop);
    }

    @Override
    public void stop() {
        running = false;
        connector.shutdownNow();
        MqttClient c = client;
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.disconnect(2_000);
                }
                c.close();
            } catch (MqttException e) {
                log.debug("Lỗi khi đóng MQTT: {}", e.getMessage());
            }
        }
        subscriber.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ Kết nối
    /** Chạy trên luồng mqtt-connector: tạo client mới, kết nối, subscribe; lỗi thì thử lại với thời gian giãn dần. */
    private void connectLoop() {
        long delay = MIN_RETRY_MS;
        boolean reconnect = client != null;
        discardOldClient();
        while (running) {
            MqttClient c = null;
            try {
                c = new MqttClient(cfg.url(), cfg.clientId(), new MemoryPersistence());
                c.setCallback(new Callback(c));
                c.connect(options());
                client = c;
                log.info("{} MQTT broker {}", reconnect ? "Đã kết nối lại" : "Đã kết nối", cfg.url());
                for (Subscription sub : subscriptions) {
                    doSubscribe(c, sub);
                }
                return;
            } catch (MqttException e) {
                closeQuietly(c);
                log.warn("Chưa kết nối được MQTT broker {} ({}). Thử lại sau {} s", cfg.url(), e.getMessage(), delay / 1000.0);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delay = Math.min(delay * 2, MAX_RETRY_MS);
            }
        }
    }

    private void discardOldClient() {
        MqttClient old = client;
        client = null;
        closeQuietly(old);
    }

    private static void closeQuietly(MqttClient c) {
        if (c == null) {
            return;
        }
        try {
            c.disconnectForcibly(500, 500);
        } catch (MqttException | RuntimeException ignored) {
            // đã mất kết nối
        }
        try {
            c.close(true);
        } catch (MqttException | RuntimeException ignored) {
            // bỏ qua
        }
    }

    private MqttConnectOptions options() {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        o.setUserName(cfg.username());
        o.setPassword(cfg.password().toCharArray());
        o.setCleanSession(false);                        // phiên bền: broker xếp hàng QoS1 khi backend mất kết nối
        o.setAutomaticReconnect(false);                  // tự kết nối lại bằng connectLoop()
        o.setKeepAliveInterval(30);
        o.setConnectionTimeout(10);
        o.setMaxInflight(1000);
        return o;
    }

    private void doSubscribe(MqttClient c, Subscription sub) {
        try {
            IMqttToken token = c.subscribeWithResponse(new String[]{sub.filter()}, new int[]{sub.qos()});
            int[] granted = token.getGrantedQos();
            if (granted != null && granted.length > 0 && granted[0] == 0x80) {
                log.error("Broker TỪ CHỐI subscribe '{}' (kiểm tra ACL của role 'backend')", sub.filter());
            } else {
                log.info("Đã subscribe MQTT '{}' (QoS {})", sub.filter(), sub.qos());
            }
        } catch (MqttException e) {
            log.error("Subscribe '{}' thất bại: {}", sub.filter(), e.getMessage());
        }
    }

    private class Callback implements MqttCallback {
        private final MqttClient owner;

        Callback(MqttClient owner) {
            this.owner = owner;
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.warn("Mất kết nối MQTT broker: {}. Đang kết nối lại...", cause == null ? "?" : cause.getMessage());
            if (running && owner == client) {
                connector.submit(MqttGateway.this::connectLoop);
            }
        }

        /** Mọi bản tin (kể cả bản tin broker xếp hàng giao ngay sau khi kết nối lại) -> handler có filter khớp. */
        @Override
        public void messageArrived(String topic, MqttMessage message) {
            for (Subscription sub : subscriptions) {
                if (MqttTopic.isMatched(sub.filter(), topic)) {
                    try {
                        sub.handler().accept(topic, message);
                    } catch (Throwable t) {
                        // Không để exception lọt ra: Paho sẽ ngắt kết nối nếu callback ném lỗi
                        log.error("Lỗi xử lý bản tin MQTT topic={}", topic, t);
                    }
                }
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
    }

    private record Subscription(String filter, int qos, BiConsumer<String, MqttMessage> handler) {}
}
