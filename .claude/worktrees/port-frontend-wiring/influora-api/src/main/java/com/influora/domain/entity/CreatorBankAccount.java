package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Creator bank / UPI instrument (Spec 12 §3.1 / §4) — ciphertext columns only (Kabir M-K6-C3-2).
 * Persist exclusively via {@code CreatorBankAccountService} which encrypts before save and enforces
 * 24h cool-down.
 */
@Entity
@Table(name = "creator_bank_accounts")
public class CreatorBankAccount {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    /** AES-GCM ciphertext of account number / UPI VPA — never plaintext. */
    @Column(name = "account_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String accountCiphertext;

    /** AES-GCM ciphertext of IFSC (nullable for UPI-only). */
    @Column(name = "ifsc_ciphertext", columnDefinition = "TEXT")
    private String ifscCiphertext;

    @Column(name = "instrument_type", nullable = false, length = 16)
    private String instrumentType;

    /** Last-4 / masked display only (not full PAN). */
    @Column(name = "display_mask", nullable = false, length = 32)
    private String displayMask;

    /** Razorpay Contact ID (P2-12) — set after first fund account provisioning. */
    @Column(name = "razorpay_contact_id", length = 64)
    private String razorpayContactId;

    /** Razorpay Fund Account ID (P2-12) — the reference PayoutService passes to RazorpayX. */
    @Column(name = "razorpay_fund_account_id", length = 64)
    private String razorpayFundAccountId;

    /**
     * Explicit primary-instrument flag. {@link com.influora.service.payout.CreatorBankAccountService}
     * guarantees at most one row per creator has this set to true. {@code PayoutService} resolves the
     * real payout destination from this flag, not from recency.
     */
    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "usable_after", nullable = false)
    private Instant usableAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorBankAccount() {}

    public static CreatorBankAccount createEncrypted(
            String id,
            String creatorUserId,
            String accountCiphertext,
            String ifscCiphertext,
            String instrumentType,
            String displayMask,
            boolean primary,
            Instant usableAfter,
            Instant now) {
        CreatorBankAccount row = new CreatorBankAccount();
        row.id = id;
        row.creatorUserId = creatorUserId;
        row.accountCiphertext = accountCiphertext;
        row.ifscCiphertext = ifscCiphertext;
        row.instrumentType = instrumentType;
        row.displayMask = displayMask;
        row.primary = primary;
        row.usableAfter = usableAfter;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    public String getId() {
        return id;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public String getAccountCiphertext() {
        return accountCiphertext;
    }

    public String getIfscCiphertext() {
        return ifscCiphertext;
    }

    public String getInstrumentType() {
        return instrumentType;
    }

    public String getDisplayMask() {
        return displayMask;
    }

    public Instant getUsableAfter() {
        return usableAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getRazorpayContactId() {
        return razorpayContactId;
    }

    public String getRazorpayFundAccountId() {
        return razorpayFundAccountId;
    }

    public boolean isUsableAt(Instant when) {
        return !when.isBefore(usableAfter);
    }

    public boolean isPrimary() {
        return primary;
    }

    /** Sets this row as the creator's primary instrument. Caller must have already cleared it on every other row for this creator in the same transaction. */
    public void markPrimary() {
        this.primary = true;
        this.updatedAt = Instant.now();
    }

    public void clearPrimary() {
        this.primary = false;
        this.updatedAt = Instant.now();
    }

    /**
     * Stores Razorpay fund account provisioning refs (P2-12). Called after the first successful
     * creation in RazorpayX — lazy provisioning, not eager at instrument-add time.
     */
    public void markRazorpayFundAccountProvisioned(String contactId, String fundAccountId) {
        this.razorpayContactId = contactId;
        this.razorpayFundAccountId = fundAccountId;
        this.updatedAt = Instant.now();
    }
}
