package com.influora.service.creatorcopilot;

import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.dto.FacebookAccountsListResponse.InstagramBusinessAccount;
import com.influora.integration.meta.dto.MetaPermissionsResponse;
import com.influora.integration.meta.dto.InstagramShortLivedTokenResponse;
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

    /**
     * CR-114 — {@code MetaOAuthService.exchangeForLongLivedToken}'s javadoc documents Meta's
     * long-lived token as valid ~60 days; used ONLY as the fallback when Meta's response
     * unexpectedly omits {@code expires_in}. Before this fix, a missing {@code expires_in}
     * computed {@code expiresAt = Instant.now().plusSeconds(0)} — an already-expired token,
     * stored and returned as if the connect succeeded, silently excluding that creator from
     * every downstream job's validity filter with no error anywhere.
     */
    private static final long DEFAULT_LONG_LIVED_TOKEN_LIFETIME_SECONDS = 60L * 24 * 60 * 60;

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
     * {@code MetaCallbackResponse.accountType} exactly — the controller maps this 1:1.
     *
     * <p>{@code grantedScopes} (CR-104) is the REAL set of scopes Meta's {@code /me/permissions}
     * reports as granted for this token — never {@code MetaOAuthService.REQUIRED_SCOPES}, which
     * is only what the dialog *requested*; a creator can decline individual permissions there.
     * {@code null} means the permissions check itself failed (network/Meta API issue) and the
     * true grant set is unknown — callers must render that as "unknown", never silently fall
     * back to claiming the full requested set was granted. */
    public record ConnectResult(boolean connected, List<String> grantedScopes, String accountType) {}

    /**
     * Exchanges the OAuth code for a long-lived token, stores it against the creator (never a
     * workspace), and resolves whether a usable IG Business/Creator account is linked.
     */
    @Transactional
    public ConnectResult connect(String creatorProfileId, String code) {
        return connect(creatorProfileId, code, MetaAuthPath.FACEBOOK_LOGIN);
    }

    /**
     * As above, for a specific Instagram Platform configuration (T-IGLOGIN-0820).
     *
     * <p>The Instagram-Login branch is NOT the Facebook branch with different URLs. Its code
     * exchange is a form POST returning a different body; the Instagram user id arrives IN that
     * body rather than being resolved from a Page; and {@code FacebookPageClient} must never be
     * called, because on this path there is no Facebook Page to call it about.
     */
    @Transactional
    public ConnectResult connect(String creatorProfileId, String code, MetaAuthPath authPath) {
        if (authPath == MetaAuthPath.INSTAGRAM_LOGIN) {
            return connectViaInstagramLogin(creatorProfileId, code);
        }
        MetaTokenResponse shortLived = oAuthService.exchangeCodeForToken(code);
        MetaTokenResponse longLived = oAuthService.exchangeForLongLivedToken(shortLived.accessToken());

        // CR-114 — a null expires_in used to compute Instant.now() (0-second lifetime), silently
        // storing an already-expired token. Meta's long-lived exchange is documented ~60 days;
        // fall back to that rather than "already expired" for a response shape that should not
        // happen but must not silently disable the creator's whole Meta pipeline if it does.
        if (longLived.expiresInSeconds() == null) {
            log.warn(
                    "CreatorMetaOAuthService: Meta long-lived token exchange for creator {} omitted"
                            + " expires_in; falling back to the documented ~60-day default instead of"
                            + " storing an already-expired token",
                    creatorProfileId);
        }
        long expiresInSeconds =
                longLived.expiresInSeconds() != null
                        ? longLived.expiresInSeconds()
                        : DEFAULT_LONG_LIVED_TOKEN_LIFETIME_SECONDS;
        Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);

        InstagramBusinessAccount igAccount = resolveIgAccountSafely(longLived.accessToken(), creatorProfileId);
        String igBusinessAccountId = igAccount != null ? igAccount.id() : null;

        List<String> grantedScopes = resolveGrantedScopesSafely(longLived.accessToken(), creatorProfileId);

        tokenStorage.storeCreatorToken(
                creatorProfileId,
                longLived.accessToken(),
                expiresAt,
                grantedScopes,
                igBusinessAccountId,
                MetaAuthPath.FACEBOOK_LOGIN);

        if (igAccount == null) {
            return new ConnectResult(false, grantedScopes, ACCOUNT_TYPE_PERSONAL);
        }
        return new ConnectResult(true, grantedScopes, ACCOUNT_TYPE_BUSINESS);
    }

    /**
     * Business Login for Instagram connect — the path for a creator with no linked Facebook Page.
     *
     * <p>{@code connected=false} here means Meta returned no {@code user_id} with the exchange.
     * That is the only failure mode available on this path: there is no Page lookup that can come
     * back empty, which is what {@code ACCOUNT_TYPE_PERSONAL} represents on the Facebook branch.
     * Business Login is only offered to professional accounts, so a successful exchange is by
     * definition a professional account.
     */
    private ConnectResult connectViaInstagramLogin(String creatorProfileId, String code) {
        InstagramShortLivedTokenResponse shortLived = oAuthService.exchangeInstagramCodeForToken(code);
        MetaTokenResponse longLived =
                oAuthService.exchangeInstagramForLongLivedToken(shortLived.accessToken());

        if (longLived.expiresInSeconds() == null) {
            log.warn(
                    "CreatorMetaOAuthService: Instagram long-lived exchange for creator {} omitted"
                            + " expires_in; falling back to the documented ~60-day default",
                    creatorProfileId);
        }
        long expiresInSeconds =
                longLived.expiresInSeconds() != null
                        ? longLived.expiresInSeconds()
                        : DEFAULT_LONG_LIVED_TOKEN_LIFETIME_SECONDS;
        Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);

        // The code exchange is the ONLY source of both of these on this path — there is no
        // /me/accounts to resolve an id from and no /me/permissions call in this flow.
        String igUserId = shortLived.userId();
        List<String> grantedScopes =
                shortLived.permissions() != null ? shortLived.permissions() : List.of();

        tokenStorage.storeCreatorToken(
                creatorProfileId,
                longLived.accessToken(),
                expiresAt,
                grantedScopes,
                igUserId,
                MetaAuthPath.INSTAGRAM_LOGIN);

        if (igUserId == null || igUserId.isBlank()) {
            log.warn(
                    "CreatorMetaOAuthService: Instagram code exchange for creator {} returned no"
                            + " user_id; token stored but no account id to call Graph with",
                    creatorProfileId);
            return new ConnectResult(false, grantedScopes, ACCOUNT_TYPE_PERSONAL);
        }
        return new ConnectResult(true, grantedScopes, ACCOUNT_TYPE_BUSINESS);
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

    /**
     * CR-104 — resolves the REAL granted-scope set via {@link FacebookPageClient#fetchPermissions},
     * not {@code MetaOAuthService.REQUIRED_SCOPES}. That constant is only what the authorize URL
     * requested (see {@code MetaOAuthService#buildAuthorizationUrl}); Meta's consent dialog lets a
     * user approve some scopes and decline others, and only {@code GET /me/permissions} reflects
     * which is which. Previously this method didn't exist at all — {@code connect} stored and
     * returned {@code REQUIRED_SCOPES} unconditionally, so a creator who declined a permission
     * still saw it listed as granted in Settings.
     *
     * <p>Same resilience discipline as {@link #resolveIgAccountSafely}: a transient Graph API
     * failure here must not fail the whole connect flow (the token exchange already succeeded).
     * Unlike that method, the failure fallback here is deliberately {@code null} ("unknown"), not
     * an empty list and not {@code REQUIRED_SCOPES} — either of those would misrepresent what was
     * actually verified. {@code null} is the caller's/UI's signal to render "could not verify
     * permissions" rather than asserting a specific (and possibly wrong) grant state.
     */
    private List<String> resolveGrantedScopesSafely(String accessToken, String creatorProfileId) {
        try {
            MetaPermissionsResponse response = facebookPageClient.fetchPermissions(accessToken);
            if (response == null || response.data() == null) {
                return null;
            }
            return response.data().stream()
                    .filter(MetaPermissionsResponse.Permission::isGranted)
                    .map(MetaPermissionsResponse.Permission::permission)
                    .toList();
        } catch (MetaApiException e) {
            log.warn(
                    "CreatorMetaOAuthService: granted-permissions check failed for creator {}: {}",
                    creatorProfileId,
                    e.getMessage());
            return null;
        }
    }
}
