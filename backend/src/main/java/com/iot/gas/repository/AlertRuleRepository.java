package com.iot.gas.repository;

import com.iot.gas.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
    /** Luật đang bật cho metric này: luật chung (device_id null) + luật riêng của thiết bị. */
    @Query("""
           select r from AlertRule r
           where r.enabled = true and r.metric = :metric
             and (r.deviceId is null or r.deviceId = :deviceId)
           """)
    List<AlertRule> findApplicable(@Param("deviceId") Long deviceId, @Param("metric") String metric);

    List<AlertRule> findByDeviceIdIsNullOrDeviceIdIn(List<Long> deviceIds);
}
