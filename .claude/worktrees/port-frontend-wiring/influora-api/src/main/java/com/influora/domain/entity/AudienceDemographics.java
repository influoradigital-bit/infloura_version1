package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single audience-demographics snapshot for a creator (V25 {@code audience_demographics}) —
 * Wave B task B4 (wiki/tech/REMAINING_WORK_PLAN.md), fetched weekly by {@code
 * AudienceDemographicsJob} from Meta's {@code audience_city}/{@code audience_country}/{@code
 * audience_gender_age}/{@code audience_locale} insights (see {@code
 * InstagramInsightsClient#getAudienceDemographics}).
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Ordinary
 * MySQL row, not a TimescaleDB hypertable row — same translation discipline as {@code
 * CreatorMetric}/{@code MediaMetric}/{@code CreatorScore}.
 *
 * <p><b>Immutable snapshot rows, matching {@code CreatorScore}'s pattern (not an upsert-latest
 * table like {@code CouponCode.usageCount}).</b> Meta's audience insights are themselves a
 * recomputed-at-fetch-time "lifetime" snapshot, not something this codebase increments — the same
 * shape as {@code CreatorScore}'s daily algorithm output. One row is written per {@code
 * AudienceDemographicsJob} run per creator; there is deliberately no update/mutator method. The
 * "current" value for the brand-facing endpoint is the most recent row by {@code (creatorProfileId,
 * time DESC)} — see {@code AudienceDemographicsRepository#findFirstByCreatorProfileIdOrderByTimeDesc}
 * (same finder shape as {@code CreatorScoreRepository}). A failed/partial fetch therefore never
 * destroys the previous good snapshot.
 *
 * <p>Each demographic dimension is stored as its own raw JSON {@code {bucket: count}} map — same
 * convention as every other variable-shape breakdown in this codebase ({@code
 * CreatorScore.fakeFollowerReasonsJson}, {@code CreatorScore.garmFlagsJson}, {@code
 * MetaOAuthToken.grantedScopesJson}): never a directly-mapped {@code Map}/{@code List} field. Use
 * {@code com.influora.common.JsonLists#toJsonObject(Object)} to serialize a {@code Map<String,
 * Long>} before passing it to the builder, and {@code JsonLists#objectFromJson(String, Class)} (or
 * a {@code TypeReference} equivalent) to deserialize when reading.
 */
@Entity
@Table(name = "audience_demographics")
public class AudienceDemographics {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "time", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant time;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "age_gender_breakdown", columnDefinition = "json")
    private String ageGenderBreakdownJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "country_breakdown", columnDefinition = "json")
    private String countryBreakdownJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "city_breakdown", columnDefinition = "json")
    private String cityBreakdownJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "locale_breakdown", columnDefinition = "json")
    private String localeBreakdownJson;

    @Column(name = "data_source", nullable = false, length = 20)
    private String dataSource;

    @Column(name = "fetched_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant fetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AudienceDemographics() {}

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

    public String getAgeGenderBreakdownJson() {
        return ageGenderBreakdownJson;
    }

    public String getCountryBreakdownJson() {
        return countryBreakdownJson;
    }

    public String getCityBreakdownJson() {
        return cityBreakdownJson;
    }

    public String getLocaleBreakdownJson() {
        return localeBreakdownJson;
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
        private final AudienceDemographics d = new AudienceDemographics();

        public Builder id(String id) {
            d.id = id;
            return this;
        }

        public Builder time(Instant time) {
            d.time = time;
            return this;
        }

        public Builder creatorProfileId(String creatorProfileId) {
            d.creatorProfileId = creatorProfileId;
            return this;
        }

        public Builder platform(String platform) {
            d.platform = platform;
            return this;
        }

        public Builder ageGenderBreakdownJson(String ageGenderBreakdownJson) {
            d.ageGenderBreakdownJson = ageGenderBreakdownJson;
            return this;
        }

        public Builder countryBreakdownJson(String countryBreakdownJson) {
            d.countryBreakdownJson = countryBreakdownJson;
            return this;
        }

        public Builder cityBreakdownJson(String cityBreakdownJson) {
            d.cityBreakdownJson = cityBreakdownJson;
            return this;
        }

        public Builder localeBreakdownJson(String localeBreakdownJson) {
            d.localeBreakdownJson = localeBreakdownJson;
            return this;
        }

        public Builder dataSource(String dataSource) {
            d.dataSource = dataSource;
            return this;
        }

        public Builder fetchedAt(Instant fetchedAt) {
            d.fetchedAt = fetchedAt;
            return this;
        }

        public AudienceDemographics build() {
            if (d.time == null) {
                d.time = Instant.now();
            }
            if (d.fetchedAt == null) {
                d.fetchedAt = Instant.now();
            }
            if (d.dataSource == null) {
                d.dataSource = "META_API";
            }
            if (d.platform == null) {
                d.platform = "INSTAGRAM";
            }
            d.createdAt = Instant.now();
            return d;
        }
    }
}
