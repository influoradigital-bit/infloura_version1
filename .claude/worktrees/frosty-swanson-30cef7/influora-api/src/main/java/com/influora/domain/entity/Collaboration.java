package com.influora.domain.entity;

import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "collaborations")
public class Collaboration {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "campaign_id", nullable = false, length = 26)
    private String campaignId;

    @Column(name = "creator_id", nullable = false, length = 26)
    private String creatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CollaborationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CollaborationSource source;

    @Column(name = "agreed_rate", precision = 12, scale = 2)
    private BigDecimal agreedRate;

    @Column(length = 3)
    private String currency;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Collaboration() {}

    public static Collaboration invite(
            String id, String campaignId, String creatorUserId, String message, String currency) {
        Collaboration c = new Collaboration();
        c.id = id;
        c.campaignId = campaignId;
        c.creatorId = creatorUserId;
        c.status = CollaborationStatus.INVITED;
        c.source = CollaborationSource.INVITATION;
        c.currency = currency != null ? currency : "INR";
        c.notes = message;
        Instant now = Instant.now();
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public String getId() {
        return id;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public CollaborationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
