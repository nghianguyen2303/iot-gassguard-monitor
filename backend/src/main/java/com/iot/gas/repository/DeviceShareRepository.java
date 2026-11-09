package com.iot.gas.repository;

import com.iot.gas.domain.DeviceShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceShareRepository extends JpaRepository<DeviceShare, DeviceShare.Pk> {
    boolean existsByDeviceIdAndUserId(Long deviceId, Long userId);
    List<DeviceShare> findByDeviceId(Long deviceId);
    void deleteByDeviceIdAndUserId(Long deviceId, Long userId);
}
