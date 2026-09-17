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

        public Builder workspaceId(String workspaceId) {
            c.workspaceId = workspaceId;
            return this;
        }

        public Builder creditsRemaining(int creditsRemaining) {
            c.creditsRemaining = creditsRemaining;
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
            if (c.creditsRemaining == 0) {
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
