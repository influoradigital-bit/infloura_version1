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

        public PlatformStat build() {
            return s;
        }
    }
}
