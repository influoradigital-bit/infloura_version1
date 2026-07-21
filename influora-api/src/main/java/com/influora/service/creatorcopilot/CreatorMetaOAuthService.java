package com.influora.service.creatorcopilot;

import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.dto.FacebookAccountsListResponse.InstagramBusinessAccount;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin orchestrator for the creator-owned Meta OAuth connect step (be-services-plan.md §3 item 5)
 * — gives Kabir one seam to audit instead of three call sites inline in the controller. Wraps the
 * fully-generic {@link MetaOAuthService} (stateless URL-building/token-exchange, zero workspace/
 * creator awareness — nothing brand-specific to fork here) + {@link
 * MetaTokenStorage#storeCreatorToken} + the NO_BUSINESS_ACCOUNT detection (API-CONTRACT.md §4.2).
 *
 * <p><b>NO_BUSINESS_ACCOUNT is a 200 success with a field, not a thrown error</b> (API-CONTRACT.md
 * §4.2 ruling) — the OAuth code exchange itself genuinely succeeded (Meta issued a valid token);
 * only the linked account type is wrong for co-pilot purposes. The token is still stored either
 * way (a personal-account connect is a real, valid token — just one that can't fetch IG business
 * captions yet); {@code connected=false} in the {@link ConnectResult} is what tells the caller this
 * connection is not yet usable for the co-pilot, without discarding a token the creator might
 * later upgrade (e.g. converting to a Business/Creator account on Instagram's side).
 */
@Service
public class CreatorMetaOAuthService {

    private static final Logger log = LoggerFactory.getLogger(CreatorMetaOAuthService.class);

    /** {@code accountType} values — API-CONTRACT.md §4.2. */
    private static final String ACCOUNT_TYPE_PERSONAL = "personal";

    private static final String ACCOUNT_TYPE_BUSINESS = "business";

    private final MetaOAuthService oAuthService;
    private final MetaTokenStorage tokenStorage;
    private final FacebookPageClient facebookPageClient;

    public CreatorMetaOAuthService(
            MetaOAuthService oAuthService,
            MetaTokenStorage tokenStorage,
            FacebookPageClient facebookPageClient) {
        this.oAuthService = oAuthService;
        this.tokenStorage = tokenStorage;
        this.facebookPageClient = facebookPageClient;
    }

    /** {@code accountType} is {@code "personal" | "business"}, matching API-CONTRACT.md §4.2's
     * {@code MetaCallbackResponse.accountType} exactly — the controller maps this 1:1. */
    public record ConnectResult(boolean connected, List<String> grantedScopes, String accountType) {}

    /**
     * Exchanges the OAuth code for a long-lived token, stores it against the creator (never a
     * workspace), and resolves whether a usable IG Business/Creator account is linked.
     */
    @Transactional
    public ConnectResult connect(String creatorProfileId, String code) {
        MetaTokenResponse shortLived = oAuthService.exchangeCodeForToken(code);
        MetaTokenResponse longLived = oAuthService.exchangeForLongLivedToken(shortLived.accessToken());

        Instant expiresAt =
                Instant.now()
                        .plusSeconds(longLived.expiresInSeconds() != null ? longLived.expiresInSeconds() : 0);

        InstagramBusinessAccount igAccount = resolveIgAccountSafely(longLived.accessToken(), creatorProfileId);
        String igBusinessAccountId = igAccount != null ? igAccount.id() : null;

        tokenStorage.storeCreatorToken(
                creatorProfileId,
                longLived.accessToken(),
                expiresAt,
                MetaOAuthService.REQUIRED_SCOPES,
                igBusinessAccountId);

        if (igAccount == null) {
            return new ConnectResult(false, MetaOAuthService.REQUIRED_SCOPES, ACCOUNT_TYPE_PERSONAL);
        }
        return new ConnectResult(true, MetaOAuthService.REQUIRED_SCOPES, ACCOUNT_TYPE_BUSINESS);
    }

    /** {@link FacebookPageClient#resolveConnectedInstagram} is a live Graph API call — a
     * transient failure here must not fail the whole connect flow (the token exchange already
     * succeeded and is worth keeping); treat it the same as "no business account resolved yet",
     * same resilience discipline {@code MetaConnectionService.getStatus} already uses for this
     * exact call. */
    private InstagramBusinessAccount resolveIgAccountSafely(String accessToken, String creatorProfileId) {
        try {
            return facebookPageClient.resolveConnectedInstagram(accessToken);
        } catch (MetaApiException e) {
            log.warn(
                    "CreatorMetaOAuthService: IG business account resolution failed for creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
            return null;
        }
    }
}
