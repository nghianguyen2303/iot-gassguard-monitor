package com.iot.gas.web.dto;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.ConfigStatus;
import com.iot.gas.domain.enums.Connectivity;
import com.iot.gas.domain.enums.LifecycleStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DeviceDto(Long id, String deviceCode, String name, String deviceType, String location, String zone,
                        Long ownerId, String ownerUsername,
                        LifecycleStatus lifecycleStatus, Connectivity connectivity, Instant lastSeenAt,
                        String hwVersion, String fwVersion,
                        Map<String, Object> config, int configVersion, int configAppliedVersion,
                        ConfigStatus configStatus, String configMessage, Instant configAckedAt,
                        Map<String, Object> actuatorState,
                        Instant createdAt, Instant updatedAt, Instant decommissionedAt,
                        boolean canManage, List<String> allowedActions) {

    public static DeviceDto from(Device d, String ownerUsername, boolean canManage, List<String> allowedActions) {
        return new DeviceDto(d.getId(), d.getDeviceCode(), d.getName(), d.getDeviceType(), d.getLocation(), d.getZone(),
                d.getOwnerId(), ownerUsername, d.getLifecycleStatus(), d.getConnectivity(), d.getLastSeenAt(),
                d.getHwVersion(), d.getFwVersion(), d.getConfig(), d.getConfigVersion(), d.getConfigAppliedVersion(),
                d.getConfigStatus(), d.getConfigMessage(), d.getConfigAckedAt(),
                d.getActuatorState(), d.getCreatedAt(), d.getUpdatedAt(), d.getDecommissionedAt(),
                canManage, canManage ? allowedActions : List.of());
    }
}
