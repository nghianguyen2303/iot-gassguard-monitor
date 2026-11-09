package com.iot.gas.web.dto;

import com.iot.gas.domain.enums.Connectivity;
import com.iot.gas.domain.enums.LifecycleStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Tổng quan cho trang dashboard: trạng thái hệ thống + thẻ từng thiết bị với số đo mới nhất. */
public record DashboardDto(int devicesTotal, Map<String, Long> byConnectivity, Map<String, Long> byLifecycle,
                           AlertSummaryDto alerts, List<DeviceCard> devices, Instant generatedAt) {

    public record DeviceCard(String deviceCode, String name, String location, String zone,
                             LifecycleStatus lifecycleStatus, Connectivity connectivity, Instant lastSeenAt,
                             Map<String, Object> actuatorState, Map<String, LatestReadingDto> latest,
                             long activeAlerts, boolean canManage) {
    }
}
