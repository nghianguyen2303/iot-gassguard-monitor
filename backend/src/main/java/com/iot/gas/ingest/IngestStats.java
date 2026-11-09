package com.iot.gas.ingest;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bộ đếm trong bộ nhớ phục vụ kiểm thử phi chức năng (mục 7.2):
 * số bản tin nhận/lưu/trùng/bị từ chối và độ trễ thiết bị -> backend (received_at - ts).
 * Reset khi khởi động lại backend; số liệu lâu dài lấy từ bảng telemetry.
 */
@Component
public class IngestStats {

    private static final int LATENCY_WINDOW = 1000;
    private static final int RECENT_REJECTIONS = 30;

    private final Instant startedAt = Instant.now();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong telemetryMessages = new AtomicLong();
    private final AtomicLong telemetryAccepted = new AtomicLong();
    private final AtomicLong readingsStored = new AtomicLong();
    private final AtomicLong duplicateReadings = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final Map<String, AtomicLong> rejectedByReason = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> recentRejections = new ArrayDeque<>();

    private final long[] latencies = new long[LATENCY_WINDOW];
    private int latencyCount;
    private int latencyNext;
    private long latencyMax;
    private long latencyTotal;
    private long latencySamples;

    public void messageReceived() {
        messagesReceived.incrementAndGet();
    }

    public void telemetryReceived() {
        telemetryMessages.incrementAndGet();
    }

    public void telemetryAccepted(int stored, int duplicates) {
        telemetryAccepted.incrementAndGet();
        readingsStored.addAndGet(stored);
        duplicateReadings.addAndGet(duplicates);
    }

    public void rejected(String channel, String deviceCode, String reason, String detail) {
        rejected.incrementAndGet();
        rejectedByReason.computeIfAbsent(channel + ":" + reason, k -> new AtomicLong()).incrementAndGet();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("at", Instant.now());
        sample.put("channel", channel);
        sample.put("device", deviceCode);
        sample.put("reason", reason);
        sample.put("detail", detail);
        synchronized (recentRejections) {
            recentRejections.addFirst(sample);
            while (recentRejections.size() > RECENT_REJECTIONS) {
                recentRejections.removeLast();
            }
        }
    }

    public synchronized void recordLatency(long millis) {
        long ms = Math.max(0, millis);
        latencies[latencyNext] = ms;
        latencyNext = (latencyNext + 1) % LATENCY_WINDOW;
        latencyCount = Math.min(latencyCount + 1, LATENCY_WINDOW);
        latencyMax = Math.max(latencyMax, ms);
        latencyTotal += ms;
        latencySamples++;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("since", startedAt);
        m.put("mqttMessagesReceived", messagesReceived.get());
        m.put("telemetryMessages", telemetryMessages.get());
        m.put("telemetryAccepted", telemetryAccepted.get());
        m.put("readingsStored", readingsStored.get());
        m.put("duplicateReadingsIgnored", duplicateReadings.get());
        m.put("rejectedMessages", rejected.get());
        Map<String, Long> byReason = new TreeMap<>();
        rejectedByReason.forEach((k, v) -> byReason.put(k, v.get()));
        m.put("rejectedByReason", byReason);
        m.put("latencyMs", latencySnapshot());
        synchronized (recentRejections) {
            m.put("recentRejections", new ArrayList<>(recentRejections));
        }
        return m;
    }

    private synchronized Map<String, Object> latencySnapshot() {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("samplesTotal", latencySamples);
        if (latencyCount == 0) {
            return l;
        }
        long[] window = Arrays.copyOf(latencies, latencyCount);
        Arrays.sort(window);
        l.put("windowSize", latencyCount);
        l.put("avg", latencyTotal / latencySamples);
        l.put("p50", window[(int) Math.floor(0.50 * (latencyCount - 1))]);
        l.put("p95", window[(int) Math.floor(0.95 * (latencyCount - 1))]);
        l.put("max", latencyMax);
        return l;
    }
}
