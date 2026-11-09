package com.iot.gas.service;

import com.iot.gas.domain.enums.LifecycleAction;
import com.iot.gas.domain.enums.LifecycleStatus;
import com.iot.gas.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.*;

import static com.iot.gas.domain.enums.LifecycleStatus.*;

/**
 * Máy trạng thái vòng đời thiết bị (mục 4.4b, 6 "mô hình trạng thái vòng đời").
 * <pre>
 *  REGISTERED --provision--> PROVISIONED --ACTIVATE / dữ liệu đầu tiên--> ACTIVE
 *  ACTIVE --START_MAINTENANCE--> MAINTENANCE --END_MAINTENANCE--> ACTIVE
 *  PROVISIONED | ACTIVE | MAINTENANCE --SUSPEND--> SUSPENDED --RESUME--> ACTIVE
 *  mọi trạng thái (trừ DECOMMISSIONED) --DECOMMISSION--> DECOMMISSIONED (không quay lại được)
 * </pre>
 * ONLINE/OFFLINE/FAULT là trường riêng (connectivity) do hệ thống tự cập nhật, không nằm trong máy trạng thái này.
 */
public final class DeviceLifecycle {
    private DeviceLifecycle() {
    }

    private record Rule(Set<LifecycleStatus> from, LifecycleStatus to) {}

    private static final Map<LifecycleAction, Rule> RULES = new EnumMap<>(LifecycleAction.class);

    static {
        RULES.put(LifecycleAction.ACTIVATE, new Rule(EnumSet.of(PROVISIONED), ACTIVE));
        RULES.put(LifecycleAction.START_MAINTENANCE, new Rule(EnumSet.of(ACTIVE), MAINTENANCE));
        RULES.put(LifecycleAction.END_MAINTENANCE, new Rule(EnumSet.of(MAINTENANCE), ACTIVE));
        RULES.put(LifecycleAction.SUSPEND, new Rule(EnumSet.of(PROVISIONED, ACTIVE, MAINTENANCE), SUSPENDED));
        RULES.put(LifecycleAction.RESUME, new Rule(EnumSet.of(SUSPENDED), ACTIVE));
        RULES.put(LifecycleAction.DECOMMISSION,
                new Rule(EnumSet.of(REGISTERED, PROVISIONED, ACTIVE, MAINTENANCE, SUSPENDED), DECOMMISSIONED));
    }

    /** Trạng thái đích, hoặc ném 409 nếu chuyển không hợp lệ (vd. DECOMMISSIONED -> ACTIVE). */
    public static LifecycleStatus next(LifecycleStatus current, LifecycleAction action) {
        Rule rule = RULES.get(action);
        if (rule == null || !rule.from().contains(current)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "Không thể " + action + " khi thiết bị đang " + current
                            + ". Thao tác hợp lệ: " + allowedActions(current));
        }
        return rule.to();
    }

    public static List<String> allowedActions(LifecycleStatus current) {
        List<String> out = new ArrayList<>();
        if (current == REGISTERED) {
            out.add("PROVISION");
        }
        if (current != REGISTERED && current != DECOMMISSIONED) {
            out.add("ROTATE_CREDENTIALS");
        }
        RULES.forEach((action, rule) -> {
            if (rule.from().contains(current)) {
                out.add(action.name());
            }
        });
        return out;
    }
}
