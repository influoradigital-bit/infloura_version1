package com.influora.domain.entity;

import com.influora.common.TextSanitizer;
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

    /**
     * A7-U1 — raw usage-rights terms submitted on the initial brand proposal ({@code
     * DealDtos.CreateDealRequest.usageRights}). Previously accepted by {@code
     * DealService#createProposal} and silently dropped (no persistence target existed). This is
     * the minimum-viable fix: store the submitted string as-is. Not yet a structured
     * rights model — that redesign is out of scope here.
     */
    @Column(name = "usage_rights", columnDefinition = "TEXT")
    private String usageRights;

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
        c.notes = TextSanitizer.sanitizePlainText(message);
        Instant now = Instant.now();
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    /**
     * Creator-initiated counterpart to {@link #invite}. Task #7 (creator campaign browse/apply,
     * Creator Week 2 sprint) — mirrors the same construction shape (status/source pair, currency
     * fallback, notes carries the creator's optional application message) so both entry points
     * into a collaboration read the same way downstream.
     */
    public static Collaboration apply(
            String id, String campaignId, String creatorUserId, String message, String currency) {
        Collaboration c = new Collaboration();
        c.id = id;
        c.campaignId = campaignId;
        c.creatorId = creatorUserId;
        c.status = CollaborationStatus.APPLIED;
        c.source = CollaborationSource.APPLICATION;
        c.currency = currency != null ? currency : "INR";
        c.notes = TextSanitizer.sanitizePlainText(message);
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public CollaborationSource getSource() {
        return source;
    }

    public BigDecimal getAgreedRate() {
        return agreedRate;
    }

    public String getCurrency() {
        return currency;
    }

    public String getNotes() {
        return notes;
    }

    public String getUsageRights() {
        return usageRights;
    }

    /** A7-U1 — set post-construction so both {@link #propose} and future entry points can attach it. */
    public void setUsageRights(String usageRights) {
        this.usageRights = usageRights;
        this.updatedAt = Instant.now();
    }

    public void transitionTo(CollaborationStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void updateAgreedRate(BigDecimal rate) {
        this.agreedRate = rate;
        this.updatedAt = Instant.now();
    }

    /** Brand-initiated proposal with explicit terms (Task #9 POST /deals). */
    public static Collaboration propose(
            String id,
            String campaignId,
            String creatorUserId,
            BigDecimal amount,
            String currency,
            String message) {
        Collaboration c = new Collaboration();
        c.id = id;
        c.campaignId = campaignId;
        c.creatorId = creatorUserId;
        c.status = CollaborationStatus.IN_NEGOTIATION;
        c.source = CollaborationSource.INVITATION;
        c.agreedRate = amount;
        c.currency = currency != null ? currency : "INR";
        c.notes = TextSanitizer.sanitizePlainText(message);
        Instant now = Instant.now();
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public boolean canAccept() {
        return status == CollaborationStatus.INVITED
                || status == CollaborationStatus.APPLIED
                || status == CollaborationStatus.SHORTLISTED
                || status == CollaborationStatus.IN_NEGOTIATION;
    }

    public boolean canCounter() {
        return canAccept();
    }

    public boolean canReject() {
        return status != CollaborationStatus.COMPLETED
                && status != CollaborationStatus.CANCELLED
                && status != CollaborationStatus.DISPUTED;
    }
}
