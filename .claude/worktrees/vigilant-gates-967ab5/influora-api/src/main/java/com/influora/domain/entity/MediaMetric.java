package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single per-post metrics snapshot (V21 {@code media_metrics}) — Phase 2 Data Pipeline
 * (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2), fetched from the Meta Graph API by {@code
 * MetricsPollingJob}.
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Ordinary
 * MySQL row, not a TimescaleDB hypertable row; see {@code CreatorMetric} javadoc and the V21
 * migration comment for the full rationale. Rows are immutable — one row per poll per media item.
 */
@Entity
@Table(name = "media_metrics")
public class MediaMetric {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "time", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant time;

    @Column(name = "media_id", nullable = false, length = 50)
    private String mediaId;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "media_type", nullable = false, length = 20)
    private String mediaType;

    @Column(name = "permalink", length = 500)
    private String permalink;

    @Column(name = "impressions")
    private Long impressions;

    @Column(name = "reach")
    private Long reach;

    @Column(name = "engagement")
    private Long engagement;

    @Column(name = "likes")
    private Long likes;

    @Column(name = "comments")
    private Long comments;

    @Column(name = "saves")
    private Long saves;

    @Column(name = "shares")
    private Long shares;

    @Column(name = "video_views")
    private Long videoViews;

    @Column(name = "avg_watch_time_seconds", precision = 10, scale = 2)
    private BigDecimal avgWatchTimeSeconds;

    @Column(name = "posted_at", columnDefinition = "DATETIME(6)")
    private Instant postedAt;

    @Column(name = "data_source", nullable = false, length = 20)
    private String dataSource;

    @Column(name = "fetched_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant fetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MediaMetric() {}

    public String getId() {
        return id;
    }

    public Instant getTime() {
        return time;
    }

    public String getMediaId() {
        return mediaId;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getPermalink() {
        return permalink;
    }

    public Long getImpressions() {
        return impressions;
    }

    public Long getReach() {
        return reach;
    }

    public Long getEngagement() {
        return engagement;
    }

    public Long getLikes() {
        return likes;
    }

    public Long getComments() {
        return comments;
    }

    public Long getSaves() {
        return saves;
    }

    public Long getShares() {
        return shares;
    }

    public Long getVideoViews() {
        return videoViews;
    }

    public BigDecimal getAvgWatchTimeSeconds() {
        return avgWatchTimeSeconds;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public String getDataSource() {
        return dataSource;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final MediaMetric m = new MediaMetric();

        public Builder id(String id) {
            m.id = id;
            return this;
        }

        public Builder time(Instant time) {
            m.time = time;
            return this;
        }

        public Builder mediaId(String mediaId) {
            m.mediaId = mediaId;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            m.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder platform(String platform) {
            m.platform = platform;
            return this;
        }

        public Builder mediaType(String mediaType) {
            m.mediaType = mediaType;
            return this;
        }

        public Builder permalink(String permalink) {
            m.permalink = permalink;
            return this;
        }

        public Builder impressions(Long impressions) {
            m.impressions = impressions;
            return this;
        }

        public Builder reach(Long reach) {
            m.reach = reach;
            return this;
        }

        public Builder engagement(Long engagement) {
            m.engagement = engagement;
            return this;
        }

        public Builder likes(Long likes) {
            m.likes = likes;
            return this;
        }

        public Builder comments(Long comments) {
            m.comments = comments;
            return this;
        }

        public Builder saves(Long saves) {
            m.saves = saves;
            return this;
        }

        public Builder shares(Long shares) {
            m.shares = shares;
            return this;
        }

        public Builder videoViews(Long videoViews) {
            m.videoViews = videoViews;
            return this;
        }

        public Builder avgWatchTimeSeconds(BigDecimal avgWatchTimeSeconds) {
            m.avgWatchTimeSeconds = avgWatchTimeSeconds;
            return this;
        }

        public Builder postedAt(Instant postedAt) {
            m.postedAt = postedAt;
            return this;
        }

        public Builder dataSource(String dataSource) {
            m.dataSource = dataSource;
            return this;
        }

        public Builder fetchedAt(Instant fetchedAt) {
            m.fetchedAt = fetchedAt;
            return this;
        }

        public MediaMetric build() {
            if (m.time == null) {
                m.time = Instant.now();
            }
            if (m.fetchedAt == null) {
                m.fetchedAt = Instant.now();
            }
            if (m.dataSource == null) {
                m.dataSource = "META_API";
            }
            m.createdAt = Instant.now();
            return m;
        }
    }
}
