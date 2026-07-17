package com.influora.integration.shopify.oauth;

import com.influora.common.ApiException;
import com.influora.config.ShopifyProperties;
import com.influora.integration.shopify.dto.ShopifyTokenResponse;
import com.influora.integration.shopify.exception.ShopifyApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Shopify OAuth flow orchestration for the FREE "custom app" install path (Wave D task D1,
 * {@code wiki/tech/REMAINING_WORK_PLAN.md}) -- no $99/mo Shopify Plus fee, no paid-app-listing
 * fee. This is the same "redirect merchant to an authorize URL, they approve, Shopify redirects
 * back with a code, exchange the code for a token" handshake every Shopify app (free or paid)
 * uses; "free" here refers to not requiring a paid Shopify plan or App Store listing, not a
 * different/lesser OAuth mechanism.
 *
 * <p>Mirrors {@code MetaOAuthService}'s shape (this service never persists tokens itself --
 * callers pass the resulting {@link ShopifyTokenResponse} to {@code ShopifyTokenStorage}), with
 * two structural differences from Meta's flow:
 *
 * <ul>
 *   <li>The authorize/token-exchange URLs are PER-SHOP ({@code https://{shop}.myshopify.com/...}),
 *       not a fixed dialog host -- {@code shop} is caller-supplied at connect time, so it MUST be
 *       validated against {@link #SHOP_DOMAIN_PATTERN} before being interpolated into a URL
 *       (unvalidated interpolation here would be an SSRF vector: a caller could supply an
 *       arbitrary host and have this server's credentials/code POSTed to it).
 *   <li>There is no short-lived/long-lived two-step exchange -- {@link #exchangeCodeForToken}
 *       returns a single non-expiring token directly; the caller stores it as-is with no
 *       expiry/refresh bookkeeping (unlike {@code MetaTokenStorage}'s {@code expiresAt}).
 * </ul>
 */
@Service
public class ShopifyOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOAuthService.class);

    /**
     * [SEC] Shopify shop domains are always {@code <subdomain>.myshopify.com}, subdomain
     * lowercase alphanumeric + hyphens. Validating this BEFORE interpolating {@code shop} into any
     * outbound URL is load-bearing -- see class javadoc SSRF note. Rejects anything else
     * (including a bare hostname with no {@code .myshopify.com} suffix, protocol-relative values,
     * or an attacker-supplied arbitrary host).
     */
    private static final Pattern SHOP_DOMAIN_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]*\\.myshopify\\.com$");

    private final ShopifyProperties props;
    private final RestClient restClient;

    @Autowired
    public ShopifyOAuthService(ShopifyProperties props) {
        this.props = props;
        this.restClient = RestClient.builder().build();
    }

    /** Package-private test constructor for injecting a mocked RestClient. */
    ShopifyOAuthService(ShopifyProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    /**
     * Validates a caller-supplied shop domain is a genuine {@code *.myshopify.com} host. MUST be
     * called before {@code shop} is used in {@link #buildAuthorizationUrl} or {@link
     * #exchangeCodeForToken} -- see class javadoc SSRF note.
     *
     * @throws ApiException {@code INVALID_SHOP_DOMAIN} (400) if {@code shop} is null/blank or does
     *     not match {@link #SHOP_DOMAIN_PATTERN}
     */
    public static String validateShopDomain(String shop) {
        if (shop == null || shop.isBlank() || !SHOP_DOMAIN_PATTERN.matcher(shop.toLowerCase()).matches()) {
            throw new ApiException(
                    "INVALID_SHOP_DOMAIN",
                    "shop must be a valid *.myshopify.com domain",
                    HttpStatus.BAD_REQUEST);
        }
        return shop.toLowerCase();
    }

    /** Builds the Shopify OAuth authorization URL the brand is redirected to, scoped to one validated shop domain. */
    public String buildAuthorizationUrl(String shop, String state) {
        String validatedShop = validateShopDomain(shop);
        return "https://"
                + validatedShop
                + "/admin/oauth/authorize"
                + "?client_id="
                + urlEncode(props.getApiKey())
                + "&scope="
                + urlEncode(props.getScopes())
                + "&redirect_uri="
                + urlEncode(props.getRedirectUri())
                + "&state="
                + urlEncode(state);
    }

    /**
     * Exchanges an authorization code for a permanent (non-expiring) access token. Unlike Meta,
     * this is the only exchange step -- see class javadoc.
     */
    public ShopifyTokenResponse exchangeCodeForToken(String shop, String code) {
        String validatedShop = validateShopDomain(shop);
        String url = "https://" + validatedShop + "/admin/oauth/access_token";

        record TokenRequest(String client_id, String client_secret, String code) {}

        try {
            return restClient
                    .post()
                    .uri(url)
                    .body(new TokenRequest(props.getApiKey(), props.getApiSecret(), code))
                    .retrieve()
                    .body(ShopifyTokenResponse.class);
        } catch (RestClientResponseException e) {
            // M-K6-C3-5: never log OAuth response bodies (may contain tokens / PII).
            log.error(
                    "Shopify OAuth code-exchange failed for shop={}: status={}",
                    validatedShop,
                    e.getStatusCode().value());
            throw new ShopifyApiException("Shopify OAuth code exchange failed", e);
        }
    }

    /** Scopes requested at connect time, parsed from {@code influora.shopify.scopes} (comma-separated). */
    public List<String> requestedScopes() {
        return List.of(props.getScopes().split(","));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
