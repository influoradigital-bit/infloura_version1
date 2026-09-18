package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "brand_ai_credits")
public class BrandAiCredit {

    /** 1:1 with workspaces — the PK IS the FK, no surrogate id. */
    @Id
    @Column(name = "workspace_id", length = 26)
    private String workspaceId;

    @Column(name = "credits_remaining", nullable = false)
    private int creditsRemaining;

    // F-3 [vikram · 2026-09-17] -- monthlyAllotment used to be written directly by TWO owners
    //   (plan sync via applyPlanAllotment AND the loyalty bonus via applyEscrowFundedReset), so
    //   whichever wrote last clobbered the other -- a Pro brand's first funded campaign silently
    //   dropped them from 400 to a flat 150. Split into planAllotment (plan sync only) and
    //   loyaltyBonus (earned once, sticky); monthlyAllotment is now derived by
    //   recomputeMonthlyAllotment() below and must never be set directly by a caller.
    //   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3, §7 SM-0.2
    @Column(name = "monthly_allotment", nullable = false)
    private int monthlyAllotment;

    // F-3 [vikram · 2026-09-17] -- synced only from the workspace's active plan
    //   (AICreditService#applyPlanAllotment); never touched by the loyalty path.
    @Column(name = "plan_allotment", nullable = false)
    private int planAllotment;

    // SM-0.2 [vikram · 2026-09-17] -- earned once (first funded campaign) and STACKS on top of
    //   planAllotment rather than replacing it, per Swapnil's ruling: Pro + funded campaign = 450,
    //   Pro without = 400. Written only by AICreditService#applyEscrowFundedReset.
    //   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §7 SM-0.2
    @Column(name = "loyalty_bonus", nullable = false)
    private int loyaltyBonus;

    @Column(name = "cycle_start", nullable = false)
    private LocalDate cycleStart;

    @Column(name = "unlimited_until")
    private Instant unlimitedUntil;

    @Column(name = "last_reset", nullable = false)
    private LocalDate lastReset;

    @Column(name = "first_campaign_at")
    private Instant firstCampaignAt;

    // T-S3-F0879-0917 REPAIR ROUND [vikram · 2026-09-18] -- Swapnil's ruling (RULING-upgrade-grant.md):
    //   an allotment INCREASE grants the full new monthlyAllotment at most once per BILLING PERIOD
    //   (Subscription.currentPeriodStart/currentPeriodEnd), not the calendar month cycleStart/lastReset
    //   already own. Stores the currentPeriodEnd this workspace's last increase-grant was applied for;
    //   a repeat applyPlanAllotment call resolving the SAME currentPeriodEnd is a no-op (see
    //   BrandAiCreditRepository#grantAllotmentIncrease). Written ONLY by that atomic query -- the
    //   setter below exists for test fixtures and the (documented) full-row-save fallback paths.
    //   Source: F-0881/F-0883 repair round
    @Column(name = "credit_grant_period_end")
    private Instant creditGrantPeriodEnd;

    // T-S3-F0879-0917 REPAIR ROUND [vikram · 2026-09-18] -- F-0884: closes the "missed renewal
    // webhook -> safety-net resets mid-period, monthly job resets again on the 1st -> two full
    // refills" double-reset, WITHOUT touching SubscriptionService.java (out of scope for this
    // lane). resetForNewCycle now guards on the workspace's billing period (this field) in
    // addition to resetForNewCycleIfDue's separate calendar-month guard -- see
    // AICreditService#resetForNewCycle javadoc.
    //   Source: F-0884 repair round
    //
    // T-CREDITCLOCK-0918 [vikram · 2026-09-18] -- DEPRECATED, LEAVE UNUSED per Swapnil's ruling
    // (wiki/decisions/2026-09-18-ai-credit-clock.md §4): the F-0884 double-reset problem is now
    // closed by AICreditResetJob skipping BILLING_PERIOD workspaces entirely, not by a second
    // period marker on this entity -- credit_grant_period_end alone is the single "billing period
    // last filled" marker for both the grant and the renewal/reset paths. Nothing reads or writes
    // this field any more. No destructive migration drops the column as part of this build; a
    // later housekeeping migration may.
    @Deprecated
    @Column(name = "last_reset_period_end")
    private Instant lastResetPeriodEnd;

    // T-CREDITCLOCK-0918 repair round [vikram · 2026-09-18] -- Swapnil's new ruling (relayed
    // 2026-09-18, same day as the clock ruling): a funded-launch reset via
    // AICreditService#applyEscrowFundedReset is now capped at ONE full refill per BILLING PERIOD,
    // "like upgrades" -- reversing the clock decision doc's earlier "not decided here, keep
    // refilling on every funded launch" note for BILLING_PERIOD workspaces. This is a SEPARATE
    // marker from creditGrantPeriodEnd on purpose: the clock decision doc's scenario 9 ("a funded
    // launch on Pro -> 450 without touching credit_grant_period_end") still holds verbatim, and a
    // repeat funded launch must not suppress (or be suppressed by) the billing-refill primitive's
    // own once-per-period grant -- the two guards are independent so a webhook renewal and a
    // funded launch in the same period each still get exactly their own one grant. NULL for a
    // CALENDAR_MONTH workspace (Free/comp/ex-Pro), which has no billing period to gate on and
    // keeps the pre-existing "refill on every funded launch" behavior unchanged.
    //   Source: repair round HIGH finding on applyEscrowFundedReset; Swapnil ruling 2026-09-18
    @Column(name = "escrow_funded_period_end")
    private Instant escrowFundedPeriodEnd;

    /** P4: daily action counter for the 500/day hard cap (20-ROHAN-COST-REVIEW.md section 5). */
    @Column(name = "daily_actions_used", nullable = false)
    private int dailyActionsUsed;

    /** P4: the date for which dailyActionsUsed applies; resets at midnight UTC. */
    @Column(name = "daily_actions_date")
    private LocalDate dailyActionsDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BrandAiCredit() {}

    public String getWorkspaceId() {
        return workspaceId;
    }

    public int getCreditsRemaining() {
        return creditsRemaining;
    }

    public void setCreditsRemaining(int creditsRemaining) {
        this.creditsRemaining = creditsRemaining;
        touch();
    }

    /**
     * F-3 [vikram · 2026-09-17] -- read-only from the outside; see the field javadoc above.
     * Callers that want to change the allotment call {@link #setPlanAllotment(int)} or {@link
     * #setLoyaltyBonus(int)}, both of which recompute this column so it can still be read
     * directly in JPQL (BrandAiCreditRepository#refundCredits pins {@code c.monthlyAllotment} in
     * an @Query, which requires a real mapped column, not a @Transient/derived-in-Java-only field).
     */
    public int getMonthlyAllotment() {
        return monthlyAllotment;
    }

    public int getPlanAllotment() {
        return planAllotment;
    }

    public void setPlanAllotment(int planAllotment) {
        this.planAllotment = planAllotment;
        recomputeMonthlyAllotment();
    }

    public int getLoyaltyBonus() {
        return loyaltyBonus;
    }

    public void setLoyaltyBonus(int loyaltyBonus) {
        this.loyaltyBonus = loyaltyBonus;
        recomputeMonthlyAllotment();
    }

    /**
     * F-3 [vikram · 2026-09-17] -- the single place planAllotment and loyaltyBonus combine. Do
     * not inline this at each call site -- the whole point of the split is that nothing else
     * ever assigns monthlyAllotment.
     *   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3
     */
    private void recomputeMonthlyAllotment() {
        this.monthlyAllotment = this.planAllotment + this.loyaltyBonus;
        touch();
    }

    public LocalDate getCycleStart() {
        return cycleStart;
    }

    public Instant getUnlimitedUntil() {
        return unlimitedUntil;
    }

    public void setUnlimitedUntil(Instant unlimitedUntil) {
        this.unlimitedUntil = unlimitedUntil;
        touch();
    }

    public LocalDate getLastReset() {
        return lastReset;
    }

    public void setLastReset(LocalDate lastReset) {
        this.lastReset = lastReset;
        touch();
    }

    public Instant getFirstCampaignAt() {
        return firstCampaignAt;
    }

    public void setFirstCampaignAt(Instant firstCampaignAt) {
        this.firstCampaignAt = firstCampaignAt;
        touch();
    }

    public Instant getCreditGrantPeriodEnd() {
        return creditGrantPeriodEnd;
    }

    public void setCreditGrantPeriodEnd(Instant creditGrantPeriodEnd) {
        this.creditGrantPeriodEnd = creditGrantPeriodEnd;
        touch();
    }

    /** @deprecated LEAVE UNUSED -- see field javadoc above. */
    @Deprecated
    public Instant getLastResetPeriodEnd() {
        return lastResetPeriodEnd;
    }

    /** @deprecated LEAVE UNUSED -- see field javadoc above. */
    @Deprecated
    public void setLastResetPeriodEnd(Instant lastResetPeriodEnd) {
        this.lastResetPeriodEnd = lastResetPeriodEnd;
        touch();
    }

    public Instant getEscrowFundedPeriodEnd() {
        return escrowFundedPeriodEnd;
    }

    /** Test-fixture / full-row-save convenience -- written by the atomic query in production. */
    public void setEscrowFundedPeriodEnd(Instant escrowFundedPeriodEnd) {
        this.escrowFundedPeriodEnd = escrowFundedPeriodEnd;
        touch();
    }

    public int getDailyActionsUsed() {
        return dailyActionsUsed;
    }

    public void setDailyActionsUsed(int dailyActionsUsed) {
        this.dailyActionsUsed = dailyActionsUsed;
        touch();
    }

    public LocalDate getDailyActionsDate() {
        return dailyActionsDate;
    }

    public void setDailyActionsDate(LocalDate dailyActionsDate) {
        this.dailyActionsDate = dailyActionsDate;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    /** True if the workspace is currently in an unlimited (funded-campaign) window. */
    public boolean isUnlimited(Instant now) {
        return unlimitedUntil != null && unlimitedUntil.isAfter(now);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final BrandAiCredit c = new BrandAiCredit();

        // F-0882 [vikram · 2026-09-18 REPAIR ROUND] -- build() used to rewrite ANY row whose
        // creditsRemaining == 0 up to monthlyAllotment, with no way to tell "caller never set it"
        // apart from "caller explicitly wants a genuine 0-credit row". Every test that built a
        // "Free, 0 credits" fixture via .creditsRemaining(0) silently got a FULL allotment instead
        // -- e.g. the pre-repair testApplyPlanAllotmentTopsUpCreditsRemainingOnIncrease "proved"
        // 0 -> 400 while its fixture actually held 100 -> 400 the entire time. This flag is set
        // ONLY by the explicit .creditsRemaining(int) call below, so build() can distinguish
        // "explicitly zero" (kept as 0) from "never set" (defaulted, unchanged production
        // behavior for every real caller, none of which ever omits it -- see AICreditService
        // #ensureInitialized).
        //   Source: F-0882 repair round
        private boolean creditsRemainingExplicitlySet = false;

        public Builder workspaceId(String workspaceId) {
            c.workspaceId = workspaceId;
            return this;
        }

        public Builder creditsRemaining(int creditsRemaining) {
            c.creditsRemaining = creditsRemaining;
            creditsRemainingExplicitlySet = true;
            return this;
        }

        /**
         * F-3 [vikram · 2026-09-17] -- kept as a convenience alias for planAllotment (not a
         * separate concept): existing callers across the repo (Builder call sites outside this
         * package, e.g. MeeraSessionServiceTest) still say {@code .monthlyAllotment(100)} to mean
         * "this workspace's base allotment is 100, no loyalty bonus" -- changing this method's
         * meaning would silently break those unowned tests without a compile error.
         */
        public Builder monthlyAllotment(int monthlyAllotment) {
            c.planAllotment = monthlyAllotment;
            return this;
        }

        public Builder planAllotment(int planAllotment) {
            c.planAllotment = planAllotment;
            return this;
        }

        public Builder loyaltyBonus(int loyaltyBonus) {
            c.loyaltyBonus = loyaltyBonus;
            return this;
        }

        public Builder cycleStart(LocalDate cycleStart) {
            c.cycleStart = cycleStart;
            return this;
        }

        public Builder lastReset(LocalDate lastReset) {
            c.lastReset = lastReset;
            return this;
        }

        /** Test-fixture convenience (F-0881/F-0883 repair round) -- see field javadoc above. */
        public Builder creditGrantPeriodEnd(Instant creditGrantPeriodEnd) {
            c.creditGrantPeriodEnd = creditGrantPeriodEnd;
            return this;
        }

        /** Test-fixture convenience (F-0884 repair round) -- see field javadoc above. */
        public Builder lastResetPeriodEnd(Instant lastResetPeriodEnd) {
            c.lastResetPeriodEnd = lastResetPeriodEnd;
            return this;
        }

        /** Test-fixture convenience (T-CREDITCLOCK-0918 repair round) -- see field javadoc above. */
        public Builder escrowFundedPeriodEnd(Instant escrowFundedPeriodEnd) {
            c.escrowFundedPeriodEnd = escrowFundedPeriodEnd;
            return this;
        }

        public BrandAiCredit build() {
            Instant now = Instant.now();
            c.createdAt = now;
            c.updatedAt = now;
            if (c.planAllotment == 0) {
                c.planAllotment = 100;
            }
            // F-3 [vikram · 2026-09-17] -- monthlyAllotment is derived; compute it here instead of
            //   defaulting it independently, or a builder that sets planAllotment/loyaltyBonus but
            //   not monthlyAllotment would leave the column at its Java-default 0.
            c.monthlyAllotment = c.planAllotment + c.loyaltyBonus;
            // F-0882 [vikram · 2026-09-18 REPAIR ROUND] -- only default an UNSET creditsRemaining
            //   to the full allotment; an explicit .creditsRemaining(0) must stay a genuine 0. See
            //   the creditsRemainingExplicitlySet field javadoc above for why == 0 alone can't tell
            //   the two apart.
            if (!creditsRemainingExplicitlySet) {
                c.creditsRemaining = c.monthlyAllotment;
            }
            if (c.cycleStart == null) {
                c.cycleStart = LocalDate.now();
            }
            if (c.lastReset == null) {
                c.lastReset = c.cycleStart;
            }
            return c;
        }
    }
}
