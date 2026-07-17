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
}
