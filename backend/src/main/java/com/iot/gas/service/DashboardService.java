package com.iot.gas.service;

import com.iot.gas.alert.AlertService;
import com.iot.gas.data.TelemetryQueryService;
import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.Connectivity;
import com.iot.gas.domain.enums.LifecycleStatus;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.web.dto.DashboardDto;
import com.iot.gas.web.dto.LatestReadingDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Dashboard trạng thái hệ thống (mục 4.4d): số thiết bị online/offline, cảnh báo, số đo mới nhất. */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DeviceRepository devices;
    private final AccessService access;
    private final AlertService alertService;
    private final TelemetryQueryService telemetry;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public DashboardDto overview(AuthUser user) {
        List<Device> list = (user.isAdmin() ? devices.findAll(Sort.by("id")) : devices.findAccessibleBy(user.id()))
                .stream().filter(d -> d.getLifecycleStatus() != LifecycleStatus.DECOMMISSIONED).toList();
        List<Long> ids = list.stream().map(Device::getId).toList();

        Map<String, Long> byConn = new LinkedHashMap<>();
        for (Connectivity c : Connectivity.values()) {
            byConn.put(c.name(), 0L);
        }
        Map<String, Long> byLife = new LinkedHashMap<>();
        for (LifecycleStatus s : LifecycleStatus.values()) {
            if (s != LifecycleStatus.DECOMMISSIONED) {
                byLife.put(s.name(), 0L);
            }
        }
        list.forEach(d -> {
            byConn.merge(d.getConnectivity().name(), 1L, Long::sum);
            byLife.merge(d.getLifecycleStatus().name(), 1L, Long::sum);
        });

        Map<Long, Long> activeAlerts = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.query("SELECT device_id, count(*) AS n FROM alerts WHERE status <> 'RESOLVED' "
                            + "AND device_id = ANY(CAST(:ids AS bigint[])) GROUP BY device_id",
                    new MapSqlParameterSource("ids", ids.toArray(new Long[0])),
                    rs -> { activeAlerts.put(rs.getLong("device_id"), rs.getLong("n")); });
        }
        Map<Long, Map<String, LatestReadingDto>> latest = telemetry.latestByDevice(ids);

        List<DashboardDto.DeviceCard> cards = list.stream().map(d -> new DashboardDto.DeviceCard(
                d.getDeviceCode(), d.getName(), d.getLocation(), d.getZone(), d.getLifecycleStatus(),
                d.getConnectivity(), d.getLastSeenAt(), d.getActuatorState(),
                latest.getOrDefault(d.getId(), Map.of()), activeAlerts.getOrDefault(d.getId(), 0L),
                access.canManage(user, d))).toList();

        return new DashboardDto(list.size(), byConn, byLife, alertService.summary(user), cards, Instant.now());
    }
}
