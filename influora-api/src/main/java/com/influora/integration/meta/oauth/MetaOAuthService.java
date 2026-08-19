package com.influora.integration.meta.oauth;

import com.influora.config.MetaApiProperties;
import com.influora.integration.meta.dto.InstagramShortLivedTokenResponse;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Meta OAuth flow orchestration: authorization URL construction, code-for-token exchange,
 * short-lived-to-long-lived token exchange, and refresh (spec §1.5).
 *
 * <p>This service never persists tokens itself — callers pass the resulting {@link
 * MetaTokenResponse} to {@link MetaTokenStorage#storeToken}.
 */
@Service
public class MetaOAuthService {

    private static final Logger log = LoggerFactory.getLogger(MetaOAuthService.class);

    /**
     * Required OAuth scopes for full functionality (spec §1.7).
     *
     * <p>CR-115 — {@code pages_read_engagement} removed. It was requested and granted but
     * {@link com.influora.integration.meta.client.FacebookPageClient#getPage} — the only client
     * method that scope backs — had zero production callers anywhere in the codebase; no job or
     * controller ever surfaces Facebook Page engagement data. Least-privilege: don't ask a creator
     * to grant a permission the product doesn't use yet. {@code pages_show_list} stays — it backs
     * {@code resolveConnectedInstagram}'s {@code GET /me/accounts} call, which is live.
     */
    public static final List<String> REQUIRED_SCOPES =
            List.of(
                    "instagram_basic", // Basic profile and media access
                    "instagram_manage_insights", // Insights and demographics
                    "pages_show_list" // List connected Facebook Pages
                    );

    /**
     * Scopes for Business Login for Instagram (T-IGLOGIN-0820). These are NOT the Facebook-Login
     * names with a prefix — they are a separate vocabulary, and sending
     * {@link #REQUIRED_SCOPES} to the Instagram authorize dialog fails. No {@code pages_show_list}
     * equivalent exists or is needed: this path has no Facebook Page to enumerate.
     */
    public static final List<String> INSTAGRAM_LOGIN_SCOPES =
            List.of(
                    "instagram_business_basic", // Profile and media access
                    "instagram_business_manage_insights" // Insights and demographics
                    );

    private final MetaApiProperties props;

    // Nullable until first use (or injected by the test constructor). Built lazily rather than
    // in the container constructor for the same reason as MetaGraphApiClient: RestClient.build()
    // spins up the JDK HttpClient (selector thread + NIO pipe), and application boot must not
    // depend on outbound-HTTP plumbing that only OAuth flows ever exercise.
    private volatile RestClient restClient;

    // @Autowired is required, not decorative: with the test constructor below also present,
    // Spring has two candidates and refuses to guess ("No default constructor found" at boot).
    @org.springframework.beans.factory.annotation.Autowired
    public MetaOAuthService(MetaApiProperties props) {
        this.props = props;
    }

    /** Package-private test constructor for injecting mocked RestClient. */
    MetaOAuthService(MetaApiProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    private RestClient restClient() {
        RestClient client = restClient;
        if (client == null) {
            synchronized (this) {
                if (restClient == null) {
                    restClient = RestClient.builder().build();
                }
                client = restClient;
            }
        }
        return client;
    }

    /** Builds the Meta OAuth dialog URL the brand/creator is redirected to. */
    public String buildAuthorizationUrl(String state) {
        return "https://www.facebook.com/"
                + props.getGraphApiVersion()
                + "/dialog/oauth"
                + "?client_id="
                + urlEncode(props.getAppId())
                + "&redirect_uri="
                + urlEncode(props.getRedirectUri())
                + "&scope="
                + urlEncode(String.join(",", REQUIRED_SCOPES))
                + "&response_type=code"
                + "&state="
                + urlEncode(state);
    }

    /**
     * Exchanges an authorization code for a short-lived access token (~1-2 hours). Callers must
     * immediately exchange the result via {@link #exchangeForLongLivedToken(String)} — this
     * service never stores the short-lived token.
     */
    public MetaTokenResponse exchangeCodeForToken(String code) {
        String url =
                "https://graph.facebook.com/"
                        + props.getGraphApiVersion()
                        + "/oauth/access_token"
                        + "?client_id="
                        + urlEncode(props.getAppId())
                        + "&client_secret="
                        + urlEncode(props.getAppSecret())
                        + "&redirect_uri="
                        + urlEncode(props.getRedirectUri())
                        + "&code="
                        + urlEncode(code);
        return fetchToken(url, "code-exchange");
    }

    /** Exchanges a short-lived token for a long-lived token (~60 days). */
    public MetaTokenResponse exchangeForLongLivedToken(String shortLivedToken) {
        String url =
                "https://graph.facebook.com/"
                        + props.getGraphApiVersion()
                        + "/oauth/access_token"
                        + "?grant_type=fb_exchange_token"
                        + "&client_id="
                        + urlEncode(props.getAppId())
                        + "&client_secret="
                        + urlEncode(props.getAppSecret())
                        + "&fb_exchange_token="
                        + urlEncode(shortLivedToken);
        return fetchToken(url, "long-lived-exchange");
    }

    /**
     * Refreshes a long-lived token before expiry. Call when a token has fewer than {@code
     * influora.meta.token-refresh-days-before-expiry} days remaining. Meta reuses the same
     * fb_exchange_token endpoint for both the initial long-lived exchange and subsequent refreshes.
     */
    public MetaTokenResponse refreshLongLivedToken(String currentToken) {
        return exchangeForLongLivedToken(currentToken);
    }

    /**
     * Builds the Business Login for Instagram authorization URL (T-IGLOGIN-0820) — the path for a
     * creator whose Instagram professional account is NOT linked to a Facebook Page. Note the host
     * is instagram.com, not facebook.com, and it is unversioned.
     */
    public String buildInstagramAuthorizationUrl(String state) {
        return "https://www.instagram.com/oauth/authorize"
                + "?client_id="
                + urlEncode(props.getInstagramAppId())
                + "&redirect_uri="
                + urlEncode(props.getInstagramRedirectUri())
                + "&scope="
                + urlEncode(String.join(",", INSTAGRAM_LOGIN_SCOPES))
                + "&response_type=code"
                + "&state="
                + urlEncode(state);
    }

    /**
     * Exchanges an Instagram authorization code for a short-lived token (~1 hour).
     *
     * <p>Unlike the Facebook exchange this is a form-encoded POST to api.instagram.com, and the
     * response carries {@code user_id} — the Instagram user id, which this path has no other way
     * to obtain. Callers must keep it and immediately exchange the token via
     * {@link #exchangeInstagramForLongLivedToken(String)}.
     */
    public InstagramShortLivedTokenResponse exchangeInstagramCodeForToken(String code) {
        String form =
                "client_id="
                        + urlEncode(props.getInstagramAppId())
                        + "&client_secret="
                        + urlEncode(props.getInstagramAppSecret())
                        + "&grant_type=authorization_code"
                        + "&redirect_uri="
                        + urlEncode(props.getInstagramRedirectUri())
                        + "&code="
                        + urlEncode(code);
        try {
            return restClient()
                    .post()
                    .uri("https://api.instagram.com/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(InstagramShortLivedTokenResponse.class);
        } catch (RestClientResponseException e) {
            log.error(
                    "Meta OAuth instagram-code-exchange failed: status={}, body={}",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            throw new MetaApiException("Meta OAuth instagram-code-exchange failed", e);
        }
    }

    /** Exchanges a short-lived Instagram token for a long-lived one (~60 days). */
    public MetaTokenResponse exchangeInstagramForLongLivedToken(String shortLivedToken) {
        String url =
                "https://graph.instagram.com/access_token"
                        + "?grant_type=ig_exchange_token"
                        + "&client_secret="
                        + urlEncode(props.getInstagramAppSecret())
                        + "&access_token="
                        + urlEncode(shortLivedToken);
        return fetchToken(url, "instagram-long-lived-exchange");
    }

    /**
     * Refreshes a long-lived Instagram token. A DIFFERENT endpoint from the Facebook path, which
     * reuses its long-lived exchange for refresh — do not collapse the two.
     */
    public MetaTokenResponse refreshInstagramLongLivedToken(String currentToken) {
        String url =
                "https://graph.instagram.com/refresh_access_token"
                        + "?grant_type=ig_refresh_token"
                        + "&access_token="
                        + urlEncode(currentToken);
        return fetchToken(url, "instagram-refresh");
    }

    private MetaTokenResponse fetchToken(String url, String opName) {
        try {
            return restClient().get().uri(url).retrieve().body(MetaTokenResponse.class);
        } catch (RestClientResponseException e) {
            log.error(
                    "Meta OAuth {} failed: status={}, body={}",
                    opName,
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            throw new MetaApiException("Meta OAuth " + opName + " failed", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
