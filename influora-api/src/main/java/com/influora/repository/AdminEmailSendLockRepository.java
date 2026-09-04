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
     * Blocking pessimistic-write lock on the singleton row. Must run inside the caller's own
     * {@code @Transactional} method — the lock is held until that transaction commits or rolls
     * back.
     *
     * <p><b>The 10s {@code jakarta.persistence.lock.timeout} hint below is inert on MySQL.</b>
     * Hibernate's {@code MySQLDialect} honours only {@code NO_WAIT} (0) and {@code SKIP_LOCKED}
     * (-2) and otherwise emits a bare {@code FOR UPDATE}, so the real bound is the server's
     * {@code innodb_lock_wait_timeout} (50s by default). A caller queued behind an in-flight send
     * therefore usually blocks for most of a minute and then surfaces the underlying timeout —
     * {@code AdminCustomEmailService#acquireSendLock}'s fast {@code SEND_IN_PROGRESS} 429 mostly
     * will not fire. The hint is kept because it IS honoured on other dialects (and on the H2
     * harness in {@code AdminEmailSendLockRepositoryConcurrencyTest}), but do not rely on it for
     * request latency here. Making the 429 real on MySQL needs {@code innodb_lock_wait_timeout}
     * set on the connection, or a {@code NO_WAIT} lock plus an application-level retry.
     *
     * <p>This javadoc previously asserted the 10s bound as fact — corrected after a review caught
     * that the code does not deliver it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT l FROM AdminEmailSendLock l WHERE l.id = :id")
    Optional<AdminEmailSendLock> lockForUpdate(@Param("id") String id);
}
