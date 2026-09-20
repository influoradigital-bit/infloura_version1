package com.influora.domain.entity;

import com.influora.domain.enums.CreditBucket;
import com.influora.domain.enums.CreditLedgerReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.2, §2.5.
 *
 * <p>The creator credits money record. {@code delta} (NOT {@code amount} -- CREDITS-SPEC flags
 * this explicitly) is signed and in TENTHS: negative = debit. {@code monthlyAfter} /
 * {@code purchasedAfter} are the post-write balances, also TENTHS, sourced by re-reading the
 * balance row via {@code findById} AFTER the successful atomic UPDATE -- never from a managed,
 * possibly-stale {@link CreatorAiCredit} instance (Priya's fix to §2.6's repository shape: a JPQL
 * bulk UPDATE bypasses the persistence context, so a "fresh read inside the same transaction"
 * without {@code clearAutomatically}/{@code flushAutomatically} would return stale pre-debit
 * values and write WRONG after-balances into this table).
 *
 * <p>Immutable after insert: no setters. A ledger row is a fact about what happened; nothing
 * about a past debit or refund is ever revised in place.
 */
@Entity
@Table(name = "creator_credit_ledger")
public class CreatorCreditLedgerEntry {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    /** Signed; negative = debit. TENTHS of a credit (§0 rule 11). */
    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CreditBucket bucket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CreditLedgerReason reason;

    /** turnId / briefId / orderId / cycle date; never null, defaults to "" when there is none. */
    @Column(name = "reference_id", nullable = false, length = 64)
    private String referenceId;

    /** Post-write monthly_remaining, TENTHS. */
    @Column(name = "monthly_after", nullable = false)
    private int monthlyAfter;

    /** Post-write purchased_balance, TENTHS. */
    @Column(name = "purchased_after", nullable = false)
    private int purchasedAfter;

    /** Admin user id for ADMIN_GRANT, else "". Never null. */
    @Column(name = "actor_id", nullable = false, length = 26)
    private String actorId;

    @Column(nullable = false, length = 255)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreatorCreditLedgerEntry() {}

    public String getId() {
        return id;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public int getDelta() {
        return delta;
    }

    public CreditBucket getBucket() {
        return bucket;
    }

    public CreditLedgerReason getReason() {
        return reason;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public int getMonthlyAfter() {
        return monthlyAfter;
    }

    public int getPurchasedAfter() {
        return purchasedAfter;
    }

    public String getActorId() {
        return actorId;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CreatorCreditLedgerEntry e = new CreatorCreditLedgerEntry();

        public Builder id(String id) {
            e.id = id;
            return this;
        }

        public Builder creatorUserId(String creatorUserId) {
            e.creatorUserId = creatorUserId;
            return this;
        }

        public Builder delta(int delta) {
            e.delta = delta;
            return this;
        }

        public Builder bucket(CreditBucket bucket) {
            e.bucket = bucket;
            return this;
        }

        public Builder reason(CreditLedgerReason reason) {
            e.reason = reason;
            return this;
        }

        public Builder referenceId(String referenceId) {
            e.referenceId = referenceId;
            return this;
        }

        public Builder monthlyAfter(int monthlyAfter) {
            e.monthlyAfter = monthlyAfter;
            return this;
        }

        public Builder purchasedAfter(int purchasedAfter) {
            e.purchasedAfter = purchasedAfter;
            return this;
        }

        public Builder actorId(String actorId) {
            e.actorId = actorId;
            return this;
        }

        public Builder note(String note) {
            e.note = note;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            e.createdAt = createdAt;
            return this;
        }

        /** referenceId, actorId and note default to "" -- never null (rule 4). */
        public CreatorCreditLedgerEntry build() {
            if (e.referenceId == null) {
                e.referenceId = "";
            }
            if (e.actorId == null) {
                e.actorId = "";
            }
            if (e.note == null) {
                e.note = "";
            }
            if (e.createdAt == null) {
                e.createdAt = Instant.now();
            }
            return e;
        }
    }
}
