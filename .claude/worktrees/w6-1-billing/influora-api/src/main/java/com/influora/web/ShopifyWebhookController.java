package com.influora.web;

import com.influora.common.ApiException;
import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.ShopifyIntegration;
import com.influora.integration.shopify.webhook.ShopifyOrderWebhookPayload;
import com.influora.integration.shopify.webhook.ShopifyWebhookSignatureVerifier;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.tracking.RedemptionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shopify order webhook receiver (Wave D task D1, {@code wiki/tech/REMAINING_WORK_PLAN.md}) —
 * receives {@code orders/paid} / {@code orders/create} deliveries from a brand's connected store
 * and routes them into {@link RedemptionService}, the same Wave A service {@code
 * ConversionWebhookController#redeemCoupon} calls for a brand's own custom-checkout webhook.
 *
 * <p><b>[SEC: Kabir load-bearing] Signature verification is the entire trust boundary.</b> Mirrors
 * {@code RazorpayWebhookController}'s discipline exactly: the raw request body is HMAC-verified
 * against Shopify's {@code X-Shopify-Hmac-Sha256} header BEFORE any parsing or dispatch (see
 * {@link ShopifyWebhookSignatureVerifier} — same constant-time-comparison, fail-closed-if-
 * unconfigured discipline as {@code razorpay.WebhookSignatureVerifier}, differing only in
 * Shopify's base64 vs. Razorpay's hex encoding). An invalid or missing signature is rejected 401
 * and NEVER reaches {@link RedemptionService}.
 *
 * <p><b>Shop-to-workspace resolution.</b> Unlike {@code ConversionWebhookController} (where the
 * coupon {@code code} itself is the only trusted identifier — no workspace lookup needed at all,
 * since {@code RedemptionService#redeem} looks up the coupon globally), a Shopify webhook's {@code
 * X-Shopify-Shop-Domain} header is the identifier this controller resolves to a workspace, via
 * {@link ShopifyIntegrationRepository#findByShopDomainAndRevokedFalse} — the same "public endpoint,
 * caller-supplied identifier resolved server-side, never a caller-supplied workspace id"
 * discipline as every other public webhook in this codebase. If no active connection exists for
 * the shop, the request is rejected 404 before any coupon lookup — this also means a request
 * cannot be used to enumerate workspaces, since the same 404 is returned whether the shop domain
 * is simply unknown or was never connected.
 *
 * <p><b>Idempotency [standing rule, {@code REMAINING_WORK_PLAN.md} line ~20].</b> Every webhook
 * delivery is wrapped in {@link IdempotencyService#executeOnce} keyed by a SHA-256 digest of
 * {@code shopDomain|topic|orderId} — Shopify retries webhook deliveries on any non-2xx response
 * (including a slow response, not just an error), so the same delivery can arrive more than once
 * even before considering {@code RedemptionService#redeem}'s OWN required-idempotency-key
 * mechanism. This is deliberately a SECOND, independent dedup layer: {@code executeOnce} here
 * guards the whole webhook-handling operation (shop resolution + payload parsing + the call into
 * {@code RedemptionService}) as one unit, while {@code RedemptionService#redeem}'s internal
 * dedup — keyed by the SAME derived key, passed through as the {@code idempotencyKey} argument —
 * guards the coupon-redemption row itself against a double-counted discount/usage-count
 * increment. Both layers agreeing on the same key means a replayed delivery is a clean no-op at
 * either layer, whichever one a retry happens to race against.
 *
 * <p><b>Scope [documented cut]:</b> only DISCOUNT-CODE-based redemption ({@code
 * order.discount_codes[0].code} → {@link RedemptionService#redeem}) is wired up. UTM
 * click-based conversion attribution ({@code ConversionTrackingService#recordConversion}) is NOT
 * wired from this controller — verified against the real code before making this call, not
 * assumed: {@code CampaignLinkService#buildTrackingUrl} embeds a human-readable campaign-title
 * SLUG as the {@code utm_campaign} query param (see that class), never the {@code UtmCampaign}
 * row's own ULID {@code recordConversion} requires as {@code utmCampaignId}. There is no existing
 * mechanism in this codebase that carries that ULID through to a merchant's Shopify checkout/order
 * object (e.g. via {@code landing_site}, a cart attribute, or {@code note_attributes}), so
 * inventing one here — with no real field to parse it from — would be exactly the kind of
 * unverified, isolated-shape assumption the Wave C4 retrospective flagged. {@code TODO(follow-up)}:
 * a real UTM-to-order correlation needs either (a) {@code CampaignLinkService} to also mint a
 * short code embedding the {@code UtmCampaign} ULID that a merchant's storefront script writes
 * into a Shopify cart/order attribute, or (b) a checkout-extension-based approach — a schema and
 * checkout-integration decision out of scope for this task.
 */
@RestController
@RequestMapping("/webhooks/shopify")
public class ShopifyWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookController.class);

    private static final String IDEMPOTENCY_SCOPE = "shopify.webhook";

    /** Order-related topics this controller acts on; every other topic is acknowledged (200) but not processed, per Shopify's webhook retry contract (a non-2xx on an unhandled-but-valid topic causes indefinite retries). */
    private static final String TOPIC_ORDERS_PAID = "orders/paid";

    private static final String TOPIC_ORDERS_CREATE = "orders/create";

    private final ShopifyWebhookSignatureVerifier signatureVerifier;
    private final ShopifyIntegrationRepository shopifyIntegrationRepository;
    private final RedemptionService redemptionService;
    private final IdempotencyService idempotencyService;

    public ShopifyWebhookController(
            ShopifyWebhookSignatureVerifier signatureVerifier,
            ShopifyIntegrationRepository shopifyIntegrationRepository,
            RedemptionService redemptionService,
            IdempotencyService idempotencyService) {
        this.signatureVerifier = signatureVerifier;
        this.shopifyIntegrationRepository = shopifyIntegrationRepository;
        this.redemptionService = redemptionService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader("X-Shopify-Hmac-Sha256") String signature,
            @RequestHeader("X-Shopify-Shop-Domain") String shopDomain,
            @RequestHeader(value = "X-Shopify-Topic", required = false) String topic,
            @RequestBody String rawPayload) {

        // [SEC: Kabir load-bearing] Verify BEFORE any parsing/dispatch — mirrors
        // RazorpayWebhookController exactly. The per-shop webhookSecret override (if one is ever
        // issued) is looked up AFTER a successful shop-domain-keyed repository read below would
        // normally happen, but signature verification must come first per this codebase's standing
        // webhook discipline — so this first pass verifies against the APP-LEVEL secret only
        // (secretOverride=null). No per-shop secret is issued by Shopify's standard OAuth flow
        // today (see ShopifyIntegration.webhookSecret javadoc — reserved for a future per-shop
        // issuance), so this is not a functional gap yet, only documented here so a future
        // per-shop-secret rollout does not silently skip verification.
        if (!signatureVerifier.verify(rawPayload, signature, null)) {
            throw new ApiException(
                    "INVALID_WEBHOOK_SIGNATURE", "Webhook signature verification failed", HttpStatus.UNAUTHORIZED);
        }

        if (shopDomain == null || shopDomain.isBlank()) {
            throw new ApiException(
                    "MISSING_SHOP_DOMAIN", "X-Shopify-Shop-Domain header is required", HttpStatus.BAD_REQUEST);
        }

        // Resolve-then-scope: the shop domain is the only trusted identifier here, resolved
        // server-side to the owning workspace's connection row — never a caller-supplied
        // workspace id. Same 404 whether the domain is unknown or simply not connected, so this
        // cannot be used to enumerate which shops ARE connected.
        ShopifyIntegration integration =
                shopifyIntegrationRepository
                        .findByShopDomainAndRevokedFalse(shopDomain)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SHOP_NOT_CONNECTED",
                                                "No active Shopify connection for this shop",
                                                HttpStatus.NOT_FOUND));

        String effectiveTopic = topic == null ? "" : topic;
        if (!TOPIC_ORDERS_PAID.equals(effectiveTopic) && !TOPIC_ORDERS_CREATE.equals(effectiveTopic)) {
            // Unhandled-but-verified topic (e.g. app/uninstalled, customers/redact) — acknowledge,
            // do not process. Never returns non-2xx for a topic we simply don't act on.
            return ResponseEntity.ok().build();
        }

        ShopifyOrderWebhookPayload order = ShopifyOrderWebhookPayload.parse(rawPayload);

        if (order.discountCode() == null || order.discountCode().isBlank()) {
            // No discount code on this order — nothing for RedemptionService to attribute. Not an
            // error; most Shopify orders will not carry a creator's tracking coupon.
            return ResponseEntity.ok().build();
        }

        String idempotencyKey = deriveIdempotencyKey(integration.getShopDomain(), effectiveTopic, order.orderId());

        try {
            idempotencyService.executeOnce(
                    idempotencyKey,
                    integration.getWorkspaceId(),
                    IDEMPOTENCY_SCOPE,
                    () ->
                            redeemViaShopifyOrder(
                                    integration.getWorkspaceId(),
                                    order.discountCode(),
                                    order.orderId(),
                                    order.totalPrice(),
                                    idempotencyKey));
        } catch (IdempotencyService.AlreadyCompletedException | IdempotencyService.AlreadyInProgressException replay) {
            // Clean, idempotent no-op — this exact webhook delivery (or a concurrent duplicate of
            // it) was already handled. RedemptionService#redeem's own idempotency-key replay would
            // return the same result if called again directly; there is nothing further to do here.
            log.info(
                    "Shopify webhook replay/duplicate for shop={} topic={} order={} — no-op",
                    integration.getShopDomain(),
                    effectiveTopic,
                    order.orderId());
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Delegates to the WORKSPACE-SCOPED {@link RedemptionService#redeem(String, String, String,
     * java.math.BigDecimal, String, String)} overload, passing the resolved {@code workspaceId} of
     * the {@link ShopifyIntegration} that this webhook's signature proved sent it, and the SAME
     * derived idempotency key this controller reserved via {@link IdempotencyService#executeOnce} —
     * see class javadoc for why both layers deliberately share one key rather than each deriving
     * their own.
     *
     * <p><b>[SEC: Kabir, Wave D1 HIGH — FIXED] Cross-tenant coupon redemption.</b> This previously
     * called the GLOBAL {@code RedemptionService#redeem(String, String, BigDecimal, String,
     * String)} overload, discarding the resolved {@code workspaceId} entirely — since {@code
     * CouponCodeRepository#findByCode} is a global, not workspace-scoped, lookup and coupon codes
     * are only unique per-workspace ({@code UNIQUE(workspace_id, code)}), a webhook signed by Brand
     * A's own connected store (a real, legitimately-obtained signature) could redeem a coupon code
     * that happens to belong to Brand B, inflating Brand B's usage count and triggering an
     * incorrect affiliate-commission accrual for an order that never touched Brand B's store. See
     * {@code wiki/errors/wave-d1-shopify-integration-redteam.md} for the full report. Passing {@code
     * workspaceId} here means {@link RedemptionService} now validates the coupon belongs to THIS
     * shop's own workspace before ever mutating anything, rejecting a cross-workspace match with the
     * same {@code INVALID_CODE} 404 used for "code doesn't exist" — no new enumeration signal.
     */
    private CouponRedemption redeemViaShopifyOrder(
            String workspaceId,
            String discountCode,
            String orderId,
            java.math.BigDecimal orderAmount,
            String idempotencyKey) {
        return redemptionService.redeem(workspaceId, discountCode, orderId, orderAmount, null, idempotencyKey);
    }

    /**
     * Derives a stable idempotency key for one webhook delivery from fields Shopify itself
     * guarantees are stable across retries of the SAME delivery: the shop domain, the topic, and
     * the order id. Hashed (not a plain concatenation) purely to keep the key within {@code
     * idempotency_keys.idempotency_key}'s column bound regardless of how long a shop domain is —
     * same defensive-length reasoning as {@code ConversionTrackingService#deriveFallbackKey},
     * though the threat model here is different: this key is never attacker-suppliable (there is
     * no caller-supplied idempotency key on this endpoint at all), so there is no squatting vector
     * to defend against, only a length bound to satisfy.
     */
    private static String deriveIdempotencyKey(String shopDomain, String topic, String orderId) {
        String canonical = shopDomain + "|" + topic + "|" + orderId;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "shopify:" + hex;
        } catch (Exception e) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_DERIVATION_FAILED",
                    "Failed to derive idempotency key",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
