package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.billing.SubscriptionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit gate + atomic decrement (Guardrail 5 — 03-SECURITY-SPEC.md §G5): the hard cost
 * circuit-breaker. Runs in Spring BEFORE any Python/LLM call is reachable.
 *
 * <p><b>P4 — 500 actions/day hard cap (20-ROHAN-COST-REVIEW.md §5):</b> even when in "unlimited
 * while live" mode ({@code unlimitedUntil} in the future), the workspace is hard-blocked after
 * 500 tool actions per day. This is a safety net against runaway loops and abuse, NOT the
 * primary billing mechanism. The counter resets at midnight UTC.
 *
 * <p>Escrow-funded reset hook (V9 {@code EscrowFundedEvent} listener) and the monthly cron
 * reset are wired here per the data model (01-DATA-MODEL.md §8), but this phase does not
 * touch the escrow/money tables (Domain A is out of scope) — {@link #applyEscrowFundedReset}
 * is provided as the seam Domain A's event publisher will call into; it is not itself an
 * {@code @EventListener} yet since {@code EscrowFundedEvent} is defined in the parallel
 * money-core build. Wiring the listener annotation is a follow-up once that event class exists.
 *
 * <p><b>SECURITY FIX (Wave 2, Kabir red-team, two HIGH exploits in the charge-on-success
 * streaming model):</b>
 *
 * <ul>
 *   <li><b>FAIL 1 (disconnect-farm):</b> the old model only decremented credit (and only bumped
 *       the 500/day counter) in the end-of-stream write-back. A client that read every {@code
 *       token} SSE event and then disconnected before {@code done} never triggered the
 *       write-back at all — unlimited free metered turns, and the daily cap never engaged either
 *       since it was bumped in the same place.
 *   <li><b>FAIL 2 (client-supplied turn id):</b> the write-back's idempotency key was the
 *       CLIENT-supplied {@code turn_id}, never cross-checked against anything server-minted. A
 *       client pinning {@code turn_id} to a constant across N turns made turns 2..N hit {@code
 *       AlreadyCompletedException} — {@code creditsCharged=0} — for turns that were never charged
 *       at all.
 * </ul>
 *
 * <p><b>The fix moves the charge to the SEND gate</b> ({@link #tryConsumeForTurn}, called from
 * {@code MeeraSessionService#doSendTurn} BEFORE the USER message is persisted or any token is
 * minted), keyed on the server-minted {@code messageId} (never a client-supplied value) — every
 * send is charged exactly once, unconditionally, closing both FAILs at the root: there is no
 * longer any code path that streams tokens to the browser without having already decremented
 * credit and bumped the daily counter for that exact turn.
 *
 * <p>Charging up front means a genuine PROVIDER failure (not a client disconnect — the two are
 * deliberately kept distinct, see {@code influora-ai/app/routes/chat.py}) must be able to give the
 * money back: {@link #release} refunds a turn's charge, GUARDED so it can never fire for a turn
 * that was never charged, and can never fire once that turn's assistant reply has successfully
 * persisted (no refund-and-keep-the-reply). {@link #tryConsumeForTurn} and {@link #release} share
 * a completion ledger built on the existing {@link IdempotencyService} (no new schema): a
 * COMPLETED {@code CHARGE_SCOPE} row for a {@code turnId} means "this exact turn was charged at
 * send"; a COMPLETED {@code MeeraSessionService#PERSIST_WRITEBACK_SCOPE} row for the SAME {@code
 * turnId} means "this exact turn's reply already landed" — {@link #release} is a no-op unless the
 * former is true and the latter is false. {@code turnId} is always {@code MeeraSessionService}'s
 * server-minted {@code messageId}, both here and on the write-back (Kabir FAIL 2 fix), so the two
 * ledgers are guaranteed to be talking about the same turn.
 */
@Service
public class AICreditService {

    private static final Logger log = LoggerFactory.getLogger(AICreditService.class);

    private static final int DEFAULT_MONTHLY_ALLOTMENT = 100;

    // F-3/SM-0.2 [vikram · 2026-09-17] -- was LOYALTY_MONTHLY_ALLOTMENT = 150, a flat override
    //   that clobbered BrandAiCredit.monthlyAllotment regardless of plan (a Pro brand's first
    //   funded campaign silently dropped them from 400 to 150). Swapnil's ruling is that the
    //   loyalty bonus STACKS on the current plan allotment: Free + funded = 150, Pro + funded =
    //   450. This constant is now just the bonus itself, added via BrandAiCredit#setLoyaltyBonus.
    //   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §7 SM-0.2
    private static final int LOYALTY_BONUS = 50;

    /**
     * P4: hard cap on daily actions for unlimited-tier workspaces. This is roughly 30x a normal
     * day's usage — generous enough that no real brand hits it, but it kills runaway/abuse
     * scenarios (20-ROHAN-COST-REVIEW.md §5).
     */
    private static final int DAILY_ACTION_HARD_CAP = 500;

    /** Completion ledger scope: a COMPLETED row here means this {@code turnId} was charged at send. */
    private static final String CHARGE_SCOPE = "meera.turn_charged";

    /** Completion ledger scope for {@link #release} itself — makes a double/racing release a no-op. */
    private static final String RELEASE_SCOPE = "meera.turn_released";

    private final BrandAiCreditRepository creditRepository;
    private final IdempotencyService idempotencyService;
    private final SubscriptionService subscriptionService;

    /**
     * T-S3-F0879-0917 [vikram · 2026-09-17]: {@code subscriptionService} is {@code @Lazy} because
     * {@link SubscriptionService} already depends on {@code AICreditService} directly (constructor
     * field) -- a plain, eager dependency here would be a genuine circular bean-creation cycle that
     * Spring cannot construct. {@code @Lazy} hands this constructor a deferred proxy instead of the
     * real bean, breaking the cycle without touching {@code SubscriptionService.java} (out of scope
     * for this lane). Same precedent already used in this codebase to break circular-bean cycles:
     * {@code CampaignServiceInvoiceService}'s {@code @Lazy} self-reference and {@code
     * AffiliateEarningsService}'s {@code @Lazy} self-reference. Only {@link
     * SubscriptionService#getActivePlanForWorkspace} is called on it, which is {@code
     * @Transactional(readOnly = true)} and does not call back into {@code AICreditService}, so there
     * is no re-entrancy risk.
     *   Source: assignments-0917-subscription.md S3 item 5 (F-0879)
     */
    public AICreditService(
            BrandAiCreditRepository creditRepository,
            IdempotencyService idempotencyService,
            @Lazy SubscriptionService subscriptionService) {
        this.creditRepository = creditRepository;
        this.idempotencyService = idempotencyService;
        this.subscriptionService = subscriptionService;
    }

    /** Ensures a credit row exists for the workspace, creating the default allotment if not. */
    @Transactional
    public BrandAiCredit ensureInitialized(String workspaceId) {
        return creditRepository
                .findByWorkspaceId(workspaceId)
                .orElseGet(
                        () ->
                                creditRepository.save(
                                        BrandAiCredit.builder()
                                                .workspaceId(workspaceId)
                                                .monthlyAllotment(DEFAULT_MONTHLY_ALLOTMENT)
                                                .creditsRemaining(DEFAULT_MONTHLY_ALLOTMENT)
                                                .cycleStart(LocalDate.now())
                                                .lastReset(LocalDate.now())
                                                .build()));
    }

    /**
     * Gate + atomic decrement. Throws {@code 402 CREDITS_EXHAUSTED} if the workspace has no
     * credits left and is not in an unlimited window — callers must not proceed to issue a
     * stream token or call Python if this throws. Throws {@code 429 DAILY_ACTION_LIMIT_EXCEEDED}
     * if the 500/day hard cap is hit, even for unlimited-tier workspaces.
     */
    @Transactional
    public void tryConsume(String workspaceId, int cost) {
        BrandAiCredit credit = ensureInitialized(workspaceId);
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);

        // T-S3-F0879-0917 [vikram · 2026-09-17] CREDIT RACE FIX: this used to mutate `credit`'s
        // daily-action setters and call creditRepository.save(credit) -- a full-row write using
        // EVERY mapped column from the entity's in-memory state, including creditsRemaining as
        // read at the START of this transaction. Under concurrency, two turns hitting a
        // workspace with 1 credit left could both pass this point, and one transaction's blind
        // save(credit) could land AFTER the other's tryDecrement below had already correctly
        // decremented creditsRemaining in the DB, silently reverting it back to the
        // pre-decrement value -- letting BOTH turns succeed against a single remaining credit.
        // Fix: never call a setter on the managed `credit` entity in this method (so Hibernate
        // has nothing to dirty-flush for it), and read the daily-cap value from an untouched
        // local snapshot instead. The daily-actions counter itself is now written via a single
        // atomic, single-column-scoped UPDATE (bumpDailyActions) that cannot touch
        // creditsRemaining even under concurrent execution.
        //   Source: assignments-0917-subscription.md S3 "Credit race" (tech N4, T-5)
        boolean dailyActionsRolledOver = !todayUtc.equals(credit.getDailyActionsDate());
        int dailyActionsUsedToday = dailyActionsRolledOver ? 0 : credit.getDailyActionsUsed();

        // P4: 500/day hard cap — applies EVEN to unlimited-tier workspaces as abuse prevention.
        // This check is a best-effort snapshot read (matches the existing SOFT abuse-cap
        // semantics documented in the class javadoc — not the primary billing gate) and is not
        // itself claimed to be perfectly race-free; only the creditsRemaining path below is.
        if (dailyActionsUsedToday >= DAILY_ACTION_HARD_CAP) {
            throw new ApiException(
                    "DAILY_ACTION_LIMIT_EXCEEDED",
                    "Daily action limit (500 actions/day) exceeded for this workspace; resets at midnight UTC",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        // Atomic, single-column-scoped bump — never touches creditsRemaining (applies to all tiers).
        creditRepository.bumpDailyActions(workspaceId, todayUtc);

        if (credit.isUnlimited(Instant.now())) {
            // Unlimited window (funded campaign) — no credit decrement, but still gated/allowed.
            return;
        }

        int updated = creditRepository.tryDecrement(workspaceId, cost);
        if (updated == 0) {
            throw new ApiException(
                    "CREDITS_EXHAUSTED",
                    "AI credits exhausted for this workspace",
                    HttpStatus.PAYMENT_REQUIRED);
        }
    }

    /** Read-only credit status for the credit-status endpoint. */
    @Transactional(readOnly = true)
    public BrandAiCredit getStatus(String workspaceId) {
        return ensureInitialized(workspaceId);
    }

    /**
     * SECURITY FIX (Wave 2, Kabir FAILs #1/#2 — see class javadoc): the SEND-gate charge, called
     * from {@code MeeraSessionService#doSendTurn} BEFORE the USER message is persisted or any
     * token is minted. Replaces the old non-decrementing {@code assertAvailable} pre-check —
     * this one actually charges. Runs the same two gates {@link #tryConsume} always has
     * (exhausted credits / 500-day cap); if either rejects, this throws and NOTHING below it in
     * {@code doSendTurn} ever runs — no dangling charged state.
     *
     * <p>On success, in the SAME transaction, records a completion-ledger marker keyed on {@code
     * turnId} (the server-minted {@code messageId}) under {@link #CHARGE_SCOPE} — this is the
     * record {@link #release} consults to confirm a turn was actually charged before it will ever
     * refund it. {@code turnId} must be freshly minted per send (a {@code Ulids.newUlid()} value,
     * never reused) — a collision here would throw {@link IdempotencyService.AlreadyInProgressException}
     * or {@link IdempotencyService.AlreadyCompletedException} out of this method after the credit
     * was already decremented by {@link #tryConsume} in its own committed transaction, which would
     * leave that credit decremented with no charge marker recorded; this is an accepted,
     * vanishingly-unlikely risk (ULID collision), not a normal-path outcome.
     */
    @Transactional
    public void tryConsumeForTurn(String workspaceId, int cost, String turnId) {
        tryConsume(workspaceId, cost);
        idempotencyService.executeOnce(turnId, workspaceId, CHARGE_SCOPE, () -> turnId);
    }

    /**
     * True if {@code turnId} (the server-minted {@code messageId}) was actually charged via
     * {@link #tryConsumeForTurn} — consulted by {@code MeeraSessionService#doPersistAssistantWriteback}
     * to set the persisted ASSISTANT row's {@code creditsCharged} so it reflects the send-time
     * charge (the decrement itself never happens again at write-back time).
     */
    @Transactional(readOnly = true)
    public boolean wasCharged(String workspaceId, String turnId) {
        return idempotencyService.isCompleted(turnId, workspaceId, CHARGE_SCOPE);
    }

    /**
     * SECURITY FIX (Wave 2): refunds a turn's send-time charge after a genuine PROVIDER failure —
     * called from the internal {@code /internal/meera/turns/release} route, which influora-ai
     * hits when a turn's stream errors out (or ends with no assistant text) BEFORE {@code done}.
     * Deliberately never called for a plain client disconnect (Kabir FAIL 1) — the browser already
     * received the streamed tokens in that case, so the charge correctly stays; that distinction
     * is enforced entirely on the influora-ai side (see {@code app/routes/chat.py}), this method
     * has no way to tell the two apart and doesn't need to.
     *
     * <p>IDEMPOTENT: wrapped in its own {@link IdempotencyService#executeOnce} under {@link
     * #RELEASE_SCOPE} keyed on {@code turnId} — a duplicate or racing release call for the same
     * turn is a no-op ({@link IdempotencyService.AlreadyCompletedException} / {@code
     * AlreadyInProgressException} are both swallowed here, not rethrown).
     *
     * <p>GUARDED: {@link #doRelease} only actually refunds if (a) {@link #wasCharged} is true for
     * this {@code turnId}, and (b) {@code MeeraSessionService#PERSIST_WRITEBACK_SCOPE} has NOT
     * already completed for the SAME {@code turnId} — i.e. no assistant reply has successfully
     * persisted yet. (b) is what makes "refund after a successful write-back" structurally
     * impossible: a client can never end up with both a refund AND the reply.
     */
    @Transactional
    public void release(String workspaceId, int cost, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            throw new ApiException(
                    "RELEASE_TURN_ID_REQUIRED", "turnId is required to release a charge", HttpStatus.BAD_REQUEST);
        }
        try {
            idempotencyService.executeOnce(
                    turnId,
                    workspaceId,
                    RELEASE_SCOPE,
                    () -> {
                        doRelease(workspaceId, cost, turnId);
                        return turnId;
                    });
        } catch (IdempotencyService.AlreadyCompletedException
                | IdempotencyService.AlreadyInProgressException duplicate) {
            log.info(
                    "release: turnId={} workspaceId={} already released or in-flight -- no-op",
                    turnId,
                    workspaceId);
        }
    }

    private void doRelease(String workspaceId, int cost, String turnId) {
        if (!idempotencyService.isCompleted(turnId, workspaceId, CHARGE_SCOPE)) {
            log.warn(
                    "release: turnId={} workspaceId={} was never charged at send -- refusing to refund",
                    turnId,
                    workspaceId);
            return;
        }
        if (idempotencyService.isCompleted(turnId, workspaceId, MeeraSessionService.PERSIST_WRITEBACK_SCOPE)) {
            log.warn(
                    "release: turnId={} workspaceId={} already has a persisted assistant reply --"
                            + " refusing to refund-and-keep-reply",
                    turnId,
                    workspaceId);
            return;
        }

        BrandAiCredit credit = ensureInitialized(workspaceId);
        if (!credit.isUnlimited(Instant.now())) {
            creditRepository.refundCredits(workspaceId, cost);
        }
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        if (todayUtc.equals(credit.getDailyActionsDate())) {
            creditRepository.refundDailyActions(workspaceId, cost, todayUtc);
        }
    }

    /**
     * Seam for the escrow-funded event (V9, Domain A — not built in this phase). When wired,
     * this resets credits to the (possibly loyalty-bumped) monthly allotment and opens an
     * unlimited window through {@code campaignEndDate + 3 days}.
     *
     * <p>F-3/SM-0.2 [vikram · 2026-09-17]: sets {@code loyaltyBonus} (which stacks on whatever
     * {@code planAllotment} the workspace currently has), not {@code monthlyAllotment} directly —
     * see {@link BrandAiCredit} field javadoc for why the two writers were split.
     *   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3, §7 SM-0.2
     */
    @Transactional
    public void applyEscrowFundedReset(String workspaceId, Instant unlimitedUntil) {
        BrandAiCredit credit = ensureInitialized(workspaceId);

        // F-0879 [vikram · 2026-09-17]: re-derive planAllotment from the workspace's CURRENT
        // active plan BEFORE refilling. Previously this refilled straight to the STORED
        // monthlyAllotment, which is only kept in sync by applyPlanAllotment — called from
        // SubscriptionService's webhook/renewal/reset paths, none of which are guaranteed to have
        // run between a plan change and a campaign being funded. A workspace whose planAllotment
        // was stale at the moment a campaign is funded (e.g. downgraded from Pro to Free with no
        // sync in between) was refilled to the STALE allotment — a Free workspace stuck at a
        // stale Pro planAllotment=400 was refilled to 450 instead of the correct 150 (100 + the
        // 50 loyalty bonus). Syncing here makes this method self-contained: it no longer depends
        // on some other caller having synced planAllotment first.
        //   Source: F-0879, assignments-0917-subscription.md S3 item 5
        Plan activePlan = subscriptionService.getActivePlanForWorkspace(workspaceId);
        if (activePlan != null) {
            credit.setPlanAllotment(activePlan.getAiMonthlyAllotment());
        } else {
            // Mirrors AICreditResetJob#syncPlanAllotment's null-plan handling: getActivePlanForWorkspace
            // is documented to fall back to Free rather than return null in normal operation, so a
            // null here means the resolver itself is in an unexpected state — log it loudly instead
            // of silently refilling to a possibly-stale stored allotment.
            log.warn(
                    "applyEscrowFundedReset: getActivePlanForWorkspace returned null for workspace {}"
                            + " -- planAllotment sync skipped, refill will use the stored allotment"
                            + " unchanged",
                    workspaceId);
        }

        if (credit.getFirstCampaignAt() == null) {
            credit.setFirstCampaignAt(Instant.now());
            credit.setLoyaltyBonus(LOYALTY_BONUS);
        }
        credit.setCreditsRemaining(credit.getMonthlyAllotment());
        credit.setUnlimitedUntil(unlimitedUntil);
        creditRepository.save(credit);
    }

    /**
     * Syncs {@code planAllotment} to the workspace's current subscription plan, and — per
     * Swapnil's ruling (REPAIR-ROUND {@code RULING-upgrade-grant.md}, replacing the old top-up
     * rule) — grants the FULL new allotment immediately on an INCREASE, at most once per billing
     * period. A DECREASE still never claws back {@code creditsRemaining} mid-cycle (unchanged
     * documented intent — see {@link #resetForNewCycle} for where a decreased allotment
     * eventually takes effect).
     *
     * <p><b>F-0885 (lost update) [vikram · 2026-09-18 REPAIR ROUND]:</b> this method no longer
     * mutates the managed {@code BrandAiCredit} entity or calls a full-row {@code save()} at all —
     * every write here is a targeted, atomic UPDATE ({@link BrandAiCreditRepository
     * #syncPlanAllotment}, {@link BrandAiCreditRepository#grantAllotmentIncrease}), the same
     * discipline the credit-race fix already applies to {@link #tryConsume}'s daily-action bump.
     * A concurrent {@link BrandAiCreditRepository#tryDecrement} racing this call can therefore
     * never be silently reverted by a stale in-memory read here.
     *
     * <p><b>F-0881 ruling + F-0883 (repeat-grant) [vikram · 2026-09-18 REPAIR ROUND]:</b> the
     * increase check below still uses the in-memory {@code oldMonthlyAllotment} snapshot to
     * decide WHETHER to attempt a grant (a benign race against a concurrent plan-allotment call —
     * not the credit-decrement race F-0885 is about), but the grant itself is authoritatively
     * guarded server-side by {@link BrandAiCreditRepository#grantAllotmentIncrease}'s WHERE
     * clause on {@code creditGrantPeriodEnd}, keyed on the subscription's OWN
     * {@code currentPeriodStart}/{@code currentPeriodEnd} (Subscription.java:46-49) — not the
     * calendar month {@code cycleStart}/{@code lastReset} already track. That is what makes a
     * PAST_DUE→ACTIVE flap (which re-syncs the SAME Pro allotment on every reactivation, per
     * {@code SubscriptionService#reconcileAiCreditAllotment}) grant exactly once per period
     * instead of re-granting on every flap (Kabir's "granted=300 three times" probe).
     * {@code periodEnd == null} (no resolvable subscription) fails OPEN — grants anyway, since
     * this codebase treats "cannot prove a duplicate" as safer than silently withholding a paying
     * brand's allowance; every real webhook-driven caller (only {@code
     * SubscriptionService#reconcileAiCreditAllotment} and {@code #applyRenewalSafetyNet} — both
     * out of scope for this lane) creates or already has a {@code Subscription} row before
     * calling this, so {@code periodEnd} is expected to be resolvable in practice.
     *
     * <p>F-3 [vikram · 2026-09-17]: the {@code monthlyAllotment} parameter name is kept (not
     * renamed to {@code planAllotment}) because it mirrors what every existing caller (e.g.
     * {@code SubscriptionService#reconcileAiCreditAllotment}, {@code Plan#getAiMonthlyAllotment()})
     * actually passes: the plan's own base allotment.
     *   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3; F-0881/F-0883/F-0885 repair
     *   round; RULING-upgrade-grant.md
     */
    @Transactional
    public void applyPlanAllotment(String workspaceId, int monthlyAllotment) {
        BrandAiCredit credit = ensureInitialized(workspaceId);
        int oldMonthlyAllotment = credit.getMonthlyAllotment();
        int loyaltyBonus = credit.getLoyaltyBonus();

        creditRepository.syncPlanAllotment(workspaceId, monthlyAllotment);

        int newMonthlyAllotment = monthlyAllotment + loyaltyBonus;
        if (newMonthlyAllotment > oldMonthlyAllotment) {
            Instant periodEnd = currentBillingPeriodEnd(workspaceId);
            int granted = creditRepository.grantAllotmentIncrease(workspaceId, newMonthlyAllotment, periodEnd);
            if (granted == 0) {
                log.info(
                        "applyPlanAllotment: skipping repeat allotment-increase grant for workspace"
                                + " {} -- already granted for the current billing period"
                                + " (endingAt={})",
                        workspaceId,
                        periodEnd);
            }
        }
    }

    /**
     * Resolves the workspace's current billing period end from its {@code Subscription} row
     * (never the calendar month) — {@code null} if no subscription row exists yet. Shared by the
     * grant-once-per-period guard in {@link #applyPlanAllotment} and the double-reset guard in
     * {@link #resetForNewCycle}.
     *   Source: F-0881/F-0883/F-0884 repair round
     */
    private Instant currentBillingPeriodEnd(String workspaceId) {
        return subscriptionService.getByWorkspaceId(workspaceId).map(Subscription::getCurrentPeriodEnd).orElse(null);
    }

    /**
     * Monthly reset cron seam (1st of month) — resets non-live brands to their allotment. {@code
     * AICreditResetJob} (and any other automated monthly-cron caller) must call {@link
     * #resetForNewCycleIfDue} instead so a duplicate trigger in the same UTC CALENDAR MONTH is a
     * no-op — see that method's javadoc for why that guard lives there and not here.
     *
     * <p><b>F-0884 (repeat-reset across the renewal safety net) [vikram · 2026-09-18 REPAIR
     * ROUND]:</b> this method is ALSO called directly by {@code
     * SubscriptionService#applyRenewalSafetyNet} on a per-subscription BILLING-PERIOD boundary
     * that does not align to the calendar month — {@code resetForNewCycleIfDue}'s calendar-month
     * guard alone cannot see that call at all (it is a different call site in a different, {@code
     * SubscriptionService.java}, file this lane must not edit). Without a guard THIS method could
     * see two full refills for the same billing period: the safety net resets mid-month when a
     * renewal webhook is missed, and {@code AICreditResetJob}'s next 1st-of-month run (a
     * DIFFERENT calendar month, so {@code resetForNewCycleIfDue}'s own guard does not block it)
     * would reset the SAME still-current billing period again. Fixed here, inside this lane's own
     * files, by guarding on the workspace's OWN {@code currentPeriodEnd}
     * (Subscription.java:46-49) instead of the calendar month: a second call resolving the SAME
     * {@code currentPeriodEnd} as the last reset is a no-op. When the period genuinely advances
     * (a real renewal, {@code applyRenewalSafetyNet} always calls {@code subscription
     * #renewPeriod} BEFORE this method) the resolved {@code currentPeriodEnd} differs from what
     * was last stored, so the legitimate mid-period reset still proceeds — this guard only blocks
     * a SECOND reset attempt for a period that was already reset. {@code periodEnd == null} (no
     * subscription row — e.g. a workspace that has never touched billing) disables the guard, so
     * behavior for every plain Free workspace is unchanged.
     *   Source: F-0884 repair round
     */
    @Transactional
    public void resetForNewCycle(String workspaceId) {
        BrandAiCredit credit = ensureInitialized(workspaceId);

        Instant periodEnd = currentBillingPeriodEnd(workspaceId);
        if (periodEnd != null && periodEnd.equals(credit.getLastResetPeriodEnd())) {
            log.info(
                    "resetForNewCycle: workspace {} already reset for the current billing period"
                            + " (endingAt={}) -- no-op (F-0884 double-reset guard)",
                    workspaceId,
                    periodEnd);
            return;
        }

        credit.setCreditsRemaining(credit.getMonthlyAllotment());
        credit.setLastReset(LocalDate.now(ZoneOffset.UTC));
        credit.setLastResetPeriodEnd(periodEnd);
        creditRepository.save(credit);
    }

    /**
     * T-S3-F0879-0917 [vikram · 2026-09-17] "Reset runs twice": idempotent wrapper around {@link
     * #resetForNewCycle} for {@code AICreditResetJob} — a no-op if this workspace was already
     * reset in the same UTC calendar month (compares {@code lastReset}'s year+month).
     *
     * <p>Deliberately kept SEPARATE from {@link #resetForNewCycle} rather than adding the guard
     * to that method directly: {@code SubscriptionService#applyRenewalSafetyNet} also calls
     * {@code resetForNewCycle}, but on a PER-SUBSCRIPTION renewal boundary that does not
     * necessarily land on the calendar month (e.g. a workspace that subscribed on the 15th
     * renews on the 15th of the next month). Guarding THAT call by calendar month would wrongly
     * skip a legitimate mid-month renewal reset — a business-logic change outside this lane's
     * scope (S1/S2 own {@code SubscriptionService.java}). This method is only the job-specific
     * idempotency guard the ticket asked for.
     *
     * <p>Catch-up on startup (a missed monthly run, e.g. the app was down at 2am UTC on the 1st)
     * is intentionally NOT implemented here — reliably distinguishing "the scheduler never fired
     * this month" from "it fired and is merely not yet due again" needs either a dedicated
     * last-run ledger or an ops/monitoring signal, and deciding how far to catch up (immediately
     * on next boot vs. next real cron tick) is a scheduling/ops call, not something this lane
     * should decide unilaterally. NOT DONE; reported as such.
     *   Source: assignments-0917-subscription.md S3 item 3 (tech N3)
     */
    @Transactional
    public void resetForNewCycleIfDue(String workspaceId) {
        BrandAiCredit credit = ensureInitialized(workspaceId);
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        LocalDate lastReset = credit.getLastReset();
        if (lastReset != null
                && lastReset.getYear() == todayUtc.getYear()
                && lastReset.getMonth() == todayUtc.getMonth()) {
            log.info(
                    "resetForNewCycleIfDue: workspace {} already reset this UTC month ({}) -- no-op",
                    workspaceId,
                    todayUtc);
            return;
        }
        resetForNewCycle(workspaceId);
    }

    /** Unused-but-available helper for future callers needing a fresh ULID for related rows. */
    public static String newId() {
        return Ulids.newUlid();
    }
}
