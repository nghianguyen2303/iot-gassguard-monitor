package com.iot.gas.repository;

import com.iot.gas.domain.Telemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    /** Trả về 1 nếu ghi mới, 0 nếu bản tin trùng (cùng device, metric, ts). */
    @Modifying
    @Query(value = """
           INSERT INTO telemetry (device_id, metric, value, unit, seq, ts, received_at)
           VALUES (:deviceId, :metric, :value, :unit, :seq, :ts, :receivedAt)
           ON CONFLICT ON CONSTRAINT uq_telemetry_dedup DO NOTHING
           """, nativeQuery = true)
    int insertIgnoreDuplicate(@Param("deviceId") Long deviceId,
                              @Param("metric") String metric,
                              @Param("value") double value,
                              @Param("unit") String unit,
                              @Param("seq") Long seq,
                              @Param("ts") Instant ts,
                              @Param("receivedAt") Instant receivedAt);

    List<Telemetry> findByDeviceIdAndMetricAndTsBetweenOrderByTsAsc(
            Long deviceId, String metric, Instant from, Instant to);

    Optional<Telemetry> findTopByDeviceIdAndMetricOrderByTsDesc(Long deviceId, String metric);
}
