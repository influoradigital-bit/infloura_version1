package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One fetch of a creator's account-level Instagram insights over {@code periodStart..periodEnd}
 * (inclusive, IST days): accounts reached, views, interactions, accounts engaged and profile-link
 * taps. Immutable snapshot (see V20260924130000); a metric Meta did not return stays null.
 */
@Entity
@Table(name = "creator_account_insights")
public class CreatorAccountInsight {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "reach")
    private Long reach;

    @Column(name = "views")
    private Long views;

    @Column(name = "total_interactions")
    private Long totalInteractions;

    @Column(name = "accounts_engaged")
    private Long accountsEngaged;

    @Column(name = "profile_links_taps")
    private Long profileLinksTaps;

    @Column(name = "data_source", nullable = false, length = 20)
    private String dataSource;

    @Column(name = "fetched_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant fetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreatorAccountInsight() {}

    @SuppressWarnings("java:S107") // one argument per stored column, same as the table
    public CreatorAccountInsight(
            String id,
            String creatorProfileId,
            String platform,
            LocalDate periodStart,
            LocalDate periodEnd,
            Long reach,
            Long views,
            Long totalInteractions,
            Long accountsEngaged,
            Long profileLinksTaps,
            String dataSource,
            Instant fetchedAt) {
        this.id = id;
        this.creatorProfileId = creatorProfileId;
        this.platform = platform;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.reach = reach;
        this.views = views;
        this.totalInteractions = totalInteractions;
        this.accountsEngaged = accountsEngaged;
        this.profileLinksTaps = profileLinksTaps;
        this.dataSource = dataSource;
        this.fetchedAt = fetchedAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** True when Meta returned at least one of the five numbers. */
    public boolean hasAnyMetric() {
        return reach != null || views != null || totalInteractions != null || accountsEngaged != null
                || profileLinksTaps != null;
    }

    public String getId() {
        return id;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getPlatform() {
        return platform;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public Long getReach() {
        return reach;
    }

    public Long getViews() {
        return views;
    }

    public Long getTotalInteractions() {
        return totalInteractions;
    }

    public Long getAccountsEngaged() {
        return accountsEngaged;
    }

    public Long getProfileLinksTaps() {
        return profileLinksTaps;
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
}
