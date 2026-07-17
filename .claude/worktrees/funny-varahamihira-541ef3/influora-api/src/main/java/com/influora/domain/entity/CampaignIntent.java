package com.influora.domain.entity;

import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.IntentStatus;
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

@Entity
@Table(name = "campaign_intents")
public class CampaignIntent {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 26)
    private String conversationId;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false)
    private CampaignIntentType campaignType;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "product_url", length = 500)
    private String productUrl;

    @Column(name = "product_price", precision = 12, scale = 2)
    private BigDecimal productPrice;

    /** AI proposal only — NEVER authoritative. See {@code payment_milestones.amount} for the real charge. */
    @Column(name = "proposed_budget", precision = 12, scale = 2)
    private BigDecimal proposedBudget;

    @Column(name = "creator_count")
    private Integer creatorCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "location_filter", columnDefinition = "json")
    private String locationFilterJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntentStatus status;

    @Column(nullable = false)
    private boolean confirmed;

    @Column(name = "campaign_id", length = 26)
    private String campaignId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampaignIntent() {}

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public CampaignIntentType getCampaignType() {
        return campaignType;
    }

    public String getProductName() {
        return productName;
    }

    public String getProductUrl() {
        return productUrl;
    }

    public BigDecimal getProductPrice() {
        return productPrice;
    }

    public BigDecimal getProposedBudget() {
        return proposedBudget;
    }

    public Integer getCreatorCount() {
        return creatorCount;
    }

    public String getLocationFilterJson() {
        return locationFilterJson;
    }

    public IntentStatus getStatus() {
        return status;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getCampaignId() {
        return campaignId;
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

    /** Stamped only by the server-side confirm_launch/create-campaign flow — never by the AI. */
    public void confirm(String campaignId) {
        this.campaignId = campaignId;
        this.confirmed = true;
        this.status = IntentStatus.CONFIRMED;
        touch();
    }

    public void abandon() {
        this.status = IntentStatus.ABANDONED;
        touch();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CampaignIntent i = new CampaignIntent();

        public Builder id(String id) {
            i.id = id;
            return this;
        }

        public Builder conversationId(String conversationId) {
            i.conversationId = conversationId;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            i.workspaceId = workspaceId;
            return this;
        }

        public Builder campaignType(CampaignIntentType campaignType) {
            i.campaignType = campaignType;
            return this;
        }

        public Builder productName(String productName) {
            i.productName = productName;
            return this;
        }

        public Builder productUrl(String productUrl) {
            i.productUrl = productUrl;
            return this;
        }

        public Builder productPrice(BigDecimal productPrice) {
            i.productPrice = productPrice;
            return this;
        }

        public Builder proposedBudget(BigDecimal proposedBudget) {
            i.proposedBudget = proposedBudget;
            return this;
        }

        public Builder creatorCount(Integer creatorCount) {
            i.creatorCount = creatorCount;
            return this;
        }

        public Builder locationFilterJson(String locationFilterJson) {
            i.locationFilterJson = locationFilterJson;
            return this;
        }

        public Builder status(IntentStatus status) {
            i.status = status;
            return this;
        }

        public CampaignIntent build() {
            Instant now = Instant.now();
            i.createdAt = now;
            i.updatedAt = now;
            if (i.status == null) {
                i.status = IntentStatus.DRAFTING;
            }
            return i;
        }
    }
}
