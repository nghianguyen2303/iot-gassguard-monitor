package com.iot.gas.web;

import com.iot.gas.alert.AlertService;
import com.iot.gas.domain.enums.AlertType;
import com.iot.gas.domain.enums.Severity;
import com.iot.gas.security.CurrentUser;
import com.iot.gas.web.dto.AlertActionRequest;
import com.iot.gas.web.dto.AlertDto;
import com.iot.gas.web.dto.AlertSummaryDto;
import com.iot.gas.web.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    /** Lịch sử cảnh báo trong phạm vi thiết bị người dùng được xem. status: OPEN|ACKNOWLEDGED|RESOLVED|ACTIVE */
    @GetMapping
    public PageResponse<AlertDto> list(@RequestParam(required = false) String deviceCode,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Severity severity,
                                       @RequestParam(required = false) AlertType type,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return alertService.list(CurrentUser.require(), deviceCode, status, severity, type, from, to, page, size);
    }

    @GetMapping("/summary")
    public AlertSummaryDto summary() {
        return alertService.summary(CurrentUser.require());
    }

    @PostMapping("/{id}/ack")
    public AlertDto acknowledge(@PathVariable Long id, @Valid @RequestBody(required = false) AlertActionRequest req) {
        return alertService.acknowledge(CurrentUser.require(), id, req == null ? null : req.note());
    }

    @PostMapping("/{id}/resolve")
    public AlertDto resolve(@PathVariable Long id, @Valid @RequestBody AlertActionRequest req) {
        return alertService.resolve(CurrentUser.require(), id, req.note());
    }
}
