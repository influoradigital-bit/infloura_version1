package com.influora.domain.entity;

import com.influora.domain.enums.NudgeMessageSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code creator_nudge_log} (V20260721140000) — Creator Co-pilot's flywheel, canonical column list
 * per Priya's R2 ruling (Option b), be-services-plan.md §2.3. {@code theme} is always server-sourced
 * from the theme-match step ({@code ThemeMatchService.score}), never round-tripped through the AI
 * client. {@code headline}/{@code contentIdea} come from either the AI phrasing call or the
 * templated fallback — same two-value tuple shape either way, so this entity never branches on
 * message source structurally.
 *
 * <p>{@code expiresAt} is deliberately NOT a column here (API-CONTRACT.md §2) — it is a pure
 * function of {@code shownAt} computed at read time in {@code CreatorNudgeService.toDto}.
 */
@Entity
@Table(name = "creator_nudge_log")
public class CreatorNudgeLog {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "trend_id", nullable = false, length = 26)
    private String trendId;

    @Column(name = "match_score", nullable = false)
    private Integer matchScore;

    /** Closed-vocab theme member the trend/creator theme-match overlap picked — server-sourced,
     * never AI output (be-services-plan §2.3). */
    @Column(name = "theme", nullable = false, length = 32)
    private String theme;

    @Column(name = "headline", nullable = false, length = 255)
    private String headline;

    @Column(name = "content_idea", nullable = false, columnDefinition = "TEXT")
    private String contentIdea;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_source", nullable = false, length = 16)
    private NudgeMessageSource messageSource;

    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Column(name = "shown_at", nullable = false)
    private Instant shownAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "acted_at")
    private Instant actedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreatorNudgeLog() {}

    public String getId() {
        return id;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getTrendId() {
        return trendId;
    }

    public Integer getMatchScore() {
        return matchScore;
    }

    public String getTheme() {
        return theme;
    }

    public String getHeadline() {
        return headline;
    }

    public String getContentIdea() {
        return contentIdea;
    }

    public NudgeMessageSource getMessageSource() {
        return messageSource;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Instant getShownAt() {
        return shownAt;
    }

    public Instant getDismissedAt() {
        return dismissedAt;
    }

    public Instant getActedAt() {
        return actedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Idempotent set-once — mirrors {@link NudgeLog#markClicked()}. */
    public void markDismissed() {
        if (this.dismissedAt == null) {
            this.dismissedAt = Instant.now();
        }
    }

    /** Idempotent set-once — mirrors {@link NudgeLog#markClicked()}. */
    public void markActed() {
        if (this.actedAt == null) {
            this.actedAt = Instant.now();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CreatorNudgeLog n = new CreatorNudgeLog();

        public Builder id(String id) {
            n.id = id;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            n.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder trendId(String trendId) {
            n.trendId = trendId;
            return this;
        }

        public Builder matchScore(int matchScore) {
            n.matchScore = matchScore;
            return this;
        }

        public Builder theme(String theme) {
            n.theme = theme;
            return this;
        }

        public Builder headline(String headline) {
            n.headline = headline;
            return this;
        }

        public Builder contentIdea(String contentIdea) {
            n.contentIdea = contentIdea;
            return this;
        }

        public Builder messageSource(NudgeMessageSource messageSource) {
            n.messageSource = messageSource;
            return this;
        }

        public Builder promptVersion(String promptVersion) {
            n.promptVersion = promptVersion;
            return this;
        }

        public CreatorNudgeLog build() {
            Instant now = Instant.now();
            n.shownAt = now;
            n.createdAt = now;
            return n;
        }
    }
}
