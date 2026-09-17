package com.influora.job;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.integration.razorpay.RazorpayClient.SubscriptionSnapshot;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.BrandContextService;
import com.influora.service.billing.SubscriptionBillingEmailPublisher;
import com.influora.service.billing.SubscriptionService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Renewal safety net — Task 24 subscription-billing Phase 4a. Money-lifecycle code: mandatory
 * Kabir gate after Kavya, same discipline as Phase 2/3a.
 *
 * <p><b>This is NOT the primary renewal mechanism.</b> The primary mechanism is Razorpay's own
 * {@code subscription.charged} webhook ({@code RazorpayWebhookController}), which advances {@code
 * Subscription.currentPeriodStart}/{@code currentPeriodEnd} into the future on every successful
 * recurring charge. This job exists purely to catch the case where that webhook was missed or
 * delayed — a workspace whose subscription is still nominally {@code ACTIVE} but whose {@code
 * currentPeriodEnd} has already passed is stuck with a stale billing-cycle anchor: {@code
 * UsageCounterService#resolvePeriodStart} derives the "current period" straight from that column,
 * so a stale {@code currentPeriodStart} means the metered usage caps (tracked creators, analytics
 * views — the whole of {@code UsageMetric}) never reset even though the workspace should be in a
 * fresh billing cycle. Export is NOT among them: it is a per-plan boolean
 * ({@code Plan.isExportEnabled()}), not a counter, so it has nothing to reset.
 *
 * <p><b>Query naturally excludes already-renewed subscriptions</b> (per the task breakdown):
 * {@code currentPeriodEnd < now()} is only ever true for a subscription the webhook has NOT yet
 * advanced. The moment the real webhook fires (before or after this job runs), {@code
 * currentPeriodEnd} moves into the future and this job's query stops matching that row on every
 * subsequent day — no separate idempotency/dedup bookkeeping is needed beyond this condition
 * itself.
 *
 * <p><b>A stale-ACTIVE row is NOT a single case — {@link #processOne} routes it by what actually
 * produced the row</b> (F-0859/F-0860/F-0861 fix, further hardened by Kabir's S2 review — see the
 * per-branch notes below):
 *
 * <ol>
 *   <li><b>{@link Subscription#isCancelAtPeriodEnd()}</b> — the customer already cancelled; {@link
 *       #cancelOne} finalizes it to {@code CANCELLED} via {@link
 *       SubscriptionService#finalizeLapsedCancellation}. Checked FIRST, ahead of every other
 *       branch (BL-2, BrandF.md §98 — see below).
 *   <li><b>{@link Subscription#isComp()}</b> — an admin comp/override grant (F-0859). Nothing else
 *       in the system enforces {@link Subscription#getCompExpiresAt()}; {@link #expireCompOne}
 *       demotes it to Free via {@link SubscriptionService#expireComp}.
 *   <li><b>Free plan, not comp, no {@code razorpaySubscriptionId}</b> (F-0861) — a workspace's
 *       lazily-created Free row that simply hasn't rolled its period anchor forward.
 *       {@link #advanceFreeOne} advances the period ONLY, via {@link
 *       SubscriptionService#advanceFreePeriod} — no credit reset, no plan-allotment call (Free's
 *       credit reset is {@code AICreditResetJob}'s job, monthly, for every workspace regardless of
 *       plan; resetting it again here would double-reset the exact workspace that job already
 *       covers).
 *   <li><b>{@code razorpaySubscriptionId != null}</b> (F-0860) — a real Razorpay-backed Pro row.
 *       Previously this job blindly re-renewed ANY such row on the sole assumption that a stale
 *       period always means "the webhook was missed but the customer is still paying" — which is
 *       false whenever Razorpay itself already knows the subscription lapsed (a lost {@code
 *       subscription.pending}/{@code .halted} webhook, not just a lost {@code .charged}). {@link
 *       #syncFromRazorpay} fetches the live status via {@link RazorpayClient#fetchSubscription}
 *       FIRST and routes on what Razorpay actually reports:
 *       <ul>
 *         <li>{@code active} with a {@code current_end} that is genuinely NEWER than both the
 *             local {@code currentPeriodEnd} and {@code now()} — {@link #renewFromRazorpayOne}
 *             renews using Razorpay's own {@code current_start}/{@code current_end}. (Kabir S2
 *             HIGH fix: {@code active} with an UNCHANGED period — normal with UPI autopay/eMandate
 *             subscriptions where the next charge simply hasn't landed yet — is left untouched,
 *             counted {@code awaitingCharge}, not renewed and not estimated; the previous-
 *             cycle-length estimate this job used to fall back to when Razorpay returned no period
 *             is GONE entirely from this path, because it was exactly how an unpaid cycle could
 *             refill Pro AI credits for free, day after day, until the real charge landed. Kabir S2
 *             LOW fix: {@code authenticated} is no longer treated as a renewal trigger — the real
 *             webhook never maps it to ACTIVE either, so this job must not either.)
 *         <li>{@code pending}/{@code halted}/{@code cancelled}/{@code completed}/{@code expired} —
 *             {@link #syncStatusOne} applies the identical status-only transition {@code
 *             RazorpayWebhookController} applies for that Razorpay event, via {@link
 *             SubscriptionService#applySubscriptionWebhookUpdate}, and — Kabir S2 LOW fix —
 *             publishes the SAME billing email the real webhook would for HALTED/PAST_DUE via the
 *             now-shared {@link SubscriptionBillingEmailPublisher} (previously this job's own
 *             job-driven transitions sent no email at all).
 *         <li>A fetch failure, or any other/unrecognized status, changes nothing (counted {@code
 *             failed}, retried on the next run).
 *       </ul>
 *   <li><b>Pro plan, not comp, no {@code razorpaySubscriptionId}</b> — an unverifiable row: paid
 *       tier with nothing to confirm against Razorpay and no admin grant to explain it. {@link
 *       #processOne} refuses to extend it (logs at ERROR, counted {@code failed}); no code path in
 *       this codebase currently produces this state (see that method's own comment for the
 *       call-site audit), so seeing this log line in production means either a data-integrity bug
 *       or a manual DB edit, and needs investigation rather than silent renewal.
 * </ol>
 *
 * <p><b>Kabir S2 MEDIUM fix — fetch-to-write race.</b> Between this job reading a stale row,
 * calling out to Razorpay, and writing its own conclusion, a REAL webhook for that exact
 * subscription can land and apply its own (more authoritative) transition. Two failure modes were
 * possible before this fix, both now closed:
 *
 * <ul>
 *   <li><i>Earlier webhook overwritten.</i> This job used to timestamp its own write with {@code
 *       Instant.now()} captured AFTER the Razorpay round-trip — later than a real webhook that
 *       landed and applied itself in the meantime, so the job's write would look newer and
 *       overwrite it. {@link #syncFromRazorpay} now captures {@code fetchedAt} BEFORE calling
 *       {@link RazorpayClient#fetchSubscription}, and that earlier instant is what's passed as
 *       {@code webhookEventAt} to {@link SubscriptionService#applySubscriptionWebhookUpdate} in
 *       {@link #syncStatusOne} — that method's own out-of-order-delivery guard (its class javadoc)
 *       then correctly treats the job's write as OLDER than the intervening real webhook and skips
 *       it.
 *   <li><i>Later webhook dropped.</i> The same {@code fetchedAt}-before-fetch capture also fixes
 *       the opposite direction: a real webhook that is DELIVERED after this job's write, but whose
 *       own {@code created_at} is naturally after {@code fetchedAt} (any real event's clock is
 *       always ahead of a timestamp captured before this job even started talking to Razorpay), is
 *       no longer mistaken for stale against the job's own watermark.
 *   <li><i>The renewal path had no such guard at all.</i> {@link #renewFromRazorpayOne} does not go
 *       through {@link SubscriptionService#applySubscriptionWebhookUpdate}'s staleness check —
 *       it now instead calls {@link SubscriptionService#applyRenewalSafetyNetIfUnchanged}, which
 *       re-reads the row FRESH, inside its own transaction, and refuses to renew unless the row's
 *       status/period still match what this job observed before calling Razorpay. If the row moved
 *       (a real webhook landed concurrently), the renewal is skipped — counted {@code
 *       raceSkipped}, never a clobber.
 * </ul>
 *
 * <p><b>Cycle-length estimate — REMOVED from the Razorpay-backed path (Kabir S2 HIGH fix).</b> This
 * job used to estimate a next period boundary (re-applying the previous cycle length, or a 30-day
 * fallback) whenever Razorpay's response carried no {@code current_start}/{@code current_end}, or
 * — the actual bug — whenever it carried the SAME unchanged period as an {@code active} status.
 * There is no estimate anymore for a Razorpay-backed row: {@link #renewFromRazorpayOne} either
 * renews with Razorpay's own confirmed, genuinely-newer period, or it does nothing and waits.
 *
 * <p><b>BL-2 fix (BrandF.md §98):</b> the stale-period query above (any {@code ACTIVE} row with a
 * lapsed {@code currentPeriodEnd}) matches a cancel-at-period-end subscription exactly as well as
 * a genuinely missed-renewal one — {@link com.influora.service.billing.SubscriptionService#cancel}
 * deliberately leaves status {@code ACTIVE} at cancel time (correct — access continues until the
 * period ends) and only sets {@code cancelAtPeriodEnd = true}. Left unguarded, this job would
 * treat that exact row as "webhook missed" and re-renew it — silently undoing the customer's
 * cancellation every single day, forever. {@link #processOne} checks {@link
 * Subscription#isCancelAtPeriodEnd()} first, ahead of every other branch, for exactly this reason.
 */
@Component
public class SubscriptionRenewalResetJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRenewalResetJob.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final RazorpayClient razorpayClient;
    private final BrandContextService brandContextService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLog;
    private final TransactionTemplate transactionTemplate;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public SubscriptionRenewalResetJob(
            SubscriptionRepository subscriptionRepository,
            SubscriptionService subscriptionService,
            RazorpayClient razorpayClient,
            BrandContextService brandContextService,
            ApplicationEventPublisher eventPublisher,
            AuditLogService auditLog,
            PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.razorpayClient = razorpayClient;
        this.brandContextService = brandContextService;
        this.eventPublisher = eventPublisher;
        this.auditLog = auditLog;
        // [Kabir S2R3 item 1] Same idiom as SubscriptionDunningJob's own publishHaltedEmail call
        // site: an explicit, job-owned transaction for publishing SubscriptionHaltedEvent/
        // SubscriptionPaymentFailedEvent, since both listeners are
        // @TransactionalEventListener(AFTER_COMMIT) with no fallbackExecution
        // (NotificationListener.java) and this job carries no @Transactional of its own.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Daily at 3:30am UTC — after {@code SubscriptionDunningJob} (3am), before {@code StaleTokenCleanupJob} (4am). */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @SchedulerLock(name = "SubscriptionRenewalResetJob", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void runRenewalSafetyNet() {
        if (!running.compareAndSet(false, true)) {
            log.warn("SubscriptionRenewalResetJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            doRun();
        } catch (Exception e) {
            log.error("SubscriptionRenewalResetJob: crashed mid-run", e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private void doRun() {
        Instant now = Instant.now();
        List<Subscription> stale =
                subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE).stream()
                        .filter(sub -> sub.getCurrentPeriodEnd().isBefore(now))
                        .toList();

        RunStats stats = new RunStats();
        for (Subscription subscription : stale) {
            try {
                processOne(subscription, stats);
            } catch (Exception e) {
                stats.failed++;
                log.error(
                        "SubscriptionRenewalResetJob: unexpected failure processing subscription {}",
                        subscription.getId(),
                        e);
            }
        }

        if (stats.total() > 0) {
            // Only genuinely noteworthy when this safety net actually caught something — a missed
            // webhook is not the steady state, so logging every no-op day at this level would be
            // noise (mirrors AICreditResetJob's completion-summary-only logging discipline).
            log.warn(
                    "SubscriptionRenewalResetJob: safety net caught {} subscription(s) with a stale"
                            + " period — renewed={}, cancelled={}, compExpired={}, freeAdvanced={},"
                            + " razorpayStatusSynced={}, awaitingCharge={}, raceSkipped={}, failed={}",
                    stale.size(),
                    stats.renewed,
                    stats.cancelled,
                    stats.compExpired,
                    stats.freeAdvanced,
                    stats.razorpayStatusSynced,
                    stats.awaitingCharge,
                    stats.raceSkipped,
                    stats.failed);
        }
    }

    /** See class javadoc's numbered routing list for the full rationale behind this order. */
    private void processOne(Subscription subscription, RunStats stats) {
        if (subscription.isCancelAtPeriodEnd()) {
            cancelOne(subscription);
            stats.cancelled++;
            return;
        }

        if (subscription.isComp()) {
            expireCompOne(subscription);
            stats.compExpired++;
            return;
        }

        String razorpaySubscriptionId = subscription.getRazorpaySubscriptionId();
        if (razorpaySubscriptionId == null) {
            if (subscriptionService.isFreePlan(subscription.getPlanId())) {
                advanceFreeOne(subscription);
                stats.freeAdvanced++;
            } else {
                // No code path in this codebase currently produces "Pro plan, comp=false,
                // razorpaySubscriptionId=null": createFreeSubscription only ever writes FREE;
                // grantAdminPlan always pairs a plan change with markComp(...) in the same
                // transaction (so an admin-granted Pro row always has comp=true); and every write
                // inside applySubscriptionWebhookUpdate always sets/keeps a real
                // razorpaySubscriptionId. Reaching this branch means either a manual DB edit, a
                // migration bug, or a future code path that stopped honoring that invariant —
                // refuse to extend for free rather than guess, and surface it loudly.
                stats.failed++;
                log.error(
                        "SubscriptionRenewalResetJob: ACTIVE subscription {} (workspace {}) has a"
                                + " lapsed period, is on a paid plan, is not a comp, and has no"
                                + " razorpaySubscriptionId — cannot verify against Razorpay, refusing"
                                + " to extend; this is not a known-reachable state and needs manual"
                                + " investigation",
                        subscription.getId(),
                        subscription.getWorkspaceId());
            }
            return;
        }

        syncFromRazorpay(subscription, razorpaySubscriptionId, stats);
    }

    /**
     * [BL-2 fix, BrandF.md §98] Finalizes a subscription the customer already cancelled ({@link
     * Subscription#isCancelAtPeriodEnd()}) whose paid period has now lapsed — flips it to {@code
     * CANCELLED} via {@link SubscriptionService#finalizeLapsedCancellation} instead of letting it
     * fall into the renewal path. See class javadoc for why this row would otherwise match the
     * same stale-period query as a genuinely missed webhook.
     */
    private void cancelOne(Subscription subscription) {
        String workspaceId = subscription.getWorkspaceId();
        Instant lapsedPeriodEnd = subscription.getCurrentPeriodEnd();

        subscriptionService.finalizeLapsedCancellation(subscription);

        auditLog.recordMoneyEvent(
                workspaceId,
                "SUBSCRIPTION_CANCELLATION_FINALIZED",
                null,
                null,
                null,
                "subscription-cancellation-finalized:" + subscription.getId() + ":" + lapsedPeriodEnd.getEpochSecond(),
                Map.of(
                        "subscriptionId", subscription.getId(),
                        "lapsedPeriodEnd", lapsedPeriodEnd.toString()));

        log.warn(
                "SubscriptionRenewalResetJob: finalized lapsed cancellation for subscription {}"
                        + " (workspace {}) — cancelAtPeriodEnd was true and currentPeriodEnd {} has"
                        + " passed with no subscription.cancelled webhook ever applying it; status set"
                        + " to CANCELLED",
                subscription.getId(), workspaceId, lapsedPeriodEnd);
    }

    /**
     * F-0859 fix: demotes a lapsed admin-comp row to Free via {@link
     * SubscriptionService#expireComp} — see that method's javadoc.
     */
    private void expireCompOne(Subscription subscription) {
        String workspaceId = subscription.getWorkspaceId();
        Instant compExpiresAt = subscription.getCompExpiresAt();
        String previousPlanId = subscription.getPlanId();

        subscriptionService.expireComp(subscription);

        auditLog.recordMoneyEvent(
                workspaceId,
                "SUBSCRIPTION_COMP_EXPIRED",
                null,
                null,
                null,
                "subscription-comp-expired:" + subscription.getId() + ":"
                        + (compExpiresAt != null ? compExpiresAt.getEpochSecond() : "none"),
                Map.of(
                        "subscriptionId", subscription.getId(),
                        "previousPlanId", previousPlanId,
                        "compExpiresAt", String.valueOf(compExpiresAt)));

        log.warn(
                "SubscriptionRenewalResetJob: expired admin comp for subscription {} (workspace {})"
                        + " — compExpiresAt {} has passed; demoted to Free",
                subscription.getId(), workspaceId, compExpiresAt);
    }

    /**
     * F-0861 fix: advances a lapsed Free row's period anchor only, via {@link
     * SubscriptionService#advanceFreePeriod} — see that method's javadoc for why no credit/
     * allotment call happens here.
     */
    private void advanceFreeOne(Subscription subscription) {
        String workspaceId = subscription.getWorkspaceId();
        Instant oldStart = subscription.getCurrentPeriodStart();
        Instant oldEnd = subscription.getCurrentPeriodEnd();

        subscriptionService.advanceFreePeriod(subscription);

        Instant newStart = subscription.getCurrentPeriodStart();
        Instant newEnd = subscription.getCurrentPeriodEnd();

        auditLog.recordMoneyEvent(
                workspaceId,
                "SUBSCRIPTION_FREE_PERIOD_ADVANCED",
                null,
                null,
                null,
                "subscription-free-period-advanced:" + subscription.getId() + ":" + oldEnd.getEpochSecond(),
                Map.of(
                        "subscriptionId", subscription.getId(),
                        "previousPeriodStart", oldStart.toString(),
                        "previousPeriodEnd", oldEnd.toString(),
                        "newPeriodStart", newStart.toString(),
                        "newPeriodEnd", newEnd.toString()));

        log.warn(
                "SubscriptionRenewalResetJob: advanced lapsed Free-tier period anchor for"
                        + " subscription {} (workspace {}) — {}..{} was stale, no Razorpay/credit"
                        + " involvement for Free",
                subscription.getId(), workspaceId, oldStart, oldEnd);
    }

    /**
     * F-0860 fix: a Razorpay-backed row's local {@code ACTIVE} status is no longer trusted on its
     * own — the live status is fetched from Razorpay first, and the outcome is routed by what
     * Razorpay actually reports. A fetch failure ({@link RazorpayClient#fetchSubscription} throws)
     * propagates to {@link #processOne}'s caller ({@link #doRun}'s per-row catch), which counts it
     * {@code failed} and leaves the row untouched for a retry on the next run — no partial state is
     * ever written here on a fetch failure.
     *
     * <p>[Kabir S2 MEDIUM fix] {@code fetchedAt} is captured HERE, immediately before the Razorpay
     * call — see class javadoc's "fetch-to-write race" section for why this exact ordering (not a
     * timestamp captured after the fetch/processing) is what closes both directions of the race.
     */
    private void syncFromRazorpay(Subscription subscription, String razorpaySubscriptionId, RunStats stats) {
        Instant fetchedAt = Instant.now();
        SubscriptionSnapshot snapshot = razorpayClient.fetchSubscription(razorpaySubscriptionId);
        String status = snapshot.status() == null ? "" : snapshot.status().toLowerCase(Locale.ROOT);

        switch (status) {
            // [Kabir S2 LOW fix] "authenticated" deliberately removed from this case — the real
            // webhook never maps it to ACTIVE (RazorpayWebhookController has no
            // subscription.authenticated handling at all), so this job must not treat it as a
            // renewal trigger either. It now falls to the default branch below: no change, logged,
            // retried next run.
            case "active" -> renewFromRazorpayOne(subscription, snapshot, stats);
            case "pending" -> syncStatusAndCount(subscription, SubscriptionStatus.PAST_DUE, snapshot, fetchedAt, stats);
            case "halted" -> syncStatusAndCount(subscription, SubscriptionStatus.HALTED, snapshot, fetchedAt, stats);
            case "cancelled", "completed", "expired" ->
                    syncStatusAndCount(subscription, SubscriptionStatus.CANCELLED, snapshot, fetchedAt, stats);
            default -> {
                stats.failed++;
                log.error(
                        "SubscriptionRenewalResetJob: subscription {} (workspace {}) —"
                                + " razorpaySubscriptionId={} returned an unrecognized/missing/"
                                + "not-a-renewal-trigger Razorpay status \"{}\"; changing nothing,"
                                + " will retry on the next run",
                        subscription.getId(),
                        subscription.getWorkspaceId(),
                        razorpaySubscriptionId,
                        snapshot.status());
            }
        }
    }

    /**
     * Razorpay confirmed {@code active}. Renews ONLY when Razorpay's own {@code current_end} is
     * genuinely newer than both the local {@code currentPeriodEnd} and {@code now()} (Kabir S2
     * HIGH fix) — {@code active} with an UNCHANGED period (the SAME lapsed cycle Razorpay hasn't
     * actually charged yet, routine for UPI autopay/eMandate subscriptions between the due date and
     * the actual debit) is left completely alone: no renewal, no estimate, just a wait for the real
     * charge or a real webhook. Renewing on "active" alone used to call {@link
     * SubscriptionService#applyRenewalSafetyNet}'s {@code resetForNewCycle} against a cycle
     * Razorpay had not actually charged — refilling Pro AI credits for free, once per day, until
     * the charge eventually landed.
     *
     * <p>[Kabir S2 MEDIUM fix] Delegates to {@link
     * SubscriptionService#applyRenewalSafetyNetIfUnchanged} rather than {@link
     * SubscriptionService#applyRenewalSafetyNet} directly — see that method's javadoc for the
     * fetch-to-write race it guards against.
     */
    private void renewFromRazorpayOne(Subscription subscription, SubscriptionSnapshot snapshot, RunStats stats) {
        Instant oldEnd = subscription.getCurrentPeriodEnd();
        Instant now = Instant.now();
        String workspaceId = subscription.getWorkspaceId();

        boolean razorpayReportsNewerPeriod =
                snapshot.currentStart() != null
                        && snapshot.currentEnd() != null
                        && snapshot.currentEnd().isAfter(oldEnd)
                        && snapshot.currentEnd().isAfter(now);

        if (!razorpayReportsNewerPeriod) {
            stats.awaitingCharge++;
            log.info(
                    "SubscriptionRenewalResetJob: subscription {} (workspace {}) — Razorpay reports"
                            + " active but current_end ({}) is not a newer, already-elapsed period"
                            + " than the local currentPeriodEnd ({}); awaiting an actual charge"
                            + " (common with UPI autopay/eMandate), NOT renewing and NOT estimating",
                    subscription.getId(), workspaceId, snapshot.currentEnd(), oldEnd);
            return;
        }

        Instant newStart = snapshot.currentStart();
        Instant newEnd = snapshot.currentEnd();

        boolean applied =
                subscriptionService.applyRenewalSafetyNetIfUnchanged(subscription.getId(), oldEnd, newStart, newEnd);
        if (!applied) {
            stats.raceSkipped++;
            log.info(
                    "SubscriptionRenewalResetJob: subscription {} (workspace {}) — currentPeriodEnd"
                            + " changed since this job read the row (a webhook likely landed"
                            + " concurrently); skipped the safety-net renewal rather than clobber it,"
                            + " will re-evaluate on the next run",
                    subscription.getId(), workspaceId);
            return;
        }

        stats.renewed++;

        auditLog.recordMoneyEvent(
                workspaceId,
                "SUBSCRIPTION_RENEWAL_SAFETY_NET",
                null,
                null,
                null,
                "subscription-renewal-safetynet:" + subscription.getId() + ":" + oldEnd.getEpochSecond(),
                Map.of(
                        "subscriptionId", subscription.getId(),
                        "previousPeriodEnd", oldEnd.toString(),
                        "newPeriodStart", newStart.toString(),
                        "newPeriodEnd", newEnd.toString()));

        log.warn(
                "SubscriptionRenewalResetJob: safety-net renewed subscription {} (workspace {}) —"
                        + " Razorpay confirmed status={} with a newer current_end, stale period end"
                        + " {} was never advanced by a webhook, new period {}..{}",
                subscription.getId(), workspaceId, snapshot.status(), oldEnd, newStart, newEnd);
    }

    /**
     * Razorpay confirmed {@code pending}/{@code halted}/{@code cancelled}/{@code completed}/
     * {@code expired} — applies the SAME status-only transition {@code
     * RazorpayWebhookController#handleSubscriptionEvent} would apply for that Razorpay event, via
     * {@link SubscriptionService#applySubscriptionWebhookUpdate} with no period change ({@code
     * periodStart}/{@code periodEnd} both {@code null}, matching {@code updatePeriod=false} for
     * those same event types on the real webhook path) — see that method's javadoc. Reusing it
     * here (rather than a job-local re-implementation) is what keeps this job and the webhook from
     * diverging on what these FIVE statuses mean locally; it makes no claim about {@code active}/
     * {@code authenticated}, which this job handles through an entirely different path (see {@link
     * #renewFromRazorpayOne}) precisely because renewal and first-activation are not the same
     * operation.
     *
     * <p>[Kabir S2 LOW fix] Also publishes the SAME billing email the real webhook publishes for
     * HALTED/PAST_DUE (see {@code RazorpayWebhookController#applySubscriptionEventTransactionally})
     * via the shared {@link SubscriptionBillingEmailPublisher} — previously this job's own
     * job-driven transitions moved the row but never notified the brand at all.
     *
     * <p>[Kabir S2R3 item 1 — BLOCKING] The email publish now runs inside an explicit {@code
     * transactionTemplate}-managed transaction. {@code SubscriptionHaltedEvent}/{@code
     * SubscriptionPaymentFailedEvent} are both consumed by {@code
     * NotificationListener}'s {@code @TransactionalEventListener(phase = AFTER_COMMIT)} handlers
     * with no {@code fallbackExecution} — Spring SILENTLY DROPS an event published with no
     * transaction synchronized to the current thread (proved with a real Spring context: {@code
     * KABIR_PROBE outside=0 inside=1}). {@link SubscriptionService#applySubscriptionWebhookUpdate}
     * is {@code @Transactional} on its OWN method call, which has already committed and returned by
     * the time this method would otherwise call {@code eventPublisher.publishEvent(...)} — so the
     * publish needs its own transaction, exactly like {@code
     * SubscriptionDunningJob#haltOne}'s {@code transactionTemplate.executeWithoutResult(status ->
     * publishHaltedEmail(subscription))} call. Mocking {@link ApplicationEventPublisher} (as this
     * job's own unit tests do) cannot catch this class of bug — it never exercises a real
     * transaction/listener, so the wrap is not (and cannot be) proven by a mocked-publisher test.
     *
     * <p>[Kabir S2R3 item 2 — BLOCKING] {@code applySubscriptionWebhookUpdate} now reports whether
     * it actually applied the delivery ({@code true}) or no-op'd because a newer delivery had
     * already won the race ({@code false} — its own out-of-order-delivery guard). This method
     * audits and emails ONLY when {@code true}: a skipped write must never be reported or emailed
     * as if it happened — e.g. a brand whose charge just succeeded getting a "payment failed"
     * email for a PAST_DUE sync this job's OWN fetch-to-write race left stale and un-applied.
     *
     * @param webhookEventAt the instant captured BEFORE this job called Razorpay (see {@link
     *     #syncFromRazorpay}), not one captured after — see class javadoc's fetch-to-write race
     *     section.
     * @return {@code true} if the transition was actually applied (and therefore audited/emailed),
     *     {@code false} if it was skipped because a newer delivery already won.
     */
    private boolean syncStatusOne(
            Subscription subscription,
            SubscriptionStatus targetStatus,
            SubscriptionSnapshot snapshot,
            Instant webhookEventAt) {
        SubscriptionStatus previousStatus = subscription.getStatus();
        String workspaceId = subscription.getWorkspaceId();
        String razorpaySubscriptionId = subscription.getRazorpaySubscriptionId();

        boolean applied =
                subscriptionService.applySubscriptionWebhookUpdate(
                        razorpaySubscriptionId,
                        workspaceId,
                        snapshot.planId(),
                        targetStatus,
                        null,
                        null,
                        webhookEventAt);

        if (!applied) {
            log.info(
                    "SubscriptionRenewalResetJob: subscription {} (workspace {}) — Razorpay status"
                            + " sync to {} was skipped by SubscriptionService (a newer delivery had"
                            + " already been applied to this row); not auditing or emailing a"
                            + " transition that did not happen",
                    subscription.getId(), workspaceId, targetStatus);
            return false;
        }

        auditLog.recordMoneyEvent(
                workspaceId,
                "SUBSCRIPTION_RENEWAL_JOB_RAZORPAY_STATUS_SYNCED",
                null,
                null,
                null,
                "subscription-razorpay-status-synced:" + subscription.getId() + ":" + targetStatus.name(),
                Map.of(
                        "subscriptionId", subscription.getId(),
                        "previousStatus", previousStatus.name(),
                        "newStatus", targetStatus.name(),
                        "razorpayStatus", String.valueOf(snapshot.status())));

        log.warn(
                "SubscriptionRenewalResetJob: synced subscription {} (workspace {}) to {} — Razorpay"
                        + " reported status={} for a row this job found stale/ACTIVE locally; the"
                        + " webhook for this transition was apparently missed",
                subscription.getId(), workspaceId, targetStatus, snapshot.status());

        if (targetStatus == SubscriptionStatus.HALTED || targetStatus == SubscriptionStatus.PAST_DUE) {
            try {
                transactionTemplate.executeWithoutResult(
                        status -> {
                            if (targetStatus == SubscriptionStatus.HALTED) {
                                SubscriptionBillingEmailPublisher.publishHalted(
                                        brandContextService, eventPublisher, workspaceId, razorpaySubscriptionId);
                            } else {
                                SubscriptionBillingEmailPublisher.publishPaymentFailed(
                                        brandContextService, eventPublisher, workspaceId, razorpaySubscriptionId);
                            }
                        });
            } catch (Exception e) {
                // Best-effort, matching SubscriptionDunningJob#haltOne's discipline: an email
                // dispatch failure must not undo the already-correctly-applied status transition
                // and audit log recorded above.
                log.error(
                        "SubscriptionRenewalResetJob: billing email dispatch failed for subscription"
                                + " {} — status transition + audit log were still recorded",
                        subscription.getId(),
                        e);
            }
        }

        return true;
    }

    /**
     * [Kabir S2R3 item 2] Thin wrapper so {@link #syncFromRazorpay}'s switch can count a skipped
     * (raced) status sync the same way {@link #renewFromRazorpayOne} counts a skipped renewal —
     * {@code raceSkipped}, not {@code razorpayStatusSynced} (which now means "actually applied").
     */
    private void syncStatusAndCount(
            Subscription subscription,
            SubscriptionStatus targetStatus,
            SubscriptionSnapshot snapshot,
            Instant webhookEventAt,
            RunStats stats) {
        if (syncStatusOne(subscription, targetStatus, snapshot, webhookEventAt)) {
            stats.razorpayStatusSynced++;
        } else {
            stats.raceSkipped++;
        }
    }

    /** Per-run outcome counters — see {@link #doRun}'s completion-summary log. */
    private static final class RunStats {
        int renewed;
        int cancelled;
        int compExpired;
        int freeAdvanced;
        int razorpayStatusSynced;
        int awaitingCharge;
        int raceSkipped;
        int failed;

        int total() {
            return renewed
                    + cancelled
                    + compExpired
                    + freeAdvanced
                    + razorpayStatusSynced
                    + awaitingCharge
                    + raceSkipped
                    + failed;
        }
    }
}
