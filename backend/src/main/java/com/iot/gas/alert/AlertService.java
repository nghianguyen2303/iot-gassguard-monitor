package com.iot.gas.alert;

import com.iot.gas.domain.Alert;
import com.iot.gas.domain.AlertRule;
import com.iot.gas.domain.Device;
import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.*;
import com.iot.gas.ingest.Reading;
import com.iot.gas.ingest.event.ConnectivityChangedEvent;
import com.iot.gas.ingest.event.DeviceEventReceivedEvent;
import com.iot.gas.ingest.event.TelemetryReceivedEvent;
import com.iot.gas.repository.AlertRepository;
import com.iot.gas.repository.AlertRuleRepository;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.service.AccessService;
import com.iot.gas.service.AuditService;
import com.iot.gas.service.event.AlertChangedEvent;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.AlertDto;
import com.iot.gas.web.dto.AlertSummaryDto;
import com.iot.gas.web.dto.PageResponse;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Giám sát và cảnh báo (mục 4.4d).
 * <ul>
 *   <li>THRESHOLD: mỗi telemetry mới được so với các luật đang bật (luật chung + luật riêng của thiết bị).</li>
 *   <li>OFFLINE: thiết bị mất kết nối (LWT của broker hoặc watchdog không thấy heartbeat).</li>
 *   <li>FAULT: thiết bị tự báo lỗi cảm biến (event SENSOR_FAULT).</li>
 * </ul>
 * Chống "bão cảnh báo": mỗi (thiết bị, loại, luật) chỉ có 1 cảnh báo chưa xử lý; vi phạm lặp lại chỉ tăng
 * occurrenceCount. Khi số đo trở lại bình thường -> ghi clearedAt, nhưng cảnh báo vẫn chờ người dùng xử lý.
 * Chỉ thiết bị ACTIVE mới sinh cảnh báo: MAINTENANCE / SUSPENDED / PROVISIONED được bỏ qua (có chủ đích).
 * <p>
 * Lắng nghe sự kiện SAU KHI transaction lưu telemetry đã commit, chạy trong transaction riêng:
 * lỗi ở phần cảnh báo không bao giờ làm mất dữ liệu telemetry.
 */
@Service
@Slf4j
public class AlertService {

    static final Map<String, String> METRIC_LABEL = Map.of("gas", "Khí gas", "temp", "Nhiệt độ");
    static final Map<String, String> METRIC_UNIT = Map.of("gas", "ppm", "temp", "°C");
    static final Map<RuleOperator, String> OP_SYMBOL = Map.of(
            RuleOperator.GT, ">", RuleOperator.GTE, "≥", RuleOperator.LT, "<", RuleOperator.LTE, "≤");

    private final AlertRepository alerts;
    private final AlertRuleRepository rules;
    private final DeviceRepository devices;
    private final UserRepository users;
    private final AccessService access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate newTx;

    public AlertService(AlertRepository alerts, AlertRuleRepository rules, DeviceRepository devices,
                        UserRepository users, AccessService access, AuditService audit,
                        ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this.alerts = alerts;
        this.rules = rules;
        this.devices = devices;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ====================================================================== Sinh cảnh báo
    @TransactionalEventListener(fallbackExecution = true)
    public void onTelemetry(TelemetryReceivedEvent e) {
        safely("telemetry " + e.deviceCode(), () -> {
            Device d = devices.findById(e.deviceId()).orElse(null);
            if (d == null || d.getLifecycleStatus() != LifecycleStatus.ACTIVE) {
                return;                                                  // bảo trì / chưa kích hoạt: không cảnh báo
            }
            for (Reading r : e.readings()) {
                for (AlertRule rule : rules.findApplicable(d.getId(), r.metric())) {
                    evaluate(d, rule, r, e.receivedAt());
                }
            }
        });
    }

    private void evaluate(Device d, AlertRule rule, Reading r, Instant at) {
        boolean breach = rule.getOperator().test(r.value(), rule.getThreshold());
        Optional<Alert> active = alerts.findFirstByDeviceIdAndTypeAndRuleIdAndStatusNot(
                d.getId(), AlertType.THRESHOLD, rule.getId(), AlertStatus.RESOLVED);
        String msg = thresholdMessage(rule, r.value());
        if (breach) {
            if (active.isPresent()) {
                Alert a = active.get();
                boolean recurred = a.getClearedAt() != null;
                a.setOccurrenceCount(a.getOccurrenceCount() + 1);
                a.setLastSeenAt(at);
                a.setValue(r.value());
                a.setMessage(msg);
                a.setSeverity(rule.getSeverity());
                a.setClearedAt(null);
                alerts.save(a);
                if (recurred) {
                    publish(a, "RECURRED");
                }
            } else {
                Alert a = newAlert(d, AlertType.THRESHOLD, rule.getSeverity(), msg, at);
                a.setRuleId(rule.getId());
                a.setMetric(r.metric());
                a.setValue(r.value());
                alerts.save(a);
                log.warn("[{}] CẢNH BÁO {}: {}", d.getDeviceCode(), rule.getSeverity(), msg);
                publish(a, "CREATED");
            }
        } else if (active.isPresent() && active.get().getClearedAt() == null) {
            Alert a = active.get();
            a.setClearedAt(at);
            alerts.save(a);
            log.info("[{}] {} trở lại bình thường ({} {})", d.getDeviceCode(), rule.getName(),
                    fmt(r.value()), METRIC_UNIT.getOrDefault(r.metric(), ""));
            publish(a, "CLEARED");
        }
    }

    /** Mất kết nối (LWT, watchdog, tắt máy) -> OFFLINE; có lại kết nối -> đánh dấu trở lại bình thường. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onConnectivity(ConnectivityChangedEvent e) {
        safely("connectivity " + e.deviceCode(), () -> {
            Device d = devices.findById(e.deviceId()).orElse(null);
            if (d == null) {
                return;
            }
            if (e.to() == Connectivity.OFFLINE) {
                if (d.getLifecycleStatus() == LifecycleStatus.ACTIVE) {
                    raise(d, AlertType.OFFLINE, Severity.WARNING,
                            "Thiết bị mất kết nối (" + offlineReason(e.reason()) + ")", e.at());
                }
            } else if (e.to() == Connectivity.ONLINE) {
                clear(d, AlertType.OFFLINE, e.at());
            }
        });
    }

    /** Thiết bị tự báo lỗi / hết lỗi cảm biến. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onDeviceEvent(DeviceEventReceivedEvent e) {
        if (!"SENSOR_FAULT".equals(e.type()) && !"SENSOR_OK".equals(e.type())) {
            return;
        }
        safely("event " + e.deviceCode(), () -> {
            Device d = devices.findById(e.deviceId()).orElse(null);
            if (d == null) {
                return;
            }
            if ("SENSOR_FAULT".equals(e.type())) {
                if (d.getLifecycleStatus() == LifecycleStatus.ACTIVE) {
                    Object sensor = e.body().getOrDefault("sensor", "?");
                    Object detail = e.body().getOrDefault("message", "");
                    raise(d, AlertType.FAULT, Severity.WARNING, "Lỗi cảm biến " + sensor + ": " + detail, e.at());
                }
            } else {
                clear(d, AlertType.FAULT, e.at());
            }
        });
    }

    private void raise(Device d, AlertType type, Severity severity, String message, Instant at) {
        Optional<Alert> active = alerts.findFirstByDeviceIdAndTypeAndRuleIdIsNullAndStatusNot(
                d.getId(), type, AlertStatus.RESOLVED);
        if (active.isPresent()) {
            Alert a = active.get();
            boolean recurred = a.getClearedAt() != null;
            a.setOccurrenceCount(a.getOccurrenceCount() + 1);
            a.setLastSeenAt(at);
            a.setMessage(truncate(message));
            a.setClearedAt(null);
            alerts.save(a);
            if (recurred) {
                publish(a, "RECURRED");
            }
        } else {
            Alert a = alerts.save(newAlert(d, type, severity, message, at));
            log.warn("[{}] CẢNH BÁO {}: {}", d.getDeviceCode(), type, message);
            publish(a, "CREATED");
        }
    }

    private void clear(Device d, AlertType type, Instant at) {
        alerts.findFirstByDeviceIdAndTypeAndRuleIdIsNullAndStatusNot(d.getId(), type, AlertStatus.RESOLVED)
                .filter(a -> a.getClearedAt() == null)
                .ifPresent(a -> {
                    a.setClearedAt(at);
                    alerts.save(a);
                    log.info("[{}] Cảnh báo {} trở lại bình thường", d.getDeviceCode(), type);
                    publish(a, "CLEARED");
                });
    }

    private static Alert newAlert(Device d, AlertType type, Severity severity, String message, Instant at) {
        Alert a = new Alert();
        a.setDeviceId(d.getId());
        a.setType(type);
        a.setSeverity(severity);
        a.setMessage(truncate(message));
        a.setStatus(AlertStatus.OPEN);
        a.setCreatedAt(at);
        a.setLastSeenAt(at);
        return a;
    }

    /** Chạy trong transaction RIÊNG; lỗi (kể cả trùng chỉ mục uq_alert_active khi 2 luồng cùng tạo) chỉ ghi log. */
    private void safely(String what, Runnable body) {
        try {
            newTx.executeWithoutResult(s -> body.run());
        } catch (DataIntegrityViolationException ex) {
            log.debug("Bỏ qua cảnh báo trùng ({}): {}", what, ex.getMostSpecificCause().getMessage());
        } catch (Exception ex) {
            log.error("Lỗi xử lý cảnh báo ({})", what, ex);
        }
    }

    // ====================================================================== Xác nhận / xử lý
    /** OPEN -> ACKNOWLEDGED: "đã biết, đang xử lý". */
    @Transactional
    public AlertDto acknowledge(AuthUser user, Long id, String note) {
        Alert a = loadForHandle(user, id);
        if (a.getStatus() != AlertStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Chỉ xác nhận được cảnh báo đang OPEN");
        }
        Instant now = Instant.now();
        a.setStatus(AlertStatus.ACKNOWLEDGED);
        a.setAcknowledgedBy(user.id());
        a.setAcknowledgedAt(now);
        if (note != null && !note.isBlank()) {
            a.setResolutionNote(note.trim());
        }
        audit.log(user.id(), user.username(), "ALERT_ACK", "ALERT", String.valueOf(id), AuditResult.SUCCESS, null);
        AlertDto dto = toDtos(user, List.of(a)).get(0);
        events.publishEvent(new AlertChangedEvent(a.getDeviceId(), "ACKNOWLEDGED", dto));
        return dto;
    }

    /** OPEN/ACKNOWLEDGED -> RESOLVED, bắt buộc ghi cách xử lý. */
    @Transactional
    public AlertDto resolve(AuthUser user, Long id, String note) {
        Alert a = loadForHandle(user, id);
        if (a.getStatus() == AlertStatus.RESOLVED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Cảnh báo đã được xử lý");
        }
        if (note == null || note.trim().length() < 3) {
            throw ApiException.badRequest("NOTE_REQUIRED", "Cần ghi chú cách đã xử lý (ít nhất 3 ký tự)");
        }
        Instant now = Instant.now();
        if (a.getAcknowledgedAt() == null) {
            a.setAcknowledgedBy(user.id());
            a.setAcknowledgedAt(now);
        }
        a.setStatus(AlertStatus.RESOLVED);
        a.setResolvedBy(user.id());
        a.setResolvedAt(now);
        a.setResolutionNote(note.trim());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("note", note.trim());
        details.put("conditionStillActive", a.getClearedAt() == null);
        audit.log(user.id(), user.username(), "ALERT_RESOLVE", "ALERT", String.valueOf(id), AuditResult.SUCCESS, details);
        AlertDto dto = toDtos(user, List.of(a)).get(0);
        events.publishEvent(new AlertChangedEvent(a.getDeviceId(), "RESOLVED", dto));
        return dto;
    }

    private Alert loadForHandle(AuthUser user, Long id) {
        Alert a = alerts.findById(id).orElseThrow(() -> ApiException.notFound("Không tìm thấy cảnh báo"));
        Device d = devices.findById(a.getDeviceId()).orElseThrow();
        access.requireManage(user, d);                                    // VIEWER chỉ xem, không xử lý
        return a;
    }

    // ====================================================================== Tra cứu
    /** status: OPEN | ACKNOWLEDGED | RESOLVED | ACTIVE (= chưa xử lý xong). */
    @Transactional(readOnly = true)
    public PageResponse<AlertDto> list(AuthUser user, String deviceCode, String status, Severity severity,
                                       AlertType type, Instant from, Instant to, int page, int size) {
        Collection<Long> scope = accessibleDeviceIds(user);
        Long deviceId = null;
        if (deviceCode != null && !deviceCode.isBlank()) {
            Device d = devices.findByDeviceCode(deviceCode)
                    .orElseThrow(() -> ApiException.notFound("Không tìm thấy thiết bị '" + deviceCode + "'"));
            access.requireView(user, d);
            deviceId = d.getId();
        }
        final Long fDevice = deviceId;
        Specification<Alert> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (scope != null) {
                p.add(scope.isEmpty() ? cb.disjunction() : root.get("deviceId").in(scope));
            }
            if (fDevice != null) {
                p.add(cb.equal(root.get("deviceId"), fDevice));
            }
            if (status != null && !status.isBlank()) {
                if ("ACTIVE".equalsIgnoreCase(status)) {
                    p.add(cb.notEqual(root.get("status"), AlertStatus.RESOLVED));
                } else {
                    p.add(cb.equal(root.get("status"), parseStatus(status)));
                }
            }
            if (severity != null) {
                p.add(cb.equal(root.get("severity"), severity));
            }
            if (type != null) {
                p.add(cb.equal(root.get("type"), type));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                p.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        int s = Math.min(Math.max(size, 1), 200);
        Page<Alert> result = alerts.findAll(spec, PageRequest.of(Math.max(page, 0), s, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(toDtos(user, result.getContent()), result.getTotalElements(), result.getNumber(), s);
    }

    @Transactional(readOnly = true)
    public AlertSummaryDto summary(AuthUser user) {
        Collection<Long> scope = accessibleDeviceIds(user);
        List<Object[]> rows = alerts.countActive(scope == null, scope == null || scope.isEmpty() ? List.of(-1L) : scope);
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity sv : Severity.values()) {
            bySeverity.put(sv.name(), 0L);
        }
        long open = 0, ack = 0;
        for (Object[] r : rows) {
            Severity sv = (Severity) r[0];
            AlertStatus st = (AlertStatus) r[1];
            long n = (Long) r[2];
            bySeverity.merge(sv.name(), n, Long::sum);
            if (st == AlertStatus.OPEN) {
                open += n;
            } else {
                ack += n;
            }
        }
        return new AlertSummaryDto(open + ack, open, ack, bySeverity);
    }

    /** null = ADMIN (không giới hạn). */
    private Collection<Long> accessibleDeviceIds(AuthUser user) {
        if (user.isAdmin()) {
            return null;
        }
        return devices.findAccessibleBy(user.id()).stream().map(Device::getId).toList();
    }

    // ====================================================================== Chuyển sang DTO
    private List<AlertDto> toDtos(AuthUser user, List<Alert> list) {
        Map<Long, Device> devs = devices.findAllById(list.stream().map(Alert::getDeviceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Device::getId, Function.identity()));
        Set<Long> ruleIds = list.stream().map(Alert::getRuleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> ruleNames = rules.findAllById(ruleIds).stream()
                .collect(Collectors.toMap(AlertRule::getId, AlertRule::getName));
        Set<Long> userIds = new HashSet<>();
        list.forEach(a -> {
            if (a.getAcknowledgedBy() != null) userIds.add(a.getAcknowledgedBy());
            if (a.getResolvedBy() != null) userIds.add(a.getResolvedBy());
        });
        Map<Long, String> names = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        return list.stream().map(a -> {
            Device d = devs.get(a.getDeviceId());
            boolean canHandle = d != null && access.canManage(user, d);
            return new AlertDto(a.getId(), a.getDeviceId(), d == null ? null : d.getDeviceCode(),
                    d == null ? null : d.getName(), a.getRuleId(), a.getRuleId() == null ? null : ruleNames.get(a.getRuleId()),
                    a.getType(), a.getSeverity(), a.getMessage(), a.getMetric(), a.getValue(), a.getStatus(),
                    a.getOccurrenceCount(), a.getCreatedAt(), a.getLastSeenAt(), a.getClearedAt(),
                    names.get(a.getAcknowledgedBy()), a.getAcknowledgedAt(), names.get(a.getResolvedBy()),
                    a.getResolvedAt(), a.getResolutionNote(), canHandle);
        }).toList();
    }

    /** Sự kiện cho realtime (không có người dùng cụ thể nên canHandle=false; giao diện tự tính lại quyền). */
    private void publish(Alert a, String change) {
        Device d = devices.findById(a.getDeviceId()).orElse(null);
        String ruleName = a.getRuleId() == null ? null : rules.findById(a.getRuleId()).map(AlertRule::getName).orElse(null);
        AlertDto dto = new AlertDto(a.getId(), a.getDeviceId(), d == null ? null : d.getDeviceCode(),
                d == null ? null : d.getName(), a.getRuleId(), ruleName, a.getType(), a.getSeverity(), a.getMessage(),
                a.getMetric(), a.getValue(), a.getStatus(), a.getOccurrenceCount(), a.getCreatedAt(), a.getLastSeenAt(),
                a.getClearedAt(), null, a.getAcknowledgedAt(), null, a.getResolvedAt(), a.getResolutionNote(), false);
        events.publishEvent(new AlertChangedEvent(a.getDeviceId(), change, dto));
    }

    // ====================================================================== Tiện ích
    static String thresholdMessage(AlertRule rule, double value) {
        String unit = METRIC_UNIT.getOrDefault(rule.getMetric(), "");
        return truncate(METRIC_LABEL.getOrDefault(rule.getMetric(), rule.getMetric()) + " " + fmt(value) + " " + unit
                + " " + OP_SYMBOL.get(rule.getOperator()) + " " + fmt(rule.getThreshold()) + " " + unit
                + " (" + rule.getName() + ")");
    }

    private static String offlineReason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "LWT" -> "broker báo mất kết nối đột ngột - LWT";
            case "WATCHDOG" -> "quá thời gian không nhận heartbeat";
            case "SHUTDOWN" -> "thiết bị tự tắt";
            default -> reason;
        };
    }

    private static AlertStatus parseStatus(String s) {
        try {
            return AlertStatus.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("BAD_PARAMETER", "status phải là OPEN, ACKNOWLEDGED, RESOLVED hoặc ACTIVE");
        }
    }

    static String fmt(double v) {
        return (v == Math.rint(v)) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
