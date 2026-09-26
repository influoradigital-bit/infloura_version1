package com.influora.domain.entity;

import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.CreatorRecommendationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Meera intelligence v1, slice 2 (spec 8.2, migration {@code V20260925150100}) -- one thing the
 * creator was told to post, and, once a post of hers filled it and settled, how it did against her
 * own usual as of that post.
 *
 * <p>Every column carries an explicit {@code name} and the exact MySQL type (INT never TINYINT,
 * DATETIME(6) for instants): a camelCase field without a name compiles and passes Mockito, then
 * fails {@code ddl-auto=validate} on MySQL.
 *
 * <p>The recommendation half is written once, at creation. The outcome half is written only by
 * {@link #matchTo}, {@link #settle}, {@link #markMissed} and {@link #markNoOutcome}, each of which
 * refuses a frozen row (SETTLED, MISSED or NO_OUTCOME), so a settled outcome can never be
 * rewritten by a later read in the same JVM. Across concurrent evaluations the {@link #version}
 * column (JPA {@code @Version}) does the same job in the database: every outcome write is {@code
 * UPDATE ... WHERE id = ? AND version = ?}, so an evaluation that read the row before another one
 * froze it fails with an optimistic-lock exception instead of overwriting it (Kabir L-1).
 */
@Entity
@Table(
        name = "creator_recommendations",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_creator_rec_source",
                    columnNames = {"creator_profile_id", "source", "source_ref"}),
            @UniqueConstraint(
                    name = "uk_creator_rec_media",
                    columnNames = {"creator_profile_id", "matched_media_id"})
        },
        indexes = {
            @Index(name = "idx_creator_rec_open", columnList = "creator_profile_id, status, recommended_for"),
            @Index(name = "idx_creator_rec_conversation", columnList = "conversation_id")
        })
public class CreatorRecommendation {

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "creator_user_id", nullable = false, length = 26)
    private String creatorUserId;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private CreatorRecommendationSource source;

    @Column(name = "source_ref", nullable = false, length = 64)
    private String sourceRef;

    @Column(name = "conversation_id", length = 26)
    private String conversationId;

    @Column(name = "recommended_for")
    private LocalDate recommendedFor;

    /** The first IST date that no longer matches (exclusive end of the matching window). */
    @Column(name = "match_until", nullable = false)
    private LocalDate matchUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false, length = 12)
    private ChallengeDayType postType;

    @Column(name = "window_label", length = 24)
    private String windowLabel;

    @Column(name = "window_from")
    private LocalTime windowFrom;

    @Column(name = "window_to")
    private LocalTime windowTo;

    @Column(name = "structure_name", length = 80)
    private String structureName;

    @Column(name = "hook_template", length = 80)
    private String hookTemplate;

    @Column(name = "topic", length = 160)
    private String topic;

    @Column(name = "festival", length = 80)
    private String festival;

    @Column(name = "prompt_version", length = 32)
    private String promptVersion;

    @Column(name = "knowledge_version", length = 32)
    private String knowledgeVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private CreatorRecommendationStatus status;

    @Column(name = "matched_media_id", length = 50)
    private String matchedMediaId;

    /**
     * The Instagram account whose posts decided this row: stamped by {@link #matchTo}, {@link
     * #markMissed} and {@link #markNoOutcome} (a MATCHED row keeps the account it was matched on).
     * {@code followed_recommendations} of an account-switcher counts only the rows decided on the
     * account connected now (Kabir L-3).
     */
    @Column(name = "outcome_ig_account_id", length = 64)
    private String outcomeIgAccountId;

    @Column(name = "matched_type")
    private Boolean matchedType;

    @Column(name = "matched_window")
    private Boolean matchedWindow;

    @Column(name = "reach")
    private Long reach;

    @Column(name = "engagement")
    private Long engagement;

    @Column(name = "baseline_median_reach")
    private Long baselineMedianReach;

    @Column(name = "baseline_sample_size")
    private Integer baselineSampleSize;

    @Column(name = "reach_vs_baseline_pct")
    private Integer reachVsBaselinePct;

    @Column(name = "settled_at", columnDefinition = "DATETIME(6)")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    /**
     * Optimistic lock, {@code version BIGINT NOT NULL DEFAULT 0}. A {@code Long} so a new row
     * (null) is persisted, not merged; Hibernate writes 0 on insert and increments it on every
     * outcome write.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected CreatorRecommendation() {}

    /** A new OPEN recommendation. Free text must already be neutralised and capped by the caller. */
    public static CreatorRecommendation open(
            String id,
            String creatorUserId,
            String creatorProfileId,
            CreatorRecommendationSource source,
            String sourceRef,
            String conversationId,
            LocalDate recommendedFor,
            LocalDate matchUntil,
            ChallengeDayType postType,
            String windowLabel,
            LocalTime windowFrom,
            LocalTime windowTo,
            String structureName,
            String hookTemplate,
            String topic,
            String festival,
            String promptVersion,
            String knowledgeVersion,
            Instant createdAt) {
        CreatorRecommendation r = new CreatorRecommendation();
        r.id = id;
        r.creatorUserId = creatorUserId;
        r.creatorProfileId = creatorProfileId;
        r.source = source;
        r.sourceRef = sourceRef;
        r.conversationId = conversationId;
        r.recommendedFor = recommendedFor;
        r.matchUntil = matchUntil;
        r.postType = postType;
        r.windowLabel = windowLabel;
        r.windowFrom = windowFrom;
        r.windowTo = windowTo;
        r.structureName = structureName;
        r.hookTemplate = hookTemplate;
        r.topic = topic;
        r.festival = festival;
        r.promptVersion = promptVersion;
        r.knowledgeVersion = knowledgeVersion;
        r.status = CreatorRecommendationStatus.OPEN;
        r.createdAt = createdAt;
        return r;
    }

    public boolean isFrozen() {
        return status == CreatorRecommendationStatus.SETTLED
                || status == CreatorRecommendationStatus.MISSED
                || status == CreatorRecommendationStatus.NO_OUTCOME;
    }

    /** OPEN -> MATCHED with the post that filled it, on the account {@code igAccountId}. */
    public void matchTo(String mediaId, boolean typeMatched, Boolean windowMatched, String igAccountId) {
        if (status != CreatorRecommendationStatus.OPEN) {
            throw new IllegalStateException("only an OPEN recommendation can be matched, was " + status);
        }
        this.matchedMediaId = mediaId;
        this.matchedType = typeMatched;
        this.matchedWindow = windowMatched;
        this.outcomeIgAccountId = igAccountId;
        this.status = CreatorRecommendationStatus.MATCHED;
    }

    /**
     * MATCHED -> SETTLED, frozen from then on. {@code baselineMedianReach} and {@code
     * reachVsBaselinePct} are null when the baseline rests on fewer than 10 posts.
     */
    public void settle(
            Long reach,
            Long engagement,
            Long baselineMedianReach,
            int baselineSampleSize,
            Integer reachVsBaselinePct,
            Instant settledAt) {
        if (status != CreatorRecommendationStatus.MATCHED) {
            throw new IllegalStateException("only a MATCHED recommendation can settle, was " + status);
        }
        this.reach = reach;
        this.engagement = engagement;
        this.baselineMedianReach = baselineMedianReach;
        this.baselineSampleSize = baselineSampleSize;
        this.reachVsBaselinePct = reachVsBaselinePct;
        this.settledAt = settledAt;
        this.status = CreatorRecommendationStatus.SETTLED;
    }

    /** OPEN -> MISSED, decided on the account {@code igAccountId}; frozen from then on. */
    public void markMissed(String igAccountId) {
        if (status != CreatorRecommendationStatus.OPEN) {
            throw new IllegalStateException("only an OPEN recommendation can be missed, was " + status);
        }
        this.outcomeIgAccountId = igAccountId;
        this.status = CreatorRecommendationStatus.MISSED;
    }

    /**
     * MATCHED (or OPEN) -> NO_OUTCOME, frozen from then on: the outcome can no longer be decided
     * (the matched post never settled in time, or the row's window lies before the scan floor). A
     * MATCHED row keeps the account it was matched on; an OPEN one takes {@code igAccountId}.
     */
    public void markNoOutcome(String igAccountId) {
        if (status != CreatorRecommendationStatus.OPEN && status != CreatorRecommendationStatus.MATCHED) {
            throw new IllegalStateException("only a live recommendation can end without an outcome, was " + status);
        }
        if (this.outcomeIgAccountId == null) {
            this.outcomeIgAccountId = igAccountId;
        }
        this.status = CreatorRecommendationStatus.NO_OUTCOME;
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

    public CreatorRecommendationSource getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getConversationId() {
        return conversationId;
    }

    public LocalDate getRecommendedFor() {
        return recommendedFor;
    }

    public LocalDate getMatchUntil() {
        return matchUntil;
    }

    public ChallengeDayType getPostType() {
        return postType;
    }

    public String getWindowLabel() {
        return windowLabel;
    }

    public LocalTime getWindowFrom() {
        return windowFrom;
    }

    public LocalTime getWindowTo() {
        return windowTo;
    }

    public String getStructureName() {
        return structureName;
    }

    public String getHookTemplate() {
        return hookTemplate;
    }

    public String getTopic() {
        return topic;
    }

    public String getFestival() {
        return festival;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getKnowledgeVersion() {
        return knowledgeVersion;
    }

    public CreatorRecommendationStatus getStatus() {
        return status;
    }

    public String getMatchedMediaId() {
        return matchedMediaId;
    }

    public String getOutcomeIgAccountId() {
        return outcomeIgAccountId;
    }

    public Long getVersion() {
        return version;
    }

    public Boolean getMatchedType() {
        return matchedType;
    }

    public Boolean getMatchedWindow() {
        return matchedWindow;
    }

    public Long getReach() {
        return reach;
    }

    public Long getEngagement() {
        return engagement;
    }

    public Long getBaselineMedianReach() {
        return baselineMedianReach;
    }

    public Integer getBaselineSampleSize() {
        return baselineSampleSize;
    }

    public Integer getReachVsBaselinePct() {
        return reachVsBaselinePct;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
