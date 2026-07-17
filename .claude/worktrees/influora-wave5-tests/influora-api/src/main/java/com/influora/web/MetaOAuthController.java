package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaOAuthStateStore;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.CreatorProfileRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.meta.MetaDtos.MetaAuthorizeResponse;
import com.influora.web.dto.meta.MetaDtos.MetaCallbackResponse;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Meta (Instagram/Facebook) OAuth connect flow for creators (Week 1-2 backend spec §1.5/§6.3).
 *
 * <p>{@code /authorize} returns the dialog URL as JSON (matching this codebase's {@code
 * ApiResponse} envelope convention) rather than issuing a server-side 302 — the SPA frontend
 * navigates the browser itself. {@code /callback} is the one endpoint Meta redirects the browser
 * to directly, so it also returns a normal JSON {@code ApiResponse}; the frontend route at {@code
 * redirect-uri} reads the result and forwards the user, rather than this controller performing a
 * second redirect Meta didn't ask for.
 */
@RestController
@RequestMapping("/meta/oauth")
public class MetaOAuthController {

    private final MetaOAuthService oAuthService;
    private final MetaTokenStorage tokenStorage;
    private final MetaOAuthStateStore stateStore;
    private final CreatorProfileRepository creatorProfileRepository;

    public MetaOAuthController(
            MetaOAuthService oAuthService,
            MetaTokenStorage tokenStorage,
            MetaOAuthStateStore stateStore,
            CreatorProfileRepository creatorProfileRepository) {
        this.oAuthService = oAuthService;
        this.tokenStorage = tokenStorage;
        this.stateStore = stateStore;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    /** Mints a CSRF-bound state token and returns the Meta authorization dialog URL. */
    @GetMapping("/authorize")
    public ApiResponse<MetaAuthorizeResponse> authorize(@AuthenticationPrincipal AuthPrincipal principal) {
        requireCreator(principal);
        String state = stateStore.issue(principal.getUserId());
        return ApiResponse.ok(new MetaAuthorizeResponse(oAuthService.buildAuthorizationUrl(state), state));
    }

    /**
     * Handles Meta's redirect back with {@code code}/{@code state}. Exchanges the code for a
     * short-lived token, immediately upgrades it to a long-lived token, then stores it encrypted
     * scoped to the caller's own workspace + creator profile.
     */
    @GetMapping("/callback")
    public ApiResponse<MetaCallbackResponse> callback(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String code,
            @RequestParam String state) {
        requireCreator(principal);

        if (!stateStore.consume(state, principal.getUserId())) {
            throw new ApiException(
                    "META_OAUTH_STATE_INVALID", "OAuth state is invalid, expired, or already used", HttpStatus.BAD_REQUEST);
        }

        CreatorProfile creatorProfile =
                creatorProfileRepository
                        .findByUserId(principal.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "No creator profile for this user",
                                                HttpStatus.NOT_FOUND));

        MetaTokenResponse shortLived = oAuthService.exchangeCodeForToken(code);
        MetaTokenResponse longLived = oAuthService.exchangeForLongLivedToken(shortLived.accessToken());

        Instant expiresAt =
                Instant.now()
                        .plusSeconds(longLived.expiresInSeconds() != null ? longLived.expiresInSeconds() : 0);

        tokenStorage.storeToken(
                creatorProfile.getId(),
                principal.getWorkspaceId(),
                longLived.accessToken(),
                expiresAt,
                MetaOAuthService.REQUIRED_SCOPES);

        return ApiResponse.ok(new MetaCallbackResponse(true, MetaOAuthService.REQUIRED_SCOPES));
    }

    private void requireCreator(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.CREATOR) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        }
    }
}
