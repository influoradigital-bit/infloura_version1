package com.influora.integration.meta.oauth;

import com.influora.config.MetaApiProperties;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** Required OAuth scopes for full functionality (spec §1.7). */
    public static final List<String> REQUIRED_SCOPES =
            List.of(
                    "instagram_basic", // Basic profile and media access
                    "instagram_manage_insights", // Insights and demographics
                    "pages_show_list", // List connected Facebook Pages
                    "pages_read_engagement" // Page engagement metrics
                    );

    private final MetaApiProperties props;
    private final RestClient restClient;

    public MetaOAuthService(MetaApiProperties props) {
        this.props = props;
        this.restClient = RestClient.builder().build();
    }

    /** Package-private test constructor for injecting mocked RestClient. */
    MetaOAuthService(MetaApiProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
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

    private MetaTokenResponse fetchToken(String url, String opName) {
        try {
            return restClient.get().uri(url).retrieve().body(MetaTokenResponse.class);
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
