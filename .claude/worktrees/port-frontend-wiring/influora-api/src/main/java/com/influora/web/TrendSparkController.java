package com.influora.web;

import com.influora.common.ApiResponse;
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
 */
@RestController
@RequestMapping("/brand/trendspark")
public class TrendSparkController {

    private final BrandContextService brandContextService;
    private final TrendSparkNudgeService nudgeService;

    public TrendSparkController(
            BrandContextService brandContextService, TrendSparkNudgeService nudgeService) {
        this.brandContextService = brandContextService;
        this.nudgeService = nudgeService;
    }

    /** Returns the current nudge, or an empty 204 when Trend-Spark has nothing to say (below
     * score threshold, no active trend, or brand has no profile yet) — a silent, correct state. */
    @GetMapping("/nudge")
    public ResponseEntity<ApiResponse<NudgeResponse>> getNudge(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        Optional<NudgeResponse> nudge = nudgeService.getNudge(workspace.getId());
        return nudge.map(n -> ResponseEntity.ok(ApiResponse.ok(n)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Click callback — stamps {@code clicked_at} on the flywheel (schema lock §1d/§4). */
    @PostMapping("/nudge/{nudgeId}/click")
    public ResponseEntity<ApiResponse<Void>> click(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String nudgeId) {
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
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        nudgeService.markPurchased(workspace.getId(), nudgeId, body == null ? null : body.videoId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
