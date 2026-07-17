package com.influora.domain.entity;

import com.influora.domain.enums.WalletTopUpStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Brand wallet top-up order (B0 — wiki/tech/BRAND_EXECUTION_PLAN.md, LOCKED leadership ruling
 * 2026-07-09, item 3). Mirrors {@link EscrowHold}'s PENDING-order / webhook-confirmed-credit
 * discipline: {@code initiateTopUp} creates this row PENDING alongside a Razorpay order; only a
 * signature-verified {@code payment.captured} webhook can flip it to {@code CREDITED} and credit
 * the brand workspace wallet via {@code WalletLedgerService.post} — never the client's
 * order-creation response.
 *
 * <p>Wallet balance funded by a top-up is a customer-deposit LIABILITY, not platform revenue (CFO
 * constraint, LOCKED ruling item 6) — this entity carries no revenue-recognition fields; the
 * credited amount lands in the brand's own {@code wallets.balance} row, same as any other
 * {@code DEPOSIT} ledger posting.
 */
@Entity
@Table(name = "wallet_topups")
public class WalletTopUp {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalletTopUpStatus status;

    @Column(name = "razorpay_order_id", length = 64)
    private String razorpayOrderId;

    @Column(name = "credit_txn_id", length = 26)
    private String creditTxnId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletTopUp() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public WalletTopUpStatus getStatus() {
        return status;
    }

    public String getRazorpayOrderId() {
        return razorpayOrderId;
    }

    public void setRazorpayOrderId(String razorpayOrderId) {
        this.razorpayOrderId = razorpayOrderId;
        touch();
    }

    public String getCreditTxnId() {
        return creditTxnId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreditedAt() {
        return creditedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** PENDING -> CREDITED, recording the ledger credit leg that funded the brand's wallet. */
    public void markCredited(String creditTxnId) {
        this.status = WalletTopUpStatus.CREDITED;
        this.creditTxnId = creditTxnId;
        this.creditedAt = Instant.now();
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final WalletTopUp t = new WalletTopUp();

        public Builder id(String id) {
            t.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            t.workspaceId = workspaceId;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            t.amount = amount;
            return this;
        }

        public Builder currency(String currency) {
            t.currency = currency;
            return this;
        }

        public Builder status(WalletTopUpStatus status) {
            t.status = status;
            return this;
        }

        public Builder razorpayOrderId(String razorpayOrderId) {
            t.razorpayOrderId = razorpayOrderId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            t.idempotencyKey = idempotencyKey;
            return this;
        }

        public WalletTopUp build() {
            if (t.currency == null) {
                t.currency = "INR";
            }
            if (t.status == null) {
                t.status = WalletTopUpStatus.PENDING;
            }
            Instant now = Instant.now();
            t.createdAt = now;
            t.updatedAt = now;
            return t;
        }
    }
}
