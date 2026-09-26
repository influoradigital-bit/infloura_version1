package com.influora.domain.entity;

import com.influora.domain.enums.CreditBucket;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §3-4) — one "lot" of credit (design-priya C1 / Kabir K-19): every
 * purchase/grant carries its OWN expiry, so a creator's balance is the sum of spendable grants, not
 * a single column. Spent down in place under the parent {@link CreatorCreditAccount}'s row lock.
 */
@Entity
@Table(name = "creator_credit_grants")
public class CreatorCreditGrant {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreditBucket bucket;

    @Column(name = "credits_granted", nullable = false)
    private int creditsGranted;

    @Column(name = "credits_remaining", nullable = false)
    private int creditsRemaining;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Server-minted, caller-scoped idempotency key for THIS grant — never blank, at most 64 chars (K-30). */
    @Column(name = "source_ref", nullable = false, length = 64)
    private String sourceRef;

    protected CreatorCreditGrant() {}

    public static CreatorCreditGrant of(
            String id,
            String creatorUserId,
            CreditBucket bucket,
            int credits,
            Instant grantedAt,
            Instant expiresAt,
            String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("sourceRef must not be blank");
        }
        if (sourceRef.length() > 64) {
            throw new IllegalArgumentException("sourceRef exceeds 64 characters: " + sourceRef);
        }
        CreatorCreditGrant g = new CreatorCreditGrant();
        g.id = id;
        g.creatorUserId = creatorUserId;
        g.bucket = bucket;
        g.creditsGranted = credits;
        g.creditsRemaining = credits;
        g.grantedAt = grantedAt;
        g.expiresAt = expiresAt;
        g.sourceRef = sourceRef;
        return g;
    }

    public String getId() {
        return id;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public CreditBucket getBucket() {
        return bucket;
    }

    public int getCreditsGranted() {
        return creditsGranted;
    }

    public int getCreditsRemaining() {
        return creditsRemaining;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public boolean isSpendable(Instant now) {
        return creditsRemaining > 0 && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** Debits up to {@code amount} (never more than {@link #creditsRemaining}); returns the amount actually debited. */
    public int debit(int amount) {
        int applied = Math.min(amount, creditsRemaining);
        creditsRemaining -= applied;
        return applied;
    }

    /**
     * Restores {@code amount} to this grant on release/refund — even if the grant has since
     * expired or is otherwise not currently spendable (SPEC.md §5.1 release step 5: "even if that
     * grant has just expired"). Never exceeds {@link #creditsGranted}.
     */
    public void refund(int amount) {
        creditsRemaining = Math.min(creditsGranted, creditsRemaining + amount);
    }

    /** Zeroes out any remaining credit on this grant (monthly roll-forward "not carried over" rule). */
    public int expireRemaining() {
        int remaining = creditsRemaining;
        creditsRemaining = 0;
        return remaining;
    }
}
