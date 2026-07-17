package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.repository.BrandAiCreditRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 */
@Service
public class AICreditService {

    private static final int DEFAULT_MONTHLY_ALLOTMENT = 100;
    private static final int LOYALTY_MONTHLY_ALLOTMENT = 150;

    /**
     * P4: hard cap on daily actions for unlimited-tier workspaces. This is roughly 30x a normal
     * day's usage — generous enough that no real brand hits it, but it kills runaway/abuse
     * scenarios (20-ROHAN-COST-REVIEW.md §5).
     */
    private static final int DAILY_ACTION_HARD_CAP = 500;

    private final BrandAiCreditRepository creditRepository;

    public AICreditService(BrandAiCreditRepository creditRepository) {
        this.creditRepository = creditRepository;
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
