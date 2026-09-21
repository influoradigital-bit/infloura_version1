package com.influora.domain.entity;

import com.influora.domain.enums.OfferActor;
import com.influora.domain.enums.OfferEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;2.6) — append-only negotiation ledger: who offered what, in
 * what order, and whether Meera drafted it.
 *
 * <p><b>{@link Collaboration} cannot answer this.</b> {@code agreed_rate} is OVERWRITTEN on every
 * counter, so after three rounds the first two offers are gone. This is the same gap
 * {@link ApplicationHistoryEvent} (V69) fills for the application journey.
 *
 * <p><b>{@link #meeraDrafted} is the whole point of the table for Phase B0.</b> SPEC.md
 * &sect;14.1.d's {@code meeraAnchoredShare} — the number the B0-to-B1 decision turns on — is the
 * share of negotiations where a Meera-drafted counter appeared, and it is not recoverable later
 * without a per-event authorship stamp captured at write time.
 *
 * <p><b>{@link #sequenceNo} is derived as {@code countByCollaborationId(collaborationId) + 1} under
 * the collaboration row lock</b> — never with a separate {@code SELECT max(sequence_no)} read, which
 * would be a TOCTOU. The row lock makes the derivation correct; the migration's {@code UNIQUE KEY
 * uk_doh_collab_seq (collaboration_id, sequence_no)} makes a mistake in it loud rather than silently
 * corrupting the ordering. That is PRIYA-COMPAT-0904 &sect;7 condition 3.
 *
 * <p><b>The lock is taken by {@code DealService.recordOffer} itself, and an earlier revision of this
 * paragraph was wrong about that.</b> It claimed the four write points ({@code createProposal},
 * {@code doCounter}, {@code doAccept}, {@code doReject}) "all take that lock before reaching the
 * recordOffer helper", as SPEC.md &sect;2.6 and the migration comment still do. Only {@code doReject}
 * did; the other three reach their collaboration through {@code requireOwnedCollaboration}, which is
 * deliberately unlocked. The helper therefore acquires the lock in one place rather than trusting
 * three callers to, and checks the result so an unlockable row fails loudly — see that method's
 * javadoc, which is where this invariant is actually enforced. The migration comment cannot be
 * corrected in place: Flyway checksums an applied migration file, comments included.
 *
 * <p><b>Append-only:</b> no {@code updatedAt}, and this entity deliberately exposes no setters. A
 * negotiation event happened or it did not; it is never revised. Same discipline as
 * {@link ApplicationHistoryEvent}.
 */
@Entity
@Table(name = "deal_offer_history")
public class DealOfferHistory {

    public static final String DEFAULT_CURRENCY = "INR";

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    /** 1-based, derived under the collaboration row lock — see the class javadoc. */
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, length = 16)
    private OfferActor actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 24)
    private OfferEvent event;

    /** Null for {@link OfferEvent#REJECT}, which carries no number. */
    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * ISO 4217, matching {@code creator_agent_preferences.floor_currency}. An amount without a
     * currency is the decorative-column bug V20260903170000 was written to repair.
     */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * Authorship, orthogonal to {@link #actor}: a {@link OfferActor#CREATOR} offer Meera drafted is
     * {@code actor = CREATOR} with this true, because the creator approved and owns it.
     */
    @Column(name = "meera_drafted", nullable = false)
    private boolean meeraDrafted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DealOfferHistory() {}

    /**
     * The only construction path. {@code sequenceNo} is supplied by the caller because only the
     * caller is inside the transaction holding the collaboration row lock that makes the derivation
     * safe — see the class javadoc.
     */
    public static DealOfferHistory record(
            String id,
            String collaborationId,
            int sequenceNo,
            OfferActor actor,
            OfferEvent event,
            BigDecimal amount,
            String currency,
            boolean meeraDrafted) {
        DealOfferHistory row = new DealOfferHistory();
        row.id = id;
        row.collaborationId = collaborationId;
        row.sequenceNo = sequenceNo;
        row.actor = actor;
        row.event = event;
        row.amount = amount;
        row.currency = currency != null ? currency : DEFAULT_CURRENCY;
        row.meeraDrafted = meeraDrafted;
        row.createdAt = Instant.now();
        return row;
    }

    public String getId() {
        return id;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public OfferActor getActor() {
        return actor;
    }

    public OfferEvent getEvent() {
        return event;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isMeeraDrafted() {
        return meeraDrafted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
