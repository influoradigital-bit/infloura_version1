package com.influora.domain.entity;

import com.influora.domain.enums.TransactionStatus;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "wallet_id", nullable = false, length = 26)
    private String walletId;

    @Column(name = "group_id", nullable = false, length = 26)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxnDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalletTransactionType type;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_after", nullable = false, precision = 14, scale = 2)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type")
    private TxnReferenceType referenceType;

    @Column(name = "reference_id", length = 26)
    private String referenceId;

    @Column(length = 300)
    private String description;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "gateway_ref", length = 120)
    private String gatewayRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletTransaction() {}

    public String getId() {
        return id;
    }

    public String getWalletId() {
        return walletId;
    }

    public String getGroupId() {
        return groupId;
    }

    public TxnDirection getDirection() {
        return direction;
    }

    public WalletTransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public TxnReferenceType getReferenceType() {
        return referenceType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getDescription() {
        return description;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getGatewayRef() {
        return gatewayRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final WalletTransaction t = new WalletTransaction();

        public Builder id(String id) {
            t.id = id;
            return this;
        }

        public Builder walletId(String walletId) {
            t.walletId = walletId;
            return this;
        }

        public Builder groupId(String groupId) {
            t.groupId = groupId;
            return this;
        }

        public Builder direction(TxnDirection direction) {
            t.direction = direction;
            return this;
        }

        public Builder type(WalletTransactionType type) {
            t.type = type;
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

        public Builder balanceAfter(BigDecimal balanceAfter) {
            t.balanceAfter = balanceAfter;
            return this;
        }

        public Builder status(TransactionStatus status) {
            t.status = status;
            return this;
        }

        public Builder referenceType(TxnReferenceType referenceType) {
            t.referenceType = referenceType;
            return this;
        }

        public Builder referenceId(String referenceId) {
            t.referenceId = referenceId;
            return this;
        }

        public Builder description(String description) {
            t.description = description;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            t.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder gatewayRef(String gatewayRef) {
            t.gatewayRef = gatewayRef;
            return this;
        }

        public WalletTransaction build() {
            if (t.currency == null) {
                t.currency = "INR";
            }
            if (t.status == null) {
                t.status = TransactionStatus.COMPLETED;
            }
            t.createdAt = Instant.now();
            return t;
        }
    }
}
