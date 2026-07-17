package com.influora.repository;

import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.enums.EmailOutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for email outbox (Domain B, transactional outbox pattern).
 */
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, String> {

    /** Find pending emails ready to send (nextRetryAt is null or in the past). */
    @Query(
            "SELECT e FROM EmailOutbox e WHERE e.status = :status "
                    + "AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now) "
                    + "ORDER BY e.createdAt ASC")
    List<EmailOutbox> findPendingForSend(
            @Param("status") EmailOutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable);

    /** Check idempotency before creating a new outbox entry. */
    Optional<EmailOutbox> findByIdempotencyKey(String idempotencyKey);

    /** Find by userId for admin/debugging purposes. */
    List<EmailOutbox> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
