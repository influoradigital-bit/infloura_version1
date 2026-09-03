package com.influora.domain.entity;

import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.domain.enums.ExternalCreatorStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An Instagram handle Influora knows about that is NOT (yet) a real {@link CreatorProfile}
 * (T-CREATORCONNECT-0902, V20260902120000). Sourced from the flagged {@code
 * CreatorMarketplaceClient}, a live Business Discovery lookup, or an admin bulk import — see
 * {@link ExternalCreatorSource}. Never holds fabricated data: every metric column is nullable and
 * stays {@code null} until Meta actually returns a value (F-0259/F-0260 — never coerced to 0).
 *
 * <p>{@link #status} starts {@code UNVERIFIED} and only ever moves forward to {@code INVITED}
 * (admin sent the join invitation) and then {@code JOINED} (matched to a real {@code
 * CreatorProfile} by {@code ExternalCreatorLinkService} — see {@link #markJoined}). {@code
 * igUsername} is stored lower-cased with no leading {@code @} (enforced by the service layer,
 * not this entity).
 */
@Entity
@Table(name = "external_creators")
public class ExternalCreator {

    @Id
    @Column(length = 26)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExternalCreatorSource source;

    @Column(name = "ig_account_id", length = 64)
    private String igAccountId;

    @Column(name = "ig_username", nullable = false, length = 80)
    private String igUsername;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "profile_picture_url", length = 500)
    private String profilePictureUrl;

    @Column
    private Long followers;

    @Column(name = "media_count")
    private Long mediaCount;

    @Column(name = "engagement_rate", precision = 5, scale = 2)
    private BigDecimal engagementRate;

    @Column(length = 64)
    private String country;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories", columnDefinition = "json")
    private String categoriesJson;

    /** Admin-supplied only — Business Discovery never returns an email. */
    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExternalCreatorStatus status;

    @Column(name = "linked_creator_profile_id", length = 26)
    private String linkedCreatorProfileId;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExternalCreator() {}

    public String getId() {
        return id;
    }

    public ExternalCreatorSource getSource() {
        return source;
    }

    public String getIgAccountId() {
        return igAccountId;
    }

    public String getIgUsername() {
        return igUsername;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public String getProfilePictureUrl() {
        return profilePictureUrl;
    }

    public Long getFollowers() {
        return followers;
    }

    public Long getMediaCount() {
        return mediaCount;
    }

    public BigDecimal getEngagementRate() {
        return engagementRate;
    }

    public String getCountry() {
        return country;
    }

    public String getCategoriesJson() {
        return categoriesJson;
    }

    public String getEmail() {
        return email;
    }

    public ExternalCreatorStatus getStatus() {
        return status;
    }

    public String getLinkedCreatorProfileId() {
        return linkedCreatorProfileId;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Applies a fresh Business Discovery / Marketplace sync onto this row. Never overwrites a
     * present value with {@code null} — a single poll omitting a field must not blank out a
     * previously-recorded one (same discipline as {@code PlatformStat#applySnapshot}'s CR-116
     * note), EXCEPT {@code igAccountId}, which is only ever resolved once and passed as the exact
     * value the caller already has.
     */
    public void applySync(
            String displayName,
            String bio,
            String profilePictureUrl,
            Long followers,
            Long mediaCount,
            BigDecimal engagementRate,
            String country) {
        this.displayName = displayName != null ? displayName : this.displayName;
        this.bio = bio != null ? bio : this.bio;
        this.profilePictureUrl = profilePictureUrl != null ? profilePictureUrl : this.profilePictureUrl;
        this.followers = followers != null ? followers : this.followers;
        this.mediaCount = mediaCount != null ? mediaCount : this.mediaCount;
        this.engagementRate = engagementRate != null ? engagementRate : this.engagementRate;
        this.country = country != null ? country : this.country;
        this.lastSyncedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void applyIgAccountId(String igAccountId) {
        if (igAccountId != null && !igAccountId.isBlank()) {
            this.igAccountId = igAccountId;
            this.updatedAt = Instant.now();
        }
    }

    public void applyCategories(String categoriesJson) {
        this.categoriesJson = categoriesJson;
        this.updatedAt = Instant.now();
    }

    /** Admin invite — {@code POST /admin/creator-connections/{id}/invite}. Re-invite (resend) is
     * allowed, so this always overwrites email/invitedAt with the latest submission.
     *
     * <p>Q5.5 (T-CREATORCONNECT-0902, Medium) — {@code invitedAt} is truncated to whole SECONDS
     * HERE, at write time, rather than left at millisecond precision. {@code
     * external_creators.invited_at} is a MySQL {@code TIMESTAMP} with fsp 0, which ROUNDS (not
     * truncates) a fractional value on INSERT — a value like {@code 10:15:30.750} is stored as
     * {@code 10:15:31}. {@link com.influora.service.InviteTokenService#issue} embeds {@code
     * invitedAt} truncated (floor, not round) to seconds, so without this the two sides disagree
     * on roughly half of all invites and {@code RegistrationService#consumeInviteToken} rejects a
     * legitimate just-issued token as "superseded by a re-invite". Truncating before the value
     * ever reaches Hibernate means there is no fractional component left for MySQL to round —
     * what {@link InviteTokenService#issue} is called with (immediately after this method, same
     * request) and what the DB persists are byte-for-byte the same instant. */
    public void markInvited(String email) {
        this.email = email;
        if (this.status == ExternalCreatorStatus.UNVERIFIED) {
            this.status = ExternalCreatorStatus.INVITED;
        }
        this.invitedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        this.updatedAt = Instant.now();
    }

    /** {@code ExternalCreatorLinkService#onCreatorIdentified} — the JOINED hook.
     *
     * <p>Q5.2 (T-CREATORCONNECT-0902) — truly idempotent, not just re-runnable: a repeat call for
     * a row that is already {@code JOINED} and already linked to this exact {@code
     * creatorProfileId} (e.g. {@code MetaTokenRefreshService}'s ~55-day token refresh, which
     * re-fires this hook on every rotation) is a no-op on {@code joinedAt}/{@code updatedAt} —
     * the real join date must not drift forward, and {@code updatedAt} must not silently bump this
     * creator to the top of every brand's {@code updatedAt DESC} Discover sort on a background
     * token write nobody asked for. Same preserve-the-original-timestamp discipline as {@code
     * MetaTokenStorage}'s F-0173 handling of {@code createdAt}. */
    public void markJoined(String creatorProfileId) {
        boolean alreadyJoinedToSameProfile =
                this.status == ExternalCreatorStatus.JOINED
                        && creatorProfileId != null
                        && creatorProfileId.equals(this.linkedCreatorProfileId);
        this.status = ExternalCreatorStatus.JOINED;
        this.linkedCreatorProfileId = creatorProfileId;
        if (!alreadyJoinedToSameProfile) {
            this.joinedAt = Instant.now();
            this.updatedAt = Instant.now();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final ExternalCreator e = new ExternalCreator();

        public Builder id(String id) {
            e.id = id;
            return this;
        }

        public Builder source(ExternalCreatorSource source) {
            e.source = source;
            return this;
        }

        public Builder igAccountId(String igAccountId) {
            e.igAccountId = igAccountId;
            return this;
        }

        public Builder igUsername(String igUsername) {
            e.igUsername = igUsername;
            return this;
        }

        public Builder displayName(String displayName) {
            e.displayName = displayName;
            return this;
        }

        public Builder bio(String bio) {
            e.bio = bio;
            return this;
        }

        public Builder profilePictureUrl(String profilePictureUrl) {
            e.profilePictureUrl = profilePictureUrl;
            return this;
        }

        public Builder followers(Long followers) {
            e.followers = followers;
            return this;
        }

        public Builder mediaCount(Long mediaCount) {
            e.mediaCount = mediaCount;
            return this;
        }

        public Builder engagementRate(BigDecimal engagementRate) {
            e.engagementRate = engagementRate;
            return this;
        }

        public Builder country(String country) {
            e.country = country;
            return this;
        }

        public Builder categoriesJson(String categoriesJson) {
            e.categoriesJson = categoriesJson;
            return this;
        }

        public Builder email(String email) {
            e.email = email;
            return this;
        }

        public ExternalCreator build() {
            e.status = ExternalCreatorStatus.UNVERIFIED;
            Instant now = Instant.now();
            e.createdAt = now;
            e.updatedAt = now;
            e.lastSyncedAt = now;
            return e;
        }
    }
}
