package com.influora.domain.entity;

import com.influora.common.TextSanitizer;
import com.influora.domain.enums.BriefSource;
import com.influora.domain.enums.BriefStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;2.2) — one brief a creator brought to Meera: the raw text
 * of a DM or email she pasted ({@link BriefSource#PASTED}), or a brief lifted from a collaboration
 * that already exists on Influora ({@link BriefSource#PLATFORM}).
 *
 * <p><b>{@link #rawText} is persisted before any model is consulted.</b> Brief extraction is an AI
 * call and AI calls fail; {@code BriefFallbackExtractor} exists so a paste survives an outage, and
 * it can only re-run over text that was already stored. {@link #extractedJson} stays null until an
 * extraction succeeds, and {@link #extractionSource} records which path produced it so a degraded
 * analysis is labelled rather than disguised.
 *
 * <p><b>Id space (SPEC.md &sect;0.8):</b> {@link #creatorProfileId} is a {@code creator_profiles.id},
 * NOT a {@code users.id} — unlike {@link Collaboration#getCreatorId()}, which is a user id. Callers
 * resolve with {@code creatorProfileRepository.findByUserId(userId)} first.
 *
 * <p><b>Info barrier (SPEC.md &sect;0.3, &sect;3.8):</b> this table is the CREATOR's own data and is
 * never brand-readable. {@link #quoteJson} therefore MAY contain the creator's floor:
 * {@code CreatorToolDtos.PackageQuote} carries {@code floor_total} and {@code floor_total_value}
 * (and {@code anchor}, {@code range_*}, {@code provenance}), and the quote is stored whole. That is
 * deliberate -- the creator must be able to reopen a brief and see the same floor she was shown.
 *
 * <p>The barrier is enforced at the ONE boundary where this row's analysis becomes brand-visible:
 * {@code createSecureLink} (Phase B1, SPEC.md &sect;3.8) builds a SEPARATE {@code package_json} for
 * the link and strips {@code floor_total}, {@code anchor}, {@code range_*} and {@code provenance}
 * from it. <b>That strip is mandatory and is not already done for you here</b> -- do not read this
 * row's {@code quote_json} and hand it to a brand-facing path unstripped.
 *
 * <p>The three {@code *Json} columns are frozen SNAPSHOTS, not live views. The rate model, the risk
 * rules and the creator's own floors all move; a brief the creator read last week must keep showing
 * what she was actually shown, not what the current code would compute today.
 */
@Entity
@Table(name = "creator_briefs")
public class CreatorBrief {

    /** SPEC.md &sect;2.2 — pasted text is capped before persistence, not merely validated at the edge. */
    public static final int MAX_RAW_TEXT_LENGTH = 8000;

    public static final String EXTRACTION_SOURCE_AI = "AI";
    public static final String EXTRACTION_SOURCE_FALLBACK = "FALLBACK";

    @Id
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private BriefSource source;

    /**
     * Null for a {@link BriefSource#PASTED} brief until a secure link is redeemed and a deal is
     * created. Deliberately NOT an FK: this is the creator's own record of what she was sent, and it
     * must not be cascade-deleted when a brand's collaboration row is cleaned up.
     */
    @Column(name = "collaboration_id", length = 26)
    private String collaborationId;

    @Column(name = "raw_text", nullable = false, columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "brand_name_guess", length = 200)
    private String brandNameGuess;

    /** {@code BriefExtraction} JSON (SPEC.md &sect;2.11). */
    @Column(name = "extracted_json", columnDefinition = "TEXT")
    private String extractedJson;

    /** {@code List<RiskFlag>} JSON (SPEC.md &sect;5.2). */
    @Column(name = "risk_flags_json", columnDefinition = "TEXT")
    private String riskFlagsJson;

    /**
     * {@code PackageQuote} JSON (SPEC.md &sect;4.3), stored whole. MAY contain the creator's floor
     * ({@code floor_total}/{@code floor_total_value}) and her anchor/range/provenance — see the
     * class javadoc: the strip happens at the B1 secure-link boundary, not here.
     */
    @Column(name = "quote_json", columnDefinition = "TEXT")
    private String quoteJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BriefStatus status;

    /** {@code AI} or {@code FALLBACK} — which extraction path produced {@link #extractedJson}. */
    @Column(name = "extraction_source", length = 16)
    private String extractionSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorBrief() {}

    /**
     * SPEC.md &sect;2.2 — the paste path. Sanitizes with {@link TextSanitizer#sanitizePlainText} (a
     * pasted email body routinely carries HTML) and caps at {@link #MAX_RAW_TEXT_LENGTH} AFTER
     * sanitizing, so stripped markup does not eat into the creator's real 8000 characters.
     */
    public static CreatorBrief paste(String id, String creatorProfileId, String rawText) {
        CreatorBrief brief = new CreatorBrief();
        brief.id = id;
        brief.creatorProfileId = creatorProfileId;
        brief.source = BriefSource.PASTED;
        brief.rawText = capped(TextSanitizer.sanitizePlainText(rawText));
        brief.status = BriefStatus.NEW;
        Instant now = Instant.now();
        brief.createdAt = now;
        brief.updatedAt = now;
        return brief;
    }

    private static String capped(String sanitized) {
        if (sanitized == null) {
            return "";
        }
        return sanitized.length() > MAX_RAW_TEXT_LENGTH
                ? sanitized.substring(0, MAX_RAW_TEXT_LENGTH)
                : sanitized;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * Writes back everything one analysis pass produced and moves the brief to
     * {@link BriefStatus#ANALYZED}. All five values are written together because they are one
     * snapshot: a quote computed against one extraction must never be stored beside a different one.
     */
    public void applyAnalysis(
            String brandNameGuess,
            String extractedJson,
            String riskFlagsJson,
            String quoteJson,
            String extractionSource) {
        this.brandNameGuess = brandNameGuess;
        this.extractedJson = extractedJson;
        this.riskFlagsJson = riskFlagsJson;
        this.quoteJson = quoteJson;
        this.extractionSource = extractionSource;
        this.status = BriefStatus.ANALYZED;
        touch();
    }

    /** Meera has composed a reply for this brief. */
    public void markDrafted() {
        this.status = BriefStatus.DRAFTED;
        touch();
    }

    /** The brief became a real collaboration on Influora (secure-link redemption, Phase B1). */
    public void markSecured(String collaborationId) {
        this.collaborationId = collaborationId;
        this.status = BriefStatus.SECURED;
        touch();
    }

    /** The creator decided this brief was not worth pursuing. Reachable from any other status. */
    public void dismiss() {
        this.status = BriefStatus.DISMISSED;
        touch();
    }

    public String getId() {
        return id;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public BriefSource getSource() {
        return source;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public String getRawText() {
        return rawText;
    }

    public String getBrandNameGuess() {
        return brandNameGuess;
    }

    public String getExtractedJson() {
        return extractedJson;
    }

    public String getRiskFlagsJson() {
        return riskFlagsJson;
    }

    public String getQuoteJson() {
        return quoteJson;
    }

    public BriefStatus getStatus() {
        return status;
    }

    public String getExtractionSource() {
        return extractionSource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
