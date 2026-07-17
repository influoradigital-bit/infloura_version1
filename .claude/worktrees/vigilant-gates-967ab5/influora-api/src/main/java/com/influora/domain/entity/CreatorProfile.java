package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "creator_profiles")
public class CreatorProfile {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "user_id", nullable = false, unique = true, length = 26)
    private String userId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Column(length = 100)
    private String city;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories", columnDefinition = "json")
    private String categoriesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "languages", columnDefinition = "json")
    private String languagesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_styles", columnDefinition = "json")
    private String contentStylesJson;

    @Column(name = "rate_min", precision = 12, scale = 2)
    private BigDecimal rateMin;

    @Column(name = "rate_max", precision = 12, scale = 2)
    private BigDecimal rateMax;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    @Column(name = "is_discoverable", nullable = false)
    private boolean discoverable;

    @Column(name = "engagement_rate", precision = 5, scale = 2)
    private BigDecimal engagementRate;

    @Column(name = "total_followers", nullable = false)
    private long totalFollowers;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorProfile() {}

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public String getCity() {
        return city;
    }

    public String getCategoriesJson() {
        return categoriesJson;
    }

    public String getLanguagesJson() {
        return languagesJson;
    }

    public String getContentStylesJson() {
        return contentStylesJson;
    }

    public BigDecimal getRateMin() {
        return rateMin;
    }

    public BigDecimal getRateMax() {
        return rateMax;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isVerified() {
        return verified;
    }

    public boolean isDiscoverable() {
        return discoverable;
    }

    public BigDecimal getEngagementRate() {
        return engagementRate;
    }

    public long getTotalFollowers() {
        return totalFollowers;
    }
}
