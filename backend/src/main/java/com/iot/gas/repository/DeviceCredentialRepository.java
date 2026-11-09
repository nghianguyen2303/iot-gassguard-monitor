package com.iot.gas.repository;

import com.iot.gas.domain.DeviceCredential;
import com.iot.gas.domain.enums.CredentialStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceCredentialRepository extends JpaRepository<DeviceCredential, Long> {
    Optional<DeviceCredential> findFirstByDeviceIdAndStatusNot(Long deviceId, CredentialStatus status);
    List<DeviceCredential> findByDeviceIdOrderByIssuedAtDesc(Long deviceId);
}
