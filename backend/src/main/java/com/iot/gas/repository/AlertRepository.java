package com.iot.gas.repository;

import com.iot.gas.domain.Alert;
import com.iot.gas.domain.enums.AlertStatus;
import com.iot.gas.domain.enums.AlertType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {
    /** Cảnh báo ngưỡng chưa xử lý của (thiết bị, luật). */
    Optional<Alert> findFirstByDeviceIdAndTypeAndRuleIdAndStatusNot(
            Long deviceId, AlertType type, Long ruleId, AlertStatus status);

    /** Cảnh báo OFFLINE/FAULT chưa xử lý của thiết bị (không gắn luật). */
    Optional<Alert> findFirstByDeviceIdAndTypeAndRuleIdIsNullAndStatusNot(
            Long deviceId, AlertType type, AlertStatus status);

    List<Alert> findByRuleIdAndStatusNot(Long ruleId, AlertStatus status);

    long countByStatus(AlertStatus status);

    /** [severity, status, count] của cảnh báo chưa xử lý xong; all=true bỏ qua lọc thiết bị (ADMIN). */
    @Query("""
           select a.severity, a.status, count(a) from Alert a
           where a.status <> com.iot.gas.domain.enums.AlertStatus.RESOLVED
             and (:all = true or a.deviceId in :deviceIds)
           group by a.severity, a.status
           """)
    List<Object[]> countActive(@Param("all") boolean all, @Param("deviceIds") Collection<Long> deviceIds);
}
