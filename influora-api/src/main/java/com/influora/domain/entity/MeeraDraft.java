package com.influora.domain.entity;

import com.influora.domain.enums.DraftKind;
import com.influora.domain.enums.DraftStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;2.4) — a message Meera composed for a creator but did not
 * send. Approval level 0 is draft-only by definition, so this table IS the product at level 0: the
 * draft is written here, the creator reads it, and nothing leaves the platform until she approves.
 *
 * <p><b>Only three of {@link DraftStatus}'s five values are ever written.</b> Creation writes
 * {@link DraftStatus#PENDING}, SPEC.md &sect;3.7's approve writes {@link DraftStatus#SENT}, discard
 * writes {@link DraftStatus#DISCARDED}. {@code APPROVED} and {@code EDITED} are declared but inert —
 * see {@link #edited} for what replaced them.
 *
 * <p><b>Four of the five parent ids are nullable and un-FK'd.</b> Only {@link #creatorProfileId} is
 * mandatory and FK-enforced, because a draft always belongs to exactly one creator and must die with
 * her profile. {@link #collaborationId}, {@link #briefId}, {@link #campaignId} and
 * {@link #conversationId} are deliberately unconstrained: a draft is the creator's own record of
 * what Meera suggested and must not be cascade-deleted when a brand cancels a campaign.
 * {@link #kind}, not the set of populated ids, is what tells a reader which parent matters.
 *
 * <p><b>Id space (SPEC.md &sect;0.8):</b> {@link #creatorProfileId} is a {@code creator_profiles.id}.
 */
@Entity
@Table(name = "meera_drafts")
public class MeeraDraft {

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    /** {@code ai_conversations.id} of the turn that produced this draft. */
    @Column(name = "conversation_id", length = 26)
    private String conversationId;

    /** Deal thread this draft targets, if any. */
    @Column(name = "collaboration_id", length = 26)
    private String collaborationId;

    @Column(name = "brief_id", length = 26)
    private String briefId;

    /** Set for {@link DraftKind#APPLICATION} drafts (SPEC.md B7). */
    @Column(name = "campaign_id", length = 26)
    private String campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private DraftKind kind;

    /** {@code RoutineIntent} name, set only when {@link #kind} is {@link DraftKind#ROUTINE}. */
    @Column(name = "intent", length = 32)
    private String intent;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    /**
     * {@code DECIMAL(12,2)}, matching {@code collaborations.agreed_rate} and the three
     * {@code creator_agent_preferences} floors. A {@link DraftKind#COUNTER}'s number is compared
     * against those directly, and a floating-point type would make the below-floor guard wrong at
     * the boundary.
     */
    @Column(name = "proposed_amount", precision = 12, scale = 2)
    private BigDecimal proposedAmount;

    /** Frozen {@code DealTermsDto} snapshot — the counter form is prefilled from what the creator approved. */
    @Column(name = "deal_terms_json", columnDefinition = "TEXT")
    private String dealTermsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DraftStatus status;

    /**
     * SPEC.md &sect;14.6 must-fix 1 (findings W21/W22) — true when the creator changed the text
     * before sending. This is a real column rather than a local boolean because Phase-B0 gate metric
     * 3 (SPEC.md &sect;14.5.c) has a second threshold, "&gt;= 25% approved UNEDITED", that had no
     * backing column anywhere and could therefore not be computed at all. It cannot be reconstructed
     * after the fact either: once the creator's edited text is saved over the draft, the original
     * Meera text is gone and "did she change it" is unanswerable.
     *
     * <p>Set by SPEC.md &sect;3.7's approve, which already computes it. Defaults to false, which is
     * the correct reading for a draft that has not been approved yet AND the exact case metric 3
     * counts for one sent verbatim.
     */
    @Column(name = "edited", nullable = false)
    private boolean edited;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /**
     * REPLY-only and nullable (SPEC.md B0-47): counter and reject return the collaboration, not a
     * message row, so there is no id to record for those kinds.
     */
    @Column(name = "sent_message_id", length = 26)
    private String sentMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MeeraDraft() {}

    /**
     * The only construction path. Every draft is born {@link DraftStatus#PENDING} and
     * {@link #edited} false — approval level 0 means nothing is ever created already-approved.
     *
     * <p>The nullable parents are passed positionally rather than through a builder because
     * {@link #kind} already determines which of them a caller must supply, and a builder would let a
     * caller omit the one that matters for the kind it chose.
     */
    public static MeeraDraft create(
            String id,
            String creatorProfileId,
            String conversationId,
            String collaborationId,
            String briefId,
            String campaignId,
            DraftKind kind,
            String intent,
            String text,
            BigDecimal proposedAmount,
            String dealTermsJson) {
        MeeraDraft draft = new MeeraDraft();
        draft.id = id;
        draft.creatorProfileId = creatorProfileId;
        draft.conversationId = conversationId;
        draft.collaborationId = collaborationId;
        draft.briefId = briefId;
        draft.campaignId = campaignId;
        draft.kind = kind;
        draft.intent = intent;
        draft.text = text;
        draft.proposedAmount = proposedAmount;
        draft.dealTermsJson = dealTermsJson;
        draft.status = DraftStatus.PENDING;
        draft.edited = false;
        draft.createdAt = Instant.now();
        return draft;
    }

    /**
     * SPEC.md &sect;3.7 approve — the creator approved this draft and it went out. Writes
     * {@link DraftStatus#SENT} directly, never {@code APPROVED} or {@code EDITED} (see
     * {@link DraftStatus}); whether she changed the text is recorded on {@link #edited} instead,
     * which is what gate metric 3 measures.
     *
     * <p>{@code sentMessageId} is REPLY-only — pass null for COUNTER and DECLINE, which return the
     * collaboration rather than a message row.
     */
    public void markSent(String sentMessageId, boolean edited) {
        this.status = DraftStatus.SENT;
        this.edited = edited;
        this.sentMessageId = sentMessageId;
        this.approvedAt = Instant.now();
    }

    /** The creator threw the draft away. Terminal, like {@link #markSent}. */
    public void discard() {
        this.status = DraftStatus.DISCARDED;
    }

    public String getId() {
        return id;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public String getBriefId() {
        return briefId;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public DraftKind getKind() {
        return kind;
    }

    public String getIntent() {
        return intent;
    }

    public String getText() {
        return text;
    }

    public BigDecimal getProposedAmount() {
        return proposedAmount;
    }

    public String getDealTermsJson() {
        return dealTermsJson;
    }

    public DraftStatus getStatus() {
        return status;
    }

    /** SPEC.md &sect;14.5.c gate metric 3 reads this. See the field javadoc for why it is stored. */
    public boolean isEdited() {
        return edited;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getSentMessageId() {
        return sentMessageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
