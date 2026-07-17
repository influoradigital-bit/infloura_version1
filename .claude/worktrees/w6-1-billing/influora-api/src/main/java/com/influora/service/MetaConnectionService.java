package com.influora.service;

import com.influora.common.JsonLists;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.PlatformStat;
import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.dto.FacebookAccountsListResponse;
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
 * Mirrors the workspace-scoped revoke pattern in {@link MetaTokenStorage#revoke}.
 */
@Service
public class MetaConnectionService {

    private static final Logger log = LoggerFactory.getLogger(MetaConnectionService.class);
    private static final String PLATFORM_INSTAGRAM = "instagram";

    private final MetaOAuthTokenRepository tokenRepository;
    private final MetaTokenStorage tokenStorage;
    private final PlatformStatRepository platformStatRepository;
    private final FacebookPageClient facebookPageClient;

    public MetaConnectionService(
            MetaOAuthTokenRepository tokenRepository,
            MetaTokenStorage tokenStorage,
            PlatformStatRepository platformStatRepository,
            FacebookPageClient facebookPageClient) {
        this.tokenRepository = tokenRepository;
        this.tokenStorage = tokenStorage;
        this.platformStatRepository = platformStatRepository;
        this.facebookPageClient = facebookPageClient;
    }

    @Transactional(readOnly = true)
    public MetaConnectionStatusResponse getStatus(CreatorProfile profile, String workspaceId) {
        Optional<MetaOAuthToken> tokenRow =
                tokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        workspaceId, profile.getId());

        if (tokenRow.isEmpty() || tokenRow.get().getExpiresAt().isBefore(Instant.now())) {
            return disconnected();
        }

        MetaOAuthToken token = tokenRow.get();
        List<String> grantedScopes = JsonLists.stringListFromJson(token.getGrantedScopesJson());
        Instant connectedAt = token.getCreatedAt();

        String handle = null;
        Long followers = null;

        Optional<PlatformStat> cachedInstagram =
                platformStatRepository.findByCreatorProfileId(profile.getId()).stream()
                        .filter(ps -> PLATFORM_INSTAGRAM.equalsIgnoreCase(ps.getPlatform()))
                        .findFirst();
        if (cachedInstagram.isPresent()) {
            handle = formatHandle(cachedInstagram.get().getHandle());
            followers = cachedInstagram.get().getFollowers();
        }

        Optional<String> accessToken = tokenStorage.getValidToken(workspaceId, profile.getId());
        if (accessToken.isPresent()) {
            try {
                FacebookAccountsListResponse.InstagramBusinessAccount igAccount =
                        facebookPageClient.resolveConnectedInstagram(accessToken.get());
                if (igAccount != null) {
                    if (igAccount.username() != null) {
                        handle = formatHandle(igAccount.username());
                    }
                    if (igAccount.followersCount() != null) {
                        followers = igAccount.followersCount();
                    }
                }
            } catch (MetaApiException e) {
                log.warn(
                        "MetaConnectionService: live profile fetch failed for creator {}: {}",
                        profile.getId(),
                        e.getMessage());
            }
        }

        if (handle == null && profile.getUsername() != null && !profile.getUsername().isBlank()) {
            handle = formatHandle(profile.getUsername());
        }
        if (followers == null && profile.getTotalFollowers() > 0) {
            followers = profile.getTotalFollowers();
        }

        return new MetaConnectionStatusResponse(true, handle, followers, connectedAt, grantedScopes);
    }

    @Transactional
    public MetaDisconnectResponse disconnect(String workspaceId, String creatorProfileId) {
        tokenStorage.revoke(workspaceId, creatorProfileId);
        return new MetaDisconnectResponse(true);
    }

    private static MetaConnectionStatusResponse disconnected() {
        return new MetaConnectionStatusResponse(
                false, null, null, null, Collections.emptyList());
    }

    private static String formatHandle(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return username.startsWith("@") ? username : "@" + username;
    }
}
