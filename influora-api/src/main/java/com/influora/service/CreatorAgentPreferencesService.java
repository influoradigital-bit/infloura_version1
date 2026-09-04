package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorAgentPreferencesRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.RateEstimationService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.UpdatePreferencesRequest;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.2/2.3, A3). Owns the ONE {@link CreatorAgentPreferences} row
 * per creator: computed-on-first-read defaults, the PUT full-replace, and DPDP consent (A6 —
 * {@code recordConsent} lives here rather than a separate service since it mutates the same row).
 *
 * <p><b>Info barrier (A7):</b> this is the ONLY brand-reachable... no — this class is
 * CREATOR-facing only. It must never be imported by a brand-facing class (service/meera/brand*,
 * any {@code *Brand*}-named class) — see {@code InfoBarrierTest}. The floors it reads/writes are
 * exactly the numbers SPEC.md &sect;3.2 says must never reach a brand.
 */
@Service
public class CreatorAgentPreferencesService {

    /** Risk-table fallback (SPEC.md &sect;9) — used only when RateEstimationService also resolves to 0. */
    private static final BigDecimal FALLBACK_REEL_FLOOR = new BigDecimal("500");

    private static final BigDecimal FALLBACK_STORY_FLOOR = new BigDecimal("300");
    private static final BigDecimal FALLBACK_POST_FLOOR = new BigDecimal("600");

    private final CreatorAgentPreferencesRepository preferencesRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CollaborationRepository collaborationRepository;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final RateEstimationService rateEstimationService;

    public CreatorAgentPreferencesService(
            CreatorAgentPreferencesRepository preferencesRepository,
            CreatorProfileRepository creatorProfileRepository,
            CollaborationRepository collaborationRepository,
            CreatorMetricsRepository creatorMetricsRepository,
            RateEstimationService rateEstimationService) {
        this.preferencesRepository = preferencesRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.collaborationRepository = collaborationRepository;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.rateEstimationService = rateEstimationService;
    }

    /** Resolves the caller's {@code creator_profiles} row — every method below needs it first. */
    public CreatorProfile requireCreatorProfile(String userId) {
        return creatorProfileRepository
                .findByUserId(userId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public PreferencesResponse getOrCreatePreferences(String userId) {
        CreatorProfile profile = requireCreatorProfile(userId);
        CreatorAgentPreferences prefs =
                preferencesRepository
                        .findByCreatorId(profile.getId())
                        .orElseGet(() -> createWithComputedDefaults(profile));
        return toResponse(prefs);
    }

    /**
     * A6 (fix round 2, item 1) — the consent PRECONDITION {@link
     * com.influora.web.CreatorMeeraController} must check before persisting anything for a Meera
     * turn (session start or send). Deliberately read-only and does NOT lazily create a
     * preferences row (unlike {@link #getOrCreatePreferences}/{@link #recordConsent}) — a creator
     * who has never touched Meera has no row, which correctly means "not consented" without side
     * effects on what is, at this point, still a rejected request.
     */
    @Transactional(readOnly = true)
    public boolean isConsentAccepted(String userId) {
        CreatorProfile profile = requireCreatorProfile(userId);
        return preferencesRepository
                .findByCreatorId(profile.getId())
                .map(CreatorAgentPreferences::isConsentAccepted)
                .orElse(false);
    }

    private CreatorAgentPreferences createWithComputedDefaults(CreatorProfile profile) {
        BigDecimal floor = computeDefaultFloor(profile);
        String language = defaultLanguage(profile);
        CreatorAgentPreferences prefs =
                CreatorAgentPreferences.newWithDefaults(
                        Ulids.newUlid(), profile.getId(), floor, floor, floor, language);
        return preferencesRepository.save(prefs);
    }

    /**
     * SPEC.md 2.2 step 2 / &sect;9 risk table: last COMPLETED collaboration's {@code agreedRate}
     * when one exists, else {@link RateEstimationService#estimate}'s low end, else the hardcoded
     * platform-minimum fallback if that also resolves to zero (an unconnected creator with no
     * metrics). Applied uniformly to all three floors (reel/story-set/post) — Phase A has no
     * per-deliverable-type rate history to differentiate them from.
     */
    private BigDecimal computeDefaultFloor(CreatorProfile profile) {
        Optional<BigDecimal> lastCompletedRate =
                collaborationRepository.findByCreatorId(profile.getUserId()).stream()
                        .filter(c -> c.getStatus() == CollaborationStatus.COMPLETED)
                        .filter(c -> c.getAgreedRate() != null)
                        .max(Comparator.comparing(Collaboration::getUpdatedAt))
                        .map(Collaboration::getAgreedRate);
        if (lastCompletedRate.isPresent() && lastCompletedRate.get().compareTo(BigDecimal.ZERO) > 0) {
            return lastCompletedRate.get();
        }

        Optional<CreatorMetric> latestMetric =
                creatorMetricsRepository
                        .findByCreatorProfileIdOrderByTimeDesc(profile.getId(), PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        List<String> categories = JsonLists.stringListFromJson(profile.getCategoriesJson());
        BigDecimal estimated =
                rateEstimationService.estimate(latestMetric, QualityScoreResult.absent(), categories).min();
        if (estimated != null && estimated.compareTo(BigDecimal.ZERO) > 0) {
            return estimated;
        }
        return FALLBACK_REEL_FLOOR;
    }

    private static String defaultLanguage(CreatorProfile profile) {
        List<String> languages = JsonLists.stringListFromJson(profile.getLanguagesJson());
        return languages.isEmpty() ? CreatorAgentPreferences.DEFAULT_LANGUAGE : languages.get(0);
    }

    @Transactional
    public PreferencesResponse updatePreferences(String userId, UpdatePreferencesRequest req) {
        CreatorProfile profile = requireCreatorProfile(userId);
        validate(req);
        CreatorAgentPreferences prefs =
                preferencesRepository.findByCreatorId(profile.getId()).orElseGet(() -> createWithComputedDefaults(profile));

        prefs.applyPreferences(
                req.reelFloor(),
                req.storySetFloor(),
                req.postFloor(),
                req.floorCurrency() != null ? req.floorCurrency() : CreatorAgentPreferences.DEFAULT_FLOOR_CURRENCY,
                JsonLists.toJson(req.excludedCategories()),
                JsonLists.toJson(req.blockedBrands()),
                req.approvalLevel(),
                req.creatorLanguage() != null ? req.creatorLanguage() : CreatorAgentPreferences.DEFAULT_LANGUAGE,
                req.brandTone() != null ? req.brandTone() : CreatorAgentPreferences.TONE_FRIENDLY,
                req.workingHoursStart(),
                req.workingHoursEnd(),
                req.workingHoursTimezone() != null
                        ? req.workingHoursTimezone()
                        : CreatorAgentPreferences.DEFAULT_WORKING_HOURS_TIMEZONE,
                JsonLists.toJson(intListToStringList(req.workingDays())),
                req.weeklySponsoredLimit(),
                req.represented(),
                req.agencyName());
        preferencesRepository.save(prefs);
        return toResponse(prefs);
    }

    private static void validate(UpdatePreferencesRequest req) {
        if (req.approvalLevel() < CreatorAgentPreferences.APPROVAL_LEVEL_DRAFT_ONLY
                || req.approvalLevel() > CreatorAgentPreferences.APPROVAL_LEVEL_AUTO_DECLINE) {
            throw new ApiException("INVALID_APPROVAL_LEVEL", "approval_level must be 0, 1 or 2", HttpStatus.BAD_REQUEST);
        }
        if (req.brandTone() != null
                && !CreatorAgentPreferences.TONE_FORMAL.equals(req.brandTone())
                && !CreatorAgentPreferences.TONE_FRIENDLY.equals(req.brandTone())) {
            throw new ApiException("INVALID_BRAND_TONE", "brand_tone must be FORMAL or FRIENDLY", HttpStatus.BAD_REQUEST);
        }
        if (req.represented() && (req.agencyName() == null || req.agencyName().isBlank())) {
            throw new ApiException(
                    "AGENCY_NAME_REQUIRED", "agency_name is required when represented=true", HttpStatus.BAD_REQUEST);
        }
        if (req.reelFloor() != null && req.reelFloor().compareTo(BigDecimal.ZERO) < 0
                || req.storySetFloor() != null && req.storySetFloor().compareTo(BigDecimal.ZERO) < 0
                || req.postFloor() != null && req.postFloor().compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException("INVALID_FLOOR", "Rate floors must be >= 0", HttpStatus.BAD_REQUEST);
        }
        // Gate fix round 2, item 3 (Priya Q8) — reject a currency code that cannot round-trip
        // rather than silently persisting a value nothing downstream can interpret.
        if (req.floorCurrency() != null) {
            try {
                java.util.Currency.getInstance(req.floorCurrency());
            } catch (IllegalArgumentException e) {
                throw new ApiException(
                        "INVALID_CURRENCY", "floor_currency must be a valid ISO 4217 code", HttpStatus.BAD_REQUEST);
            }
        }
        if (req.workingHoursTimezone() != null) {
            try {
                java.time.ZoneId.of(req.workingHoursTimezone());
            } catch (java.time.DateTimeException e) {
                throw new ApiException(
                        "INVALID_TIMEZONE", "working_hours_timezone must be a valid IANA zone id", HttpStatus.BAD_REQUEST);
            }
        }
    }

    private static List<String> intListToStringList(List<Integer> ints) {
        return ints == null ? null : ints.stream().map(String::valueOf).toList();
    }

    /** A6 — creates the row with computed defaults first if this creator has never touched Meera. */
    @Transactional
    public com.influora.web.dto.creator.CreatorAgentDtos.ConsentResponse recordConsent(String userId) {
        CreatorProfile profile = requireCreatorProfile(userId);
        CreatorAgentPreferences prefs =
                preferencesRepository.findByCreatorId(profile.getId()).orElseGet(() -> createWithComputedDefaults(profile));
        prefs.recordConsent();
        preferencesRepository.save(prefs);
        return new com.influora.web.dto.creator.CreatorAgentDtos.ConsentResponse(
                prefs.getConsentAcceptedAt(), prefs.getConsentVersion());
    }

    /**
     * A6 (fix round 1, item 4) — DPDP requires withdrawal to be as easy as giving consent. A
     * creator who never consented (no row, or a row with {@code consentAcceptedAt} already null)
     * gets a no-op, not a 404 — withdrawing something that was never granted is still a
     * successful "you are not consented" outcome, not an error.
     */
    @Transactional
    public void withdrawConsent(String userId) {
        CreatorProfile profile = requireCreatorProfile(userId);
        preferencesRepository
                .findByCreatorId(profile.getId())
                .ifPresent(
                        prefs -> {
                            prefs.withdrawConsent();
                            preferencesRepository.save(prefs);
                        });
    }

    /**
     * Gate fix round 1 (Priya Q7) — admin-only override of the per-creator monthly AI-spend cap
     * (influora-ai's {@code spend_tracker} reads this back via {@code
     * MeeraContextService#assembleCreatorContext}'s {@code ai_monthly_cap_usd} field). Deliberately
     * a SEPARATE method from {@link #updatePreferences} — that method is reachable only via the
     * creator's own {@code PUT /creator/agent-preferences} route and {@link
     * UpdatePreferencesRequest} has no cap field at all, so a creator can never raise their own
     * cap through this service; only {@code AdminCreatorAgentController} (a {@code hasRole("ADMIN")}
     * route) calls this. {@code capUsd == null} clears the override back to the process-wide
     * default. Identifies the creator by {@code creator_profiles.id} directly (the admin console
     * already has this id from the creator list/detail view), not by {@code userId} — unlike every
     * other method on this service, which resolves from the CALLING creator's own principal.
     */
    @Transactional
    public BigDecimal adminSetMonthlyCapOverride(String creatorProfileId, BigDecimal capUsd) {
        if (capUsd != null && capUsd.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(
                    "INVALID_CAP", "ai_monthly_cap_usd must be >= 0", HttpStatus.BAD_REQUEST);
        }
        CreatorProfile profile =
                creatorProfileRepository
                        .findById(creatorProfileId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.NOT_FOUND));
        CreatorAgentPreferences prefs =
                preferencesRepository.findByCreatorId(profile.getId()).orElseGet(() -> createWithComputedDefaults(profile));
        prefs.setAiMonthlyCapUsdOverride(capUsd);
        preferencesRepository.save(prefs);
        return prefs.getAiMonthlyCapUsd();
    }

    private PreferencesResponse toResponse(CreatorAgentPreferences prefs) {
        List<Integer> workingDays =
                JsonLists.stringListFromJson(prefs.getWorkingDaysJson()).stream().map(Integer::parseInt).toList();
        return new PreferencesResponse(
                prefs.getReelFloor(),
                prefs.getStorySetFloor(),
                prefs.getPostFloor(),
                prefs.getFloorCurrency(),
                JsonLists.stringListFromJson(prefs.getExcludedCategoriesJson()),
                JsonLists.stringListFromJson(prefs.getBlockedBrandsJson()),
                prefs.getApprovalLevel(),
                prefs.getCreatorLanguage(),
                prefs.getBrandTone(),
                prefs.getWorkingHoursStart(),
                prefs.getWorkingHoursEnd(),
                prefs.getWorkingHoursTimezone(),
                workingDays,
                prefs.getWeeklySponsoredLimit(),
                prefs.isRepresented(),
                prefs.getAgencyName(),
                prefs.isConsentAccepted(),
                prefs.getConsentVersion());
    }
}
