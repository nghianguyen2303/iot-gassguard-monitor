package com.iot.gas.repository;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.Connectivity;
import com.iot.gas.domain.enums.LifecycleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByDeviceCode(String deviceCode);
    boolean existsByDeviceCode(String deviceCode);

    /** Thiết bị mà user sở hữu hoặc được chia sẻ quyền xem. */
    @Query("""
           select d from Device d
           where d.ownerId = :userId
              or d.id in (select s.deviceId from DeviceShare s where s.userId = :userId)
           order by d.id
           """)
    List<Device> findAccessibleBy(@Param("userId") Long userId);

    /** Dùng cho watchdog: thiết bị đang online/fault nhưng quá lâu không thấy heartbeat. */
    List<Device> findByLifecycleStatusInAndConnectivityInAndLastSeenAtBefore(
            Collection<LifecycleStatus> statuses, Collection<Connectivity> connectivities, Instant cutoff);

    // ------------------------------------------------------------------
    // Cập nhật có điều kiện (Bước 6). Dùng câu UPDATE riêng thay vì save() cả entity
    // để bản tin MQTT không ghi đè các cột khác (vd. config do người dùng vừa đổi qua API).
    // Giá trị trả về = số dòng thay đổi -> biết trạng thái có thực sự chuyển hay không.
    // ------------------------------------------------------------------

    @Modifying
    @Query("update Device d set d.lastSeenAt = :ts where d.id = :id")
    int touchLastSeen(@Param("id") Long id, @Param("ts") Instant ts);

    /** Chuyển connectivity từ `from` sang `to` (chỉ khi đang đúng là `from`). */
    @Modifying
    @Query("update Device d set d.connectivity = :to, d.updatedAt = :now where d.id = :id and d.connectivity = :from")
    int transitionConnectivity(@Param("id") Long id, @Param("from") Connectivity from,
                               @Param("to") Connectivity to, @Param("now") Instant now);

    /** Đặt connectivity = `to` nếu đang khác `to`. */
    @Modifying
    @Query("update Device d set d.connectivity = :to, d.updatedAt = :now where d.id = :id and d.connectivity <> :to")
    int setConnectivity(@Param("id") Long id, @Param("to") Connectivity to, @Param("now") Instant now);

    /** Chuyển trạng thái vòng đời từ `from` sang `to` (vd. PROVISIONED -> ACTIVE khi nhận dữ liệu đầu tiên). */
    @Modifying
    @Query("update Device d set d.lifecycleStatus = :to, d.updatedAt = :now where d.id = :id and d.lifecycleStatus = :from")
    int transitionLifecycle(@Param("id") Long id, @Param("from") LifecycleStatus from,
                            @Param("to") LifecycleStatus to, @Param("now") Instant now);

    @Modifying
    @Query("update Device d set d.fwVersion = :fw where d.id = :id and (d.fwVersion is null or d.fwVersion <> :fw)")
    int updateFirmware(@Param("id") Long id, @Param("fw") String fw);

    @Modifying
    @Query("update Device d set d.hwVersion = :hw where d.id = :id and (d.hwVersion is null or d.hwVersion <> :hw)")
    int updateHardware(@Param("id") Long id, @Param("hw") String hw);

    /** Gộp trạng thái cơ cấu chấp hành mới vào JSON hiện có (vd. {"fan": true}). */
    @Modifying
    @Query(value = "UPDATE devices SET actuator_state = actuator_state || CAST(:json AS jsonb) WHERE id = :id",
           nativeQuery = true)
    int mergeActuatorState(@Param("id") Long id, @Param("json") String json);
}
