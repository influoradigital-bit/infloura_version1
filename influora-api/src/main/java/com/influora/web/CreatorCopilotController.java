package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.config.TrendFeatureGate;
import com.influora.domain.entity.CreatorProfile;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.creatorcopilot.CreatorNudgeService;
import com.influora.service.creatorcopilot.CreatorNudgeService.SuggestionResult;
import com.influora.web.dto.creatorcopilot.CreatorCopilotDtos.SuggestionTodayResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator AI Co-pilot Tier-1 (API-CONTRACT.md, frozen v1). Identity is ALWAYS resolved via {@link
 * CreatorContextService#requireCreatorProfile(AuthPrincipal)} from the authenticated principal —
 * never trusted from a path/body param (Guardrail 2, Kabir gate threat-1: PASS). The {@code {id}}
 * path param on dismiss/acted is the *suggestion* id only; ownership is enforced inside {@code
 * CreatorNudgeLogRepository.findByIdAndCreatorProfileId}, never by trusting the path.
 *
 * <p><b>T-TSOFF-0920 — one documented exception to the frozen "always 200" contract below.</b>
 * The daily suggestion is trend-derived: {@code CreatorNudgeService#getSuggestion} scores the
 * creator's theme tags against {@code TrendRepository.findActive}. With trend ingest off that
 * list is permanently empty, so the endpoint could only ever answer {@code no_suggestion_today}
 * — the same wire value it sends on a normal day when nothing matched. The SPA cannot tell those
 * apart, and it showed the difference: it rendered "No new idea today — check back tomorrow" for
 * a feature that has no tomorrow. So when {@link TrendFeatureGate#isEnabled()} is false every
 * method here answers {@code 404 TRENDS_DISABLED} instead, and the SPA renders an honest
 * "not available yet" state. API-CONTRACT.md §1.1's rule still governs the ENABLED feature
 * verbatim: "nothing to say today" is still a 200, never a 4xx.
 */
@RestController
@RequestMapping("/creator/copilot")
public class CreatorCopilotController {

    private final CreatorContextService creatorContext;
    private final CreatorNudgeService nudgeService;
    private final TrendFeatureGate trendFeatureGate;

    public CreatorCopilotController(
            CreatorContextService creatorContext,
            CreatorNudgeService nudgeService,
            TrendFeatureGate trendFeatureGate) {
        this.creatorContext = creatorContext;
        this.nudgeService = nudgeService;
        this.trendFeatureGate = trendFeatureGate;
    }

    /** "Nothing to say today" is always a 200 success with {@code status:
     * 'pending_tagging' | 'no_suggestion_today'} and {@code suggestion: null} — never a 4xx/204
     * (API-CONTRACT.md §1.1). Dismissed/acted suggestions still return here as {@code 'ready'} for
     * the rest of the day — the FE's sessionStorage marker is what collapses the card client-side. */
    @GetMapping("/suggestion/today")
    public ResponseEntity<ApiResponse<SuggestionTodayResponse>> getToday(
            @AuthenticationPrincipal AuthPrincipal principal) {
        trendFeatureGate.requireEnabled();
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        SuggestionResult result = nudgeService.getSuggestion(profile.getId());
        return ResponseEntity.ok(
                ApiResponse.ok(new SuggestionTodayResponse(result.suggestion(), result.status())));
    }

    /** Stamps {@code dismissed_at} (idempotent — a second call on an already-dismissed row is a
     * no-op). {@code SUGGESTION_NOT_FOUND} (404) whether the id doesn't exist or isn't the
     * caller's — same response either way, per IDOR discipline. */
    @PostMapping("/suggestion/{id}/dismiss")
    public ResponseEntity<ApiResponse<Void>> dismiss(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        trendFeatureGate.requireEnabled();
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        nudgeService.markDismissed(profile.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** Stamps {@code acted_at} — identical shape/discipline to {@link #dismiss}. */
    @PostMapping("/suggestion/{id}/acted")
    public ResponseEntity<ApiResponse<Void>> acted(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        trendFeatureGate.requireEnabled();
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        nudgeService.markActed(profile.getId(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
