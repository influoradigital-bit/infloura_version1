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

/** {@code trends} (V51) — n8n (Dev, T3) writes daily; Spring only reads. Never store raw
 * live-source payloads here, only the merged/tagged/expiring record (schema lock §1a). */
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
