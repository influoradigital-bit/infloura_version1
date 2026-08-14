package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single creator-level metrics snapshot (V21 {@code creator_metrics}) — Phase 2 Data Pipeline
 * (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2), fetched from the Meta Graph API by {@code
 * MetricsPollingJob}.
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] This is an
 * ordinary MySQL row, not a TimescaleDB hypertable row. {@code time} is stored as {@code
 * DATETIME(6)} UTC (see V21 migration comment). Rows are immutable — one row per poll; there is
 * deliberately no update/mutator method, matching the append-only time-series semantics from the
 * spec.
 */
@Entity
@Table(name = "creator_metrics")
public class CreatorMetric {

    /**
     * CR-119 — the canonical spelling of "this snapshot came from a real platform API call,"
     * previously a bare {@code "META_API"} literal. The sites that decide or consume THIS
     * entity's provenance now all point here: the builder default, {@code MetricsPollingJob},
     * {@code PortfolioService} and {@code RateEstimationService}. ({@code AudienceDemographicsJob}
     * keeps its own private copy — it writes a different entity with no verified-flag consumer,
     * so it is deliberately out of scope rather than silently included.) It is now load-bearing rather
     * than cosmetic: {@link #isPlatformVerified()} reads it to decide whether a {@code
     * PlatformStat} may claim verified provenance to brands, so a drifted literal would silently
     * mislabel real data as self-reported (or worse, the reverse).
     */
    public static final String DATA_SOURCE_META_API = "META_API";

    /**
     * CR-119 — the fail-closed default for {@link Builder#build()}. Matches the vocabulary
     * {@code DeliverableMetric.SOURCE_CREATOR_REPORTED} already uses for the same distinction.
     *
     * <p>The builder used to default a missing {@code dataSource} to {@link #DATA_SOURCE_META_API},
     * which was harmless while it only labelled rows — but {@link #isPlatformVerified()} now gates
     * a claim shown to brands who are about to spend money, and "the caller forgot to say where
     * this came from" must never resolve to "a platform confirmed it". Defaulting to
     * creator-reported makes the unsafe direction the one you have to ask for explicitly.
     */
    public static final String DATA_SOURCE_CREATOR_REPORTED = "CREATOR_REPORTED";

    /**
     * CR-119 — whether this snapshot's numbers came from a real platform API fetch rather than
     * creator self-report. This is the ONLY thing that may set {@code PlatformStat.verified}.
     *
     * <p>Today exactly one source qualifies: the Meta Graph API (Instagram/Facebook), via
     * {@code MetricsPollingJob} and {@code PortfolioService#syncPlatforms}. YouTube, TikTok and
     * Twitter/X have no OAuth or data-fetch integration at all — the remaining, still-open half
     * of CR-119 — so any row for those platforms is creator-reported and must never read as
     * verified. When a real integration lands for one of them, it adds its own data-source
     * constant here and this predicate widens; nothing downstream needs to change.
     */
    public boolean isPlatformVerified() {
        return DATA_SOURCE_META_API.equals(dataSource);
    }

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "time", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant time;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    /** CR-116 — InstagramUserResponse.username() at fetch time; null if Meta didn't return one. */
    @Column(name = "username", length = 200)
    private String username;

    @Column(name = "followers", nullable = false)
    private long followers;

    @Column(name = "following")
    private Long following;

    @Column(name = "media_count")
    private Integer mediaCount;

    @Column(name = "avg_engagement_rate", precision = 8, scale = 4)
    private BigDecimal avgEngagementRate;

    @Column(name = "avg_reach_per_post")
    private Long avgReachPerPost;

    @Column(name = "avg_impressions_per_post")
    private Long avgImpressionsPerPost;

    @Column(name = "profile_views")
    private Long profileViews;

    @Column(name = "website_clicks")
    private Long websiteClicks;

    @Column(name = "data_source", nullable = false, length = 20)
    private String dataSource;

    @Column(name = "fetched_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant fetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreatorMetric() {}

    public String getId() {
        return id;
    }

    public Instant getTime() {
        return time;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getUsername() {
        return username;
    }

    public long getFollowers() {
        return followers;
    }

    public Long getFollowing() {
        return following;
    }

    public Integer getMediaCount() {
        return mediaCount;
    }

    public BigDecimal getAvgEngagementRate() {
        return avgEngagementRate;
    }

    public Long getAvgReachPerPost() {
        return avgReachPerPost;
    }

    public Long getAvgImpressionsPerPost() {
        return avgImpressionsPerPost;
    }

    public Long getProfileViews() {
        return profileViews;
    }

    public Long getWebsiteClicks() {
        return websiteClicks;
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
        private final CreatorMetric m = new CreatorMetric();

        public Builder id(String id) {
            m.id = id;
            return this;
        }

        public Builder time(Instant time) {
            m.time = time;
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

        public Builder username(String username) {
            m.username = username;
            return this;
        }

        public Builder followers(long followers) {
            m.followers = followers;
            return this;
        }

        public Builder following(Long following) {
            m.following = following;
            return this;
        }

        public Builder mediaCount(Integer mediaCount) {
            m.mediaCount = mediaCount;
            return this;
        }

        public Builder avgEngagementRate(BigDecimal avgEngagementRate) {
            m.avgEngagementRate = avgEngagementRate;
            return this;
        }

        public Builder avgReachPerPost(Long avgReachPerPost) {
            m.avgReachPerPost = avgReachPerPost;
            return this;
        }

        public Builder avgImpressionsPerPost(Long avgImpressionsPerPost) {
            m.avgImpressionsPerPost = avgImpressionsPerPost;
            return this;
        }

        public Builder profileViews(Long profileViews) {
            m.profileViews = profileViews;
            return this;
        }

        public Builder websiteClicks(Long websiteClicks) {
            m.websiteClicks = websiteClicks;
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

        public CreatorMetric build() {
            if (m.time == null) {
                m.time = Instant.now();
            }
            if (m.fetchedAt == null) {
                m.fetchedAt = Instant.now();
            }
            if (m.dataSource == null) {
                // CR-119 — fail CLOSED. Both production writers (MetricsPollingJob,
                // PortfolioService#syncPlatforms) set this explicitly, so nothing real depends on
                // the default; what it must not do is let an omission silently mint a
                // platform-verified claim. See DATA_SOURCE_CREATOR_REPORTED's javadoc.
                m.dataSource = DATA_SOURCE_CREATOR_REPORTED;
            }
            m.createdAt = Instant.now();
            return m;
        }
    }
}
