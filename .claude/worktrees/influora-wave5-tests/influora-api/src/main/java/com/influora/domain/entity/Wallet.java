package com.influora.domain.entity;

import com.influora.domain.enums.WalletOwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 26)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private WalletOwnerType ownerType;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal balance;

    @Column(name = "escrow_balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal escrowBalance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {}

    public static Wallet forWorkspace(String id, String workspaceId) {
        return create(id, workspaceId, WalletOwnerType.WORKSPACE);
    }

    /** Lazily created the first time a creator (user) needs a wallet to receive a payout. */
    public static Wallet forUser(String id, String userId) {
        return create(id, userId, WalletOwnerType.USER);
    }

    private static Wallet create(String id, String ownerId, WalletOwnerType ownerType) {
        Wallet w = new Wallet();
        w.id = id;
        w.ownerId = ownerId;
        w.ownerType = ownerType;
        w.balance = BigDecimal.ZERO;
        w.escrowBalance = BigDecimal.ZERO;
        w.currency = "INR";
        w.active = true;
        Instant now = Instant.now();
        w.createdAt = now;
        w.updatedAt = now;
        return w;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public WalletOwnerType getOwnerType() {
        return ownerType;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getEscrowBalance() {
        return escrowBalance;
    }

    public String getCurrency() {
        return currency;
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

    /**
     * Applies a ledger-derived delta to the wallet's spendable balance. Only
     * {@code WalletLedgerService} calls this, inside the same transaction that writes the
     * paired wallet_transactions rows — the ledger is the source of truth and this keeps the
     * denormalized balance column in sync with it.
     */
    public void applyBalanceDelta(BigDecimal delta) {
        this.balance = this.balance.add(delta);
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
