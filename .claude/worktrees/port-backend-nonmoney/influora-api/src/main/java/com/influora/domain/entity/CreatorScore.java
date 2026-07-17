package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single computed-score snapshot for a creator (V22 {@code creator_scores}) — Phase 3 Scoring
 * Algorithms (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2.4/§4), written daily by {@code
 * ScoreCalculationJob}.
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same ruling
 * that applies to {@code CreatorMetric} applies here: this is an ordinary MySQL row, not a
 * TimescaleDB hypertable row. {@code time}/{@code computedAt} are stored as {@code DATETIME(6)}
 * UTC (see V22 migration comment). Rows are immutable — one row per {@code ScoreCalculationJob}
 * run per creator; there is deliberately no update/mutator method, matching the append-only
 * time-series semantics used by {@code CreatorMetric}/{@code MediaMetric}.
 *
 * <p><b>Scope cut:</b> {@code brandSafetyScore}/{@code garmFlags}/{@code contentSentiment} map to
 * columns that are nullable in the V22 migration and are NOT populated by {@code
 * ScoreCalculationJob} today — {@code BrandSafetyScoreService} requires cross-repo integration with
 * influora-ai (a separate Python service) and is out of scope for this pass. The getters/builder
 * methods for these 3 fields exist so the follow-up task that builds {@code
 * BrandSafetyScoreService} can start populating them with no entity/migration change needed.
 *
 * <p>{@code fakeFollowerReasons}/{@code garmFlags} are stored as raw JSON strings (MySQL {@code
 * JSON} column, {@code @JdbcTypeCode(SqlTypes.JSON)}) — same convention as every other JSON column
 * in this codebase (e.g. {@code CreatorProfile.categoriesJson}, {@code Campaign.hashtagsJson}):
 * never a directly-mapped {@code List<String>} field. Use {@code
 * com.influora.common.JsonLists#toJson(List)} to serialize a {@code List<String>} before passing it
 * to the builder, and {@code JsonLists#stringListFromJson(String)} to deserialize when reading.
 */
@Entity
@Table(name = "creator_scores")
public class CreatorScore {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "time", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant time;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "fake_follower_score", precision = 5, scale = 2)
    private BigDecimal fakeFollowerScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fake_follower_reasons", columnDefinition = "json")
    private String fakeFollowerReasonsJson;

    @Column(name = "quality_score", precision = 5, scale = 2)
    private BigDecimal qualityScore;

    @Column(name = "engagement_consistency", precision = 5, scale = 2)
    private BigDecimal engagementConsistency;

    @Column(name = "posting_frequency", precision = 5, scale = 2)
    private BigDecimal postingFrequency;

    @Column(name = "audience_match_score", precision = 5, scale = 2)
    private BigDecimal audienceMatchScore;

    // Brand Safety Score — NOT YET POPULATED (see class javadoc scope-cut note). Nullable, no
    // NOT NULL constraint per V22 migration.
    @Column(name = "brand_safety_score", precision = 5, scale = 2)
    private BigDecimal brandSafetyScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "garm_flags", columnDefinition = "json")
    private String garmFlagsJson;

    @Column(name = "content_sentiment", precision = 5, scale = 2)
    private BigDecimal contentSentiment;

    @Column(name = "estimated_rate_min", precision = 12, scale = 2)
    private BigDecimal estimatedRateMin;

    @Column(name = "estimated_rate_max", precision = 12, scale = 2)
    private BigDecimal estimatedRateMax;

    @Column(name = "rate_currency", nullable = false, length = 3)
    private String rateCurrency;

    @Column(name = "rate_confidence", precision = 5, scale = 2)
    private BigDecimal rateConfidence;

    @Column(name = "algorithm_version", nullable = false, length = 20)
    private String algorithmVersion;

    @Column(name = "computed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant computedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreatorScore() {}

    public String getId() {
        return id;
    }

    public Instant getTime() {
        return time;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public BigDecimal getFakeFollowerScore() {
        return fakeFollowerScore;
    }

    public String getFakeFollowerReasonsJson() {
        return fakeFollowerReasonsJson;
    }

    public BigDecimal getQualityScore() {
        return qualityScore;
    }

    public BigDecimal getEngagementConsistency() {
        return engagementConsistency;
    }

    public BigDecimal getPostingFrequency() {
        return postingFrequency;
    }

    public BigDecimal getAudienceMatchScore() {
        return audienceMatchScore;
    }

    public BigDecimal getBrandSafetyScore() {
        return brandSafetyScore;
    }

    public String getGarmFlagsJson() {
        return garmFlagsJson;
    }

    public BigDecimal getContentSentiment() {
        return contentSentiment;
    }

    public BigDecimal getEstimatedRateMin() {
        return estimatedRateMin;
    }

    public BigDecimal getEstimatedRateMax() {
        return estimatedRateMax;
    }

    public String getRateCurrency() {
        return rateCurrency;
    }

    public BigDecimal getRateConfidence() {
        return rateConfidence;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CreatorScore s = new CreatorScore();

        public Builder id(String id) {
            s.id = id;
            return this;
        }

        public Builder time(Instant time) {
            s.time = time;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            s.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder fakeFollowerScore(BigDecimal fakeFollowerScore) {
            s.fakeFollowerScore = fakeFollowerScore;
            return this;
        }

        public Builder fakeFollowerReasonsJson(String fakeFollowerReasonsJson) {
            s.fakeFollowerReasonsJson = fakeFollowerReasonsJson;
            return this;
        }

        public Builder qualityScore(BigDecimal qualityScore) {
            s.qualityScore = qualityScore;
            return this;
        }

        public Builder engagementConsistency(BigDecimal engagementConsistency) {
            s.engagementConsistency = engagementConsistency;
            return this;
        }

        public Builder postingFrequency(BigDecimal postingFrequency) {
            s.postingFrequency = postingFrequency;
            return this;
        }

        public Builder audienceMatchScore(BigDecimal audienceMatchScore) {
            s.audienceMatchScore = audienceMatchScore;
            return this;
        }

        /** Not yet populated by ScoreCalculationJob — see class javadoc scope-cut note. */
        public Builder brandSafetyScore(BigDecimal brandSafetyScore) {
            s.brandSafetyScore = brandSafetyScore;
            return this;
        }

        /** Not yet populated by ScoreCalculationJob — see class javadoc scope-cut note. */
        public Builder garmFlagsJson(String garmFlagsJson) {
            s.garmFlagsJson = garmFlagsJson;
            return this;
        }

        /** Not yet populated by ScoreCalculationJob — see class javadoc scope-cut note. */
        public Builder contentSentiment(BigDecimal contentSentiment) {
            s.contentSentiment = contentSentiment;
            return this;
        }

        public Builder estimatedRateMin(BigDecimal estimatedRateMin) {
            s.estimatedRateMin = estimatedRateMin;
            return this;
        }

        public Builder estimatedRateMax(BigDecimal estimatedRateMax) {
            s.estimatedRateMax = estimatedRateMax;
            return this;
        }

        public Builder rateCurrency(String rateCurrency) {
            s.rateCurrency = rateCurrency;
            return this;
        }

        public Builder rateConfidence(BigDecimal rateConfidence) {
            s.rateConfidence = rateConfidence;
            return this;
        }

        public Builder algorithmVersion(String algorithmVersion) {
            s.algorithmVersion = algorithmVersion;
            return this;
        }

        public Builder computedAt(Instant computedAt) {
            s.computedAt = computedAt;
            return this;
        }

        public CreatorScore build() {
            if (s.time == null) {
                s.time = Instant.now();
            }
            if (s.computedAt == null) {
                s.computedAt = Instant.now();
            }
            if (s.rateCurrency == null) {
                s.rateCurrency = "INR";
            }
            s.createdAt = Instant.now();
            return s;
        }
    }
}
