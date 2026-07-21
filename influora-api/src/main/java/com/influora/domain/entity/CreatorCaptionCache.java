package com.influora.domain.entity;

import com.influora.domain.enums.CaptionTagStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code creator_captions} (V20260721130000) — caches captions fetched via {@code
 * InstagramMetricsFetcher} so {@code CreatorThemeTaggingJob} tags from cache instead of re-hitting
 * the Graph API live. Entity class name intentionally differs from the table name: the build
 * spec's architecture diagram calls this concept {@code creator_caption_cache}, but the migration
 * (Meera) names the SQL table {@code creator_captions} — this entity is built against the real SQL
 * name so nobody goes looking for a table that doesn't exist.
 *
 * <p>[SEC: Kabir F-2, P2/Low, risk-accepted] {@code captionText} is stored as plaintext, NOT
 * AES-256-GCM encrypted like {@link MetaOAuthToken#getEncryptedAccessToken()}. This is a deliberate
 * choice, not an oversight: a caption is the creator's own <b>public</b> Instagram post text —
 * already visible to anyone viewing their public profile — not audience/DM/PII data. Access tokens
 * (a real secret, usable to act on the creator's behalf) remain encrypted via {@code
 * MetaTokenStorage} unchanged. Explicit risk-acceptance recorded here per Kabir's gate finding F-2
 * rather than letting the dropped control lapse unremarked.
 */
@Entity
@Table(name = "creator_captions")
public class CreatorCaptionCache {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "ig_media_id", nullable = false, length = 64)
    private String igMediaId;

    @Column(name = "caption_text", columnDefinition = "TEXT")
    private String captionText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tagged_themes", columnDefinition = "json")
    private String taggedThemesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_status", nullable = false, length = 16)
    private CaptionTagStatus tagStatus;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "tagged_at")
    private Instant taggedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreatorCaptionCache() {}

    public String getId() {
        return id;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getIgMediaId() {
        return igMediaId;
    }

    public String getCaptionText() {
        return captionText;
    }

    public String getTaggedThemesJson() {
        return taggedThemesJson;
    }

    public CaptionTagStatus getTagStatus() {
        return tagStatus;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public Instant getTaggedAt() {
        return taggedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** System-only writer ({@code CreatorThemeTaggingJob}) — always overwrites in full, no
     * null-guarded partial update needed since the caller always has a fresh result to persist. */
    public void applyTagResult(String taggedThemesJson) {
        this.taggedThemesJson = taggedThemesJson;
        this.tagStatus = CaptionTagStatus.TAGGED;
        this.taggedAt = Instant.now();
    }

    /** Defensive terminal state for an unexpected per-item tagging failure (see enum javadoc) —
     * keeps a bad row out of every future {@code findByTagStatusOrderByCreatedAtAsc(PENDING, ...)}
     * page instead of retrying it forever. */
    public void markFailed() {
        this.tagStatus = CaptionTagStatus.FAILED;
        this.taggedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CreatorCaptionCache c = new CreatorCaptionCache();

        public Builder id(String id) {
            c.id = id;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            c.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder igMediaId(String igMediaId) {
            c.igMediaId = igMediaId;
            return this;
        }

        public Builder captionText(String captionText) {
            c.captionText = captionText;
            return this;
        }

        public Builder postedAt(Instant postedAt) {
            c.postedAt = postedAt;
            return this;
        }

        public CreatorCaptionCache build() {
            c.tagStatus = CaptionTagStatus.PENDING;
            c.createdAt = Instant.now();
            return c;
        }
    }
}
