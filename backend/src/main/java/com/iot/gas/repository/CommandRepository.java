package com.iot.gas.repository;

import com.iot.gas.domain.Command;
import com.iot.gas.domain.enums.CommandStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommandRepository extends JpaRepository<Command, UUID> {
    List<Command> findByStatusAndSentAtBefore(CommandStatus status, Instant cutoff);

    List<Command> findByStatusInAndSentAtBefore(Collection<CommandStatus> statuses, Instant cutoff);

    List<Command> findByDeviceIdOrderByCreatedAtDesc(Long deviceId, Pageable pageable);

    /** Đổi trạng thái có điều kiện: chỉ đổi khi lệnh đang ở `from` (không ghi đè ACK đã về). */
    @Transactional
    @Modifying
    @Query("update Command c set c.status = :to, c.resultMessage = :message where c.id = :id and c.status = :from")
    int transition(@Param("id") UUID id, @Param("from") CommandStatus from,
                   @Param("to") CommandStatus to, @Param("message") String message);
}
