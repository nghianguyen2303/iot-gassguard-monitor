package com.iot.gas.alert;

import com.iot.gas.domain.Alert;
import com.iot.gas.domain.AlertRule;
import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.AlertStatus;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.repository.AlertRepository;
import com.iot.gas.repository.AlertRuleRepository;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.service.AccessService;
import com.iot.gas.service.AuditService;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.AlertRuleDto;
import com.iot.gas.web.dto.AlertRuleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quản lý luật cảnh báo.
 * Luật chung (deviceId = null): áp dụng mọi thiết bị, chỉ ADMIN được tạo/sửa/xóa.
 * Luật riêng: áp dụng 1 thiết bị, chủ sở hữu (OWNER) hoặc ADMIN quản lý.
 */
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    /** Chỉ số hợp lệ + dải ngưỡng cho phép (theo dải đo của cảm biến). */
    static final Map<String, double[]> METRIC_RANGE = Map.of("gas", new double[]{0, 10_000}, "temp", new double[]{-55, 125});

    private final AlertRuleRepository rules;
    private final AlertRepository alerts;
    private final DeviceRepository devices;
    private final AccessService access;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<AlertRuleDto> list(AuthUser user, String deviceCode) {
        List<Long> deviceIds;
        if (deviceCode != null && !deviceCode.isBlank()) {
            Device d = loadDevice(deviceCode);
            access.requireView(user, d);
            deviceIds = List.of(d.getId());
        } else if (user.isAdmin()) {
            deviceIds = devices.findAll().stream().map(Device::getId).toList();
        } else {
            deviceIds = devices.findAccessibleBy(user.id()).stream().map(Device::getId).toList();
        }
        List<AlertRule> list = rules.findByDeviceIdIsNullOrDeviceIdIn(deviceIds.isEmpty() ? List.of(-1L) : deviceIds);
        Map<Long, Device> devs = devices.findAllById(list.stream().map(AlertRule::getDeviceId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity()));
        return list.stream().sorted(Comparator.comparing(AlertRule::getId))
                .map(r -> toDto(user, r, r.getDeviceId() == null ? null : devs.get(r.getDeviceId()))).toList();
    }

    @Transactional
    public AlertRuleDto create(AuthUser user, AlertRuleRequest req) {
        AlertRule r = new AlertRule();
        Device d = applyScope(user, r, req.deviceCode());
        apply(r, req);
        r.setCreatedBy(user.id());
        rules.save(r);
        audit.log(user.id(), user.username(), "ALERT_RULE_CREATE", "ALERT_RULE", String.valueOf(r.getId()),
                AuditResult.SUCCESS, describe(r, d));
        return toDto(user, r, d);
    }

    @Transactional
    public AlertRuleDto update(AuthUser user, Long id, AlertRuleRequest req) {
        AlertRule r = load(id);
        Device d = requireEdit(user, r);
        String newCode = req.deviceCode() == null || req.deviceCode().isBlank() ? null : req.deviceCode();
        String oldCode = d == null ? null : d.getDeviceCode();
        if (!Objects.equals(newCode, oldCode)) {
            throw ApiException.badRequest("SCOPE_CHANGE", "Không đổi phạm vi của luật; hãy tạo luật mới");
        }
        apply(r, req);
        audit.log(user.id(), user.username(), "ALERT_RULE_UPDATE", "ALERT_RULE", String.valueOf(id),
                AuditResult.SUCCESS, describe(r, d));
        return toDto(user, r, d);
    }

    /** Xóa luật: các cảnh báo còn mở của luật được đóng tự động (ghi chú lý do) để lịch sử không bị "treo". */
    @Transactional
    public void delete(AuthUser user, Long id) {
        AlertRule r = load(id);
        Device d = requireEdit(user, r);
        Instant now = Instant.now();
        for (Alert a : alerts.findByRuleIdAndStatusNot(id, AlertStatus.RESOLVED)) {
            a.setStatus(AlertStatus.RESOLVED);
            a.setResolvedAt(now);
            a.setResolvedBy(user.id());
            a.setResolutionNote("Tự đóng do luật '" + r.getName() + "' bị xóa");
        }
        alerts.flush();
        audit.log(user.id(), user.username(), "ALERT_RULE_DELETE", "ALERT_RULE", String.valueOf(id),
                AuditResult.SUCCESS, describe(r, d));
        rules.delete(r);
    }

    // ------------------------------------------------------------------
    private Device applyScope(AuthUser user, AlertRule r, String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            if (!user.isAdmin()) {
                throw new AccessDeniedException("Chỉ ADMIN được tạo luật chung cho mọi thiết bị");
            }
            r.setDeviceId(null);
            return null;
        }
        Device d = loadDevice(deviceCode);
        access.requireManage(user, d);
        r.setDeviceId(d.getId());
        return d;
    }

    private Device requireEdit(AuthUser user, AlertRule r) {
        if (r.getDeviceId() == null) {
            if (!user.isAdmin()) {
                throw new AccessDeniedException("Chỉ ADMIN được sửa luật chung");
            }
            return null;
        }
        Device d = devices.findById(r.getDeviceId()).orElseThrow();
        access.requireManage(user, d);
        return d;
    }

    private void apply(AlertRule r, AlertRuleRequest req) {
        double[] range = METRIC_RANGE.get(req.metric());
        if (range == null) {
            throw ApiException.badRequest("UNKNOWN_METRIC", "metric phải là: gas, temp");
        }
        if (req.threshold() < range[0] || req.threshold() > range[1]) {
            throw ApiException.badRequest("THRESHOLD_OUT_OF_RANGE",
                    "Ngưỡng " + req.metric() + " phải trong khoảng " + range[0] + ".." + range[1]);
        }
        r.setName(req.name().trim());
        r.setMetric(req.metric());
        r.setOperator(req.operator());
        r.setThreshold(req.threshold());
        r.setSeverity(req.severity());
        r.setEnabled(req.enabled() == null || req.enabled());
    }

    private AlertRuleDto toDto(AuthUser user, AlertRule r, Device d) {
        boolean canEdit = r.getDeviceId() == null ? user.isAdmin() : (d != null && access.canManage(user, d));
        return new AlertRuleDto(r.getId(), r.getName(), r.getDeviceId(), d == null ? null : d.getDeviceCode(),
                r.getDeviceId() == null, r.getMetric(), r.getOperator(), r.getThreshold(),
                AlertService.METRIC_UNIT.get(r.getMetric()), r.getSeverity(), r.isEnabled(), r.getCreatedAt(), canEdit);
    }

    private Map<String, Object> describe(AlertRule r, Device d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.getName());
        m.put("scope", d == null ? "GLOBAL" : d.getDeviceCode());
        m.put("condition", r.getMetric() + " " + r.getOperator() + " " + r.getThreshold());
        m.put("severity", r.getSeverity().name());
        m.put("enabled", r.isEnabled());
        return m;
    }

    private AlertRule load(Long id) {
        return rules.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy luật cảnh báo"));
    }

    private Device loadDevice(String code) {
        return devices.findByDeviceCode(code)
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy thiết bị '" + code + "'"));
    }
}
