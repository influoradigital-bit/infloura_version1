package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorBriefService;
import com.influora.service.CreatorContextService;
import com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse;
import com.influora.web.dto.brief.BriefDtos.BriefListItem;
import com.influora.web.dto.brief.BriefDtos.PasteBriefRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8), B0-42 — the creator's own brief surface: paste one,
 * list them, read one, dismiss one.
 *
 * <p><b>Four routes, not the seven in SPEC.md &sect;3.8's table.</b> The three secure-link routes are
 * Phase B1. They depend on the {@code creator_secure_links} migration this phase does not ship, and
 * more to the point they are the ONE boundary where a creator's analysis becomes brand-visible — the
 * floor strip that has to guard that payload does not exist yet, so a route that produced the payload
 * would be shipping the leak and the fix in opposite phases.
 *
 * <p><b>Creator principal, resolved from the token, on every route.</b> Identity comes from
 * {@code principal.getUserId()} through {@link CreatorContextService#requireCreatorProfile} — never a
 * body or path id. A brief is the creator's private record of what a brand sent her, and a brief id is
 * the only handle on it, so ownership is enforced inside the service's queries rather than checked
 * after a fetch (see {@code CreatorBriefRepository#findByIdAndCreatorProfileId}).
 *
 * <p><b>Gate order is fixed: flag, then identity, then consent.</b> The flag first so a disabled
 * feature 404s uniformly regardless of who is asking — a 403 would tell an unauthenticated caller
 * that the feature exists. Consent last because it is the only one of the three that needs a resolved
 * creator.
 *
 * <p><b>No brand route reads anything this controller returns.</b>
 * {@link BriefAnalysisResponse#quote()} legitimately carries the creator's floor, anchor and range
 * (SPEC.md &sect;0.3) — she must be able to reopen a brief and see the same numbers she was shown.
 * That is safe only because every route here is creator-only.
 */
@RestController
@RequestMapping("/creator/briefs")
public class CreatorBriefController {

    private final CreatorBriefService briefService;
    private final CreatorContextService creatorContext;
    private final CreatorAgentPreferencesService preferencesService;
    private final MeeraCreatorFeatureProperties featureProperties;

    public CreatorBriefController(
            CreatorBriefService briefService,
            CreatorContextService creatorContext,
            CreatorAgentPreferencesService preferencesService,
            MeeraCreatorFeatureProperties featureProperties) {
        this.briefService = briefService;
        this.creatorContext = creatorContext;
        this.preferencesService = preferencesService;
        this.featureProperties = featureProperties;
    }

    /**
     * The Phase-A rollback flag, applied to every route here.
     *
     * <p><b>Copied, not inherited.</b> {@code CreatorMeeraController#requireFeatureEnabled} is
     * {@code private}, so it is neither callable nor inheritable from this class — the same reason
     * {@code CreatorMeeraToolController} carries its own copy. Called first in every handler, before
     * identity and consent.
     */
    private void requireFeatureEnabled() {
        if (!featureProperties.isCreatorEnabled()) {
            throw new ApiException(
                    "FEATURE_DISABLED", "Meera for Creators is currently disabled", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * DPDP consent precondition. {@code isConsentAccepted} also requires the stored consent version to
     * be current, so a creator who consented under a superseded notice is refused here with the same
     * 403 as one who never consented.
     *
     * <p>Runs BEFORE the paste is persisted. A brief is personal data a brand sent about a deal, and
     * storing it for a creator who has not accepted the notice would be the exact thing the notice
     * gates.
     */
    private void requireConsent(String creatorUserId) {
        if (!preferencesService.isConsentAccepted(creatorUserId)) {
            throw new ApiException(
                    "CONSENT_REQUIRED", "Consent is required before using Meera", HttpStatus.FORBIDDEN);
        }
    }

    /** The three gates in their fixed order, returning the resolved creator's user id. */
    private String requireConsentedCreator(AuthPrincipal principal) {
        requireFeatureEnabled();
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        String creatorUserId = profile.getUserId();
        requireConsent(creatorUserId);
        return creatorUserId;
    }

    /**
     * {@code POST /creator/briefs} — the paste. 201, because a row is created and its id is the handle
     * on everything that follows.
     *
     * <p>Rate-limited by the {@code creator-brief-paste} bucket (10 per window, USER-keyed): this is
     * the one creator route that spends real money per call, and an IP key would let one creator behind
     * a shared address starve another while letting a single creator on a mobile network rotate past
     * the limit.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BriefAnalysisResponse>> paste(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PasteBriefRequest body) {
        String creatorUserId = requireConsentedCreator(principal);
        BriefAnalysisResponse response = briefService.paste(creatorUserId, body.text());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** {@code GET /creator/briefs?limit=20} — my recent briefs, newest first. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<BriefListItem>>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit) {
        String creatorUserId = requireConsentedCreator(principal);
        return ResponseEntity.ok(ApiResponse.ok(briefService.list(creatorUserId, limit)));
    }

    /**
     * {@code GET /creator/briefs/{id}} — one brief, rebuilt from its frozen snapshot rather than
     * re-analysed, so reopening it shows the same numbers she decided against.
     */
    @GetMapping("/{briefId}")
    public ResponseEntity<ApiResponse<BriefAnalysisResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String briefId) {
        String creatorUserId = requireConsentedCreator(principal);
        return ResponseEntity.ok(ApiResponse.ok(briefService.get(creatorUserId, briefId)));
    }

    /** {@code POST /creator/briefs/{id}/dismiss} — 204, and idempotent. */
    @PostMapping("/{briefId}/dismiss")
    public ResponseEntity<Void> dismiss(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String briefId) {
        String creatorUserId = requireConsentedCreator(principal);
        briefService.dismiss(creatorUserId, briefId);
        return ResponseEntity.noContent().build();
    }
}
