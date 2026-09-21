package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §6, Kabir K-12) — a PERMANENT, once-ever claim on the welcome-40
 * grant. Deliberately carries no FK to {@code users} or {@code meta_oauth_tokens} — a claim must
 * survive account deletion and Instagram disconnect/reconnect. {@code claimKey} is one of
 * {@code "user:<usersId>"}, {@code "ig:<igBusinessAccountId>"} or {@code "meta:<metaUserId>"}.
 *
 * <p><b>Implements {@link Persistable} deliberately.</b> This entity sets its own {@code @Id}
 * (never {@code @GeneratedValue}), which by default makes Spring Data JPA treat every {@code
 * save()}/{@code saveAndFlush()} as a Hibernate {@code merge} rather than a {@code persist}: it
 * SELECTs the row first, and if one already exists (claimed by a DIFFERENT creator), it silently
 * UPDATEs {@code creator_user_id}/{@code claimed_at} onto it instead of failing — a second creator
 * "taking over" a claim already used for someone else's welcome grant, with no {@code
 * DataIntegrityViolationException} ever thrown (K-12). {@link #isNew()} always returning {@code
 * true} forces {@code persist} (a true INSERT) every time, so a colliding claim key fails loudly
 * with a constraint violation instead of overwriting.
 */
@Entity
@Table(name = "creator_credit_welcome_claims")
public class CreatorCreditWelcomeClaim implements Persistable<String> {

    @Id
    @Column(name = "claim_key", length = 80)
    private String claimKey;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    protected CreatorCreditWelcomeClaim() {}

    public static CreatorCreditWelcomeClaim of(String claimKey, String creatorUserId, Instant claimedAt) {
        CreatorCreditWelcomeClaim c = new CreatorCreditWelcomeClaim();
        c.claimKey = claimKey;
        c.creatorUserId = creatorUserId;
        c.claimedAt = claimedAt;
        return c;
    }

    @Override
    public String getId() {
        return claimKey;
    }

    /**
     * Always {@code true} (see class javadoc): this is a write-once row with a caller-assigned
     * id, never read back and mutated by this application before being saved again, so there is
     * no legitimate UPDATE path for Spring Data to choose instead.
     */
    @Override
    public boolean isNew() {
        return true;
    }

    public String getClaimKey() {
        return claimKey;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }
}
