package com.influora.repository;

import com.influora.domain.entity.AdminEmailSendLock;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Repository for the {@code admin_email_send_lock} singleton mutex row (T-ADMINMAIL-0903 round 3,
 * B1). See {@link AdminEmailSendLock} class javadoc for why a {@code SELECT ... FOR UPDATE} here
 * is what actually serializes {@code AdminCustomEmailService#send}.
 */
public interface AdminEmailSendLockRepository extends JpaRepository<AdminEmailSendLock, String> {

    /**
     * Blocking pessimistic-write lock on the singleton row, bounded to 10s
     * ({@code jakarta.persistence.lock.timeout}, milliseconds) rather than MySQL's default
     * {@code innodb_lock_wait_timeout} (50s) — this is an admin-facing HTTP request, so a caller
     * stuck behind another in-flight send should get a clear, fast 429
     * ({@code AdminCustomEmailService#acquireSendLock}'s {@code SEND_IN_PROGRESS}) rather than
     * hanging for most of a minute. Must run inside the caller's own {@code @Transactional}
     * method — the lock is held until that transaction commits or rolls back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT l FROM AdminEmailSendLock l WHERE l.id = :id")
    Optional<AdminEmailSendLock> lockForUpdate(@Param("id") String id);
}
