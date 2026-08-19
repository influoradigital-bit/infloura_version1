package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaOAuthStateStore;
import com.influora.repository.CreatorProfileRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.MetaConnectionService;
import com.influora.service.creatorcopilot.CreatorMetaOAuthService;
import com.influora.service.creatorcopilot.CreatorMetaOAuthService.ConnectResult;
import com.influora.web.dto.meta.MetaDtos.MetaAuthorizeResponse;
import com.influora.web.dto.meta.MetaDtos.MetaCallbackResponse;
import com.influora.web.dto.meta.MetaDtos.MetaConnectionStatusResponse;
import com.influora.web.dto.meta.MetaDtos.MetaDisconnectResponse;
import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaAuthPath;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final MetaOAuthStateStore stateStore;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorMetaOAuthService creatorMetaOAuthService;
    private final MetaConnectionService metaConnectionService;
    private final MetaApiProperties metaApiProperties;

    public MetaOAuthController(
            MetaOAuthService oAuthService,
            MetaOAuthStateStore stateStore,
            CreatorProfileRepository creatorProfileRepository,
            CreatorMetaOAuthService creatorMetaOAuthService,
            MetaConnectionService metaConnectionService,
            MetaApiProperties metaApiProperties) {
        this.oAuthService = oAuthService;
        this.stateStore = stateStore;
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorMetaOAuthService = creatorMetaOAuthService;
        this.metaConnectionService = metaConnectionService;
        this.metaApiProperties = metaApiProperties;
    }

    /** Mints a CSRF-bound state token and returns the Meta authorization dialog URL. */
    @GetMapping("/authorize")
    public ApiResponse<MetaAuthorizeResponse> authorize(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "authPath", required = false) MetaAuthPath authPath) {
        requireCreator(principal);

        // T-IGLOGIN-0820. Defaulting to FACEBOOK_LOGIN keeps every existing client working
        // unchanged; the creator-facing UI sends INSTAGRAM_LOGIN when the creator says they have
        // no Facebook Page linked to their Instagram account.
        MetaAuthPath resolved = authPath != null ? authPath : MetaAuthPath.FACEBOOK_LOGIN;

        // Fail loudly rather than handing back an authorize URL with a blank client_id — Meta
        // answers that with "Invalid app ID", which reads to the creator as their account being
        // broken rather than as this deploy simply not having the Instagram app configured.
        if (resolved == MetaAuthPath.INSTAGRAM_LOGIN && !metaApiProperties.isInstagramLoginConfigured()) {
            throw new ApiException(
                    "META_INSTAGRAM_LOGIN_NOT_CONFIGURED",
                    "Connecting without a Facebook Page is not available on this environment",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        String state = stateStore.issue(principal.getUserId(), resolved);
        String url =
                resolved == MetaAuthPath.INSTAGRAM_LOGIN
                        ? oAuthService.buildInstagramAuthorizationUrl(state)
                        : oAuthService.buildAuthorizationUrl(state);
        return ApiResponse.ok(new MetaAuthorizeResponse(url, state));
    }

    /**
     * Handles Meta's redirect back with {@code code}/{@code state}. Exchanges the code for a
     * short-lived token, immediately upgrades it to a long-lived token, then stores it encrypted
     * scoped to the caller's creator profile only — NEVER {@code principal.getWorkspaceId()}
     * (Creator AI Co-pilot Tier-1 OAuth-flip fix, be-services-plan.md §0/§3).
     *
     * <p><b>Verified live bug, now fixed:</b> a CREATOR-type {@link AuthPrincipal} has no {@code
     * workspaceId} (see {@code security/AnalyticsUsageCapInterceptor.java}'s own comment). Before
     * this fix, this method called {@code tokenStorage.storeToken(creatorProfile.getId(),
     * principal.getWorkspaceId(), ...)} with a {@code null} workspace against a column that was
     * {@code NOT NULL} at the time — every creator who ever completed this callback got a {@code
     * DataIntegrityViolationException} on the insert. This path could never have succeeded. It now
     * delegates to {@link CreatorMetaOAuthService#connect}, which stores the token as
     * creator-owned (nullable {@code workspace_id}, V20260721150000) and resolves whether a usable
     * IG Business/Creator account is linked (API-CONTRACT.md §4.2).
     */
    @GetMapping("/callback")
    public ApiResponse<MetaCallbackResponse> callback(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String code,
            @RequestParam String state) {
        requireCreator(principal);

        // The path is recovered from the state token, not from a request parameter: Meta's
        // redirect carries only code and state, and trusting a client-supplied path here would let
        // a caller drive the wrong token exchange for a state it did not initiate.
        MetaAuthPath authPath =
                stateStore
                        .consumePath(state, principal.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "META_OAUTH_STATE_INVALID",
                                                "OAuth state is invalid, expired, or already used",
                                                HttpStatus.BAD_REQUEST));

        CreatorProfile creatorProfile =
                creatorProfileRepository
                        .findByUserId(principal.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "No creator profile for this user",
                                                HttpStatus.NOT_FOUND));

        ConnectResult result = creatorMetaOAuthService.connect(creatorProfile.getId(), code, authPath);

        return ApiResponse.ok(
                new MetaCallbackResponse(result.connected(), result.grantedScopes(), result.accountType()));
    }

    /**
     * Current connection status for the caller's own creator profile (CR-106). Resolves the
     * profile from the authenticated principal — never from a client-supplied id — same
     * resolve-then-scope pattern as {@link #callback}.
     */
    @GetMapping("/status")
    public ApiResponse<MetaConnectionStatusResponse> status(
            @AuthenticationPrincipal AuthPrincipal principal) {
        CreatorProfile profile = requireCreatorProfile(principal);
        return ApiResponse.ok(metaConnectionService.getStatus(profile));
    }

    /**
     * Disconnects the caller's own Meta/Instagram connection (CR-106). Revokes the creator-owned
     * token row via {@link MetaConnectionService#disconnect}, which calls the creator-scoped
     * {@code MetaTokenStorage#revokeCreatorToken} — not the workspace-scoped {@code revoke}, which
     * would silently no-op since creator rows always have {@code workspace_id IS NULL}.
     */
    @PostMapping("/disconnect")
    public ApiResponse<MetaDisconnectResponse> disconnect(
            @AuthenticationPrincipal AuthPrincipal principal) {
        CreatorProfile profile = requireCreatorProfile(principal);
        return ApiResponse.ok(metaConnectionService.disconnect(profile.getId()));
    }

    private void requireCreator(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.CREATOR) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        }
    }

    /** Resolves the calling creator's own profile — the only source of {@code creatorProfileId}
     * for these routes, so a client can never pass someone else's id (IDOR-safe by construction). */
    private CreatorProfile requireCreatorProfile(AuthPrincipal principal) {
        requireCreator(principal);
        return creatorProfileRepository
                .findByUserId(principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND",
                                        "No creator profile for this user",
                                        HttpStatus.NOT_FOUND));
    }
}
