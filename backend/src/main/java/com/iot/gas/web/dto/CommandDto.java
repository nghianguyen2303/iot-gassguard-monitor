package com.iot.gas.web.dto;

import com.iot.gas.domain.Command;
import com.iot.gas.domain.enums.CommandStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** latencyMs = ackedAt - sentAt: độ trễ lệnh điều khiển (mục 7.2). */
public record CommandDto(UUID id, String deviceCode, String action, Map<String, Object> params, CommandStatus status,
                         Long requestedBy, String requestedByUsername, Instant createdAt, Instant sentAt,
                         Instant ackedAt, Long latencyMs, String resultMessage, Map<String, Object> resultState) {

    public static CommandDto from(Command c, String deviceCode, String requestedByUsername) {
        Long latency = (c.getAckedAt() != null && c.getSentAt() != null)
                ? Duration.between(c.getSentAt(), c.getAckedAt()).toMillis() : null;
        return new CommandDto(c.getId(), deviceCode, c.getAction(), c.getParams(), c.getStatus(), c.getRequestedBy(),
                requestedByUsername, c.getCreatedAt(), c.getSentAt(), c.getAckedAt(), latency, c.getResultMessage(),
                c.getResultState());
    }
}
