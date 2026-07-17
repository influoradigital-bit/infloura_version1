package com.influora.domain.entity;

import com.influora.domain.enums.MilestoneStatus;
import com.influora.domain.enums.ReleaseCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A single scheduled release within a {@link Contract}. {@code amount} is the server-side
 * source of truth for any escrow release — never trusted from an AI-proposed or client value.
 */
@Entity
@Table(name = "payment_milestones")
public class PaymentMilestone {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "contract_id", nullable = false, length = 26)
    private String contractId;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(length = 300)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MilestoneStatus status;

    @Column(name = "escrow_hold_id", length = 26)
    private String escrowHoldId;

    @Column(name = "released_txn_id", length = 26)
    private String releasedTxnId;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    /**
     * [B5 fix] {@code payment_milestones.release_condition} (V52) — previously completely unmapped
     * on this entity ("dead schema — approval never gates/triggers release in either direction,
     * grep 0 refs"). {@code EscrowService#release} now reads this to gate the release (see
     * {@link ReleaseCondition} javadoc). Defaults to {@code ON_POSTED} in {@link Builder#build()}
     * to match the column's DB default, so every existing caller that never sets this explicitly
     * (e.g. {@code ContractService}) keeps working unchanged.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "release_condition", nullable = false)
    private ReleaseCondition releaseCondition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentMilestone() {}

    public String getId() {
        return id;
    }

    public String getContractId() {
        return contractId;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public MilestoneStatus getStatus() {
        return status;
    }

    public String getEscrowHoldId() {
        return escrowHoldId;
    }

    public String getReleasedTxnId() {
        return releasedTxnId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ReleaseCondition getReleaseCondition() {
        return releaseCondition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markFunded(String escrowHoldId) {
        this.escrowHoldId = escrowHoldId;
        this.status = MilestoneStatus.FUNDED;
        touch();
    }

    public void markReleased(String releasedTxnId, String idempotencyKey) {
        this.releasedTxnId = releasedTxnId;
        this.idempotencyKey = idempotencyKey;
        this.status = MilestoneStatus.RELEASED;
        touch();
    }

    public void markRefunded(String releasedTxnId, String idempotencyKey) {
        this.releasedTxnId = releasedTxnId;
        this.idempotencyKey = idempotencyKey;
        this.status = MilestoneStatus.REFUNDED;
        touch();
    }

    /**
     * Records that a RazorpayX payout has been queued for this (already-RELEASED) milestone under
     * {@code idempotencyKey} — the local replay guard {@code PayoutService} checks before ever
     * calling {@code RazorpayXClient.initiatePayout} again [E2 audit finding #9, CRITICAL]. Does
     * NOT change {@link #status}; payout is a wholly separate, out-of-band leg from the
     * release/refund escrow state machine (see {@code PayoutService} class javadoc) — reusing this
     * column is safe only because RELEASED is terminal for {@link #markReleased}/{@link
     * #markRefunded}, so nothing else will overwrite this value afterward.
     */
    public void markPayoutQueued(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        touch();
    }

    public void markFrozen() {
        this.status = MilestoneStatus.FROZEN;
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final PaymentMilestone m = new PaymentMilestone();

        public Builder id(String id) {
            m.id = id;
            return this;
        }

        public Builder contractId(String contractId) {
            m.contractId = contractId;
            return this;
        }

        public Builder collaborationId(String collaborationId) {
            m.collaborationId = collaborationId;
            return this;
        }

        public Builder sequenceNo(int sequenceNo) {
            m.sequenceNo = sequenceNo;
            return this;
        }

        public Builder description(String description) {
            m.description = description;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            m.amount = amount;
            return this;
        }

        public Builder currency(String currency) {
            m.currency = currency;
            return this;
        }

        public Builder dueDate(LocalDate dueDate) {
            m.dueDate = dueDate;
            return this;
        }

        public Builder status(MilestoneStatus status) {
            m.status = status;
            return this;
        }

        public Builder releaseCondition(ReleaseCondition releaseCondition) {
            m.releaseCondition = releaseCondition;
            return this;
        }

        public PaymentMilestone build() {
            if (m.currency == null) {
                m.currency = "INR";
            }
            if (m.status == null) {
                m.status = MilestoneStatus.PENDING;
            }
            if (m.releaseCondition == null) {
                m.releaseCondition = ReleaseCondition.ON_POSTED;
            }
            Instant now = Instant.now();
            m.createdAt = now;
            m.updatedAt = now;
            return m;
        }
    }
}
