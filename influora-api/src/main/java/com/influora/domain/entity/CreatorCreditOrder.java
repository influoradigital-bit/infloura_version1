package com.influora.domain.entity;

import com.influora.domain.enums.CreatorCreditOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.4, §2.5.
 *
 * <p>Mirrors {@link WalletTopUp} column for column where the concept matches: {@code pack_code},
 * {@code credits} and {@code amount_paise} are point-in-time snapshots taken from {@link
 * CreatorCreditPack} at order-creation time, so a later catalogue price change never changes what
 * an already-placed order credits. {@code credits} is TENTHS (§0 rule 11) -- a Starter order
 * snapshots 500.
 *
 * <p>Priya's correction to CREDITS-SPEC §2.5(c): {@code razorpayOrderId} is nullable with a
 * UNIQUE key (MySQL permits many NULLs in a UNIQUE index) -- the column must NOT be declared
 * {@code nullable = false}, or {@code ddl-auto=validate} fails against the migration's {@code
 * NULL} column.
 */
@Entity
@Table(name = "creator_credit_orders")
public class CreatorCreditOrder {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Column(name = "pack_id", nullable = false, length = 26)
    private String packId;

    /** Snapshot of CreatorCreditPack.code at order-creation time. */
    @Column(name = "pack_code", nullable = false, length = 20)
    private String packCode;

    /** Snapshot of CreatorCreditPack.credits at order-creation time. TENTHS. */
    @Column(nullable = false)
    private int credits;

    /** Snapshot of CreatorCreditPack.pricePaise at order-creation time. */
    @Column(name = "amount_paise", nullable = false)
    private int amountPaise;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CreatorCreditOrderStatus status;

    /** Nullable with a UNIQUE key -- see class javadoc; do NOT add nullable = false here. */
    @Column(name = "razorpay_order_id", length = 64)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorCreditOrder() {}

    public String getId() {
        return id;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public String getPackId() {
        return packId;
    }

    public String getPackCode() {
        return packCode;
    }

    public int getCredits() {
        return credits;
    }

    public int getAmountPaise() {
        return amountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public CreatorCreditOrderStatus getStatus() {
        return status;
    }

    public String getRazorpayOrderId() {
        return razorpayOrderId;
    }

    /** Same shape as {@code WalletTopUp#setRazorpayOrderId}. */
    public void setRazorpayOrderId(String razorpayOrderId) {
        this.razorpayOrderId = razorpayOrderId;
        touch();
    }

    public String getRazorpayPaymentId() {
        return razorpayPaymentId;
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

    /** PENDING -> CREDITED. Same shape as {@code WalletTopUp#markCredited}. */
    public void markCredited(String razorpayPaymentId) {
        this.status = CreatorCreditOrderStatus.CREDITED;
        this.razorpayPaymentId = razorpayPaymentId;
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
        private final CreatorCreditOrder o = new CreatorCreditOrder();

        public Builder id(String id) {
            o.id = id;
            return this;
        }

        public Builder creatorUserId(String creatorUserId) {
            o.creatorUserId = creatorUserId;
            return this;
        }

        public Builder packId(String packId) {
            o.packId = packId;
            return this;
        }

        public Builder packCode(String packCode) {
            o.packCode = packCode;
            return this;
        }

        public Builder credits(int credits) {
            o.credits = credits;
            return this;
        }

        public Builder amountPaise(int amountPaise) {
            o.amountPaise = amountPaise;
            return this;
        }

        public Builder currency(String currency) {
            o.currency = currency;
            return this;
        }

        public Builder status(CreatorCreditOrderStatus status) {
            o.status = status;
            return this;
        }

        public Builder razorpayOrderId(String razorpayOrderId) {
            o.razorpayOrderId = razorpayOrderId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            o.idempotencyKey = idempotencyKey;
            return this;
        }

        public CreatorCreditOrder build() {
            if (o.currency == null) {
                o.currency = "INR";
            }
            if (o.status == null) {
                o.status = CreatorCreditOrderStatus.PENDING;
            }
            Instant now = Instant.now();
            o.createdAt = now;
            o.updatedAt = now;
            return o;
        }
    }
}
