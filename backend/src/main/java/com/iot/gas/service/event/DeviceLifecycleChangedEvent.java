package com.iot.gas.service.event;

import com.iot.gas.domain.enums.LifecycleStatus;

import java.time.Instant;

public record DeviceLifecycleChangedEvent(Long deviceId, String deviceCode, LifecycleStatus from,
                                          LifecycleStatus to, String actor, Instant at) {
}
