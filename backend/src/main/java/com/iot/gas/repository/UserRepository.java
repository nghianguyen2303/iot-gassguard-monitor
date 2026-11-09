package com.iot.gas.repository;

import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<User> findAllByRole(Role role);

    List<User> findByTelegramChatId(Long chatId);

    /** Người nhận cảnh báo Telegram của 1 thiết bị: đã liên kết, đang hoạt động, có quyền xem (admin / chủ / được chia sẻ). */
    @Query("""
            select u from User u
            where u.telegramChatId is not null
              and u.status = com.iot.gas.domain.enums.UserStatus.ACTIVE
              and (u.role = com.iot.gas.domain.enums.Role.ADMIN
                   or u.id = :ownerId
                   or u.id in (select s.userId from DeviceShare s where s.deviceId = :deviceId))
            """)
    List<User> findTelegramRecipients(@Param("deviceId") Long deviceId, @Param("ownerId") Long ownerId);

    /** Người nhận cảnh báo email của 1 thiết bị: đã xác nhận email, đang hoạt động, có quyền xem. */
    @Query("""
            select u from User u
            where u.alertEmail is not null
              and u.status = com.iot.gas.domain.enums.UserStatus.ACTIVE
              and (u.role = com.iot.gas.domain.enums.Role.ADMIN
                   or u.id = :ownerId
                   or u.id in (select s.userId from DeviceShare s where s.deviceId = :deviceId))
            """)
    List<User> findEmailRecipients(@Param("deviceId") Long deviceId, @Param("ownerId") Long ownerId);
}
