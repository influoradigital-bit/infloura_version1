package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.service.IdempotencyService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final int LOYALTY_MONTHLY_ALLOTMENT = 150;

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

    public AICreditService(BrandAiCreditRepository creditRepository, IdempotencyService idempotencyService) {
        this.creditRepository = creditRepository;
        this.idempotencyService = idempotencyService;
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

        // P4: 500/day hard cap — applies EVEN to unlimited-tier workspaces as abuse prevention.
        // Check + increment the daily counter; reset if the date has rolled over.
        if (!todayUtc.equals(credit.getDailyActionsDate())) {
            credit.setDailyActionsDate(todayUtc);
            credit.setDailyActionsUsed(0);
        }

        if (credit.getDailyActionsUsed() >= DAILY_ACTION_HARD_CAP) {
            throw new ApiException(
                    "DAILY_ACTION_LIMIT_EXCEEDED",
                    "Daily action limit (500 actions/day) exceeded for this workspace; resets at midnight UTC",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        // Increment the daily counter (applies to all tiers).
        credit.setDailyActionsUsed(credit.getDailyActionsUsed() + 1);
        creditRepository.save(credit);

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
     */
    @Transactional
    public void applyEscrowFundedReset(String workspaceId, Instant unlimitedUntil) {
        BrandAiCredit credit = ensureInitialized(workspaceId);
        if (credit.getFirstCampaignAt() == null) {
            credit.setFirstCampaignAt(Instant.now());
            credit.setMonthlyAllotment(LOYALTY_MONTHLY_ALLOTMENT);
        }
        credit.setCreditsRemaining(credit.getMonthlyAllotment());
        credit.setUnlimitedUntil(unlimitedUntil);
        creditRepository.save(credit);
    }

    /**
     * Syncs {@code monthlyAllotment} to the workspace's current subscription plan. Deliberately
     * does NOT reset {@code creditsRemaining} — callers invoke {@link #resetForNewCycle} separately
     * when a reset is intended (e.g. {@code AICreditResetJob}); {@code reconcileAiCreditAllotment}
     * calls this alone precisely to avoid resetting mid-cycle.
     */
    @Transactional
    public void applyPlanAllotment(String workspaceId, int monthlyAllotment) {
        BrandAiCredit credit = ensureInitialized(workspaceId);
        credit.setMonthlyAllotment(monthlyAllotment);
        creditRepository.save(credit);
    }

    /** Monthly reset cron seam (1st of month) — resets non-live brands to their allotment. */
    @Transactional
    public void resetForNewCycle(String workspaceId) {
        BrandAiCredit credit = ensureInitialized(workspaceId);
        credit.setCreditsRemaining(credit.getMonthlyAllotment());
        credit.setLastReset(LocalDate.now());
        creditRepository.save(credit);
    }

    /** Unused-but-available helper for future callers needing a fresh ULID for related rows. */
    public static String newId() {
        return Ulids.newUlid();
    }
}
