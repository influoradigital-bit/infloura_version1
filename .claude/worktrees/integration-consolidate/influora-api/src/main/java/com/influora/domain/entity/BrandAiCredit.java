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

    @Column(name = "monthly_allotment", nullable = false)
    private int monthlyAllotment;

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

    public int getMonthlyAllotment() {
        return monthlyAllotment;
    }

    public void setMonthlyAllotment(int monthlyAllotment) {
        this.monthlyAllotment = monthlyAllotment;
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

        public Builder monthlyAllotment(int monthlyAllotment) {
            c.monthlyAllotment = monthlyAllotment;
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
            if (c.monthlyAllotment == 0) {
                c.monthlyAllotment = 100;
            }
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
