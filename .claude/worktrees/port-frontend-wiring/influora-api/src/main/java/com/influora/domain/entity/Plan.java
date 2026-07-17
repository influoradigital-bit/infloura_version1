package com.influora.domain.entity;

import com.influora.domain.enums.BillingCycle;
import com.influora.domain.enums.PlanCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Subscription pricing tiers (FREE/PRO) — defines limits, feature gates, and fee overrides.
 * V54__subscription_billing.sql, Task 11/14 subscription billing prep batch 1.
 *
 * <p>No trial state exists anywhere in the flow. Free tier is permanently limited; Pro is a paid
 * upgrade with no time-boxed trial. Enterprise is deferred (not in this build).
 *
 * <p><b>feeBps (brand-side fee override):</b> per-plan override in basis points (700 = 7%). Null
 * means use the global {@code PlatformFeeConfig.brandFeeBps} (1000 = 10%). Audit correction: no
 * per-plan seam exists in {@code BrandCampaignFeeService} today — Task 14 must add a plan-aware
 * resolve signature, not just flip a config value.
 */
@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @Column(length = 26)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 20)
    private PlanCode code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "price_inr", nullable = false)
    private int priceInr;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 20)
    private BillingCycle billingCycle;

    @Column(name = "razorpay_plan_id", length = 100)
    private String razorpayPlanId;

    @Column(name = "fee_bps")
    private Integer feeBps;

    @Column(name = "ai_monthly_allotment", nullable = false)
    private int aiMonthlyAllotment;

    @Column(name = "seat_limit", nullable = false)
    private int seatLimit;

    @Column(name = "tracked_creator_limit")
    private Integer trackedCreatorLimit;

    @Column(name = "creator_analytics_monthly_limit")
    private Integer creatorAnalyticsMonthlyLimit;

    @Column(name = "export_enabled", nullable = false)
    private boolean exportEnabled;

    @Column(name = "campaign_templates_enabled", nullable = false)
    private boolean campaignTemplatesEnabled;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Plan() {}

    public String getId() {
        return id;
    }

    public PlanCode getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getPriceInr() {
        return priceInr;
    }

    public BillingCycle getBillingCycle() {
        return billingCycle;
    }

    public String getRazorpayPlanId() {
        return razorpayPlanId;
    }

    public Integer getFeeBps() {
        return feeBps;
    }

    public int getAiMonthlyAllotment() {
        return aiMonthlyAllotment;
    }

    public int getSeatLimit() {
        return seatLimit;
    }

    public Integer getTrackedCreatorLimit() {
        return trackedCreatorLimit;
    }

    public Integer getCreatorAnalyticsMonthlyLimit() {
        return creatorAnalyticsMonthlyLimit;
    }

    public boolean isExportEnabled() {
        return exportEnabled;
    }

    public boolean isCampaignTemplatesEnabled() {
        return campaignTemplatesEnabled;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setActive(boolean active) {
        this.active = active;
        touch();
    }

    /**
     * Backfills the Razorpay-side Plan id after lazy creation (Task 19 —
     * {@code SubscriptionService#ensureRazorpayPlanId}). V55 seeds this column NULL for both
     * tiers; Pro's is created on first checkout, not at seed time, since Razorpay Plans are
     * immutable and we only want to create one per environment, on demand.
     */
    public void setRazorpayPlanId(String razorpayPlanId) {
        this.razorpayPlanId = razorpayPlanId;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Plan p = new Plan();

        public Builder id(String id) {
            p.id = id;
            return this;
        }

        public Builder code(PlanCode code) {
            p.code = code;
            return this;
        }

        public Builder name(String name) {
            p.name = name;
            return this;
        }

        public Builder priceInr(int priceInr) {
            p.priceInr = priceInr;
            return this;
        }

        public Builder billingCycle(BillingCycle billingCycle) {
            p.billingCycle = billingCycle;
            return this;
        }

        public Builder razorpayPlanId(String razorpayPlanId) {
            p.razorpayPlanId = razorpayPlanId;
            return this;
        }

        public Builder feeBps(Integer feeBps) {
            p.feeBps = feeBps;
            return this;
        }

        public Builder aiMonthlyAllotment(int aiMonthlyAllotment) {
            p.aiMonthlyAllotment = aiMonthlyAllotment;
            return this;
        }

        public Builder seatLimit(int seatLimit) {
            p.seatLimit = seatLimit;
            return this;
        }

        public Builder trackedCreatorLimit(Integer trackedCreatorLimit) {
            p.trackedCreatorLimit = trackedCreatorLimit;
            return this;
        }

        public Builder creatorAnalyticsMonthlyLimit(Integer creatorAnalyticsMonthlyLimit) {
            p.creatorAnalyticsMonthlyLimit = creatorAnalyticsMonthlyLimit;
            return this;
        }

        public Builder exportEnabled(boolean exportEnabled) {
            p.exportEnabled = exportEnabled;
            return this;
        }

        public Builder campaignTemplatesEnabled(boolean campaignTemplatesEnabled) {
            p.campaignTemplatesEnabled = campaignTemplatesEnabled;
            return this;
        }

        public Builder active(boolean active) {
            p.active = active;
            return this;
        }

        public Plan build() {
            Instant now = Instant.now();
            p.createdAt = now;
            p.updatedAt = now;
            if (p.billingCycle == null) {
                p.billingCycle = BillingCycle.MONTHLY;
            }
            if (p.active == false && p.code != null) {
                p.active = true;
            }
            return p;
        }
    }
}
