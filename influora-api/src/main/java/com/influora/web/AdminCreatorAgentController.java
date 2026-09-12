package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.admin.CreatorAgentBaselineService;
import com.influora.service.admin.CreatorAgentRateCalibrationService;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.BaselinesResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.MonthlyCapResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.RateCalibrationResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.SetMonthlyCapRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.1, A1) — {@code GET /admin/creator-agent/baselines}, pulled
 * before Swapnil approves the rest of the Meera-for-Creators rollout. Mounted under {@code
 * /admin/**}, so admin auth is enforced structurally by {@code SecurityConfig}'s {@code
 * hasRole("ADMIN")} matcher, same as every other {@code Admin*Controller} — no per-method check
 * needed here. Returns a raw DTO (no {@link com.influora.common.ApiResponse} envelope), matching
 * {@code AdminCreatorController}'s deliberate deviation for the admin console's client contract.
 */
@RestController
@RequestMapping("/admin/creator-agent")
public class AdminCreatorAgentController {

    private final CreatorAgentBaselineService baselineService;
    private final CreatorAgentPreferencesService preferencesService;
    private final CreatorAgentRateCalibrationService rateCalibrationService;

    public AdminCreatorAgentController(
            CreatorAgentBaselineService baselineService,
            CreatorAgentPreferencesService preferencesService,
            CreatorAgentRateCalibrationService rateCalibrationService) {
        this.baselineService = baselineService;
        this.preferencesService = preferencesService;
        this.rateCalibrationService = rateCalibrationService;
    }

    @GetMapping("/baselines")
    public BaselinesResponse getBaselines(@AuthenticationPrincipal AuthPrincipal principal) {
        return baselineService.getBaselines();
    }

    /**
     * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;14.1.g, B0-35) — the rate calibration report: per tier,
     * the benchmark constant the quote path prices from, what creators in that tier actually closed
     * at, and what Meera has been quoting.
     *
     * <p>Bare DTO like every other route on this controller (see the class javadoc). Admin auth is
     * the structural {@code hasRole("ADMIN")} matcher on {@code /admin/**}; the specific route is
     * pinned in {@code SecurityConfigMatcherTest} rather than re-checked here, because a per-method
     * check would be the second copy of a rule this controller does not own.
     *
     * <p><b>The response carries no workspace id and no creator id</b> — the realised figures come
     * from a cross-tenant projection under Kabir's k-anonymity gate and reach the wire only as a
     * median, two counts and a fraction. {@code AdminCreatorAgentControllerTest} asserts the
     * payload's key set against an allow-list so a later "just add the top workspace" cannot.
     */
    @GetMapping("/rate-calibration")
    public RateCalibrationResponse getRateCalibration(@AuthenticationPrincipal AuthPrincipal principal) {
        return rateCalibrationService.getRateCalibration();
    }

    /**
     * Gate fix round 1 (Priya Q7) — admin override of the per-creator monthly AI-spend cap.
     * {@code creatorId} is {@code creator_profiles.id} (the admin console already has this from
     * the creator list/detail view — see {@code AdminCreatorController}), never a raw user id.
     * {@code ai_monthly_cap_usd: null} in the body clears the override back to the process-wide
     * default. See spend_tracker.py's {@code creator_cap_override_from_context}, which this
     * closes the previously-nonexistent write side of.
     */
    @PutMapping("/creators/{creatorId}/monthly-cap")
    public MonthlyCapResponse setMonthlyCap(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String creatorId,
            @Valid @RequestBody SetMonthlyCapRequest req) {
        return new MonthlyCapResponse(
                preferencesService.adminSetMonthlyCapOverride(creatorId, req.aiMonthlyCapUsd()));
    }
}
