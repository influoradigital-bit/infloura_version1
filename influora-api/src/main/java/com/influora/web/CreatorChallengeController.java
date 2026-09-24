package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.CreatorProfile;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorChallengeService;
import com.influora.service.CreatorContextService;
import com.influora.web.dto.challenge.ChallengeDtos.ChallengeState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator 7-day challenge (CHALLENGE-SPEC.md, Swapnil 2026-09-23) -- {@code GET/POST
 * /api/v1/creator/challenge} and {@code POST /api/v1/creator/challenge/{id}/end}. Creator
 * principal, resolved from the token via {@link CreatorContextService#requireCreatorProfile} on
 * every route -- never a body/path id -- same pattern as {@code CreatorBriefController}.
 *
 * <p>Everything date-like is Asia/Kolkata (CHALLENGE-SPEC.md). {@code today} and {@code now} are
 * both resolved here, once per request, and threaded through to {@link CreatorChallengeService} --
 * the service itself never calls {@code LocalDate.now()}/{@code Instant.now()}, same discipline as
 * {@code CreatorPostingPatternService#analyse}.
 */
@RestController
@RequestMapping("/creator/challenge")
public class CreatorChallengeController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CreatorChallengeService challengeService;
    private final CreatorContextService creatorContext;

    public CreatorChallengeController(
            CreatorChallengeService challengeService, CreatorContextService creatorContext) {
        this.challengeService = challengeService;
        this.creatorContext = creatorContext;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ChallengeState>> get(@AuthenticationPrincipal AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        ChallengeState state = challengeService.getState(profile, today(), Instant.now());
        return ResponseEntity.ok(ApiResponse.ok(state));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChallengeState>> start(@AuthenticationPrincipal AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        ChallengeState state = challengeService.start(profile, today(), Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(state));
    }

    /** Returns the updated {@link ChallengeState} ({@code active: null}) -- matches {@code
     * src/lib/api.ts}'s {@code creatorChallenge.end}, which expects the same body shape as
     * GET/POST, not 204. */
    @PostMapping("/{id}/end")
    public ResponseEntity<ApiResponse<ChallengeState>> end(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        ChallengeState state = challengeService.end(profile, id, today(), Instant.now());
        return ResponseEntity.ok(ApiResponse.ok(state));
    }

    private static LocalDate today() {
        return LocalDate.now(IST);
    }
}
