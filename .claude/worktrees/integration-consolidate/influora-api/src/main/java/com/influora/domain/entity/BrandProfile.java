package com.influora.domain.entity;

import com.influora.domain.enums.AnalysisStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "brand_profiles")
public class BrandProfile {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false)
    private AnalysisStatus analysisStatus;

    @Column(name = "scraped_at")
    private Instant scrapedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_catalog", columnDefinition = "json")
    private String productCatalogJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "brand_aesthetic", columnDefinition = "json")
    private String brandAestheticJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tone_profile", columnDefinition = "json")
    private String toneProfileJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "niche_tags", columnDefinition = "json")
    private String nicheTagsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "competitor_urls", columnDefinition = "json")
    private String competitorUrlsJson;

    @Column(name = "analysis_error", length = 500)
    private String analysisError;

    /** V51 (Trend-Spark) — brand's recurring content themes, used by {@code ThemeMatchService}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "theme_tags", columnDefinition = "json")
    private String themeTagsJson;

    /** V51 (Trend-Spark) — last time the brand posted, used by {@code ContentGapService}. */
    @Column(name = "last_posted_at")
    private Instant lastPostedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BrandProfile() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
        touch();
    }

    public AnalysisStatus getAnalysisStatus() {
        return analysisStatus;
    }

    public void setAnalysisStatus(AnalysisStatus analysisStatus) {
        this.analysisStatus = analysisStatus;
        touch();
    }

    public Instant getScrapedAt() {
        return scrapedAt;
    }

    public void setScrapedAt(Instant scrapedAt) {
        this.scrapedAt = scrapedAt;
        touch();
    }

    public String getProductCatalogJson() {
        return productCatalogJson;
    }

    public String getBrandAestheticJson() {
        return brandAestheticJson;
    }

    public String getToneProfileJson() {
        return toneProfileJson;
    }

    public String getNicheTagsJson() {
        return nicheTagsJson;
    }

    public String getCompetitorUrlsJson() {
        return competitorUrlsJson;
    }

    public String getAnalysisError() {
        return analysisError;
    }

    public String getThemeTagsJson() {
        return themeTagsJson;
    }

    public void setThemeTagsJson(String themeTagsJson) {
        this.themeTagsJson = themeTagsJson;
        touch();
    }

    public Instant getLastPostedAt() {
        return lastPostedAt;
    }

    public void setLastPostedAt(Instant lastPostedAt) {
        this.lastPostedAt = lastPostedAt;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    /** Applies the website analyzer's (Python/Domain D) callback result. */
    public void applyAnalysisResult(
            String productCatalogJson,
            String brandAestheticJson,
            String toneProfileJson,
            String nicheTagsJson,
            String competitorUrlsJson) {
        this.productCatalogJson = productCatalogJson;
        this.brandAestheticJson = brandAestheticJson;
        this.toneProfileJson = toneProfileJson;
        this.nicheTagsJson = nicheTagsJson;
        this.competitorUrlsJson = competitorUrlsJson;
        this.analysisStatus = AnalysisStatus.READY;
        this.analysisError = null;
        this.scrapedAt = Instant.now();
        touch();
    }

    public void markFailed(String error) {
        this.analysisStatus = AnalysisStatus.FAILED;
        this.analysisError = error;
        touch();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final BrandProfile p = new BrandProfile();

        public Builder id(String id) {
            p.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            p.workspaceId = workspaceId;
            return this;
        }

        public Builder websiteUrl(String websiteUrl) {
            p.websiteUrl = websiteUrl;
            return this;
        }

        public Builder analysisStatus(AnalysisStatus analysisStatus) {
            p.analysisStatus = analysisStatus;
            return this;
        }

        public BrandProfile build() {
            Instant now = Instant.now();
            p.createdAt = now;
            p.updatedAt = now;
            if (p.analysisStatus == null) {
                p.analysisStatus = AnalysisStatus.PENDING;
            }
            return p;
        }
    }
}
