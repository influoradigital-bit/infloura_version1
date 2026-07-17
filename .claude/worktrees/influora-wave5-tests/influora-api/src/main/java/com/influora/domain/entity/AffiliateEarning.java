package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One creator's commission on a single qualifying conversion (V27 {@code affiliate_earnings}) --
 * Wave D task D4 (wiki/tech/REMAINING_WORK_PLAN.md). Created by {@code AffiliateEarningsService}
 * when a {@link CouponRedemption} qualifies for affiliate commission, and later transitioned to
 * {@code SETTLED} by {@code AffiliateSettlementJob} once the creator's monthly payout for the period
 * containing this earning has gone out.
 *
 * <p><b>Double-credit guard [SEC: Kabir, mirrors coupon_redemptions.idempotency_key]</b> -- {@code
 * redemption_id} is {@code NOT NULL UNIQUE} at the schema level (V27): exactly one {@link
 * AffiliateEarning} can ever exist per {@link CouponRedemption}, structurally, regardless of
 * application-level bugs or retried calls. {@code idempotency_key} is ALSO {@code NOT NULL UNIQUE}
 * and is derived deterministically from {@code redemption_id} (see {@code
 * AffiliateEarningsService#deriveIdempotencyKey}) -- this is the belt half of belt-and-suspenders;
 * the {@code UNIQUE(redemption_id)} constraint is the suspenders that hold even if the derivation
 * itself ever changed shape.
 *
 * <p><b>Status machine:</b> {@code PENDING} (accrued, not yet paid) -&gt; {@code SETTLED} (paid out in
 * a monthly batch, see {@link #settlementBatchId}) or {@code FAILED} (a settlement attempt for this
 * earning failed -- NOT terminal, the next settlement run retries any {@code FAILED} or {@code
 * PENDING} earning for that creator/period alike, mirroring {@code IdempotencyService}'s FAILED-is-
 * retryable discipline). There is no path back from {@code SETTLED} -- once paid, permanently
 * terminal, exactly like {@code IdempotencyService}'s {@code COMPLETED}.
 */
@Entity
@Table(name = "affiliate_earnings")
public class AffiliateEarning {

    public enum Status {
        PENDING,
        SETTLED,
        FAILED
    }

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "campaign_id", nullable = false, length = 26)
    private String campaignId;

    @Column(name = "creator_id", nullable = false, length = 26)
    private String creatorId;

    @Column(name = "redemption_id", nullable = false, unique = true, length = 26)
    private String redemptionId;

    @Column(name = "commission_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal commissionAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "settlement_batch_id", length = 26)
    private String settlementBatchId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @Column(name = "settled_at", columnDefinition = "DATETIME(6)")
    private Instant settledAt;

    protected AffiliateEarning() {}

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

    public String getRedemptionId() {
        return redemptionId;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public String getSettlementBatchId() {
        return settlementBatchId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    /** Marks this earning SETTLED under {@code batchId} -- terminal, mirrors {@code IdempotencyKeyRecord#markCompleted}. */
    public void markSettled(String batchId) {
        this.status = Status.SETTLED;
        this.settlementBatchId = batchId;
        this.settledAt = Instant.now();
    }

    /** Marks this earning FAILED for {@code batchId} -- NOT terminal, see class javadoc; a later run retries it. */
    public void markFailed(String batchId) {
        this.status = Status.FAILED;
        this.settlementBatchId = batchId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AffiliateEarning e = new AffiliateEarning();

        public Builder id(String id) {
            e.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            e.workspaceId = workspaceId;
            return this;
        }

        public Builder campaignId(String campaignId) {
            e.campaignId = campaignId;
            return this;
        }

        public Builder creatorId(String creatorId) {
            e.creatorId = creatorId;
            return this;
        }

        public Builder redemptionId(String redemptionId) {
            e.redemptionId = redemptionId;
            return this;
        }

        public Builder commissionAmount(BigDecimal commissionAmount) {
            e.commissionAmount = commissionAmount;
            return this;
        }

        public Builder currency(String currency) {
            e.currency = currency;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            e.idempotencyKey = idempotencyKey;
            return this;
        }

        public AffiliateEarning build() {
            e.status = Status.PENDING;
            e.createdAt = Instant.now();
            return e;
        }
    }
}
