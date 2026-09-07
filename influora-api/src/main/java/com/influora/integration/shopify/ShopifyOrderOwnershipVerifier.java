package com.influora.integration.shopify;

import com.influora.common.ApiException;
import com.influora.config.ShopifyProperties;
import com.influora.integration.shopify.exception.ShopifyApiException;
import com.influora.integration.shopify.oauth.ShopifyOAuthService;
import com.influora.integration.shopify.oauth.ShopifyTokenStorage;
import java.net.URI;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Confirms that an order id carried by a Shopify webhook really exists in the shop the delivery
 * claims to come from, by asking that shop's own Admin API with that shop's own access token.
 *
 * <p><b>[F-0726] Why this exists — the tenant selector is not signed.</b> Shopify's {@code
 * X-Shopify-Hmac-Sha256} is an HMAC of the REQUEST BODY against the single app-level client secret.
 * It says "some shop with this app installed produced this payload." It does NOT say WHICH shop,
 * because the shop is named only in the {@code X-Shopify-Shop-Domain} header, which the HMAC does
 * not cover. {@code ShopifyWebhookController} nonetheless used that header to pick the workspace
 * every downstream write is scoped to.
 *
 * <p>{@code wiki/errors/wave-d1-shopify-integration-redteam.md:95} described that header as "a
 * real, HMAC-authenticated workspace identity". That sentence is wrong, and the D1 cross-tenant fix
 * was built on top of it: scoping the coupon lookup to a workspace an unauthenticated header
 * chooses only moves the attacker's choice from "which coupon" to "which workspace".
 *
 * <p><b>The attack it closes.</b> An attacker installs the app on a Shopify store they control and
 * adds a second webhook subscription pointing at their own collector. Shopify signs deliveries for
 * that subscription with the SAME app-level client secret, so the attacker now holds a body plus a
 * genuinely valid HMAC for an order whose contents they authored — including a {@code
 * discount_codes[0].code} of their choosing. Replaying that pair to us with {@code
 * X-Shopify-Shop-Domain} changed to a victim's shop passes signature verification, resolves to the
 * victim's workspace, and redeems the victim's own coupon: a fabricated redemption, an inflated
 * usage count, and an affiliate-commission accrual for an order that never happened.
 *
 * <p><b>Why this check and not a payload field.</b> Binding the header to a shop identity inside the
 * signed body would be cheaper, but this repository pins only {@code id} and {@code total_price} of
 * the Shopify order shape ({@code ShopifyOrderWebhookPayloadTest}) and has no fuller fixture, so
 * coding against any other field would be an unverified assumption about a third party's payload —
 * and a control that silently no-ops whenever that field is absent fails OPEN, which is worse than
 * no control because it reads as one. The order lookup needs no assumption: the attacker cannot
 * make their own order exist in the victim's store, whatever the payload looks like.
 *
 * <p><b>Failure handling is deliberately asymmetric, and F-0725 is why.</b> A definitive "this order
 * is not in that shop" ({@code 404}) is a rejection. Anything else — a network error, a 5xx from
 * Shopify, an expired token — is a {@link ShopifyApiException} (502), which is NOT in {@code
 * StoreWebhookRedemptionOutcome}'s terminal set and therefore still reaches Shopify as a non-2xx so
 * its retry can recover. Treating an unreachable Admin API as "not your order" would silently drop
 * real redemptions during any Shopify incident.
 */
@Service
public class ShopifyOrderOwnershipVerifier {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOrderOwnershipVerifier.class);

    /**
     * [SEC] Shopify order ids are numeric. Validated before interpolation because {@code orderId}
     * originates in the request body: {@code ShopifyOrderWebhookPayload.parse} stringifies whatever
     * JSON node sits at {@code id}, so a crafted body could otherwise put path segments or a query
     * string into the Admin API URL. Same validate-before-interpolate discipline {@code
     * ShopifyOAuthService.validateShopDomain} applies to the host half of this URL.
     */
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^[0-9]{1,32}$");

    private final ShopifyProperties props;
    private final ShopifyTokenStorage tokenStorage;
    private final RestClient restClient;

    @Autowired
    public ShopifyOrderOwnershipVerifier(ShopifyProperties props, ShopifyTokenStorage tokenStorage) {
        this(props, tokenStorage, RestClient.builder().build());
    }

    /** Package-private test constructor for injecting a mocked RestClient. */
    ShopifyOrderOwnershipVerifier(ShopifyProperties props, ShopifyTokenStorage tokenStorage, RestClient restClient) {
        this.props = props;
        this.tokenStorage = tokenStorage;
        this.restClient = restClient;
    }

    /**
     * {@code true} when {@code orderId} is a real order in {@code shopDomain}, {@code false} when
     * that shop's Admin API says it is not.
     *
     * @throws ApiException {@code SHOPIFY_NOT_CONNECTED} (401) if the workspace has no stored access
     *     token — the connection was revoked between the webhook arriving and this check
     * @throws ApiException {@code INVALID_WEBHOOK_PAYLOAD} (400) if {@code orderId} is not a Shopify
     *     order id
     * @throws ShopifyApiException (502) for any transport error or non-404 error status — TRANSIENT,
     *     so the platform retry can recover it (see class javadoc)
     */
    public boolean orderBelongsToShop(String shopDomain, String workspaceId, String orderId) {
        String validatedShop = ShopifyOAuthService.validateShopDomain(shopDomain);

        if (orderId == null || !ORDER_ID_PATTERN.matcher(orderId).matches()) {
            throw new ApiException(
                    "INVALID_WEBHOOK_PAYLOAD", "Order id is not a Shopify order id", HttpStatus.BAD_REQUEST);
        }

        String accessToken =
                tokenStorage
                        .getValidToken(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SHOPIFY_NOT_CONNECTED",
                                                "No Shopify access token for this workspace",
                                                HttpStatus.UNAUTHORIZED));

        // Built as a URI, never as a String template. RestClient#uri(String) expands {placeholders}
        // and re-encodes, which is how a "?" or a "{a,b}" in an interpolated value has previously
        // turned into a silent 404 or a build-time 500 in this codebase. Both halves interpolated
        // here are pattern-validated above, and URI.create sees the final literal.
        URI uri =
                URI.create(
                        "https://"
                                + validatedShop
                                + "/admin/api/"
                                + props.getApiVersion()
                                + "/orders/"
                                + orderId
                                + ".json?fields=id");

        try {
            restClient
                    .get()
                    .uri(uri)
                    .header("X-Shopify-Access-Token", accessToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                // Definitive: this shop does not have this order. The only branch that rejects.
                log.warn(
                        "Shopify webhook claimed shop={} for order={} but that shop's Admin API does not have it"
                                + " — rejecting as an unbound tenant selector (F-0726)",
                        validatedShop,
                        orderId);
                return false;
            }
            // Includes 401/403 (token revoked shop-side) and every 5xx. Never a rejection: an
            // unreachable or unhappy Admin API must not be read as "not your order".
            throw new ShopifyApiException(
                    "Shopify order ownership check failed for shop " + validatedShop + " with status "
                            + e.getStatusCode().value());
        } catch (RuntimeException e) {
            throw new ShopifyApiException("Shopify order ownership check could not reach " + validatedShop, e);
        }
    }
}
