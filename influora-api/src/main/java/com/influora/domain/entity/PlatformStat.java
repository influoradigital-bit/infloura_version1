package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "platform_stats")
public class PlatformStat {

    /**
     * F-0965 — where this platform's numbers came from. Only {@link #SOURCE_META_API} counts
     * toward a creator's verified follower total; an {@link #SOURCE_IMPORTED} stat (Meta Creator
     * Marketplace or admin import, adopted by ExternalCreatorLinkService) counts only when the
     * creator has no verified platform, and is labelled; {@link #SOURCE_CREATOR_REPORTED} (a
     * creator's own declaration) never counts. See FollowerTotals.
     */
    public static final String SOURCE_META_API = "META_API";

    public static final String SOURCE_IMPORTED = "IMPORTED";
    public static final String SOURCE_CREATOR_REPORTED = "CREATOR_REPORTED";

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(nullable = false, length = 32)
    private String platform;

    @Column(length = 200)
    private String handle;

    @Column(nullable = false)
    private long followers;

    @Column(name = "engagement_rate", precision = 5, scale = 2)
    private BigDecimal engagementRate;

    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    @Column(name = "profile_url", length = 500)
    private String profileUrl;

    @Column(name = "source", nullable = false, length = 20)
    private String source = SOURCE_CREATOR_REPORTED;

    /**
     * Per-post averages rolled up from the newest {@code creator_metrics} snapshot for this
     * platform, so the portfolio read path can serve them without a second query.
     *
     * <p>All boxed and all legitimately null — null is in fact the DEFAULT state for very nearly
     * every creator on the platform today, because a creator who never connected Meta has no
     * insights at all and never will until they do. An absent average must stay null the whole way
     * to the wire so the UI can omit the block; a 0 here would render as a measured "0 avg reach"
     * (F-0589). {@code avgViewsPerPost} holds Meta's unified {@code views} count, which arrives in
     * {@code MediaMetric.impressions}.
     */
    @Column(name = "avg_reach_per_post")
    private Long avgReachPerPost;

    @Column(name = "avg_views_per_post")
    private Long avgViewsPerPost;

    @Column(name = "avg_likes_per_post")
    private Long avgLikesPerPost;

    @Column(name = "avg_comments_per_post")
    private Long avgCommentsPerPost;

    /**
     * When this platform was last synced FROM META — deliberately not the row's {@code updated_at},
     * which any write bumps (including a creator typing a handle via {@code
     * PortfolioService#declarePlatform} and the F-0965 backfill), and which would therefore claim a
     * Meta sync that never happened. Null for a creator-declared platform, forever.
     */
    @Column(name = "last_synced_at", columnDefinition = "DATETIME(6)")
    private Instant lastSyncedAt;

    protected PlatformStat() {}

    public String getId() {
        return id;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getHandle() {
        return handle;
    }

    public long getFollowers() {
        return followers;
    }

    public BigDecimal getEngagementRate() {
        return engagementRate;
    }

    public boolean isVerified() {
        return verified;
    }

    public String getProfileUrl() {
        return profileUrl;
    }

    public String getSource() {
        return source;
    }

    public Long getAvgReachPerPost() {
        return avgReachPerPost;
    }

    public Long getAvgViewsPerPost() {
        return avgViewsPerPost;
    }

    public Long getAvgLikesPerPost() {
        return avgLikesPerPost;
    }

    public Long getAvgCommentsPerPost() {
        return avgCommentsPerPost;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    /**
     * H-10 fix: this table previously had no writer at all (no setters, no {@code save} call
     * site anywhere) — {@code PlatformStatsAggregationJob} calls this to roll the latest {@code
     * creator_metrics} snapshot up into the discovery-ranking substrate. Never fabricates a value;
     * {@code engagementRate} may legitimately be {@code null} if Meta hasn't returned one yet.
     *
     * <p>CR-116 — {@code handle} added so a real Instagram username rolls up here too; the caller
     * is responsible for not passing {@code null} over an existing value (a single poll omitting
     * the field must not blank out a previously-recorded handle).
     */
    public void applySnapshot(long followers, BigDecimal engagementRate, boolean verified, String handle) {
        this.followers = followers;
        this.engagementRate = engagementRate;
        this.verified = verified;
        this.handle = handle;
        // F-0965: a snapshot is either a Meta sync or the creator's own declaration.
        this.source = verified ? SOURCE_META_API : SOURCE_CREATOR_REPORTED;
    }

    /**
     * Rolls the newest {@code creator_metrics} averages onto this row. Deliberately NOT folded into
     * {@link #applySnapshot} — that signature has a positional test caller (FollowerTotalsTest), and
     * widening it would also force {@code PortfolioService#declarePlatform}, where a creator types a
     * handle and there are no averages to pass, to hand over four nulls.
     *
     * <p>F-0589 — each average is overwritten only when the incoming snapshot actually carries one,
     * the same CR-116 discipline {@code applySnapshot} uses for {@code handle}. {@code
     * MetricsPollingJob#pollRecentMedia} swallows every failure and returns an empty list, which
     * makes the averages null; an incoming null therefore means "no media data this cycle", which
     * must not blank a previously-recorded average.
     *
     * <p>{@code syncedAt} is advanced only for a {@code META_API} snapshot — the caller is
     * responsible for passing null otherwise, so a creator declaration never mints a sync time.
     *
     * <p>Known tradeoff: because averages are preserved on null, {@code lastSyncedAt} means "last
     * Meta sync of this platform", not "last time these averages changed". A poll whose media fetch
     * failed refreshes the follower count and this timestamp while the averages stay older. That is
     * the honest reading of the word "Synced", and it matches the existing guarantee that a media
     * failure must never block the profile snapshot.
     */
    public void applyMetricAverages(
            Long avgReach, Long avgViews, Long avgLikes, Long avgComments, Instant syncedAt) {
        if (avgReach != null) {
            this.avgReachPerPost = avgReach;
        }
        if (avgViews != null) {
            this.avgViewsPerPost = avgViews;
        }
        if (avgLikes != null) {
            this.avgLikesPerPost = avgLikes;
        }
        if (avgComments != null) {
            this.avgCommentsPerPost = avgComments;
        }
        if (syncedAt != null) {
            this.lastSyncedAt = syncedAt;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final PlatformStat s = new PlatformStat();

        public Builder id(String id) {
            s.id = id;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            s.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder platform(String platform) {
            s.platform = platform;
            return this;
        }

        public Builder handle(String handle) {
            s.handle = handle;
            return this;
        }

        public Builder followers(long followers) {
            s.followers = followers;
            return this;
        }

        public Builder engagementRate(BigDecimal engagementRate) {
            s.engagementRate = engagementRate;
            return this;
        }

        public Builder verified(boolean verified) {
            s.verified = verified;
            return this;
        }

        public Builder profileUrl(String profileUrl) {
            s.profileUrl = profileUrl;
            return this;
        }

        /** F-0965 — only needed for {@link #SOURCE_IMPORTED}; otherwise derived from {@code verified}. */
        public Builder source(String source) {
            s.source = source;
            sourceSet = true;
            return this;
        }

        private boolean sourceSet;

        public PlatformStat build() {
            if (!sourceSet) {
                s.source = s.verified ? SOURCE_META_API : SOURCE_CREATOR_REPORTED;
            }
            return s;
        }
    }
}
