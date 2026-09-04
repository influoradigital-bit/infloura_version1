package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.service.PublicCreatorService;
import com.influora.web.dto.creator.PublicCreatorDtos.VerifiedProfileResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.8, A9) — public, unauthenticated creator verified-metrics
 * page ({@code /c/:username/verified} on the frontend). Mounted under {@code /public/**};
 * {@code SecurityConfig} carries an explicit {@code permitAll} for this exact route (see its
 * "T-MEERA-CREATOR-PHASE-A" comment) — a logged-out visitor/crawler cannot present a JWT, same
 * reasoning as the existing {@code /portfolio/*} permit.
 */
@RestController
@RequestMapping("/public/creators")
public class PublicCreatorController {

    private final PublicCreatorService publicCreatorService;
    private final MeeraCreatorFeatureProperties featureProperties;

    public PublicCreatorController(
            PublicCreatorService publicCreatorService, MeeraCreatorFeatureProperties featureProperties) {
        this.publicCreatorService = publicCreatorService;
        this.featureProperties = featureProperties;
    }

    @GetMapping("/{username}/verified")
    public ResponseEntity<ApiResponse<VerifiedProfileResponse>> getVerifiedMetrics(
            @PathVariable String username) {
        // Priya gate review defect 4 — Phase A rollback flag. Checked first, before the
        // unauthenticated service call below, so a disabled feature 404s without touching the
        // database at all.
        if (!featureProperties.isCreatorEnabled()) {
            throw new ApiException(
                    "FEATURE_DISABLED", "Meera for Creators is currently disabled", HttpStatus.NOT_FOUND);
        }
        // Gate fix round 1 (Priya Q10) — this endpoint is unauthenticated and un-parameterized by
        // any secret, so nothing in this repo previously stopped an intermediary (a CDN page rule,
        // a shared proxy cache) from serving a stale snapshot after a creator opts out or is
        // suspended — PublicCreatorService's 404-for-both behavior only controls THIS server's
        // response, not what a cache in front of it might keep replaying. `no-store` means an
        // opt-out/suspension takes effect on the very next request, from any client.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.ok(publicCreatorService.getVerifiedMetrics(username)));
    }
}
