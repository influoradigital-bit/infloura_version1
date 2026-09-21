package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.config.TrendFeatureGate;
import com.influora.domain.entity.Workspace;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.trendspark.TrendSparkNudgeService;
import com.influora.web.dto.trendspark.TrendSparkDtos.CallbackRequest;
import com.influora.web.dto.trendspark.TrendSparkDtos.NudgeResponse;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trend-Spark AI nudge (T4). Workspace is ALWAYS resolved via {@link BrandContextService} from
 * the authenticated principal — never trusted from a path/query param (Guardrail 2, schema lock
 * §5.2). Frontend (Ananya, T7) calls this controller only — never influora-ai directly.
 *
 * <p>T-TSOFF-0920: every method here opens with {@link TrendFeatureGate#requireEnabled()}, so
 * with trend ingest off the whole controller answers a documented {@code 404 TRENDS_DISABLED}
 * instead of the 204 it used to return — which was indistinguishable from the ordinary
 * "nothing scored above threshold today" silence. The gate runs BEFORE {@code
 * requireBrandWorkspace} and before any nudge lookup, so a disabled feature reaches neither the
 * DB nor {@code TrendSparkAiClient}.
 */
@RestController
@RequestMapping("/brand/trendspark")
public class TrendSparkController {

    private final BrandContextService brandContextService;
    private final TrendSparkNudgeService nudgeService;
    private final TrendFeatureGate trendFeatureGate;

    public TrendSparkController(
            BrandContextService brandContextService,
            TrendSparkNudgeService nudgeService,
            TrendFeatureGate trendFeatureGate) {
        this.brandContextService = brandContextService;
        this.nudgeService = nudgeService;
        this.trendFeatureGate = trendFeatureGate;
    }

    /** Returns the current nudge, or an empty 204 when Trend-Spark has nothing to say (below
     * score threshold, no active trend, or brand has no profile yet) — a silent, correct state.
     *
     * <p>{@code 404 TRENDS_DISABLED} is the different, permanent state: the feature is switched
     * off, so 204 will never become 200 no matter how long the caller waits. The SPA renders
     * nothing at all for that case rather than an empty "no trend today" slot. */
    @GetMapping("/nudge")
    public ResponseEntity<ApiResponse<NudgeResponse>> getNudge(
            @AuthenticationPrincipal AuthPrincipal principal) {
        trendFeatureGate.requireEnabled();
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        Optional<NudgeResponse> nudge = nudgeService.getNudge(workspace.getId());
        return nudge.map(n -> ResponseEntity.ok(ApiResponse.ok(n)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Click callback — stamps {@code clicked_at} on the flywheel (schema lock §1d/§4). Gated
     * too: with the feature off there is no nudge to have clicked, so accepting the callback
     * would be writing flywheel telemetry for an impossible event. */
    @PostMapping("/nudge/{nudgeId}/click")
    public ResponseEntity<ApiResponse<Void>> click(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String nudgeId) {
        trendFeatureGate.requireEnabled();
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        nudgeService.markClicked(workspace.getId(), nudgeId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** Purchase callback — stamps {@code purchased_at}/{@code purchased_video_id}. */
    @PostMapping("/nudge/{nudgeId}/purchase")
    public ResponseEntity<ApiResponse<Void>> purchase(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String nudgeId,
            @RequestBody CallbackRequest body) {
        trendFeatureGate.requireEnabled();
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        nudgeService.markPurchased(workspace.getId(), nudgeId, body == null ? null : body.videoId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
