package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A UTM-tagged tracking link generated for one creator's participation in one campaign (V23 {@code
 * utm_campaigns}) — Phase 4 UTM/Coupon Tracking (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2.5/§5.1),
 * created by {@code CampaignLinkService#createTrackingLink} and updated on every recorded click via
 * {@code CampaignLinkService#recordClick}.
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
 * translation discipline as {@code CreatorMetric}/{@code CreatorScore}: {@code created_at}/{@code
 * updated_at}/{@code expires_at} are stored as {@code DATETIME(6)} UTC (see V23 migration comment),
 * not {@code TIMESTAMPTZ}.
 *
 * <p>Unlike the append-only {@code CreatorMetric}/{@code CreatorScore} rows, this entity is
 * deliberately mutable — {@code click_count}/{@code unique_visitors}/{@code conversion_count}/
 * {@code revenue_attributed} are counters updated in place as clicks/conversions are recorded (see
 * spec table §2.7: "Mutable (click counts)").
 *
 * <p><b>Scope cut:</b> {@code conversion_count}/{@code revenue_attributed} columns exist per the
 * full V23 column list but are NOT yet written by anything in this pass — conversion tracking
 * (coupon redemption / Shopify-WooCommerce webhooks) is deliberately deferred to a later slice (see
 * V23 migration comment). The increment helper for conversions intentionally does not exist yet;
 * whoever builds that follow-up should add it here rather than mutating the field directly.
 */
@Entity
@Table(name = "utm_campaigns")
public class UtmCampaign {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "campaign_id", nullable = false, length = 26)
    private String campaignId;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "base_url", nullable = false, length = 1000)
    private String baseUrl;

    @Column(name = "utm_source", nullable = false, length = 100)
    private String utmSource;

    @Column(name = "utm_medium", nullable = false, length = 100)
    private String utmMedium;

    @Column(name = "utm_campaign", nullable = false, length = 100)
    private String utmCampaign;

    @Column(name = "utm_content", length = 100)
    private String utmContent;

    @Column(name = "utm_term", length = 100)
    private String utmTerm;

    @Column(name = "full_tracking_url", nullable = false, length = 2000)
    private String fullTrackingUrl;

    @Column(name = "short_url", length = 200)
    private String shortUrl;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    @Column(name = "unique_visitors", nullable = false)
    private long uniqueVisitors;

    @Column(name = "conversion_count", nullable = false)
    private long conversionCount;

    @Column(name = "revenue_attributed", nullable = false, precision = 14, scale = 2)
    private BigDecimal revenueAttributed;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @Column(name = "expires_at", columnDefinition = "DATETIME(6)")
    private Instant expiresAt;

    protected UtmCampaign() {}

    public String getId() {
        return id;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getUtmSource() {
        return utmSource;
    }

    public String getUtmMedium() {
        return utmMedium;
    }

    public String getUtmCampaign() {
        return utmCampaign;
    }

    public String getUtmContent() {
        return utmContent;
    }

    public String getUtmTerm() {
        return utmTerm;
    }

    public String getFullTrackingUrl() {
        return fullTrackingUrl;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public long getClickCount() {
        return clickCount;
    }

    public long getUniqueVisitors() {
        return uniqueVisitors;
    }

    public long getConversionCount() {
        return conversionCount;
    }

    public BigDecimal getRevenueAttributed() {
        return revenueAttributed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Increments the raw click counter by one and bumps {@code updatedAt}. */
    public void incrementClickCount() {
        this.clickCount += 1;
        this.updatedAt = Instant.now();
    }

    /**
     * Increments the unique-visitor counter by one and bumps {@code updatedAt}.
     *
     * <p>Deduplication (i.e. deciding whether a given visitor has been seen before) is the caller's
     * responsibility — see {@code CampaignLinkService#recordClick} javadoc. This method only
     * performs the increment.
     */
    public void incrementUniqueVisitors() {
        this.uniqueVisitors += 1;
        this.updatedAt = Instant.now();
    }

    /**
     * Increments the conversion counter by one and bumps {@code updatedAt}. Added for {@code
     * ConversionTrackingService#recordConversion} (Phase 4 redemption/conversion processing) — the
     * class javadoc's "scope cut" note (this counter existed in the V23 column list but was
     * previously unwritten) is resolved by this method's introduction.
     */
    public void incrementConversionCount() {
        this.conversionCount += 1;
        this.updatedAt = Instant.now();
    }

    /**
     * Adds {@code amount} to the running {@code revenueAttributed} total and bumps {@code
     * updatedAt}. Added for {@code ConversionTrackingService#recordConversion}. {@code amount} must
     * be non-null and non-negative -- the caller (not this entity) is responsible for validating the
     * incoming order amount before calling this method.
     */
    public void addRevenue(BigDecimal amount) {
        this.revenueAttributed = this.revenueAttributed.add(amount);
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final UtmCampaign u = new UtmCampaign();

        public Builder id(String id) {
            u.id = id;
            return this;
        }

        public Builder campaignId(String campaignId) {
            u.campaignId = campaignId;
            return this;
        }

        public Builder collaborationId(String collaborationId) {
            u.collaborationId = collaborationId;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            u.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            u.baseUrl = baseUrl;
            return this;
        }

        public Builder utmSource(String utmSource) {
            u.utmSource = utmSource;
            return this;
        }

        public Builder utmMedium(String utmMedium) {
            u.utmMedium = utmMedium;
            return this;
        }

        public Builder utmCampaign(String utmCampaign) {
            u.utmCampaign = utmCampaign;
            return this;
        }

        public Builder utmContent(String utmContent) {
            u.utmContent = utmContent;
            return this;
        }

        public Builder utmTerm(String utmTerm) {
            u.utmTerm = utmTerm;
            return this;
        }

        public Builder fullTrackingUrl(String fullTrackingUrl) {
            u.fullTrackingUrl = fullTrackingUrl;
            return this;
        }

        public Builder shortUrl(String shortUrl) {
            u.shortUrl = shortUrl;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            u.expiresAt = expiresAt;
            return this;
        }

        public UtmCampaign build() {
            u.clickCount = 0L;
            u.uniqueVisitors = 0L;
            u.conversionCount = 0L;
            u.revenueAttributed = BigDecimal.ZERO;
            Instant now = Instant.now();
            u.createdAt = now;
            u.updatedAt = now;
            return u;
        }
    }
}
