package com.influora.domain.entity;

import com.influora.domain.enums.TrendCampaignType;
import com.influora.domain.enums.TrendThemeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code trends} (V51). n8n (Dev, T3) wrote this table daily; as of T-GOLIVE-0918
 * [vikram · 2026-09-18] {@code com.influora.job.TrendPullJob} is the writer instead (see
 * wiki/decisions/2026-09-18-trend-headline-screening.md) — n8n's workflow file is kept but
 * superseded. Never store raw live-source payloads here, only the merged/tagged/expiring record
 * (schema lock §1a). No column changes accompany this — {@link #create} is a construction path
 * only, {@code ddl-auto: validate} is unaffected. */
@Entity
@Table(name = "trends")
public class Trend {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "trend_text", nullable = false, length = 500)
    private String trendText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source", nullable = false, columnDefinition = "json")
    private String sourceJson;

    @Column(name = "region", nullable = false, length = 8)
    private String region;

    @Column(name = "detected_date", nullable = false)
    private LocalDate detectedDate;

    @Column(name = "peak_window_days", nullable = false)
    private Integer peakWindowDays;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "themes", nullable = false, columnDefinition = "json")
    private String themesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false, length = 16)
    private TrendCampaignType campaignType;

    /** Tag provenance (V20260716120000). Defaults to KEYWORD for rows written before
     * the recovery tagger existed; n8n stamps AI_RECOVERED on rescued trends. */
    @Enumerated(EnumType.STRING)
    @Column(name = "theme_source", nullable = false, length = 16)
    private TrendThemeSource themeSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Trend() {}

    /** T-GOLIVE-0918 [vikram · 2026-09-18] — construction path for {@code TrendPullJob}. No
     * setters are added; a {@code Trend} is immutable once built, matching this entity's existing
     * read-only-after-construction shape. Source: job-design.md step 12. */
    public static Trend create(
            String id,
            String trendText,
            String sourceJson,
            String region,
            LocalDate detectedDate,
            Integer peakWindowDays,
            Instant expiresAt,
            String themesJson,
            TrendCampaignType campaignType,
            TrendThemeSource themeSource,
            Instant createdAt,
            Instant updatedAt) {
        Trend trend = new Trend();
        trend.id = id;
        trend.trendText = trendText;
        trend.sourceJson = sourceJson;
        trend.region = region;
        trend.detectedDate = detectedDate;
        trend.peakWindowDays = peakWindowDays;
        trend.expiresAt = expiresAt;
        trend.themesJson = themesJson;
        trend.campaignType = campaignType;
        trend.themeSource = themeSource;
        trend.createdAt = createdAt;
        trend.updatedAt = updatedAt;
        return trend;
    }

    public String getId() {
        return id;
    }

    public String getTrendText() {
        return trendText;
    }

    public String getSourceJson() {
        return sourceJson;
    }

    public String getRegion() {
        return region;
    }

    public LocalDate getDetectedDate() {
        return detectedDate;
    }

    public Integer getPeakWindowDays() {
        return peakWindowDays;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getThemesJson() {
        return themesJson;
    }

    public TrendCampaignType getCampaignType() {
        return campaignType;
    }

    public TrendThemeSource getThemeSource() {
        return themeSource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
