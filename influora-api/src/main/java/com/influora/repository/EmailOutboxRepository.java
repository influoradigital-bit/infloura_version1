package com.influora.repository;

import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.enums.EmailOutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Repository for email outbox (Domain B, transactional outbox pattern).
 */
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, String> {

    /**
     * Find pending emails ready to send (nextRetryAt is null or in the past), atomically claiming
     * them (D5) so two concurrent {@code EmailWorker} transactions — across app instances, or
     * within one if ShedLock's crash-safety window is ever exceeded — get disjoint batches instead
     * of both picking up (and both sending) the same rows. {@code jakarta.persistence.lock.timeout}
     * {@code -2} is Hibernate's documented magic value for {@code SKIP LOCKED} (there is no portable
     * JPA API for it); must run inside the caller's existing {@code @Transactional} method.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
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
