package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A per-creator coupon code for conversion tracking (V24 {@code coupon_codes}) -- Phase 4
 * UTM/Coupon Tracking (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2.6, corrected by §10 "Unique
 * Coupons Per Creator (CRITICAL FIX)"), created by {@code CouponCodeService#addCreatorToCampaign}.
 *
 * <p>[SPEC CONFLICT RESOLVED] §2.6 originally allowed a nullable {@code collaborationId}/{@code
 * creatorProfileId} (campaign-wide codes) with a single global unique code. §10 supersedes that --
 * every creator gets a mandatory, unique coupon code per campaign -- so this entity has a NOT NULL
 * {@code creatorId} and a direct {@code workspaceId} column (unlike {@code UtmCampaign}, which has
 * no {@code workspace_id} column at all and is scoped only via a join through {@code campaigns}).
 *
 * <p>[CTO RULING -- wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
 * translation discipline as {@code UtmCampaign}/{@code CreatorScore}: {@code expiresAt}/{@code
 * createdAt} are stored as {@code DATETIME(6)} UTC (see V24 migration comment), not {@code
 * TIMESTAMPTZ}.
 *
 * <p>Per spec table §2.7: "Mutable (usage counts)" -- {@code usageCount} is incremented in place as
 * redemptions are recorded. That increment path ({@code ConversionTrackingService}/{@code
 * RedemptionService}) is a deliberately deferred follow-up -- this entity and {@code
 * CouponCodeService} cover code generation and storage only.
 */
@Entity
@Table(name = "coupon_codes")
public class CouponCode {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "campaign_id", nullable = false, length = 26)
    private String campaignId;

    @Column(name = "creator_id", nullable = false, length = 26)
    private String creatorId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    @Column(name = "expires_at", columnDefinition = "DATETIME(6)")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected CouponCode() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public String getCode() {
        return code;
    }

    public String getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public Integer getUsageLimit() {
        return usageLimit;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Increments the redemption usage counter by one. Called by {@code RedemptionService#redeem} on
     * every successful (non-idempotent-replay) redemption.
     *
     * <p><b>Scope note:</b> {@code coupon_codes} (V24) has no revenue/total-redeemed-amount column --
     * only {@code usage_limit}/{@code usage_count} exist (see V24 migration). The spec's pseudocode
     * calls a {@code coupon.addRevenue(orderAmount)} that has no backing column on this entity; per-
     * coupon revenue rollup is deliberately out of scope for this pass and deferred as a follow-up
     * (would need a new migration). Per-order discount/order-amount are still recorded durably on
     * {@code CouponRedemption} itself, so the raw data to compute this rollup later is not lost.
     */
    public void incrementUsageCount() {
        this.usageCount += 1;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CouponCode c = new CouponCode();

        public Builder id(String id) {
            c.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            c.workspaceId = workspaceId;
            return this;
        }

        public Builder campaignId(String campaignId) {
            c.campaignId = campaignId;
            return this;
        }

        public Builder creatorId(String creatorId) {
            c.creatorId = creatorId;
            return this;
        }

        public Builder code(String code) {
            c.code = code;
            return this;
        }

        public Builder discountType(String discountType) {
            c.discountType = discountType;
            return this;
        }

        public Builder discountValue(BigDecimal discountValue) {
            c.discountValue = discountValue;
            return this;
        }

        public Builder usageLimit(Integer usageLimit) {
            c.usageLimit = usageLimit;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            c.expiresAt = expiresAt;
            return this;
        }

        public CouponCode build() {
            c.usageCount = 0;
            c.createdAt = Instant.now();
            return c;
        }
    }
}
