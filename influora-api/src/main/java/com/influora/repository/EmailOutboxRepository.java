package com.influora.repository;

import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.enums.EmailOutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
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
     *
     * <p><b>C2 fix (REVIEW-R1.md, T-ADMINMAIL-0903 round 2):</b> {@code priorityKeys} (typically
     * {@code EmailWorker.TRANSACTIONAL_PRIORITY_KEYS} — OTP + password reset) always sorts first,
     * ahead of {@code createdAt}, regardless of how long any non-priority row (an {@code
     * admin.custom} marketing blast, in particular) has been sitting in the queue. Previously this
     * ordered strictly by {@code createdAt ASC} across every {@code templateKey} with no priority
     * at all: a 5,000-row {@code admin.custom} send drains at {@code BATCH_SIZE} rows per 30s poll,
     * so any {@code auth.otp}/{@code auth.password_reset} row landing behind even a fraction of
     * that blast could wait tens of minutes for a login code. With this CASE-based ordering, a
     * batch is filled with EVERY currently-pending priority row before a single non-priority row is
     * even considered, so a login/reset email queued behind an in-flight blast is claimed on the
     * very next poll, not after the blast finishes draining. Deliberately does NOT touch {@code
     * BATCH_SIZE}, the sequential-HTTP-call shape, or the claim/send/mark phase split above — see
     * {@code EmailWorker}'s class javadoc for why those are what they are.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(
            "SELECT e FROM EmailOutbox e WHERE e.status = :status "
                    + "AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now) "
                    + "ORDER BY CASE WHEN e.templateKey IN :priorityKeys THEN 0 ELSE 1 END, e.createdAt ASC")
    List<EmailOutbox> findPendingForSend(
            @Param("status") EmailOutboxStatus status,
            @Param("now") Instant now,
            @Param("priorityKeys") Collection<String> priorityKeys,
            Pageable pageable);

    /** Check idempotency before creating a new outbox entry. */
    Optional<EmailOutbox> findByIdempotencyKey(String idempotencyKey);

    /**
     * T-ADMINMAIL-0903 round 4, A3 fix (REVIEW-R3.md): {@code EmailWorker#processOne} re-checks
     * this immediately before dispatch, scoped to {@code admin.custom} only, so a row {@code
     * AdminCustomEmailService#cancel} marks terminal WHILE this row is already claimed and sitting
     * in the worker's in-memory batch (detached, read before cancel ran) is not sent anyway. Before
     * this fix, {@code cancel()}'s own {@code status = PENDING} filter still matched such a row
     * (claiming only pushes {@code nextRetryAt}, it does not change {@code status}), so the admin
     * was told the row was cancelled while the worker sent it regardless and {@code markSent()}
     * then silently overwrote the CANCELLED (FAILED) status back to SENT — a control whose whole
     * purpose is stopping a mistake, reporting a success it did not achieve. A single indexed
     * lookup on the primary key, same cost shape as the item-8 unsubscribe re-check this mirrors.
     */
    boolean existsByIdAndStatus(String id, EmailOutboxStatus status);

    /**
     * T-ADMINMAIL-0903 round 3, B3 (REVIEW-R2.md ship-blocker): every still-PENDING {@code
     * admin.custom} outbox row for one campaign, so {@code AdminCustomEmailService#cancel} can
     * mark them terminal. Scoped by {@code idempotencyKey} prefix ({@code
     * "admin.custom:<campaignId>:"}) rather than a dedicated {@code campaign_id} column on {@code
     * email_outbox} — that format is already the fixed idempotency-key shape every row carries
     * (see {@code AdminCustomEmailService#send}), so no schema change was needed to answer "which
     * rows belong to this campaign." {@code templateKey} is included so this can never match a
     * row from a different template that happened to share a literal idempotency-key substring.
     */
    List<EmailOutbox> findByTemplateKeyAndStatusAndIdempotencyKeyStartingWith(
            String templateKey, EmailOutboxStatus status, String idempotencyKeyPrefix);

    /** Find by userId for admin/debugging purposes. */
    List<EmailOutbox> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    // ---- Admin email-queue console (emailApi, api-contracts.ts 677-706) ----

    /** Queue listing, newest first, no status filter. */
    Page<EmailOutbox> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Queue listing filtered to a single status, newest first. */
    Page<EmailOutbox> findByStatusOrderByCreatedAtDesc(EmailOutboxStatus status, Pageable pageable);

    /** {@code stats.pending}. */
    long countByStatus(EmailOutboxStatus status);

    /** {@code stats.sent24h} — real: rows actually sent within the window. */
    long countByStatusAndSentAtAfter(EmailOutboxStatus status, Instant since);

    /**
     * {@code stats.failed24h} — approximate: outbox has no {@code failed_at} column, so this counts
     * FAILED rows *created* within the window, not failed within it. See {@code
     * AdminEmailService.getStats}.
     */
    long countByStatusAndCreatedAtAfter(EmailOutboxStatus status, Instant since);

    /** {@code stats.avgDeliveryTime} basis — SENT rows in the window, to average sentAt-createdAt. */
    List<EmailOutbox> findByStatusAndSentAtAfter(EmailOutboxStatus status, Instant since);

    /**
     * {@code getTemplates} — distinct template keys actually present in the outbox. There is no
     * server-side template registry (MSG91 owns names/subjects), so this is the only real,
     * non-fabricated template list available. See {@code AdminEmailService.getTemplates}.
     */
    @Query("SELECT DISTINCT e.templateKey FROM EmailOutbox e ORDER BY e.templateKey ASC")
    List<String> findDistinctTemplateKeys();
}
