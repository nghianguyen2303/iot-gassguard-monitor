package com.iot.gas.service;

import com.iot.gas.data.TimeRange;
import com.iot.gas.domain.AuditLog;
import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.repository.AuditLogRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.AuditLogDto;
import com.iot.gas.web.dto.PageResponse;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Xem lịch sử hoạt động (mục 4.6): toàn hệ thống cho ADMIN, theo từng thiết bị cho người có quyền xem. */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditLogRepository logs;
    private final DeviceService deviceService;

    @Transactional(readOnly = true)
    public PageResponse<AuditLogDto> search(String actor, String action, String result, String targetType,
                                            String targetId, String from, String to, int page, int size) {
        TimeRange range = (from == null && to == null) ? null
                : TimeRange.parse(from, to, Duration.ofDays(7), Duration.ofDays(3660));
        AuditResult res = null;
        if (result != null && !result.isBlank()) {
            try {
                res = AuditResult.valueOf(result.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("BAD_PARAMETER", "result phải là SUCCESS, DENIED hoặc FAILED");
            }
        }
        final AuditResult fRes = res;
        Specification<AuditLog> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (actor != null && !actor.isBlank()) {
                p.add(cb.equal(root.get("actorUsername"), actor.trim()));
            }
            if (action != null && !action.isBlank()) {
                p.add(cb.like(root.get("action"), action.trim().toUpperCase(Locale.ROOT) + "%"));
            }
            if (fRes != null) {
                p.add(cb.equal(root.get("result"), fRes));
            }
            if (targetType != null && !targetType.isBlank()) {
                p.add(cb.equal(root.get("targetType"), targetType.trim().toUpperCase(Locale.ROOT)));
            }
            if (targetId != null && !targetId.isBlank()) {
                p.add(cb.equal(root.get("targetId"), targetId.trim()));
            }
            if (range != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), range.from()));
                p.add(cb.lessThan(root.get("createdAt"), range.to()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        int s = Math.min(Math.max(size, 1), 200);
        Page<AuditLog> r = logs.findAll(spec, PageRequest.of(Math.max(page, 0), s, Sort.by(Sort.Direction.DESC, "id")));
        return new PageResponse<>(r.getContent().stream().map(AuditLogDto::from).toList(), r.getTotalElements(),
                r.getNumber(), s);
    }

    /** Lịch sử thao tác trên 1 thiết bị (đăng ký, cấp quyền, khóa, lệnh, cấu hình, xuất dữ liệu...). */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogDto> deviceActivity(AuthUser user, String code, int page, int size) {
        Device d = deviceService.loadForView(user, code);
        int s = Math.min(Math.max(size, 1), 200);
        Page<AuditLog> r = logs.findByTargetTypeAndTargetIdOrderByCreatedAtDesc("DEVICE", d.getDeviceCode(),
                PageRequest.of(Math.max(page, 0), s));
        return new PageResponse<>(r.getContent().stream().map(AuditLogDto::from).toList(), r.getTotalElements(),
                r.getNumber(), s);
    }
}
