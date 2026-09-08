package com.influora.integration.shopify;

import com.influora.common.ApiException;
import com.influora.config.ShopifyProperties;
import com.influora.integration.shopify.exception.ShopifyApiException;
import com.influora.integration.shopify.oauth.ShopifyOAuthService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Subscribes a freshly connected Shopify store to the order topics {@code ShopifyWebhookController}
 * receives.
 *
 * <p><b>[F-0730] Why this exists.</b> Nothing in this codebase ever told Shopify to send us
 * anything. {@code ShopifyWebhookController} was built, HMAC-verified, idempotency-guarded and
 * tested — and no store could ever have delivered to it, because a Shopify app receives webhooks
 * only for topics it explicitly subscribes to, either through the Admin API (this class) or a
 * declarative {@code shopify.app.toml} manifest. Neither existed: verified by grepping the whole
 * repository for {@code webhooks.json}, {@code registerWebhook}, {@code createWebhook} and
 * {@code *.toml} before writing this. A brand could complete OAuth, see "connected", and never have
 * a single order attributed — with nothing anywhere reporting a problem.
 *
 * <p><b>The delivery address is derived, never hardcoded.</b> It is built from {@code
 * influora.api.public-url}, which every deploy file sets to {@code https://${API_DOMAIN}/api/v1} —
 * note that value already carries the {@code /api/v1} context path, so this class appends only the
 * controller's own mapping. Hardcoding it is exactly how the WooCommerce integration shipped a
 * delivery URL that was wrong twice over (wrong TLD, missing context path) and therefore never once
 * worked; that URL is now derived on the frontend for the same reason.
 *
 * <p><b>Fails loudly, and refuses a non-https address.</b> Shopify rejects plaintext callbacks, so a
 * registration attempt against a {@code http://localhost} address (the property's dev default)
 * would either be refused by Shopify or — worse — quietly accepted for a host that can never
 * receive traffic. Either way the brand would be left "connected" and deaf, which is the precise
 * failure this class exists to end, so it is refused here with a message naming the misconfigured
 * property rather than attempted.
 *
 * <p><b>Idempotent.</b> Re-connecting a store that already has our subscription must not fail.
 * Shopify answers a duplicate {@code topic}+{@code address} pair with {@code 422} ("address for
 * this topic has already been taken"), which this class treats as success — the desired end state
 * is "this shop is subscribed", not "this call created the subscription".
 */
@Service
public class ShopifyWebhookRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookRegistrar.class);

    /**
     * The topics {@code ShopifyWebhookController} acts on. Kept in sync with that controller's
     * {@code TOPIC_ORDERS_PAID}/{@code TOPIC_ORDERS_CREATE} — subscribing to a topic the controller
     * ignores would generate deliveries it acknowledges and drops, and subscribing to fewer than it
     * handles would silently lose attributable orders.
     */
    private static final List<String> ORDER_TOPICS = List.of("orders/paid", "orders/create");

    /** {@code ShopifyWebhookController}'s own {@code @RequestMapping}, appended to the public API url. */
    private static final String WEBHOOK_PATH = "/webhooks/shopify";

    private final ShopifyProperties props;
    private final RestClient restClient;
    private final String apiPublicUrl;

    @Autowired
    public ShopifyWebhookRegistrar(
            ShopifyProperties props, @Value("${influora.api.public-url:http://localhost:8080}") String apiPublicUrl) {
        this(props, apiPublicUrl, RestClient.builder().build());
    }

    /** Package-private test constructor for injecting a mocked RestClient. */
    ShopifyWebhookRegistrar(ShopifyProperties props, String apiPublicUrl, RestClient restClient) {
        this.props = props;
        this.apiPublicUrl = apiPublicUrl;
        this.restClient = restClient;
    }

    /**
     * Subscribes {@code shopDomain} to every topic in {@link #ORDER_TOPICS}, using that shop's own
     * access token.
     *
     * @return the topics this shop is subscribed to on success — every entry in {@link
     *     #ORDER_TOPICS}, whether this call created the subscription or found it already present
     * @throws ApiException {@code SHOPIFY_WEBHOOK_ADDRESS_NOT_PUBLIC} (500) if {@code
     *     influora.api.public-url} is not an https URL, so no unreachable subscription is created
     * @throws ShopifyApiException (502) if Shopify refuses a subscription for any other reason —
     *     the caller must treat this as a failed connect, never as a connected store
     */
    public List<String> registerOrderWebhooks(String shopDomain, String accessToken) {
        String validatedShop = ShopifyOAuthService.validateShopDomain(shopDomain);
        String address = deliveryAddress();

        for (String topic : ORDER_TOPICS) {
            subscribe(validatedShop, accessToken, topic, address);
        }
        log.info("Shopify webhooks registered for shop={} topics={} address={}", validatedShop, ORDER_TOPICS, address);
        return ORDER_TOPICS;
    }

    /**
     * Builds the delivery address Shopify will POST to. Trailing slashes on the configured public
     * url are stripped so the result cannot contain {@code //webhooks/shopify}, which Shopify stores
     * verbatim and which would then never match this app's route.
     */
    private String deliveryAddress() {
        String base = apiPublicUrl == null ? "" : apiPublicUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.startsWith("https://")) {
            throw new ApiException(
                    "SHOPIFY_WEBHOOK_ADDRESS_NOT_PUBLIC",
                    "influora.api.public-url must be an https URL before a Shopify store can be"
                            + " connected; Shopify will not deliver to "
                            + (base.isEmpty() ? "an empty address" : base),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return base + WEBHOOK_PATH;
    }

    private void subscribe(String shop, String accessToken, String topic, String address) {
        URI uri = URI.create("https://" + shop + "/admin/api/" + props.getApiVersion() + "/webhooks.json");
        try {
            restClient
                    .post()
                    .uri(uri)
                    .header("X-Shopify-Access-Token", accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("webhook", Map.of("topic", topic, "address", address, "format", "json")))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                // Shopify's answer to an existing topic+address pair. Re-connecting an already
                // subscribed store is a normal action, not a failure — the goal is the end state.
                log.info("Shopify webhook topic={} already registered for shop={} — treating as subscribed", topic, shop);
                return;
            }
            throw new ShopifyApiException(
                    "Shopify refused a webhook subscription for topic " + topic + " on shop " + shop + " with status "
                            + e.getStatusCode().value());
        } catch (RuntimeException e) {
            throw new ShopifyApiException("Could not reach " + shop + " to register the " + topic + " webhook", e);
        }
    }
}
