package com.influora.domain.entity;

import com.influora.domain.enums.EscrowStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Locked funds per collaboration/milestone/campaign pool. Funding a hold moves money from
 * {@code wallets.balance} to {@code wallets.escrow_balance} via {@code WalletLedgerService.post()}
 * (never a direct balance mutation). The {@code PENDING -> FUNDED} transition is the hook that
 * resets brand AI credits (see V14 / EscrowFundedEvent) — not built in this slice.
 */
@Entity
@Table(name = "escrow_holds")
public class EscrowHold {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "collaboration_id", length = 26)
    private String collaborationId;

    @Column(name = "campaign_id", length = 26)
    private String campaignId;

    @Column(name = "milestone_id", length = 26)
    private String milestoneId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EscrowStatus status;

    @Column(name = "hold_txn_id", length = 26)
    private String holdTxnId;

    @Column(name = "release_txn_id", length = 26)
    private String releaseTxnId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "funded_at")
    private Instant fundedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EscrowHold() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    /**
     * Binds an already-FUNDED hold to the collaboration it is funding — used by
     * {@code ConfirmLaunchExecutor} to make the escrow money traceable to the specific creator
     * invited at launch, when the hold was created campaign-scoped (no collaboration yet) ahead
     * of invites being sent. A no-op if already bound (never overwrites an existing linkage).
     */
    public void bindCollaboration(String collaborationId) {
        if (this.collaborationId == null) {
            this.collaborationId = collaborationId;
            touch();
        }
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getMilestoneId() {
        return milestoneId;
    }

    public void setMilestoneId(String milestoneId) {
        this.milestoneId = milestoneId;
        touch();
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public EscrowStatus getStatus() {
        return status;
    }

    public String getHoldTxnId() {
        return holdTxnId;
    }

    public String getReleaseTxnId() {
        return releaseTxnId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getFundedAt() {
        return fundedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** PENDING -> FUNDED, recording the ledger debit leg that moved the money into escrow. */
    public void markFunded(String holdTxnId) {
        this.status = EscrowStatus.FUNDED;
        this.holdTxnId = holdTxnId;
        this.fundedAt = Instant.now();
        touch();
    }

    /** FUNDED -> RELEASED, recording the ledger credit leg that paid the creator out of escrow. */
    public void markReleased(String releaseTxnId) {
        this.status = EscrowStatus.RELEASED;
        this.releaseTxnId = releaseTxnId;
        this.releasedAt = Instant.now();
        touch();
    }

    /** FUNDED -> REFUNDED, recording the ledger credit leg that returned funds to the brand wallet. */
    public void markRefunded(String releaseTxnId) {
        this.status = EscrowStatus.REFUNDED;
        this.releaseTxnId = releaseTxnId;
        this.releasedAt = Instant.now();
        touch();
    }

    public void markFrozen() {
        this.status = EscrowStatus.FROZEN;
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final EscrowHold h = new EscrowHold();

        public Builder id(String id) {
            h.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            h.workspaceId = workspaceId;
            return this;
        }

        public Builder collaborationId(String collaborationId) {
            h.collaborationId = collaborationId;
            return this;
        }

        public Builder campaignId(String campaignId) {
            h.campaignId = campaignId;
            return this;
        }

        public Builder milestoneId(String milestoneId) {
            h.milestoneId = milestoneId;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            h.amount = amount;
            return this;
        }

        public Builder currency(String currency) {
            h.currency = currency;
            return this;
        }

        public Builder status(EscrowStatus status) {
            h.status = status;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            h.idempotencyKey = idempotencyKey;
            return this;
        }

        public EscrowHold build() {
            if (h.currency == null) {
                h.currency = "INR";
            }
            if (h.status == null) {
                h.status = EscrowStatus.PENDING;
            }
            Instant now = Instant.now();
            h.createdAt = now;
            h.updatedAt = now;
            return h;
        }
    }
}
