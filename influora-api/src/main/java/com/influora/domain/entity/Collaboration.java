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

    /**
     * CR-22a (Priya's ruling, `wiki/errors/CREATOR-BUG-TRACKER.md` §10.1/§10.7) — narrowed from a
     * denylist (everything except {@code COMPLETED}/{@code CANCELLED}/{@code DISPUTED}) to an
     * allowlist over the pre-contract negotiation states only.
     *
     * <p>The denylist previously let {@code CONTRACT_PENDING}, {@code CONTRACTED}, {@code
     * IN_PROGRESS}, {@code REVIEW_PENDING} and {@code REVISION_REQUESTED} through — so {@code
     * DealService#reject} could unilaterally CANCEL a signed, escrow-funded deal, with no escrow
     * refund, no contract voiding and no deliverable reconciliation (Kabir's audit, `wiki/errors/
     * CR-22a-withdrawal-money-path-audit.md`, finding #3/#4). {@code CONTRACT_PENDING} is the cut
     * line: it is the first status with a durable artifact (a {@code Contract} row) that a
     * cancellation would have to reconcile, and at every status below it there is genuinely
     * nothing to reconcile — no contract, no escrow, no deliverables. {@code TERMS_AGREED} stays
     * in the allowlist for exactly that reason: it is {@code ContractService#generate}'s legal
     * predecessor, not its output.
     *
     * <p>Post-contract withdrawal (CR-22b) is deliberately NOT this method widened — it needs its
     * own two-party, escrow-aware termination flow (a proposed disposition the counterparty
     * accepts or disputes), which is out of scope here and design-blocked on product input.
     *
     * <p>This is also now a denylist-to-allowlist conversion for a different reason than the
     * withdrawal fix alone: a future 14th {@link CollaborationStatus} value is non-rejectable by
     * default, which is the correct default for a terminal verb (the old denylist made a new
     * status rejectable by default instead).
     */
    public boolean canReject() {
        return status == CollaborationStatus.INVITED
                || status == CollaborationStatus.APPLIED
                || status == CollaborationStatus.SHORTLISTED
                || status == CollaborationStatus.IN_NEGOTIATION
                || status == CollaborationStatus.TERMS_AGREED;
    }
}
