package com.influora.service.notification;

import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.enums.EmailOutboxStatus;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.EmailOutboxRepository;
import com.influora.repository.EmailPreferenceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><b>C2 fix (REVIEW-R1.md, T-ADMINMAIL-0903 round 2):</b> {@link #TRANSACTIONAL_PRIORITY_KEYS}
 * is passed into {@link EmailOutboxRepository#findPendingForSend} so a claim batch always fills
 * with every currently-pending priority row (login OTP, password reset) before considering any
 * other {@code templateKey} — see that method's javadoc. This is an {@code ORDER BY} change only;
 * {@link #BATCH_SIZE} and the claim/send/mark phase split above are untouched, so the D5
 * connection-pool reasoning (no DB connection held across the sequential MSG91 calls) still holds.
 *
 * <p><b>C8/item-8 fix:</b> unsubscribe (T-ADMINMAIL-0903 control #5) is otherwise resolved ONCE,
 * at enqueue time, in {@code AdminCustomEmailService.send} — for a batch that can take a long time
 * to fully drain, someone who unsubscribes mid-drain would still receive the mail. {@link
 * #processOne} adds one extra check immediately before dispatch, scoped to {@code
 * ADMIN_CUSTOM_TEMPLATE_KEY} only (a single indexed lookup, and only for this one marketing
 * template key — every other {@code templateKey} skips it entirely, so this does not add a query
 * to the hot path for ordinary transactional/notification email).
 *
 * <p><b>A3 fix (round 4, REVIEW-R3.md):</b> same shape as the item-8 unsubscribe re-check, and for
 * the same underlying reason — {@code AdminCustomEmailService.cancel} can mark a row terminal
 * WHILE it is already claimed and sitting in this worker's in-memory batch ({@link #claimBatch}
 * only touches {@code nextRetryAt}, not {@code status}, so a claimed row is still {@code PENDING}
 * and still matches {@code cancel}'s filter). {@link #processOne} re-checks that the row is still
 * {@code PENDING} immediately before dispatch, scoped to {@code ADMIN_CUSTOM_TEMPLATE_KEY} only —
 * without this, a cancelled row would be sent anyway and {@link #applyResult}'s {@code
 * markSent()} would silently overwrite the CANCELLED status back to SENT, leaving {@code cancel}'s
 * reported count wrong with no trace of the mail that actually went out.
 *
 * <p><b>A4 fix (round 5, REVIEW-R4.md):</b> {@code spring.mail}'s connect/read/write timeouts are
 * 10s each, so a full {@link #BATCH_SIZE} (50) batch that all times out under degraded SMTP takes
 * up to ~500s — longer than both {@link #CLAIM_LEASE} (3 min) and {@code @SchedulerLock}'s {@code
 * lockAtMostFor} (5 min). If a real run ever takes that long, its own claim lease expires
 * mid-batch and a later poll (this instance or another) can re-claim and re-send rows this run has
 * already dispatched — duplicate mail, precisely under the degraded conditions a large blast
 * induces. {@link #processOutbox} now tracks elapsed wall-clock against {@link
 * #maxBatchWallClock} (comfortably under {@code CLAIM_LEASE}, leaving margin for the claim/mark
 * transactions and clock skew) and, once a later row would risk crossing it, stops dispatching for
 * THIS poll. This touches nothing else — {@link #BATCH_SIZE}, the claim/send/mark phase split, and
 * priority ordering are all untouched. The rows left un-dispatched are not orphaned: {@link
 * #claimBatch} already committed them as {@code PENDING} with {@code nextRetryAt} set to this
 * batch's lease (see {@link EmailOutbox#markClaimed}), so {@link
 * EmailOutboxRepository#findPendingForSend}'s {@code WHERE} clause skips them until that lease
 * naturally passes, at which point a normal poll claims and sends them exactly once — a delay,
 * not a duplicate, for every row the budget defers.
 *
 * <p><b>Known gap in that guarantee, under degraded SMTP.</b> {@link #maxBatchWallClock} is
 * checked BEFORE dispatching each row, and the deadline is measured from after the claim
 * transaction committed while the lease was stamped inside it — so the deadline clock starts
 * slightly later than the lease clock. A row that begins at {@code deadline - ε} finishes at
 * {@code deadline + rowDuration}, and {@code markResult} then opens a further transaction. With a
 * 30s margin ({@code CLAIM_LEASE - 30s}), any single row taking longer than ~30s pushes the run
 * past its own {@code CLAIM_LEASE}; the row becomes visible to {@code findPendingForSend} again
 * and another instance may re-claim and re-send it. {@code spring.mail}'s 10s timeout is per
 * socket read, not per message, so a slow-but-responsive relay can exceed 30s for one message
 * across an SMTP conversation's many round trips. Requires degraded SMTP AND multiple instances
 * AND a poll landing in the window; blast radius is one duplicate marketing email, never an OTP
 * (those are single-row and priority-ordered). Closing it properly means deriving the deadline
 * from the lease instant itself and reserving margin for the mark transaction — tracked, not
 * done here.
 */
@Component
public class EmailWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);
    private static final int BATCH_SIZE = 50;

    /** Mirrors {@code EmailTemplateRegistry.NO_UNSUBSCRIBE_FOOTER} (that field is private, a
     * different package — this is the same three literal keys, kept here since {@code EmailWorker}
     * needs them for query priority, not footer suppression). auth.otp / auth.password_reset /
     * otpman must never wait behind a marketing blast (C2, REVIEW-R1.md). */
    private static final Set<String> TRANSACTIONAL_PRIORITY_KEYS =
            Set.of("auth.otp", "otpman", "auth.password_reset");

    /** Mirrors {@code AdminCustomEmailService.TEMPLATE_KEY} / {@code
     * EmailTemplateRegistry.ADMIN_CUSTOM_TEMPLATE_KEY} — see class javadoc "C8/item-8 fix". */
    private static final String ADMIN_CUSTOM_TEMPLATE_KEY = "admin.custom";

    /** D5: crash-recovery window for the claim lease — must stay comfortably under
     * {@code @SchedulerLock}'s {@code lockAtMostFor = "PT5M"} so a normal run never has its own
     * claim expire out from under it, while still being short enough that a crashed run's rows
     * become retryable well within this worker's own next few 30s polls. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(3);

    /** A4 fix (round 5, REVIEW-R4.md): default wall-clock budget for one {@link #processOutbox}
     * run — 30s of margin under {@link #CLAIM_LEASE} for the claim transaction that already ran
     * and the per-item mark transactions still to come, so a batch that respects this budget can
     * never actually outlive the lease it was claimed under. See class javadoc "A4 fix". */
    private static final Duration DEFAULT_MAX_BATCH_WALL_CLOCK = CLAIM_LEASE.minus(Duration.ofSeconds(30));

    private final EmailOutboxRepository emailOutboxRepository;
    private final EmailPreferenceRepository emailPreferenceRepository;
    private final Msg91EmailClient msg91Client;
    private final TransactionTemplate transactionTemplate;
    private final Duration maxBatchWallClock;

    @Autowired
    public EmailWorker(
            EmailOutboxRepository emailOutboxRepository,
            EmailPreferenceRepository emailPreferenceRepository,
            Msg91EmailClient msg91Client,
            PlatformTransactionManager transactionManager) {
        this(
                emailOutboxRepository,
                emailPreferenceRepository,
                msg91Client,
                transactionManager,
                DEFAULT_MAX_BATCH_WALL_CLOCK);
    }

    /** A4 fix (round 5, REVIEW-R4.md): package-private overload taking the wall-clock budget
     * explicitly, purely so {@code EmailWorkerTest} can exercise the deadline boundary
     * deterministically (a tiny {@link Duration}) instead of waiting out the real ~2.5 minute
     * default. Correction to an earlier version of this note: Spring's {@code
     * AutowiredAnnotationBeanPostProcessor} inspects every declared constructor on the class,
     * package-private ones included — it does not skip this overload, and it was never blind to
     * it. With two multi-arg constructors on the classpath and neither marked, the processor had
     * no basis to choose between them and no no-arg fallback existed either, which is exactly why
     * this class used to fail at boot with {@code NoSuchMethodException: <init>()}. The fix is on
     * the public constructor above, now explicitly annotated {@code @Autowired} so the container
     * has a single unambiguous entry point; this 5-arg overload is deliberately left un-annotated
     * and stays reachable only by {@code EmailWorkerTest} calling it directly. */
    EmailWorker(
            EmailOutboxRepository emailOutboxRepository,
            EmailPreferenceRepository emailPreferenceRepository,
            Msg91EmailClient msg91Client,
            PlatformTransactionManager transactionManager,
            Duration maxBatchWallClock) {
        this.emailOutboxRepository = emailOutboxRepository;
        this.emailPreferenceRepository = emailPreferenceRepository;
        this.msg91Client = msg91Client;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxBatchWallClock = maxBatchWallClock;
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

        // A4 fix (round 5, REVIEW-R4.md): see class javadoc "A4 fix". Deadline is measured from
        // here, right after the claim transaction (which set every row's lease) committed — the
        // closest available approximation of the lease's own start, without threading a captured
        // Instant back out of claimBatch()'s TransactionTemplate.execute() just for this.
        Instant batchDeadline = Instant.now().plus(maxBatchWallClock);
        int dispatched = 0;
        for (EmailOutbox outbox : claimed) {
            if (Instant.now().isAfter(batchDeadline)) {
                log.warn(
                        "EmailWorker batch wall-clock budget ({}) exceeded after {} of {} claimed"
                                + " rows -- deferring the rest to a later poll (A4 fix,"
                                + " REVIEW-R4.md). They stay claimed (PENDING, nextRetryAt = this"
                                + " batch's lease) and are only reclaimed once that lease naturally"
                                + " passes, so this never double-sends.",
                        maxBatchWallClock,
                        dispatched,
                        claimed.size());
                break;
            }
            // Read everything needed for the send BEFORE the transaction that claimed it closed
            // (already committed by now) — these are plain field reads off a detached instance,
            // never reused for a later save.
            processOne(
                    outbox.getId(),
                    outbox.getToEmail(),
                    outbox.getTemplateKey(),
                    outbox.getTemplateData(),
                    outbox.getUserId());
            dispatched++;
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
                        EmailOutboxStatus.PENDING,
                        Instant.now(),
                        TRANSACTIONAL_PRIORITY_KEYS,
                        PageRequest.of(0, BATCH_SIZE));
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
     *
     * <p>C8/item-8 fix: for {@code ADMIN_CUSTOM_TEMPLATE_KEY} only, re-checks unsubscribe status
     * right here — immediately before the MSG91 call, as late as this architecture allows without
     * re-opening the claim transaction — instead of trusting the enqueue-time check alone. Every
     * other {@code templateKey} skips this branch entirely (one cheap {@code String.equals}), so
     * ordinary transactional/notification email pays no extra query.
     *
     * <p>A3 fix (round 4, REVIEW-R3.md): same shape, same scope, for cancellation instead of
     * unsubscribe — see class javadoc "A3 fix". If the row is no longer {@code PENDING} it has
     * already been marked terminal by {@code AdminCustomEmailService#cancel} (or, in principle, by
     * some other terminal transition) since it was claimed; this method must not touch it at all
     * (no send, no {@link #markResult} call) so that terminal state is never overwritten.
     */
    private void processOne(
            String id, String toEmail, String templateKey, String templateData, String userId) {
        if (ADMIN_CUSTOM_TEMPLATE_KEY.equals(templateKey)) {
            if (emailPreferenceRepository
                    .findUnsubscribedUserIds(List.of(userId), templateKey)
                    .contains(userId)) {
                log.info(
                        "admin.custom outbox row skipped at dispatch: userId={} unsubscribed after"
                                + " enqueue, before this row was claimed (id={})",
                        userId,
                        id);
                markSkipped(id);
                return;
            }
            if (!emailOutboxRepository.existsByIdAndStatus(id, EmailOutboxStatus.PENDING)) {
                log.info(
                        "admin.custom outbox row skipped at dispatch: id={} is no longer PENDING —"
                                + " cancelled by admin after this row was claimed, before it was"
                                + " sent. Leaving its terminal status untouched.",
                        id);
                return;
            }
        }

        boolean success;
        String errorMessage = null;
        try {
            success = msg91Client.sendTemplateEmail(toEmail, templateKey, templateData, userId);
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
     * C8/item-8 fix: terminal, non-retried outcome for a row skipped at dispatch because the
     * recipient unsubscribed after enqueue. Reuses {@code EmailOutboxStatus.FAILED} — the DB enum
     * has no dedicated "skipped" value, and adding one is a schema change out of scope here — but
     * {@link EmailOutbox#markSkippedUnsubscribed()} sets {@code retryCount} straight to the max so
     * {@code canRetry()} is immediately false: no backoff loop, no further MSG91 attempts, one poll
     * and done.
     */
    private void markSkipped(String id) {
        transactionTemplate.executeWithoutResult(
                status ->
                        emailOutboxRepository
                                .findById(id)
                                .ifPresent(
                                        outbox -> {
                                            outbox.markSkippedUnsubscribed();
                                            emailOutboxRepository.save(outbox);
                                        }));
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
