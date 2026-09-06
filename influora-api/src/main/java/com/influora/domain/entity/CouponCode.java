package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A coupon code for conversion tracking (V24 {@code coupon_codes}) -- Phase 4 UTM/Coupon Tracking
 * (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2.6, corrected by §10 "Unique Coupons Per Creator
 * (CRITICAL FIX)"), created by {@code CouponCodeService#addCreatorToCampaign} (per-creator) or
 * {@code CouponCodeService#addBrandLevelCoupon} (brand-level).
 *
 * <p>[SPEC CONFLICT RESOLVED] §2.6 originally allowed a nullable {@code collaborationId}/{@code
 * creatorProfileId} (campaign-wide codes) with a single global unique code. §10 supersedes that --
 * every creator gets a mandatory, unique coupon code per campaign -- so V24 gave this entity a NOT
 * NULL {@code creatorId} and a direct {@code workspaceId} column (unlike {@code UtmCampaign}, which
 * has no {@code workspace_id} column at all and is scoped only via a join through {@code
 * campaigns}).
 *
 * <p><b>[T-FESTIVALBOX-0905 phase 4] {@code creatorId} is now NULLABLE -- see the
 * V20260905160000 migration header for the full §10 relationship.</b> In short: §10 guarded
 * against several *creators* silently sharing one code (ambiguous attribution among real people);
 * a coupon with NO creator at all is a different, deliberate case -- a brand-level, page-exclusive
 * code shown on a public Festival Box page, attributed to the brand only. {@link #isBrandLevel()}
 * is the single source of truth for which case a given row is; the schema additionally enforces at
 * most one brand-level row per campaign (see that migration's generated-column + UNIQUE(campaign_id,
 * brand_level_marker) step), and {@link #brandLevelBuilder()} makes it structurally impossible to
 * accidentally construct a brand-level instance that also carries a creator id.
 *
 * <p><b>Money-path consequence (landmine 1 in the task brief):</b> a brand-level coupon earns NO
 * creator affiliate commission -- there is no creator to pay. {@code
 * AffiliateEarningsService#recordEarning} checks {@link #isBrandLevel()} before ever building an
 * {@code AffiliateEarning} row (whose own {@code creatorId} column is, and remains, NOT NULL); a
 * redemption against a brand-level coupon is still recorded and its {@code usageCount} still
 * incremented, only the commission step is skipped.
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

    // [T-FESTIVALBOX-0905 phase 4] Nullable since V20260905160000 -- NULL means a brand-level
    // ("page-exclusive") coupon. See class javadoc and that migration's header for the full §10
    // relationship this does NOT undo.
    @Column(name = "creator_id", length = 26)
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
     * True when this coupon has no associated creator -- a brand-level, page-exclusive code
     * (V20260905160000 migration, T-FESTIVALBOX-0905 phase 4). A brand-level code attributes a
     * sale to the brand only and earns NO creator affiliate commission -- see {@code
     * AffiliateEarningsService#recordEarning}, which checks this before ever building an {@code
     * AffiliateEarning} row. See class javadoc for the full relationship to V24 §10.
     */
    public boolean isBrandLevel() {
        return creatorId == null;
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

    /**
     * Builder for a brand-level coupon (T-FESTIVALBOX-0905 phase 4) -- deliberately has NO {@code
     * creatorId(...)} setter, so a brand-level code can never be constructed with a creator
     * attached by accident (see task brief: "add a factory or builder path for a brand-level code
     * that makes it impossible to construct one with a creator by accident"). Field-for-field
     * identical to {@link Builder} otherwise. {@link #isBrandLevel()} on the result is always
     * {@code true}.
     */
    public static BrandLevelBuilder brandLevelBuilder() {
        return new BrandLevelBuilder();
    }

    public static final class BrandLevelBuilder {
        private final CouponCode c = new CouponCode();

        public BrandLevelBuilder id(String id) {
            c.id = id;
            return this;
        }

        public BrandLevelBuilder workspaceId(String workspaceId) {
            c.workspaceId = workspaceId;
            return this;
        }

        public BrandLevelBuilder campaignId(String campaignId) {
            c.campaignId = campaignId;
            return this;
        }

        public BrandLevelBuilder code(String code) {
            c.code = code;
            return this;
        }

        public BrandLevelBuilder discountType(String discountType) {
            c.discountType = discountType;
            return this;
        }

        public BrandLevelBuilder discountValue(BigDecimal discountValue) {
            c.discountValue = discountValue;
            return this;
        }

        public BrandLevelBuilder usageLimit(Integer usageLimit) {
            c.usageLimit = usageLimit;
            return this;
        }

        public BrandLevelBuilder expiresAt(Instant expiresAt) {
            c.expiresAt = expiresAt;
            return this;
        }

        /** {@code creatorId} is never set -- there is no setter for it on this builder. */
        public CouponCode build() {
            c.usageCount = 0;
            c.createdAt = Instant.now();
            return c;
        }
    }
}
