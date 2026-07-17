package com.influora.service.notification;

import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.enums.EmailOutboxStatus;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.EmailOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled worker that processes the email outbox (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md).
 * Polls every 30 seconds, picks up pending emails, sends via MSG91, and updates status.
 *
 * <p>Uses exponential backoff on failures (30s, 90s, 270s, 810s) with max 5 retries.
 *
 * <p><b>D3 (distributed locking):</b> {@code @SchedulerLock} below stops two app instances'
 * 30-second triggers from running {@link #processOutbox} concurrently in the normal case. That
 * alone isn't sufficient on its own, though: ShedLock's {@code lockAtMostFor} is a crash-safety
 * upper bound — if one instance's run legitimately takes longer than that (slow MSG91 calls, GC
 * pause), a second instance is allowed to acquire the lock and start its own run while the first
 * is still going, which would double-send without a second layer of defense. {@link
 * com.influora.repository.EmailOutboxRepository#findPendingForSend} closes that gap: it now reads
 * with {@code SELECT ... FOR UPDATE SKIP LOCKED}, so even two genuinely concurrent transactions
 * atomically claim disjoint batches of outbox rows instead of both reading (and both sending) the
 * same pending set.
 *
 * <p><b>D5 fix:</b> the whole method used to be one {@code @Transactional} wrapping the
 * {@code FOR UPDATE SKIP LOCKED} read AND all 50 sequential, blocking MSG91 HTTP calls: any
 * uncaught failure late in the batch rolled back every already-{@code markSent()} row even
 * though the emails had already gone out externally (duplicate sends on the next poll), and the
 * DB connection sat pinned to the pool for the full duration of up to 50 sequential HTTP round
 * trips (pool starvation for every other request needing a connection). This now runs in three
 * phases (explicit {@link TransactionTemplate} demarcation, never {@code @Transactional} on a
 * private method — Spring's proxy-based AOP silently no-ops on self-invocation):
 *
 * <ol>
 *   <li><b>Claim</b> ({@link #claimBatch()}): one short, DB-only transaction — the existing
 *       {@code FOR UPDATE SKIP LOCKED} select, then immediately lease every claimed row via
 *       {@link EmailOutbox#markClaimed(Instant)} (pushes {@code nextRetryAt} into the future
 *       without touching {@code status}) and commit. No HTTP call happens while any lock is
 *       held.
 *   <li><b>Send</b> ({@link #processOne}): one MSG91 call per item, with <b>no transaction
 *       open and no DB connection held</b>.
 *   <li><b>Mark</b> ({@link #markResult}): one short transaction per item, re-fetching a fresh
 *       managed row by id and writing {@code markSent()}/{@code markFailed()}. A failure marking
 *       one item can no longer roll back the 49 others that already committed.
 * </ol>
 */
@Component
public class EmailWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);
    private static final int BATCH_SIZE = 50;

    /** D5: crash-recovery window for the claim lease — must stay comfortably under
     * {@code @SchedulerLock}'s {@code lockAtMostFor = "PT5M"} so a normal run never has its own
     * claim expire out from under it, while still being short enough that a crashed run's rows
     * become retryable well within this worker's own next few 30s polls. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(3);

    private final EmailOutboxRepository emailOutboxRepository;
    private final Msg91EmailClient msg91Client;
    private final TransactionTemplate transactionTemplate;

    public EmailWorker(
            EmailOutboxRepository emailOutboxRepository,
            Msg91EmailClient msg91Client,
            PlatformTransactionManager transactionManager) {
        this.emailOutboxRepository = emailOutboxRepository;
        this.msg91Client = msg91Client;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Polls the email outbox every 30 seconds and processes pending emails. Deliberately NOT
     * {@code @Transactional} — see class javadoc's D5 fix. Each phase below opens/closes its own
     * short transaction (or none at all, for the blocking send).
     */
    @Scheduled(fixedDelay = 30000) // 30 seconds
    @SchedulerLock(name = "EmailWorker_processOutbox", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void processOutbox() {
        List<EmailOutbox> claimed = transactionTemplate.execute(status -> claimBatch());
        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        log.info("Processing {} pending emails", claimed.size());

        for (EmailOutbox outbox : claimed) {
            // Read everything needed for the send BEFORE the transaction that claimed it closed
            // (already committed by now) — these are plain field reads off a detached instance,
            // never reused for a later save.
            processOne(outbox.getId(), outbox.getToEmail(), outbox.getTemplateKey(), outbox.getTemplateData());
        }
    }

    /**
     * D5 claim phase: short, DB-only transaction. Selects up to {@link #BATCH_SIZE} pending
     * rows via the existing {@code FOR UPDATE SKIP LOCKED} query, then leases every one of them
     * (see {@link EmailOutbox#markClaimed(Instant)}) before this transaction commits — no HTTP
     * call happens while the row locks are held.
     */
    private List<EmailOutbox> claimBatch() {
        List<EmailOutbox> pending =
                emailOutboxRepository.findPendingForSend(
                        EmailOutboxStatus.PENDING, Instant.now(), PageRequest.of(0, BATCH_SIZE));
        if (pending.isEmpty()) {
            return pending;
        }
        Instant leaseUntil = Instant.now().plus(CLAIM_LEASE);
        for (EmailOutbox outbox : pending) {
            outbox.markClaimed(leaseUntil);
        }
        emailOutboxRepository.saveAll(pending);
        return pending;
    }

    /**
     * D5 send phase: exactly one blocking MSG91 call, with no transaction open. Never throws —
     * any failure (including a thrown exception from the client) is captured and handed to the
     * mark phase as a failure result.
     */
    private void processOne(String id, String toEmail, String templateKey, String templateData) {
        boolean success;
        String errorMessage = null;
        try {
            success = msg91Client.sendTemplateEmail(toEmail, templateKey, templateData);
            if (!success) {
                errorMessage = "MSG91 returned failure";
            }
        } catch (Exception e) {
            success = false;
            errorMessage = e.getMessage();
        }
        markResult(id, success, errorMessage);
    }

    /**
     * D5 mark phase: its own short transaction per item, re-fetching a fresh managed row by id.
     * If the row was deleted/vanished between claim and mark, this is a silent no-op (nothing
     * left to mark).
     */
    private void markResult(String id, boolean success, String errorMessage) {
        transactionTemplate.executeWithoutResult(
                status ->
                        emailOutboxRepository
                                .findById(id)
                                .ifPresent(outbox -> applyResult(outbox, success, errorMessage)));
    }

    private void applyResult(EmailOutbox outbox, boolean success, String errorMessage) {
        if (success) {
            outbox.markSent();
            emailOutboxRepository.save(outbox);
            log.debug(
                    "Email sent successfully: id={}, toEmail={}, templateKey={}",
                    outbox.getId(),
                    outbox.getToEmail(),
                    outbox.getTemplateKey());
            return;
        }

        outbox.markFailed(errorMessage);
        emailOutboxRepository.save(outbox);

        if (outbox.canRetry()) {
            log.warn(
                    "Email send failed, will retry: id={}, attempt={}, nextRetry={}, error={}",
                    outbox.getId(),
                    outbox.getRetryCount(),
                    outbox.getNextRetryAt(),
                    errorMessage);
        } else {
            log.error(
                    "Email send failed permanently: id={}, toEmail={}, templateKey={}, error={}",
                    outbox.getId(),
                    outbox.getToEmail(),
                    outbox.getTemplateKey(),
                    errorMessage);
        }
    }
}
