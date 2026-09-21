package com.influora.integration.meta.oauth;

import com.influora.integration.http.OutboundRestClients;
import com.influora.common.ApiException;
import com.influora.config.MetaApiProperties;
import com.influora.config.MetaRedirectUri;
import com.influora.integration.meta.dto.InstagramShortLivedTokenResponse;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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

    /**
     * The SPA origin, used only to derive a redirect URI when none is configured (see {@link
     * MetaRedirectUri#resolve}). Injected rather than read off {@code MetaApiProperties} because it
     * is not a Meta property — it is the same {@code influora.web-base-url} the Shopify redirect
     * default is built from.
     */
    private final String webBaseUrl;

    // Nullable until first use (or injected by the test constructor). Built lazily rather than
    // in the container constructor for the same reason as MetaGraphApiClient: RestClient.build()
    // spins up the JDK HttpClient (selector thread + NIO pipe), and application boot must not
    // depend on outbound-HTTP plumbing that only OAuth flows ever exercise.
    private volatile RestClient restClient;

    // @Autowired is required, not decorative: with the test constructor below also present,
    // Spring has two candidates and refuses to guess ("No default constructor found" at boot).
    @org.springframework.beans.factory.annotation.Autowired
    public MetaOAuthService(
            MetaApiProperties props, @Value("${influora.web-base-url}") String webBaseUrl) {
        this.props = props;
        this.webBaseUrl = webBaseUrl;
    }

    /** Package-private test constructor for injecting mocked RestClient. */
    MetaOAuthService(MetaApiProperties props, String webBaseUrl, RestClient restClient) {
        this.props = props;
        this.webBaseUrl = webBaseUrl;
        this.restClient = restClient;
    }

    /**
     * The {@code redirect_uri} for this auth path — the SAME value for the dialog and for the code
     * exchange, which Meta requires to match byte-for-byte. All four call sites go through here so
     * they cannot drift, and so the impossible-configuration check below cannot be bypassed by one
     * of them.
     *
     * @throws ApiException 503 if the configured value points at the API's own callback path, a
     *     configuration that answers every Meta redirect with {@code UNAUTHENTICATED} (see {@link
     *     MetaRedirectUri}). Failing here is what turns a dead end the creator discovers AFTER
     *     granting permissions into a refusal before the dialog opens.
     */
    String resolveRedirectUri(boolean instagramLogin) {
        String configured =
                instagramLogin ? props.getInstagramRedirectUri() : props.getRedirectUri();
        String effective = MetaRedirectUri.resolve(configured, webBaseUrl);
        if (MetaRedirectUri.pointsAtApiCallback(effective)) {
            log.error(
                    "Meta {} redirect-uri is set to this API's own callback path ({}). Meta redirects"
                        + " the BROWSER there, which carries no Authorization header, so every connect"
                        + " returns UNAUTHENTICATED. Point it at the SPA route {} and add that exact"
                        + " URL to the Meta app's Valid OAuth Redirect URIs.",
                    instagramLogin ? "INSTAGRAM_LOGIN" : "FACEBOOK_LOGIN",
                    effective,
                    MetaRedirectUri.CREATOR_CALLBACK_PATH);
            throw new ApiException(
                    "META_REDIRECT_URI_MISCONFIGURED",
                    "Connecting an Instagram account is not available on this environment",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return effective;
    }

    /**
     * Fails a connect attempt before a state token is minted and before the creator is sent to
     * Meta. Called by {@code MetaOAuthController#authorize} alongside its {@code isConfigured}
     * guards; {@link #resolveRedirectUri} enforces the same rule again on the exchange leg.
     */
    public void assertRedirectUriUsable(boolean instagramLogin) {
        resolveRedirectUri(instagramLogin);
    }

    private RestClient restClient() {
        RestClient client = restClient;
        if (client == null) {
            synchronized (this) {
                if (restClient == null) {
                    // EV-045: explicit connect + read timeouts. This client performs the
                    // code-for-token exchange on a Tomcat request thread while the user waits on
                    // the OAuth callback; untimed, a hung Meta endpoint held that thread open
                    // with no bound at all.
                    restClient = OutboundRestClients.build();
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
                + urlEncode(resolveRedirectUri(false))
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
                        + urlEncode(resolveRedirectUri(false))
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
                + urlEncode(resolveRedirectUri(true))
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
                        + urlEncode(resolveRedirectUri(true))
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
        try {
            return fetchToken(url, "instagram-long-lived-exchange");
        } catch (MetaApiException e) {
            // F-0870 — this exchange fails in production with code 100 "Unsupported request -
            // method type: get" although the request matches Meta's docs and the code exchange
            // before it produced a non-blank token. What is left to rule out are properties of the
            // VALUES: token type, which app's secret, stray whitespace. Fingerprints only — see
            // MetaDiagnostics for why each is safe to print.
            log.warn(
                    "F-0870 instagram-long-lived-exchange diagnostics: shortLivedToken={},"
                            + " instagramAppId={}, instagramAppSecret={}, facebookAppSecret={},"
                            + " instagramSecretEqualsFacebookSecret={}",
                    MetaDiagnostics.tokenFingerprint(shortLivedToken),
                    props.getInstagramAppId(),
                    MetaDiagnostics.secretFingerprint(props.getInstagramAppSecret()),
                    MetaDiagnostics.secretFingerprint(props.getAppSecret()),
                    props.getInstagramAppSecret() != null
                            && props.getInstagramAppSecret().equals(props.getAppSecret()));
            throw e;
        }
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

    /**
     * F-0813 — {@code URI.create(url)}, never {@code uri(String)}.
     *
     * <p>Every caller hands this method a URL whose query values are ALREADY percent-encoded by
     * {@link #urlEncode}. {@code RestClient.uri(String)} treats its argument as a URI TEMPLATE and
     * encodes it a second time, so {@code https%3A%2F%2F…} went on the wire as
     * {@code https%253A%252F%252F…}. Meta decodes once, gets {@code https%3A%2F%2F…}, and answers:
     *
     * <pre>
     *   {"error":{"message":"redirect_uri isn't an absolute URI. Check RFC 3986.",
     *             "type":"OAuthException","code":191}}
     * </pre>
     *
     * Reproduced against Meta's live endpoint on 2026-09-13 with the real app credentials and a
     * dummy code — same URL, only the encoding differing:
     *
     * <pre>
     *   single-encoded redirect_uri -> code=100 "Invalid verification code format."  (reached the code check)
     *   double-encoded redirect_uri -> code=191 "redirect_uri isn't an absolute URI." (production's error)
     * </pre>
     *
     * <p>A {@code java.net.URI} is passed through untouched, so the single encoding survives.
     *
     * <p>This broke ONLY the code exchange, which is why the Meta dialog always worked and the
     * connect never completed: {@link #buildAuthorizationUrl} hands its URL to the BROWSER as a
     * string and never goes through {@code RestClient}. The other {@code fetchToken} callers
     * survived by luck — their parameters are tokens and codes that are almost always
     * {@code [A-Za-z0-9_-]}, where re-encoding is a no-op. A token containing {@code +} or
     * {@code /} would have broken identically, so this is fixed once, here, for all of them.
     */
    private MetaTokenResponse fetchToken(String url, String opName) {
        try {
            return restClient().get().uri(URI.create(url)).retrieve().body(MetaTokenResponse.class);
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
