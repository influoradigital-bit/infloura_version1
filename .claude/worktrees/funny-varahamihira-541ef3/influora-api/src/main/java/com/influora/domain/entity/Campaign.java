package com.influora.domain.entity;

import com.influora.domain.enums.CampaignStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignStatus status;

    @Column(name = "budget_min", precision = 12, scale = 2)
    private BigDecimal budgetMin;

    @Column(name = "budget_max", precision = 12, scale = 2)
    private BigDecimal budgetMax;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "application_deadline")
    private LocalDate applicationDeadline;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "platforms", columnDefinition = "json")
    private String platformsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_types", columnDefinition = "json")
    private String contentTypesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String objectivesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String requirementsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String hashtagsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_audience", columnDefinition = "json")
    private String targetAudienceJson;

    @Column(name = "brand_guidelines", columnDefinition = "TEXT")
    private String brandGuidelines;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Column(name = "max_collaborators")
    private Integer maxCollaborators;

    @Column(name = "created_by", nullable = false, length = 26)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Campaign() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public void setStatus(CampaignStatus status) {
        this.status = status;
        touch();
    }

    public BigDecimal getBudgetMin() {
        return budgetMin;
    }

    public BigDecimal getBudgetMax() {
        return budgetMax;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getApplicationDeadline() {
        return applicationDeadline;
    }

    public String getPlatformsJson() {
        return platformsJson;
    }

    public String getContentTypesJson() {
        return contentTypesJson;
    }

    public String getObjectivesJson() {
        return objectivesJson;
    }

    public String getRequirementsJson() {
        return requirementsJson;
    }

    public String getHashtagsJson() {
        return hashtagsJson;
    }

    public String getTargetAudienceJson() {
        return targetAudienceJson;
    }

    public String getBrandGuidelines() {
        return brandGuidelines;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public Integer getMaxCollaborators() {
        return maxCollaborators;
    }

    public String getCreatedBy() {
        return createdBy;
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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Campaign c = new Campaign();

        public Builder id(String id) {
            c.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            c.workspaceId = workspaceId;
            return this;
        }

        public Builder title(String title) {
            c.title = title;
            return this;
        }

        public Builder description(String description) {
            c.description = description;
            return this;
        }

        public Builder status(CampaignStatus status) {
            c.status = status;
            return this;
        }

        public Builder budgetMin(BigDecimal budgetMin) {
            c.budgetMin = budgetMin;
            return this;
        }

        public Builder budgetMax(BigDecimal budgetMax) {
            c.budgetMax = budgetMax;
            return this;
        }

        public Builder currency(String currency) {
            c.currency = currency;
            return this;
        }

        public Builder startDate(LocalDate startDate) {
            c.startDate = startDate;
            return this;
        }

        public Builder endDate(LocalDate endDate) {
            c.endDate = endDate;
            return this;
        }

        public Builder applicationDeadline(LocalDate applicationDeadline) {
            c.applicationDeadline = applicationDeadline;
            return this;
        }

        public Builder platformsJson(String json) {
            c.platformsJson = json;
            return this;
        }

        public Builder contentTypesJson(String json) {
            c.contentTypesJson = json;
            return this;
        }

        public Builder objectivesJson(String json) {
            c.objectivesJson = json;
            return this;
        }

        public Builder requirementsJson(String json) {
            c.requirementsJson = json;
            return this;
        }

        public Builder hashtagsJson(String json) {
            c.hashtagsJson = json;
            return this;
        }

        public Builder targetAudienceJson(String json) {
            c.targetAudienceJson = json;
            return this;
        }

        public Builder brandGuidelines(String brandGuidelines) {
            c.brandGuidelines = brandGuidelines;
            return this;
        }

        public Builder isPrivate(boolean isPrivate) {
            c.isPrivate = isPrivate;
            return this;
        }

        public Builder maxCollaborators(Integer maxCollaborators) {
            c.maxCollaborators = maxCollaborators;
            return this;
        }

        public Builder createdBy(String createdBy) {
            c.createdBy = createdBy;
            return this;
        }

        public Campaign build() {
            Instant now = Instant.now();
            c.createdAt = now;
            c.updatedAt = now;
            if (c.currency == null) {
                c.currency = "INR";
            }
            if (c.status == null) {
                c.status = CampaignStatus.DRAFT;
            }
            return c;
        }
    }

    public void applyPatch(
            String title,
            String description,
            CampaignStatus status,
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            String currency,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate applicationDeadline,
            String platformsJson,
            String contentTypesJson,
            String objectivesJson,
            String requirementsJson,
            String hashtagsJson,
            String targetAudienceJson,
            String brandGuidelines,
            Boolean isPrivate,
            Integer maxCollaborators) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (status != null) this.status = status;
        if (budgetMin != null) this.budgetMin = budgetMin;
        if (budgetMax != null) this.budgetMax = budgetMax;
        if (currency != null) this.currency = currency;
        if (startDate != null) this.startDate = startDate;
        if (endDate != null) this.endDate = endDate;
        if (applicationDeadline != null) this.applicationDeadline = applicationDeadline;
        if (platformsJson != null) this.platformsJson = platformsJson;
        if (contentTypesJson != null) this.contentTypesJson = contentTypesJson;
        if (objectivesJson != null) this.objectivesJson = objectivesJson;
        if (requirementsJson != null) this.requirementsJson = requirementsJson;
        if (hashtagsJson != null) this.hashtagsJson = hashtagsJson;
        if (targetAudienceJson != null) this.targetAudienceJson = targetAudienceJson;
        if (brandGuidelines != null) this.brandGuidelines = brandGuidelines;
        if (isPrivate != null) this.isPrivate = isPrivate;
        if (maxCollaborators != null) this.maxCollaborators = maxCollaborators;
        touch();
    }

    public Campaign duplicateCopy(String newId, String newTitle, String createdBy) {
        return Campaign.builder()
                .id(newId)
                .workspaceId(workspaceId)
                .title(newTitle)
                .description(description)
                .status(CampaignStatus.DRAFT)
                .budgetMin(budgetMin)
                .budgetMax(budgetMax)
                .currency(currency)
                .startDate(startDate)
                .endDate(endDate)
                .applicationDeadline(applicationDeadline)
                .platformsJson(platformsJson)
                .contentTypesJson(contentTypesJson)
                .objectivesJson(objectivesJson)
                .requirementsJson(requirementsJson)
                .hashtagsJson(hashtagsJson)
                .targetAudienceJson(targetAudienceJson)
                .brandGuidelines(brandGuidelines)
                .isPrivate(isPrivate)
                .maxCollaborators(maxCollaborators)
                .createdBy(createdBy)
                .build();
    }
}
