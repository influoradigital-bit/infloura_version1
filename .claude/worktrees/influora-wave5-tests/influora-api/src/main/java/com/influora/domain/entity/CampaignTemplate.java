package com.influora.domain.entity;

import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignTemplateCategory;
import com.influora.domain.enums.CampaignTemplateScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * B5 — Campaign Templates. Mirrors {@link Campaign}'s writable field set (no dates/status — a
 * template pre-fills the create form, it never carries a lifecycle of its own) plus template
 * metadata: {@code name}/{@code description}/{@code category}/{@code scope}/{@code workspaceId}/
 * {@code createdBy}. See {@code wiki/tech/CAMPAIGN-TEMPLATES-WORKFLOW.md} §1.
 *
 * <p>SYSTEM templates have {@code workspaceId == null} and are readable by every workspace;
 * CUSTOM templates are workspace-scoped and only visible/deletable by their owning workspace —
 * enforced in {@code CampaignTemplateRepository}/{@code CampaignTemplateService}, never trusted
 * from a path param alone.
 */
@Entity
@Table(name = "campaign_templates")
public class CampaignTemplate {

    @Id
    @Column(length = 26)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampaignTemplateCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CampaignTemplateScope scope;

    /** Null for SYSTEM templates; the owning workspace for CUSTOM templates. */
    @Column(name = "workspace_id", length = 26)
    private String workspaceId;

    @Column(name = "created_by", length = 26)
    private String createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", length = 20)
    private CampaignIntentType campaignType;

    @Column(name = "budget_min", precision = 12, scale = 2)
    private BigDecimal budgetMin;

    @Column(name = "budget_max", precision = 12, scale = 2)
    private BigDecimal budgetMax;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "platforms", columnDefinition = "json")
    private String platformsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_types", columnDefinition = "json")
    private String contentTypesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "objectives", columnDefinition = "json")
    private String objectivesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requirements", columnDefinition = "json")
    private String requirementsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hashtags", columnDefinition = "json")
    private String hashtagsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_audience", columnDefinition = "json")
    private String targetAudienceJson;

    @Column(name = "brand_guidelines", columnDefinition = "TEXT")
    private String brandGuidelines;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampaignTemplate() {}

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public CampaignTemplateCategory getCategory() {
        return category;
    }

    public CampaignTemplateScope getScope() {
        return scope;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public CampaignIntentType getCampaignType() {
        return campaignType;
    }

    public BigDecimal getBudgetMin() {
        return budgetMin;
    }

    public BigDecimal getBudgetMax() {
        return budgetMax;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isSystem() {
        return scope == CampaignTemplateScope.SYSTEM;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CampaignTemplate t = new CampaignTemplate();

        public Builder id(String id) {
            t.id = id;
            return this;
        }

        public Builder name(String name) {
            t.name = name;
            return this;
        }

        public Builder description(String description) {
            t.description = description;
            return this;
        }

        public Builder category(CampaignTemplateCategory category) {
            t.category = category;
            return this;
        }

        public Builder scope(CampaignTemplateScope scope) {
            t.scope = scope;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            t.workspaceId = workspaceId;
            return this;
        }

        public Builder createdBy(String createdBy) {
            t.createdBy = createdBy;
            return this;
        }

        public Builder campaignType(CampaignIntentType campaignType) {
            t.campaignType = campaignType;
            return this;
        }

        public Builder budgetMin(BigDecimal budgetMin) {
            t.budgetMin = budgetMin;
            return this;
        }

        public Builder budgetMax(BigDecimal budgetMax) {
            t.budgetMax = budgetMax;
            return this;
        }

        public Builder platformsJson(String platformsJson) {
            t.platformsJson = platformsJson;
            return this;
        }

        public Builder contentTypesJson(String contentTypesJson) {
            t.contentTypesJson = contentTypesJson;
            return this;
        }

        public Builder objectivesJson(String objectivesJson) {
            t.objectivesJson = objectivesJson;
            return this;
        }

        public Builder requirementsJson(String requirementsJson) {
            t.requirementsJson = requirementsJson;
            return this;
        }

        public Builder hashtagsJson(String hashtagsJson) {
            t.hashtagsJson = hashtagsJson;
            return this;
        }

        public Builder targetAudienceJson(String targetAudienceJson) {
            t.targetAudienceJson = targetAudienceJson;
            return this;
        }

        public Builder brandGuidelines(String brandGuidelines) {
            t.brandGuidelines = brandGuidelines;
            return this;
        }

        public CampaignTemplate build() {
            Instant now = Instant.now();
            t.createdAt = now;
            t.updatedAt = now;
            if (t.scope == null) {
                t.scope = CampaignTemplateScope.CUSTOM;
            }
            return t;
        }
    }
}
