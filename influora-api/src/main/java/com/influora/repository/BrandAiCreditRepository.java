package com.influora.repository;

import com.influora.domain.entity.BrandAiCredit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BrandAiCreditRepository extends JpaRepository<BrandAiCredit, String> {

    // T-CREDITCLOCK-0918 [vikram · 2026-09-18]: every @Modifying query in this interface binds
    // `c.updatedAt` to a passed-in `:now` Instant parameter rather than the JPQL `CURRENT_TIMESTAMP`
    // literal every one of them used before this round. H2Dialect's `current_timestamp` function
    // contributor types that literal as `java.sql.Timestamp`, which Hibernate 6's semantic
    // validator then refuses to assign to this entity's Instant-typed `updatedAt` -- discovered
    // when @EnableJpaRepositories(basePackageClasses = BrandAiCreditRepository.class) in
    // AICreditClockScenarioTest's real-H2 harness (F-0895) eagerly validates EVERY @Query method in
    // this interface at repository-proxy creation, so even a query the test never calls (e.g.
    // tryDecrement) fails the whole bean if it still used the literal. Production (MySQLDialect) is
    // unaffected by this change either way -- it is purely an H2-testability accommodation, not a
    // behavior change: callers now pass `Instant.now()` where the JPQL literal used to resolve it
    // server-side, which is the same wall-clock instant to sub-millisecond precision that matters
    // here (no caller straddles a transaction boundary between binding `now` and this UPDATE
    // executing).

    /** 1:1 workspace lookup — the PK is the workspaceId, so this is inherently tenant-scoped. */
    Optional<BrandAiCredit> findByWorkspaceId(String workspaceId);

    /**
     * Atomic, race-safe decrement: only succeeds (returns 1) if credits are still available.
     * Mirrors the {@code UPDATE ... WHERE credits_remaining > 0} pattern mandated by Guardrail 5
     * so concurrent turns can never drive the balance negative.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = c.creditsRemaining - :cost, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId AND c.creditsRemaining >= :cost")
    int tryDecrement(
            @Param("workspaceId") String workspaceId, @Param("cost") int cost, @Param("now") Instant now);

    /**
     * Refund counterpart to {@link #tryDecrement} — used by {@code AICreditService#release} to
     * un-charge a turn whose provider call failed after the send-time charge. The caller (guarded
     * by the release ledger) only ever refunds an amount it already knows was actually decremented
     * for this exact turn, but the add is still clamped to {@code monthlyAllotment} (CASE WHEN,
     * matching the {@code GREATEST}-style pattern in {@link #refundDailyActions} below — plain
     * JPQL has no {@code LEAST} function): a monthly reset landing between a turn's send-time
     * charge and its later release would otherwise let this unconditional add push
     * {@code creditsRemaining} above the workspace's allotment.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = "
                    + "CASE WHEN c.creditsRemaining + :amount > c.monthlyAllotment THEN c.monthlyAllotment "
                    + "ELSE c.creditsRemaining + :amount END, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId")
    int refundCredits(
            @Param("workspaceId") String workspaceId, @Param("amount") int amount, @Param("now") Instant now);

    /**
     * Refund counterpart to the daily-action-counter bump in {@code AICreditService#tryConsume} —
     * floors at 0 (via {@code GREATEST}) and only applies if {@code dailyActionsDate} is still the
     * SAME day the charge was recorded against; a day rollover already reset the counter for real,
     * so refunding a stale day's counter would be meaningless (and could under/overflow the new
     * day's count).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.dailyActionsUsed = "
                    + "CASE WHEN c.dailyActionsUsed > :amount THEN c.dailyActionsUsed - :amount ELSE 0 END, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId AND c.dailyActionsDate = :today")
    int refundDailyActions(
            @Param("workspaceId") String workspaceId,
            @Param("amount") int amount,
            @Param("today") LocalDate today,
            @Param("now") Instant now);

    /**
     * T-S3-F0879-0917 [vikram · 2026-09-17] -- credit-race fix. Atomically bumps the P4 daily
     * action counter (rolling over to 1 if {@code dailyActionsDate} is not {@code :today}) WITHOUT
     * touching {@code creditsRemaining} or any other column. This replaces the old {@code
     * AICreditService#tryConsume} pattern of mutating the managed {@code BrandAiCredit} entity's
     * daily-action fields and calling {@code save(credit)} -- that blind full-row save wrote back
     * EVERY mapped column using the entity's IN-MEMORY state as read at the start of the
     * transaction, including {@code creditsRemaining}. Under concurrency, that stale write could
     * land AFTER a concurrent transaction's {@link #tryDecrement} had already correctly decremented
     * {@code creditsRemaining}, silently reverting it back to the pre-decrement value -- letting a
     * second turn spend a credit that was already spent (two turns at 1 remaining credit both
     * succeeding). Isolating the daily-action bump to its own single-column-scoped UPDATE, and never
     * calling a setter on the managed entity for these fields in {@code tryConsume} any more, means
     * nothing in that hot path ever writes {@code creditsRemaining} except {@link #tryDecrement}
     * itself.
     *   Source: assignments-0917-subscription.md S3 "Credit race" (tech N4, T-5)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.dailyActionsUsed = "
                    + "CASE WHEN c.dailyActionsDate = :today THEN c.dailyActionsUsed + 1 ELSE 1 END, "
                    + "c.dailyActionsDate = :today, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId")
    int bumpDailyActions(
            @Param("workspaceId") String workspaceId, @Param("today") LocalDate today, @Param("now") Instant now);

    /**
     * T-S3-F0879-0917 REPAIR ROUND [vikram · 2026-09-18] -- F-0885 (lost update) on the grant
     * path. Syncs {@code planAllotment} (and the derived {@code monthlyAllotment}, recomputed
     * server-side against the row's CURRENT {@code loyaltyBonus} rather than a possibly-stale
     * in-memory value) via a single-scoped UPDATE that never touches {@code creditsRemaining} or
     * any other column. Replaces {@code AICreditService#applyPlanAllotment}'s old blind full-row
     * {@code save(credit)}, which could clobber a concurrent {@link #tryDecrement} the same way
     * the credit-race fix closed for {@link #bumpDailyActions} -- see that method's javadoc for
     * the shape this mirrors.
     *   Source: F-0885 repair round (Kabir MEDIUM), RULING-upgrade-grant.md
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.planAllotment = :planAllotment, "
                    + "c.monthlyAllotment = :planAllotment + c.loyaltyBonus, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId")
    int syncPlanAllotment(
            @Param("workspaceId") String workspaceId,
            @Param("planAllotment") int planAllotment,
            @Param("now") Instant now);

    // -----------------------------------------------------------------------------------------
    // T-CREDITCLOCK-0918 [vikram · 2026-09-18] -- wiki/decisions/2026-09-18-ai-credit-clock.md.
    // grantAllotmentIncrease (F-0881/F-0883/F-0885 repair round) is RETIRED: the "SET to the full
    // new allotment, guarded on creditGrantPeriodEnd" shape it introduced is now generalized into
    // refillForBillingPeriod below, which every billing-clock refill path (the upgrade grant, the
    // charged-webhook renewal, and the safety net) shares -- not just an allotment INCREASE.
    // -----------------------------------------------------------------------------------------

    /**
     * T-CREDITCLOCK-0918 [vikram · 2026-09-18] -- the billing-clock refill primitive
     * (wiki/decisions/2026-09-18-ai-credit-clock.md §2/§4). SETs {@code creditsRemaining} to the
     * row's OWN {@code monthlyAllotment} (never a Java-computed value -- F-0894), atomically
     * guarded so a repeat call for the SAME billing period (identified by the subscription's
     * {@code currentPeriodEnd}, passed in as {@code periodEnd}) is a no-op (returns 0 rows
     * updated) -- the authoritative guard, not just a pre-check in the service layer: two
     * concurrent callers race on the same InnoDB row lock, so only one can ever win for a given
     * {@code periodEnd}. Also stamps {@code lastReset = :today} in the SAME guarded UPDATE, which
     * is what lets the §3 handover top-up tell "the billing refill already covered this calendar
     * month" apart from "it didn't" -- see {@code AICreditService#refillForBillingPeriod} javadoc.
     * Unlike the retired {@code grantAllotmentIncrease}, this does NOT fail open on a null {@code
     * periodEnd} -- the service layer refuses to call this at all in that case (fails CLOSED, per
     * the ruling's update).
     *
     * <p><b>Repair round LOW [vikram · 2026-09-18]:</b> the guard used to be {@code
     * creditGrantPeriodEnd <> :periodEnd}, which is symmetric -- it also re-fires for an OLDER
     * period than the one already granted (H2 probeD: refill P2, spend, then refill P1 granted a
     * second full allowance). The marker only ever needs to move FORWARD, so the guard is now
     * {@code creditGrantPeriodEnd < :periodEnd}: a repeat of the SAME period is still a no-op (not
     * {@code <}), and an out-of-order older period can never re-grant.
     *   Source: repair round LOW finding on refillForBillingPeriod's WHERE clause
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = c.monthlyAllotment, "
                    + "c.creditGrantPeriodEnd = :periodEnd, "
                    + "c.lastReset = :today, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId "
                    + "AND (c.creditGrantPeriodEnd IS NULL OR c.creditGrantPeriodEnd < :periodEnd)")
    int refillForBillingPeriod(
            @Param("workspaceId") String workspaceId,
            @Param("periodEnd") Instant periodEnd,
            @Param("today") LocalDate today,
            @Param("now") Instant now);

    /**
     * T-CREDITCLOCK-0918 [vikram · 2026-09-18] -- the §3 "handover top-up". Raises {@code
     * creditsRemaining} up to {@code monthlyAllotment} (never lowers it -- a {@code CASE WHEN ...
     * < ... THEN ... ELSE} clamp, the same "never claw back" shape {@code refundCredits} already
     * uses) and stamps {@code lastReset = :today}, guarded to fire at most once per UTC calendar
     * month (WHERE {@code lastReset < :firstOfMonth}). Idempotent/harmless to call on every
     * reconcile for a calendar-clock workspace: once it has run this month, every later call in
     * the same month is a no-op.
     *   Source: wiki/decisions/2026-09-18-ai-credit-clock.md §3
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = "
                    + "CASE WHEN c.creditsRemaining < c.monthlyAllotment THEN c.monthlyAllotment "
                    + "ELSE c.creditsRemaining END, "
                    + "c.lastReset = :today, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId AND c.lastReset < :firstOfMonth")
    int topUpOnJoinCalendarClock(
            @Param("workspaceId") String workspaceId,
            @Param("today") LocalDate today,
            @Param("firstOfMonth") LocalDate firstOfMonth,
            @Param("now") Instant now);

    /**
     * T-CREDITCLOCK-0918 [vikram · 2026-09-18] -- the atomic calendar-month reset (F-0894 lost
     * update fix for {@code AICreditService#resetForNewCycle}, which previously did a full-row
     * {@code save()}). Unconditional SET {@code creditsRemaining = monthlyAllotment}, {@code
     * lastReset = :today} -- {@code AICreditResetJob}'s own {@code resetForNewCycleIfDue} is the
     * same-UTC-month idempotency guard; this primitive is intentionally unconditional the same way
     * {@code bumpDailyActions}/{@code syncPlanAllotment} are, with the guard living one layer up.
     *   Source: wiki/decisions/2026-09-18-ai-credit-clock.md §2, §5 (F-0894)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = c.monthlyAllotment, "
                    + "c.lastReset = :today, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId")
    int calendarReset(
            @Param("workspaceId") String workspaceId, @Param("today") LocalDate today, @Param("now") Instant now);

    /**
     * Repair round LOW [vikram · 2026-09-18] -- the ruling's §2/§5 requirement that the monthly
     * reset be "one atomic UPDATE, guarded in SQL on {@code last_reset}", not the previous
     * Java-read-then-unguarded-{@link #calendarReset}-write pattern in {@code AICreditService
     * #resetForNewCycleIfDue} (a TOCTOU window between the read and the write -- H2 probeE showed
     * {@link #calendarReset} itself has no guard at all: called directly with {@code lastReset}
     * already today, it still updates). {@code AICreditResetJob}'s ShedLock narrows but does not
     * eliminate the race (a manual re-trigger, or overlap across a redeploy). Guarded so a repeat
     * call in the SAME UTC calendar month (WHERE {@code lastReset < firstOfMonth}) is a genuine
     * no-op at the database level, not just skipped by a caller that already decided not to call
     * it. {@link #calendarReset} itself is left unconditional -- {@code
     * SubscriptionService#grantAdminPlan}'s comp-grant "fill now regardless of this month's state"
     * semantics still need it, and guarding it would silently break a same-month plan-change grant
     * needing an immediate fill; whether a REPEATED comp grant of the SAME plan within a month
     * should also be capped is a product question this lane does not decide (flagged, not fixed).
     *   Source: repair round LOW finding on calendarReset / resetForNewCycleIfDue (H2 probeE)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.creditsRemaining = c.monthlyAllotment, "
                    + "c.lastReset = :today, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId AND c.lastReset < :firstOfMonth")
    int calendarResetIfDue(
            @Param("workspaceId") String workspaceId,
            @Param("today") LocalDate today,
            @Param("firstOfMonth") LocalDate firstOfMonth,
            @Param("now") Instant now);

    /**
     * T-CREDITCLOCK-0918 [vikram · 2026-09-18] -- {@code AICreditService#applyEscrowFundedReset}
     * made atomic (F-0894): a funded launch is a funding event on NEITHER clock, so this must
     * NEVER write {@code creditGrantPeriodEnd}, {@code lastReset}, or {@code lastResetPeriodEnd} --
     * doing so could revert a concurrent billing-period or calendar-clock refill's own marker and
     * let a second refill land in the same period/month. Conditionally sets {@code loyaltyBonus}
     * and {@code firstCampaignAt} (only the FIRST funded campaign earns the bonus -- a {@code CASE
     * WHEN c.firstCampaignAt IS NULL} guard, reproduced identically for the two writes that need
     * the NEW loyalty bonus value in the SAME statement, since a JPQL UPDATE's SET expressions all
     * read the row's pre-statement values, never another SET target's new value in the same
     * statement) and unconditionally refills {@code creditsRemaining} to the resulting {@code
     * planAllotment + loyaltyBonus} and opens the unlimited window.
     *   Source: wiki/decisions/2026-09-18-ai-credit-clock.md §2 "applyEscrowFundedReset" row, §5
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET "
                    + "c.loyaltyBonus = CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END, "
                    + "c.firstCampaignAt = CASE WHEN c.firstCampaignAt IS NULL THEN :now ELSE c.firstCampaignAt END, "
                    + "c.monthlyAllotment = c.planAllotment + "
                    + "(CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END), "
                    + "c.creditsRemaining = c.planAllotment + "
                    + "(CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END), "
                    + "c.unlimitedUntil = :unlimitedUntil, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId")
    int applyEscrowFundedReset(
            @Param("workspaceId") String workspaceId,
            @Param("loyaltyBonus") int loyaltyBonus,
            @Param("now") Instant now,
            @Param("unlimitedUntil") Instant unlimitedUntil);

    /**
     * Repair round HIGH [vikram · 2026-09-18] -- Swapnil's new ruling (2026-09-18, same day as the
     * clock ruling): a funded launch on a BILLING_PERIOD workspace refills AT MOST ONCE PER
     * BILLING PERIOD, like an upgrade grant. H2 probeA (repair round) showed the un-guarded {@link
     * #applyEscrowFundedReset} let a second funded launch in the SAME period bring a spent-down
     * balance back to the full allowance every time it was called -- unlimited full allowances
     * within one paid period.
     *
     * <p>Guards on {@code escrowFundedPeriodEnd} (a marker SEPARATE from {@code
     * creditGrantPeriodEnd} -- see that field's javadoc on {@link com.influora.domain.entity.BrandAiCredit}
     * for why: the clock decision doc's scenario 9 ("a funded launch on Pro -> 450 without
     * touching {@code credit_grant_period_end}") still holds, and this guard must not suppress (or
     * be suppressed by) the billing-refill primitive's own once-per-period grant). Uses {@code <}.
     * not {@code <>}, for the same forward-only reason as {@link #refillForBillingPeriod}. Called
     * ONLY when the workspace resolves to {@code SubscriptionService.CreditClock#BILLING_PERIOD}
     * -- a CALENDAR_MONTH workspace has no billing period to gate on and instead calls {@link
     * #applyEscrowFundedResetOncePerCalendarMonth} (round 2: also capped, once per UTC calendar
     * month -- see that method's javadoc).
     *
     * <p><b>T-GOLIVE-0918-R2 CREDITS-2 [vikram · 2026-09-18] -- MEDIUM (round-2 kabir finding):</b>
     * this statement no longer writes {@code c.unlimitedUntil}. The whole UPDATE (including the
     * unlimited-window write) used to be gated behind the SAME once-per-period guard, so a second
     * funded launch in the same billing period silently skipped extending the campaign's unlimited
     * window too -- the ruling caps the CREDIT refill, not the window. The window write is now the
     * caller's separate, unconditional {@link #extendUnlimitedWindow} call, decoupling the two
     * effects.
     *   Source: repair round HIGH finding on applyEscrowFundedReset; Swapnil ruling 2026-09-18;
     *   go-live round 2 CREDITS-2 MEDIUM (b)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET "
                    + "c.loyaltyBonus = CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END, "
                    + "c.firstCampaignAt = CASE WHEN c.firstCampaignAt IS NULL THEN :now ELSE c.firstCampaignAt END, "
                    + "c.monthlyAllotment = c.planAllotment + "
                    + "(CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END), "
                    + "c.creditsRemaining = c.planAllotment + "
                    + "(CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END), "
                    + "c.escrowFundedPeriodEnd = :periodEnd, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId "
                    + "AND (c.escrowFundedPeriodEnd IS NULL OR c.escrowFundedPeriodEnd < :periodEnd)")
    int applyEscrowFundedResetOncePerPeriod(
            @Param("workspaceId") String workspaceId,
            @Param("loyaltyBonus") int loyaltyBonus,
            @Param("now") Instant now,
            @Param("periodEnd") Instant periodEnd);

    /**
     * T-GOLIVE-0918-R2 CREDITS-2 [vikram · 2026-09-18] -- go-live round 2, item (b): the unlimited
     * (funded-campaign) window is a funding event, not a refill, and must extend on EVERY funded
     * launch regardless of whether that launch's CREDIT refill was blocked by {@link
     * #applyEscrowFundedResetOncePerPeriod}'s or {@link #applyEscrowFundedResetOncePerCalendarMonth}'s
     * once-per-period/month guard. Monotonic ({@code CASE WHEN ... IS NULL OR :unlimitedUntil >
     * c.unlimitedUntil}) rather than a plain SET so a delayed/duplicate webhook replay carrying an
     * EARLIER {@code unlimitedUntil} than one already granted (e.g. a longer campaign funded after
     * a shorter one, then the shorter one's event redelivers) can never shorten the window a brand
     * is already entitled to.
     *   Source: repair round HIGH finding on applyEscrowFundedReset (b); Swapnil ruling 2026-09-18
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET c.unlimitedUntil = "
                    + "CASE WHEN c.unlimitedUntil IS NULL OR :unlimitedUntil > c.unlimitedUntil "
                    + "THEN :unlimitedUntil ELSE c.unlimitedUntil END, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId")
    int extendUnlimitedWindow(
            @Param("workspaceId") String workspaceId,
            @Param("unlimitedUntil") Instant unlimitedUntil,
            @Param("now") Instant now);

    /**
     * T-GOLIVE-0918-R2 CREDITS-2 [vikram · 2026-09-18] -- go-live round 2, item (c): a
     * CALENDAR_MONTH workspace (Free/comp/ex-Pro, no billing period to gate on) now also refills
     * from a funded launch AT MOST ONCE PER UTC CALENDAR MONTH, matching Swapnil's once-per-period
     * ruling already built for the billing clock in {@link #applyEscrowFundedResetOncePerPeriod}.
     * Previously this workspace class kept calling the fully-unconditional {@link
     * #applyEscrowFundedReset} on every funded launch, which a brand OWNER/ADMIN could repeat
     * indefinitely via a fund-then-refund loop ({@code EscrowService#refund}) -- kabir round-1
     * MEDIUM.
     *
     * <p>Guards on the NEW {@code escrowFundedMonth} column (a {@code LocalDate} keyed to the
     * first-of-UTC-month), a SEPARATE marker from {@code lastReset}/{@code
     * topUpOnJoinCalendarClock}'s guard on purpose: this is a funding event, not the ordinary
     * monthly reset, and must not be coupled to (or suppress) that guard's own semantics. Forward
     * -only ({@code <}, not {@code <>}), matching {@link #refillForBillingPeriod}'s and {@link
     * #applyEscrowFundedResetOncePerPeriod}'s forward-only guards. Does NOT write {@code
     * unlimitedUntil} -- see {@link #extendUnlimitedWindow}, called unconditionally by the caller
     * for both clocks.
     *   Source: kabir round-1 MEDIUM (applyEscrowFundedReset calendar branch); Swapnil's
     *   once-per-period ruling, wiki/decisions/2026-09-18-ai-credit-clock.md
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE BrandAiCredit c SET "
                    + "c.loyaltyBonus = CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END, "
                    + "c.firstCampaignAt = CASE WHEN c.firstCampaignAt IS NULL THEN :now ELSE c.firstCampaignAt END, "
                    + "c.monthlyAllotment = c.planAllotment + "
                    + "(CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END), "
                    + "c.creditsRemaining = c.planAllotment + "
                    + "(CASE WHEN c.firstCampaignAt IS NULL THEN :loyaltyBonus ELSE c.loyaltyBonus END), "
                    + "c.escrowFundedMonth = :firstOfMonth, "
                    + "c.updatedAt = :now "
                    + "WHERE c.workspaceId = :workspaceId "
                    + "AND (c.escrowFundedMonth IS NULL OR c.escrowFundedMonth < :firstOfMonth)")
    int applyEscrowFundedResetOncePerCalendarMonth(
            @Param("workspaceId") String workspaceId,
            @Param("loyaltyBonus") int loyaltyBonus,
            @Param("now") Instant now,
            @Param("firstOfMonth") LocalDate firstOfMonth);
}
