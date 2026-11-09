package com.iot.gas.web;

import com.iot.gas.security.CurrentUser;
import com.iot.gas.service.AuditQueryService;
import com.iot.gas.web.dto.AuditLogDto;
import com.iot.gas.web.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryService auditQuery;

    /** Toàn bộ audit log, chỉ ADMIN. action lọc theo tiền tố (vd. DEVICE, LOGIN, ALERT). result: SUCCESS|DENIED|FAILED */
    @GetMapping("/api/admin/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AuditLogDto> search(@RequestParam(required = false) String actor,
                                            @RequestParam(required = false) String action,
                                            @RequestParam(required = false) String result,
                                            @RequestParam(required = false) String targetType,
                                            @RequestParam(required = false) String targetId,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return auditQuery.search(actor, action, result, targetType, targetId, from, to, page, size);
    }

    /** Lịch sử hoạt động của 1 thiết bị, cho người có quyền xem thiết bị. */
    @GetMapping("/api/devices/{code}/activity")
    public PageResponse<AuditLogDto> deviceActivity(@PathVariable String code,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return auditQuery.deviceActivity(CurrentUser.require(), code, page, size);
    }
}
