package com.influora.service;

import com.influora.common.JsonLists;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.client.MetaGraphApiClient;
import com.influora.integration.meta.dto.FacebookAccountsListResponse;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.web.dto.meta.MetaDtos.MetaConnectionStatusResponse;
import com.influora.web.dto.meta.MetaDtos.MetaDisconnectResponse;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meta/Instagram connection status and disconnect for creators (CREATOR_EXEC_PLAN_PRIYA.md §2.3).
 *
 * <p>Creator-owned key-space only ({@code workspace_id IS NULL}) — matches {@link
 * com.influora.service.creatorcopilot.CreatorMetaOAuthService#connect} and {@link
 * com.influora.web.MetaOAuthController#callback}, which always store the creator's token via
 * {@link MetaTokenStorage#storeCreatorToken}, never the workspace-scoped {@link
 * MetaTokenStorage#storeToken}. A CREATOR-type principal has no {@code workspaceId} (see {@code
 * MetaOAuthController} javadoc), so this class must use the creator-scoped repository/storage
 * methods throughout — {@link MetaTokenStorage#revokeCreatorToken}, not the brand/workspace-scoped
 * {@link MetaTokenStorage#revoke}, which silently no-ops for every creator-owned row (CR-106).
 */
@Service
public class MetaConnectionService {

    private static final Logger log = LoggerFactory.getLogger(MetaConnectionService.class);
    private static final String PLATFORM_INSTAGRAM = "instagram";

    /**
     * {@code /me}, not {@code /{ig-user-id}}, so a row whose stored account id is missing still
     * resolves. Only fields the status response shows, all four listed for the Instagram-Login
     * user node in Meta's get-started reference — that node does not support every field the
     * Facebook-Login one does, and one unsupported field fails the whole request (F-0950).
     */
    static final String INSTAGRAM_LOGIN_PROFILE_PATH =
            "/me?fields=username,followers_count,media_count,profile_picture_url";

    private final MetaOAuthTokenRepository tokenRepository;
    private final MetaTokenStorage tokenStorage;
    private final PlatformStatRepository platformStatRepository;
    private final FacebookPageClient facebookPageClient;
    private final MetaGraphApiClient graphApiClient;

    public MetaConnectionService(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            PlatformStatRepository platformStatRepository,
            FacebookPageClient facebookPageClient,
            MetaGraphApiClient graphApiClient) {
        this.tokenRepository = tokenRepository;
        this.tokenStorage = tokenStorage;
        this.platformStatRepository = platformStatRepository;
        this.facebookPageClient = facebookPageClient;
        this.graphApiClient = graphApiClient;
    }

    @Transactional(readOnly = true)
    public MetaConnectionStatusResponse getStatus(CreatorProfile profile) {
        Optional<MetaOAuthToken> tokenRow =
                tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(
                        profile.getId());

        if (tokenRow.isEmpty() || tokenRow.get().getExpiresAt().isBefore(Instant.now())) {
            return disconnected();
        }

        MetaOAuthToken token = tokenRow.get();
        List<String> grantedScopes = JsonLists.stringListFromJson(token.getGrantedScopesJson());
        Instant connectedAt = token.getCreatedAt();

        String handle = null;
        Long followers = null;
        String profilePictureUrl = null;
        Long mediaCount = null;

        Optional<PlatformStat> cachedInstagram =
                platformStatRepository.findByCreatorProfileId(profile.getId()).stream()
                        .filter(ps -> PLATFORM_INSTAGRAM.equalsIgnoreCase(ps.getPlatform()))
                        .findFirst();
        if (cachedInstagram.isPresent()) {
            handle = formatHandle(cachedInstagram.get().getHandle());
            followers = cachedInstagram.get().getFollowers();
        }

        Optional<String> accessToken = tokenStorage.getValidCreatorToken(profile.getId());
        if (accessToken.isPresent()) {
            try {
                String liveUsername;
                Long liveFollowers;
                if (token.getAuthPath() == MetaAuthPath.INSTAGRAM_LOGIN) {
                    // F-0890 — an Instagram-Login token has no Facebook Page behind it and is only
                    // accepted by graph.instagram.com. Sending it to graph.facebook.com
                    // /me/accounts (the Facebook branch below) failed every status call with
                    // `190 "Invalid OAuth access token - Cannot parse access token"`, observed on
                    // the first ever successful Instagram-Login connect (2026-09-17 10:48 UTC).
                    InstagramUserResponse igUser =
                            graphApiClient.get(
                                    INSTAGRAM_LOGIN_PROFILE_PATH,
                                    accessToken.get(),
                                    InstagramUserResponse.class,
                                    rateLimitKey(token, profile),
                                    MetaAuthPath.INSTAGRAM_LOGIN);
                    liveUsername = igUser != null ? igUser.username() : null;
                    liveFollowers = igUser != null ? igUser.followersCount() : null;
                    profilePictureUrl = igUser != null ? igUser.profilePictureUrl() : null;
                    mediaCount = igUser != null ? igUser.mediaCount() : null;
                } else {
                    FacebookAccountsListResponse.InstagramBusinessAccount igAccount =
                            facebookPageClient.resolveConnectedInstagram(accessToken.get());
                    liveUsername = igAccount != null ? igAccount.username() : null;
                    liveFollowers = igAccount != null ? igAccount.followersCount() : null;
                }
                if (liveUsername != null) {
                    handle = formatHandle(liveUsername);
                }
                if (liveFollowers != null) {
                    followers = liveFollowers;
                }
            } catch (MetaApiException e) {
                log.warn(
                        "MetaConnectionService: live profile fetch failed for creator {}: {}",
                        profile.getId(),
                        e.getMessage());
            }
        }

        // F-0950 — no fallback to the creator's Influora username or all-platform follower total.
        // Those used to fill `handle`/`followers` when the live read failed, labelled as the
        // Instagram account. Harmless while no screen displayed these fields; now that the
        // Settings card does, a creator whose Instagram is @a but whose Influora page is /b would
        // be shown "@b" as their connected Instagram. The cached Instagram platform stat above is
        // a real Instagram value and stays; beyond it, null ("not known yet") is the honest answer.

        return new MetaConnectionStatusResponse(
                true,
                handle,
                followers,
                connectedAt,
                grantedScopes,
                token.getAuthPath() != null ? token.getAuthPath().name() : null,
                profilePictureUrl,
                mediaCount);
    }

    @Transactional
    public MetaDisconnectResponse disconnect(String creatorProfileId) {
        tokenStorage.revokeCreatorToken(creatorProfileId);
        return new MetaDisconnectResponse(true);
    }

    private static MetaConnectionStatusResponse disconnected() {
        return new MetaConnectionStatusResponse(
                false, null, null, null, Collections.emptyList(), null, null, null);
    }

    /** The Instagram account id when stored; the creator id otherwise, so throttling still keys per creator. */
    private static String rateLimitKey(MetaOAuthToken token, CreatorProfile profile) {
        String igId = token.getIgBusinessAccountId();
        return igId != null && !igId.isBlank() ? igId : profile.getId();
    }

    private static String formatHandle(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return username.startsWith("@") ? username : "@" + username;
    }
}
