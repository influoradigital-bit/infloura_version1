package com.influora.domain.entity;

import com.influora.domain.enums.CreditLedgerReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §3) — the money record. {@code uk_ccl_ref (creator_user_id,
 * reason, reference_id, grant_id)} is the structural exactly-once guard: a retried charge/release
 * for the same turn/brief/order against the same grant can never double-post.
 */
@Entity
@Table(name = "creator_credit_ledger")
public class CreatorCreditLedgerEntry {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Column(name = "grant_id", nullable = false, length = 26)
    private String grantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreditLedgerReason reason;

    /** Server-minted reference — {@code turn:<id>} / {@code tts:<id>} / {@code brief:<id>} / {@code m:YYYY-MM} / {@code signup} / {@code order:<id>}. */
    @Column(name = "reference_id", nullable = false, length = 64)
    private String referenceId;

    /** Signed — negative for a debit/expire, positive for a grant/refund. */
    @Column(nullable = false)
    private int delta;

    /** This grant's {@code credits_remaining} immediately after this entry, for audit/debug. */
    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    /** The Asia/Kolkata calendar date this entry was posted on — what the daily-cap refund guard keys on. */
    @Column(name = "ist_date", nullable = false)
    private LocalDate istDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreatorCreditLedgerEntry() {}

    public static CreatorCreditLedgerEntry of(
            String id,
            String creatorUserId,
            String grantId,
            CreditLedgerReason reason,
            String referenceId,
            int delta,
            int balanceAfter,
            LocalDate istDate,
            Instant createdAt) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("referenceId must not be blank");
        }
        if (referenceId.length() > 64) {
            throw new IllegalArgumentException("referenceId exceeds 64 characters: " + referenceId);
        }
        CreatorCreditLedgerEntry e = new CreatorCreditLedgerEntry();
        e.id = id;
        e.creatorUserId = creatorUserId;
        e.grantId = grantId;
        e.reason = reason;
        e.referenceId = referenceId;
        e.delta = delta;
        e.balanceAfter = balanceAfter;
        e.istDate = istDate;
        e.createdAt = createdAt;
        return e;
    }

    public String getId() {
        return id;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public String getGrantId() {
        return grantId;
    }

    public CreditLedgerReason getReason() {
        return reason;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public int getDelta() {
        return delta;
    }

    public int getBalanceAfter() {
        return balanceAfter;
    }

    public LocalDate getIstDate() {
        return istDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
