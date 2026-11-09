package com.iot.gas.alert;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.Connectivity;
import com.iot.gas.domain.enums.LifecycleStatus;
import com.iot.gas.ingest.event.ConnectivityChangedEvent;
import com.iot.gas.repository.DeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Cơ chế phát hiện offline thứ 2 (bổ sung cho LWT của broker).
 * LWT không bắt được trường hợp ESP32 bị treo nhưng kết nối TCP vẫn giữ (vẫn trả PINGRESP)
 * hoặc thiết bị còn kết nối nhưng ngừng gửi heartbeat. Watchdog đánh dấu OFFLINE khi quá
 * offline-timeout (mặc định 30 s = 3 chu kỳ heartbeat 10 s) không nhận bản tin nào.
 */
@Component
@Slf4j
public class OfflineWatchdog {

    private static final List<LifecycleStatus> WATCHED =
            List.of(LifecycleStatus.PROVISIONED, LifecycleStatus.ACTIVE, LifecycleStatus.MAINTENANCE);
    private static final List<Connectivity> ALIVE = List.of(Connectivity.ONLINE, Connectivity.FAULT);

    private final DeviceRepository devices;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Duration timeout;

    public OfflineWatchdog(DeviceRepository devices, ApplicationEventPublisher events, TransactionTemplate tx,
                           @Value("${app.alert.offline-timeout-sec:30}") long timeoutSec) {
        this.devices = devices;
        this.events = events;
        this.tx = tx;
        this.timeout = Duration.ofSeconds(timeoutSec);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void check() {
        Instant now = Instant.now();
        List<Device> stale = devices.findByLifecycleStatusInAndConnectivityInAndLastSeenAtBefore(
                WATCHED, ALIVE, now.minus(timeout));
        for (Device d : stale) {
            tx.executeWithoutResult(s -> {
                if (devices.setConnectivity(d.getId(), Connectivity.OFFLINE, now) == 1) {
                    log.warn("[{}] {} -> OFFLINE (watchdog: không nhận bản tin từ {})",
                            d.getDeviceCode(), d.getConnectivity(), d.getLastSeenAt());
                    events.publishEvent(new ConnectivityChangedEvent(d.getId(), d.getDeviceCode(),
                            d.getConnectivity(), Connectivity.OFFLINE, "WATCHDOG", now));
                }
            });
        }
    }
}
