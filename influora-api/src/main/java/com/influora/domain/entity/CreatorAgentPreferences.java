package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 1.2, A3) — one row per creator: the rate floors Meera must
 * never quote under, brand/category exclusion filters, the automation level the creator has
 * granted Meera, language/tone, working hours, and the DPDP consent timestamp (A6) that gates the
 * first Meera turn for this creator.
 *
 * <p><b>Info barrier (A7):</b> {@link #reelFloor}/{@link #storySetFloor}/{@link #postFloor} are the
 * exact numbers a BRAND-facing code path must never see (SPEC.md &sect;3.2: "Floor never shown to
 * brands"). No brand-facing service, controller, or DTO may import {@link
 * com.influora.repository.CreatorAgentPreferencesRepository} — see {@code InfoBarrierTest}.
 *
 * <p>{@code creatorId} is a {@code creator_profiles.id} (CreatorProfile id), NOT a {@code users.id}
 * — the FK in V73 references {@code creator_profiles(id)} directly, unlike {@code
 * Collaboration.creatorId} (a user id). Callers resolve the caller's {@link
 * com.influora.security.AuthPrincipal#getUserId()} to a {@link CreatorProfile} first.
 */
@Entity
@Table(name = "creator_agent_preferences")
public class CreatorAgentPreferences {

    public static final int APPROVAL_LEVEL_DRAFT_ONLY = 0;
    public static final int APPROVAL_LEVEL_ROUTINE = 1;
    public static final int APPROVAL_LEVEL_AUTO_DECLINE = 2;

    public static final String TONE_FORMAL = "FORMAL";
    public static final String TONE_FRIENDLY = "FRIENDLY";

    public static final String DEFAULT_LANGUAGE = "hi-IN";

    /**
     * Gate fix round 2, item 1 (Priya Q3) — the DPDP consent notice's current version. Bump this
     * whenever the consent text itself changes; {@link #isConsentAccepted()} compares the stored
     * {@link #consentVersion} against this constant (not just null-checking {@link
     * #consentAcceptedAt}), so a version bump forces every creator back behind {@code 403
     * CONSENT_REQUIRED} until they re-consent, even one who already accepted an older notice.
     */
    public static final String CURRENT_CONSENT_VERSION = "v1";

    public static final String DEFAULT_WORKING_HOURS_TIMEZONE = "Asia/Kolkata";
    public static final String DEFAULT_FLOOR_CURRENCY = "INR";

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_id", nullable = false, unique = true, length = 26)
    private String creatorId;

    @Column(name = "reel_floor", precision = 12, scale = 2)
    private BigDecimal reelFloor;

    @Column(name = "story_set_floor", precision = 12, scale = 2)
    private BigDecimal storySetFloor;

    @Column(name = "post_floor", precision = 12, scale = 2)
    private BigDecimal postFloor;

    /** Gate fix round 2, item 3 (Priya Q8) — ISO 4217 code the three floors above are denominated in. */
    @Column(name = "floor_currency", nullable = false, length = 3)
    private String floorCurrency;

    /** JSON array of category names, e.g. ["Alcohol", "Gambling"]. */
    @Column(name = "excluded_categories", columnDefinition = "TEXT")
    private String excludedCategoriesJson;

    /** JSON array of brand names. */
    @Column(name = "blocked_brands", columnDefinition = "TEXT")
    private String blockedBrandsJson;

    @Column(name = "approval_level", nullable = false)
    private int approvalLevel;

    @Column(name = "creator_language", nullable = false, length = 10)
    private String creatorLanguage;

    @Column(name = "brand_tone", nullable = false, length = 16)
    private String brandTone;

    @Column(name = "working_hours_start")
    private Integer workingHoursStart;

    @Column(name = "working_hours_end")
    private Integer workingHoursEnd;

    /**
     * Gate fix round 2, item 3 (Priya Q8) — IANA zone id (e.g. {@code "Asia/Kolkata"}) that {@link
     * #workingHoursStart}/{@link #workingHoursEnd} are hours-of-day IN. Without this the working
     * hours are decorative: nothing can determine whether "now" falls inside a bare 9-18 with no
     * zone attached.
     */
    @Column(name = "working_hours_timezone", nullable = false, length = 64)
    private String workingHoursTimezone;

    /** JSON array of ISO-8601 weekday numbers, 1 (Mon) - 7 (Sun). */
    @Column(name = "working_days", columnDefinition = "TEXT")
    private String workingDaysJson;

    @Column(name = "weekly_sponsored_limit")
    private Integer weeklySponsoredLimit;

    @Column(name = "represented", nullable = false)
    private boolean represented;

    @Column(name = "agency_name", length = 200)
    private String agencyName;

    /** DPDP consent (A6) — {@code null} means "not consented yet"; the first Meera turn is blocked until this is set. */
    @Column(name = "consent_accepted_at")
    private Instant consentAcceptedAt;

    /**
     * Gate fix round 2, item 1 (Priya Q3) — which DPDP notice {@link #consentAcceptedAt} was
     * recorded against. {@link #isConsentAccepted()} requires this to equal {@link
     * #CURRENT_CONSENT_VERSION}, not merely that {@link #consentAcceptedAt} is non-null.
     */
    @Column(name = "consent_version", nullable = false, length = 16)
    private String consentVersion;

    /**
     * Gate fix round 1 (Priya Q7) — per-creator override of influora-ai's default monthly AI-spend
     * cap (env {@code AI_CREATOR_MONTHLY_CAP_USD}, default USD 0.75), settable only through the
     * admin-only {@code PUT /admin/creator-agent/creators/{creatorId}/monthly-cap} endpoint (see
     * {@code AdminCreatorAgentController}) — never via the creator's own {@code
     * UpdatePreferencesRequest}, which has no field for it. {@code null} means "use the
     * process-wide default." {@link com.influora.service.meera.MeeraContextService} surfaces this
     * as {@code ai_monthly_cap_usd} on the CREATOR context payload, the exact key influora-ai's
     * {@code spend_tracker.creator_cap_override_from_context} already reads.
     */
    @Column(name = "ai_monthly_cap_usd", precision = 6, scale = 2)
    private BigDecimal aiMonthlyCapUsd;

    // ---------------------------------------------------------------------------------------
    // T-MEERA-CREATOR-PHASE-B (SPEC.md 2.1) — the six Phase-B columns, V20260910100000.
    // ---------------------------------------------------------------------------------------

    /**
     * B6 — the creator opted her rate card in to the public media kit. When false,
     * {@link #rateCardJson} is nulled rather than merely hidden (see {@link #applyRateCard}): a
     * card that is not shareable has no reason to sit in the database waiting to leak.
     *
     * <p>Info barrier (SPEC.md 0.3): this flag governs the RATE CARD only. The three
     * {@code *Floor} fields above stay never-brand-visible regardless of its value.
     */
    @Column(name = "rate_card_shareable", nullable = false)
    private boolean rateCardShareable;

    /**
     * JSON object of creator-TYPED strings, e.g. {@code {"reel":"5000","story_set":"2500"}} — not
     * parsed numbers. The creator authored them and the media kit echoes them back verbatim.
     */
    @Column(name = "rate_card_json", columnDefinition = "TEXT")
    private String rateCardJson;

    /**
     * B6 — this creator is in the 20 percent negotiation holdout: {@code estimate_my_rate} and
     * {@code check_deal_risks} still work, but COUNTER drafts and the quote anchor are withheld.
     * Assigned ONCE at row creation (SPEC.md 2.9), never toggled afterwards — a cohort a creator can
     * move in and out of measures nothing.
     */
    @Column(name = "negotiation_holdout", nullable = false)
    private boolean negotiationHoldout;

    /**
     * When the holdout lapses. {@link LocalDate}, not {@link Instant}: a holdout expires on a
     * calendar day (creation + 90), never at an instant. Null when {@link #negotiationHoldout} is
     * false. This is the first {@code LocalDate} on this entity.
     */
    @Column(name = "holdout_until")
    private LocalDate holdoutUntil;

    /**
     * B5 — how many drafts this creator has ever approved, the level-0 exit signal (10 approved
     * drafts prompts the level-1 offer). A stored counter and NOT a {@code COUNT} over
     * {@code meera_drafts}: the rule is "ever", and a count over a table whose rows can be discarded
     * would go down.
     */
    @Column(name = "approved_draft_count", nullable = false)
    private int approvedDraftCount;

    /** Null means never prompted — stops a creator who declined the level-1 offer being re-asked. */
    @Column(name = "level_up_prompted_at")
    private Instant levelUpPromptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorAgentPreferences() {}

    /**
     * Computed-defaults row, created lazily on first {@code GET /creator/agent-preferences} — see
     * {@code CreatorAgentPreferencesService#getOrCreatePreferences}. Floors/language are passed in
     * pre-computed by the service (last paid deal / RateEstimationService / CreatorProfile
     * language); everything else takes the spec's literal defaults.
     */
    public static CreatorAgentPreferences newWithDefaults(
            String id, String creatorId, BigDecimal reelFloor, BigDecimal storySetFloor,
            BigDecimal postFloor, String creatorLanguage) {
        CreatorAgentPreferences p = new CreatorAgentPreferences();
        p.id = id;
        p.creatorId = creatorId;
        p.reelFloor = reelFloor;
        p.storySetFloor = storySetFloor;
        p.postFloor = postFloor;
        p.approvalLevel = APPROVAL_LEVEL_DRAFT_ONLY;
        p.creatorLanguage = creatorLanguage != null ? creatorLanguage : DEFAULT_LANGUAGE;
        p.brandTone = TONE_FRIENDLY;
        p.represented = false;
        // Not consent — consentAcceptedAt stays null — but the NOT NULL column needs a value, and
        // it is what a subsequent recordConsent() would stamp anyway (see isConsentAccepted()).
        p.consentVersion = CURRENT_CONSENT_VERSION;
        p.workingHoursTimezone = DEFAULT_WORKING_HOURS_TIMEZONE;
        p.floorCurrency = DEFAULT_FLOOR_CURRENCY;
        Instant now = Instant.now();
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public BigDecimal getReelFloor() {
        return reelFloor;
    }

    public BigDecimal getStorySetFloor() {
        return storySetFloor;
    }

    public BigDecimal getPostFloor() {
        return postFloor;
    }

    public String getFloorCurrency() {
        return floorCurrency;
    }

    public String getExcludedCategoriesJson() {
        return excludedCategoriesJson;
    }

    public String getBlockedBrandsJson() {
        return blockedBrandsJson;
    }

    public int getApprovalLevel() {
        return approvalLevel;
    }

    public String getCreatorLanguage() {
        return creatorLanguage;
    }

    public String getBrandTone() {
        return brandTone;
    }

    public Integer getWorkingHoursStart() {
        return workingHoursStart;
    }

    public Integer getWorkingHoursEnd() {
        return workingHoursEnd;
    }

    public String getWorkingHoursTimezone() {
        return workingHoursTimezone;
    }

    public String getWorkingDaysJson() {
        return workingDaysJson;
    }

    public Integer getWeeklySponsoredLimit() {
        return weeklySponsoredLimit;
    }

    public boolean isRepresented() {
        return represented;
    }

    public String getAgencyName() {
        return agencyName;
    }

    public Instant getConsentAcceptedAt() {
        return consentAcceptedAt;
    }

    public String getConsentVersion() {
        return consentVersion;
    }

    public BigDecimal getAiMonthlyCapUsd() {
        return aiMonthlyCapUsd;
    }

    /** Gate fix round 1 (Priya Q7) — admin-only mutation; see the field's javadoc. {@code null} clears the override back to the process-wide default. */
    public void setAiMonthlyCapUsdOverride(BigDecimal aiMonthlyCapUsd) {
        this.aiMonthlyCapUsd = aiMonthlyCapUsd;
        touch();
    }

    // ---------------------------------------------------------------------------------------
    // T-MEERA-CREATOR-PHASE-B (SPEC.md 2.1) — accessors and mutators for the six columns above.
    // ---------------------------------------------------------------------------------------

    public boolean isRateCardShareable() {
        return rateCardShareable;
    }

    public String getRateCardJson() {
        return rateCardJson;
    }

    public boolean isNegotiationHoldout() {
        return negotiationHoldout;
    }

    public LocalDate getHoldoutUntil() {
        return holdoutUntil;
    }

    public int getApprovedDraftCount() {
        return approvedDraftCount;
    }

    public Instant getLevelUpPromptedAt() {
        return levelUpPromptedAt;
    }

    /**
     * SPEC.md 2.1 — applied from {@code updatePreferences} when the request carries the two rate-card
     * fields. Nulls the JSON when {@code shareable} is false rather than keeping it hidden: opting
     * out should remove the data, not merely stop rendering it.
     */
    public void applyRateCard(boolean shareable, String rateCardJson) {
        this.rateCardShareable = shareable;
        this.rateCardJson = shareable ? rateCardJson : null;
        touch();
    }

    /**
     * SPEC.md 2.9 — called ONCE, from
     * {@code CreatorAgentPreferencesService.createWithComputedDefaults}, between
     * {@link #newWithDefaults} and the save. Deliberately not called anywhere else: the holdout is a
     * measurement cohort, and a creator who can move in or out of it measures nothing.
     */
    public void assignHoldout(boolean holdout, LocalDate until) {
        this.negotiationHoldout = holdout;
        this.holdoutUntil = holdout ? until : null;
        touch();
    }

    /**
     * SPEC.md 2.1 / B5 — increments the level-0 exit counter and returns the NEW value, so the
     * caller can compare it against the level-up threshold without a second read.
     */
    public int recordApprovedDraft() {
        this.approvedDraftCount += 1;
        touch();
        return this.approvedDraftCount;
    }

    public void markLevelUpPrompted(Instant when) {
        this.levelUpPromptedAt = when;
        touch();
    }

    /**
     * Gate fix round 2, item 1 (Priya Q3) — accepted means both a timestamp AND the CURRENT notice
     * version, not merely a non-null timestamp. A creator who consented under an older version
     * (e.g. the migration-backfilled {@code "v1"} for pre-existing rows, once {@link
     * #CURRENT_CONSENT_VERSION} is bumped past it) reads as NOT accepted here, which is exactly
     * what routes them back through {@link
     * com.influora.web.CreatorMeeraController#requireConsent} into a fresh {@code 403
     * CONSENT_REQUIRED} — no separate version check needed at the controller.
     */
    public boolean isConsentAccepted() {
        return consentAcceptedAt != null && CURRENT_CONSENT_VERSION.equals(consentVersion);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * PUT /creator/agent-preferences — full replace of every creator-editable field (SPEC.md 2.3).
     * {@code approvalLevel} validated by the caller against {0,1,2}; {@code represented=true}
     * requires a non-blank {@code agencyName}, also validated by the caller before this is called.
     */
    public void applyPreferences(
            BigDecimal reelFloor,
            BigDecimal storySetFloor,
            BigDecimal postFloor,
            String floorCurrency,
            String excludedCategoriesJson,
            String blockedBrandsJson,
            int approvalLevel,
            String creatorLanguage,
            String brandTone,
            Integer workingHoursStart,
            Integer workingHoursEnd,
            String workingHoursTimezone,
            String workingDaysJson,
            Integer weeklySponsoredLimit,
            boolean represented,
            String agencyName) {
        this.reelFloor = reelFloor;
        this.storySetFloor = storySetFloor;
        this.postFloor = postFloor;
        this.floorCurrency = floorCurrency != null ? floorCurrency : DEFAULT_FLOOR_CURRENCY;
        this.excludedCategoriesJson = excludedCategoriesJson;
        this.blockedBrandsJson = blockedBrandsJson;
        this.approvalLevel = approvalLevel;
        this.creatorLanguage = creatorLanguage;
        this.brandTone = brandTone;
        this.workingHoursStart = workingHoursStart;
        this.workingHoursEnd = workingHoursEnd;
        this.workingHoursTimezone = workingHoursTimezone != null ? workingHoursTimezone : DEFAULT_WORKING_HOURS_TIMEZONE;
        this.workingDaysJson = workingDaysJson;
        this.weeklySponsoredLimit = weeklySponsoredLimit;
        this.represented = represented;
        this.agencyName = represented ? agencyName : null;
        touch();
    }

    /**
     * A6 — DPDP consent, recorded against {@link #CURRENT_CONSENT_VERSION}. Idempotent for a
     * creator who already accepted the CURRENT version (no-op on the timestamp); but a creator
     * whose stored {@link #consentVersion} is stale (an older notice) re-stamps both the timestamp
     * and the version — same re-consent path a first-time consent takes (gate fix round 2, item 1,
     * Priya Q3: a text change must be able to force re-consent, not silently keep old acceptances
     * valid forever).
     */
    public void recordConsent() {
        if (this.consentAcceptedAt == null || !CURRENT_CONSENT_VERSION.equals(this.consentVersion)) {
            this.consentAcceptedAt = Instant.now();
            this.consentVersion = CURRENT_CONSENT_VERSION;
            touch();
        }
    }

    /**
     * A6 (fix round 1, item 4) — DPDP requires consent withdrawal to be as easy as giving it.
     * Nulls {@link #consentAcceptedAt}, which puts this creator straight back behind the {@code
     * 403 CONSENT_REQUIRED} gate on their next Meera turn. Idempotent: withdrawing when already
     * withdrawn is a no-op on the timestamp/updatedAt.
     */
    public void withdrawConsent() {
        if (this.consentAcceptedAt != null) {
            this.consentAcceptedAt = null;
            touch();
        }
    }
}
