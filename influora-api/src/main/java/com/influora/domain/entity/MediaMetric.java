package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    /**
     * V26 caption (the field below). BrandSafety pipeline input — never surface raw caption text
     * via any brand-facing DTO/response (only derived {@code brand_safety_score}/{@code garm_flags}
     * may surface) and keep out of logs. One creator-only exception
     * (wiki/decisions/2026-09-26-creator-own-caption-to-meera.md): the creator's own Meera may see
     * the cleaned first line (max 100 chars) of the creator's OWN posts, via CreatorOwnCaption.
     */

    /**
     * The Instagram account this row was read from (the token row's ig_business_account_id).
     * A creator profile can connect a different account later; without this, rows from the old
     * account stayed indistinguishable and every read mixed them together. NULL on rows written
     * before V20260924120000 — readers treat NULL as "unknown, still show it".
     */
    @Column(name = "ig_account_id", length = 50)
    private String igAccountId;
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "permalink", length = 500)
    private String permalink;

    /**
     * V20260922130000. The post's preview image as a signed Instagram/Facebook CDN link (hotlinked,
     * never copied). The link EXPIRES (~4 days), so every poll writes the fresh one on its new row;
     * readers must take it from the latest snapshot only. 2048 because a real link measured 521
     * characters — {@code permalink}'s 500 would fail every insert. Null when Meta sent none or the
     * URL failed the host allow-list in {@code MediaMetricMapper}.
     */
    @Column(name = "preview_image_url", length = 2048)
    private String previewImageUrl;

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

    // F-0689 (dead-metric repair, T-DEADMETRIC-REPAIR-0915): avgWatchTimeSeconds was REMOVED from
    // this entity rather than repaired. InstagramInsightValues requests exactly views/reach/likes/
    // comments/saved/shares/total_interactions from Meta — no watch-time insight metric is
    // requested or mapped anywhere in MediaMetricMapper, so there is no real data to aggregate.
    // Getting one means asking Meta for a new insight metric (a permissions/App-Review change, not
    // a code change) and is out of this ticket's scope. The `avg_watch_time_seconds` DB column
    // (V21 migration) is left in place, unmapped and always NULL going forward — dropping a column
    // is a schema change this repair did not attempt to verify against a live database; an unused
    // nullable column is harmless. See MediaMetricMapper class javadoc and AnalyticsDtos
    // (ContentPerformanceResponse no longer carries this field either).

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

    public String getCaption() {
        return caption;
    }

    public String getPermalink() {
        return permalink;
    }

    public String getPreviewImageUrl() {
        return previewImageUrl;
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


    public String getIgAccountId() {
        return igAccountId;
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


        public Builder igAccountId(String igAccountId) {
            m.igAccountId = igAccountId;
            return this;
        }

        public Builder caption(String caption) {
            m.caption = caption;
            return this;
        }

        public Builder permalink(String permalink) {
            m.permalink = permalink;
            return this;
        }

        public Builder previewImageUrl(String previewImageUrl) {
            m.previewImageUrl = previewImageUrl;
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
