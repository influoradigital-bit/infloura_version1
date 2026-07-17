package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An idempotent, audit-trail record of a single coupon redemption (V24 {@code
 * coupon_redemptions}) -- Phase 4 UTM/Coupon Tracking (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md
 * §2.6). Carried over from the original §2.6 design unchanged in shape -- §10's "Unique Coupons
 * Per Creator" addendum only redefines {@code coupon_codes}, not this table -- with its FK
 * repointed at the corrected {@code coupon_codes} shape.
 *
 * <p>Immutable, append-only per spec table §2.7. NOT yet created by anything in this pass --
 * redemption processing ({@code ConversionTrackingService}/{@code RedemptionService}) is a
 * deliberately deferred follow-up task; this entity + {@link
 * com.influora.repository.CouponRedemptionRepository} exist so that follow-up has storage to write
 * to, but no service in this codebase constructs one yet.
 *
 * <p>[SEC: Kabir] {@code idempotencyKey} is NOT NULL UNIQUE at the schema level (see V24 migration)
 * -- this is the mechanism that prevents a retried/duplicated webhook delivery from being counted
 * as two redemptions. Whoever builds the redemption-processing follow-up MUST generate this key
 * from the brand's own idempotency token (or a deterministic hash of coupon+order), never a fresh
 * random value per attempt, or the uniqueness constraint provides no protection.
 *
 * <p>{@code metadata} is stored as raw JSON {@code String} (same convention as every other JSON
 * column in this codebase, e.g. {@code Campaign.hashtagsJson}), never a directly-mapped {@code Map}.
 */
@Entity
@Table(name = "coupon_redemptions")
public class CouponRedemption {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "coupon_id", nullable = false, length = 26)
    private String couponId;

    @Column(name = "order_id", nullable = false, length = 100)
    private String orderId;

    @Column(name = "order_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal orderAmount;

    @Column(name = "discount_applied", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountApplied;

    @Column(name = "customer_id", length = 100)
    private String customerId;

    @Column(name = "redeemed_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant redeemedAt;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "json")
    private String metadataJson;

    protected CouponRedemption() {}

    public String getId() {
        return id;
    }

    public String getCouponId() {
        return couponId;
    }

    public String getOrderId() {
        return orderId;
    }

    public BigDecimal getOrderAmount() {
        return orderAmount;
    }

    public BigDecimal getDiscountApplied() {
        return discountApplied;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CouponRedemption r = new CouponRedemption();

        public Builder id(String id) {
            r.id = id;
            return this;
        }

        public Builder couponId(String couponId) {
            r.couponId = couponId;
            return this;
        }

        public Builder orderId(String orderId) {
            r.orderId = orderId;
            return this;
        }

        public Builder orderAmount(BigDecimal orderAmount) {
            r.orderAmount = orderAmount;
            return this;
        }

        public Builder discountApplied(BigDecimal discountApplied) {
            r.discountApplied = discountApplied;
            return this;
        }

        public Builder customerId(String customerId) {
            r.customerId = customerId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            r.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder metadataJson(String metadataJson) {
            r.metadataJson = metadataJson;
            return this;
        }

        public CouponRedemption build() {
            r.redeemedAt = Instant.now();
            return r;
        }
    }
}
