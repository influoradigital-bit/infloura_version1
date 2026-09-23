package com.influora.domain.entity;

import com.influora.domain.enums.CreatorChallengeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A creator's 7-day posting challenge (CHALLENGE-SPEC.md, Swapnil 2026-09-23). One row per
 * challenge attempt; a creator may have many over time (one {@code ACTIVE} at a time, any number of
 * {@code COMPLETED}/{@code ENDED}).
 *
 * <p><b>{@link #activeKey} is the whole enforcement mechanism.</b> It is set to {@link
 * #creatorUserId} while {@link #status} is {@code ACTIVE}, and cleared (NULL) the moment it stops
 * being ACTIVE -- see {@link #markCompleted()}/{@link #markEnded()}. The database's {@code UNIQUE
 * (active_key)} index (V20260923100000) is what actually stops a second concurrent ACTIVE row for
 * the same creator; {@link com.influora.service.CreatorChallengeService#start} additionally
 * pre-checks via {@link com.influora.repository.CreatorChallengeRepository#findByActiveKey} so the
 * common case gets a clean {@code 409 CHALLENGE_ALREADY_ACTIVE} rather than a raw constraint
 * violation, but the constraint is what makes that check race-safe.
 *
 * <p>{@link #creatorProfileId} carries the FK (matches {@link CreatorBrief}/{@code CreatorMetric});
 * {@link #creatorUserId} is stored alongside it, denormalized, purely so lookups already keyed by
 * user id (the daily email job, {@code CreatorContextService.requireCreatorProfile}'s caller)
 * never need an extra join.
 */
@Entity
@Table(name = "creator_challenges")
public class CreatorChallenge {

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "started_on", nullable = false)
    private LocalDate startedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private CreatorChallengeStatus status;

    @Column(name = "active_key", length = 64)
    private String activeKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected CreatorChallenge() {}

    public static CreatorChallenge start(
            String id, String creatorUserId, String creatorProfileId, LocalDate startedOn) {
        CreatorChallenge challenge = new CreatorChallenge();
        challenge.id = id;
        challenge.creatorUserId = creatorUserId;
        challenge.creatorProfileId = creatorProfileId;
        challenge.startedOn = startedOn;
        challenge.status = CreatorChallengeStatus.ACTIVE;
        challenge.activeKey = creatorUserId;
        challenge.createdAt = Instant.now();
        return challenge;
    }

    /** Past day 6 on a GET (CHALLENGE-SPEC.md Backend &sect;4). Idempotent. */
    public void markCompleted() {
        this.status = CreatorChallengeStatus.COMPLETED;
        this.activeKey = null;
        this.endedAt = Instant.now();
    }

    /** {@code POST /creator/challenge/{id}/end} -- the creator stopping early. */
    public void markEnded() {
        this.status = CreatorChallengeStatus.ENDED;
        this.activeKey = null;
        this.endedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public LocalDate getStartedOn() {
        return startedOn;
    }

    public CreatorChallengeStatus getStatus() {
        return status;
    }

    public String getActiveKey() {
        return activeKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
