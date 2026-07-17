package com.influora.web;

import com.influora.common.ApiException;
import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.WooCommerceIntegration;
import com.influora.integration.woocommerce.WooCommerceIntegrationService;
import com.influora.integration.woocommerce.WooCommerceSiteUrl;
import com.influora.integration.woocommerce.webhook.WooCommerceOrderWebhookPayload;
import com.influora.integration.woocommerce.webhook.WooCommerceWebhookSignatureVerifier;
import com.influora.repository.WooCommerceIntegrationRepository;
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
 * WooCommerce order webhook receiver (Wave D task D2, {@code wiki/tech/REMAINING_WORK_PLAN.md}) --
 * receives {@code order.created}/{@code order.updated} deliveries from a brand's connected
 * WooCommerce site and routes them into {@link RedemptionService}, the SAME Wave A service {@code
 * ConversionWebhookController#redeemCoupon} and {@code ShopifyWebhookController#receive} (Wave D1)
 * call.
 *
 * <p><b>Confirmed against WooCommerce's real webhook contract before writing this, not guessed:</b>
 * WooCommerce is REST-webhook based (no OAuth app-install flow like Shopify) -- a brand configures
 * a webhook URL + secret directly in their WooCommerce admin (Settings -> Advanced -> Webhooks)
 * pointing at this endpoint. Every delivery carries {@code X-WC-Webhook-Signature} (base64-encoded
 * HMAC-SHA256 of the raw body, same shape as Shopify's {@code X-Shopify-Hmac-Sha256}), {@code
 * X-WC-Webhook-Source} (the store's site URL), {@code X-WC-Webhook-Topic} (e.g. {@code
 * order.updated}), {@code X-WC-Webhook-Resource} ({@code order}), and {@code X-WC-Webhook-Event}
 * ({@code updated}).
 *
 * <p><b>[SEC: Kabir load-bearing, D1 lesson applied FROM THE START] Signature verification is the
 * entire trust boundary -- and it is workspace-scoped from day one, unlike D1's original bug.</b>
 * Wave D1's Shopify integration shipped with a HIGH finding (see {@code
 * wiki/errors/wave-d1-shopify-integration-D1-final-verdict.md}): a webhook forged by Brand A,
 * legitimately signed with Brand A's OWN secret, could redeem a coupon code belonging to Brand B,
 * because the controller called {@code RedemptionService}'s GLOBAL, non-workspace-scoped {@code
 * redeem} overload. This controller is built to NEVER repeat that: it always calls the
 * WORKSPACE-SCOPED {@link RedemptionService#redeem(String, String, String, java.math.BigDecimal,
 * String, String)} 6-arg overload, passing the resolved {@link WooCommerceIntegration}'s {@code
 * workspaceId} -- the SAME pattern D1 ended up fixed to, applied here from the start rather than as
 * a follow-up fix. The legacy global 5-arg overload is reserved for {@code
 * ConversionWebhookController} only (that controller has no independent workspace signal at all;
 * this one does, from the resolved site).
 *
 * <p><b>Ordering difference from Shopify's controller, and why it does not weaken "verify before
 * parse."</b> Shopify signs every shop's webhooks with ONE app-level secret, so {@code
 * ShopifyWebhookController} verifies against that fixed secret FIRST, then resolves the shop
 * afterward. WooCommerce has no app-level secret -- each site's secret is per-integration -- so
 * this controller MUST resolve which integration's secret to check BEFORE it can call {@link
 * WooCommerceWebhookSignatureVerifier#verify} at all. This is still faithful to this codebase's
 * "verify raw body before any parsing/dispatch" discipline (mirroring {@code
 * RazorpayWebhookController} and {@code ShopifyWebhookController} exactly): resolving the
 * integration is a header read + an indexed DB lookup by {@code site_url}, NOT payload parsing --
 * the raw JSON body is never handed to a JSON parser (see {@link
 * WooCommerceOrderWebhookPayload#parse}) until AFTER {@link
 * WooCommerceWebhookSignatureVerifier#verify} has returned {@code true}. An invalid or missing
 * signature, OR an unresolvable/unknown site, is rejected before any coupon-redemption logic runs.
 *
 * <p><b>Site-to-workspace resolution.</b> The {@code X-WC-Webhook-Source} header is the identifier
 * this controller resolves to a workspace + secret, via {@link
 * WooCommerceIntegrationRepository#findBySiteUrlAndRevokedFalse} -- same "public endpoint,
 * caller-supplied identifier resolved server-side, never a caller-supplied workspace id" discipline
 * as {@code ShopifyWebhookController}. The header value is normalized the SAME way {@code
 * WooCommerceConnectController} normalized it at connect time (see {@link
 * WooCommerceSiteUrl#normalize}), so a header value differing only in scheme case, host case, or a
 * trailing slash still resolves correctly. If no active connection exists for the site, the request
 * is rejected 404 (SITE_NOT_CONNECTED) before any secret is read or any signature is checked --
 * same as Shopify's SHOP_NOT_CONNECTED, this also means a request cannot be used to enumerate which
 * sites ARE connected (unknown site and known-but-wrong-signature both eventually surface as a
 * generic rejection to an outside caller, though for a KNOWN site the signature check still runs
 * and can independently reject with 401 -- see the two distinct failure branches below, both
 * verified to occur strictly before any coupon lookup).
 *
 * <p><b>Idempotency [standing rule, {@code REMAINING_WORK_PLAN.md} line ~20].</b> Every webhook
 * delivery is wrapped in {@link IdempotencyService#executeOnce} keyed by a SHA-256 digest of {@code
 * siteUrl|topic|orderId} -- WooCommerce, like Shopify, retries webhook deliveries on a non-2xx (or
 * slow) response, so the same delivery can arrive more than once even before considering {@code
 * RedemptionService#redeem}'s OWN required-idempotency-key mechanism. Same deliberate two-layer
 * dedup as {@code ShopifyWebhookController}: this key guards the whole webhook-handling operation
 * (site resolution + payload parsing + the call into {@code RedemptionService}) as one unit, while
 * {@code RedemptionService#redeem}'s internal dedup -- keyed by the SAME derived key, passed through
 * as {@code idempotencyKey} -- guards the coupon-redemption row itself against a double-counted
 * usage-count increment. Both layers agreeing on the same key means a replayed delivery is a clean
 * no-op at either layer, whichever one a retry happens to race against.
 *
 * <p><b>Rate limiting.</b> {@code POST /webhooks/woocommerce} joins the SAME {@code "tracking"}
 * bucket as {@code /webhooks/shopify}/{@code /webhooks/redemption} in {@code AuthRateLimitFilter}
 * (per-IP, not per-site) -- Kabir's D1 non-blocking follow-up (his final verdict doc, Probe 3)
 * already flagged that this bucket's IP-only keying is a known, documented, cross-tenant
 * bucket-exhaustion limitation shared by every webhook surface in that bucket, contingent on an
 * unverified edge/{@code X-Forwarded-For} assumption. Reusing the same bucket here (rather than
 * inventing a new, differently-scoped one) does not introduce a NEW instance of that problem -- it
 * is the same already-tracked limitation, not a fresh one, and a proper fix (scoping by
 * resolved-site/workspace instead of raw IP) belongs in {@code AuthRateLimitFilter} once, for every
 * webhook surface at the same time, rather than as a one-off per integration.
 *
 * <p><b>Scope [documented cut]:</b> only COUPON-based redemption ({@code
 * order.coupon_lines[0].code} -> {@link RedemptionService#redeem}) is wired up, same documented cut
 * as {@code ShopifyWebhookController} for UTM click-based conversion attribution -- see that
 * class's javadoc for why inventing a UTM-to-order correlation mechanism with no real field to
 * parse it from is out of scope for this slice.
 */
@RestController
@RequestMapping("/webhooks/woocommerce")
public class WooCommerceWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WooCommerceWebhookController.class);

    private static final String IDEMPOTENCY_SCOPE = "woocommerce.webhook";

    /** Order-related topics this controller acts on; every other topic is acknowledged (200) but not processed, per WooCommerce's webhook retry contract (a non-2xx on an unhandled-but-valid topic causes indefinite retries). */
    private static final String TOPIC_ORDER_CREATED = "order.created";

    private static final String TOPIC_ORDER_UPDATED = "order.updated";

    private final WooCommerceWebhookSignatureVerifier signatureVerifier;
    private final WooCommerceIntegrationRepository integrationRepository;
    private final WooCommerceIntegrationService integrationService;
    private final RedemptionService redemptionService;
    private final IdempotencyService idempotencyService;

    public WooCommerceWebhookController(
            WooCommerceWebhookSignatureVerifier signatureVerifier,
            WooCommerceIntegrationRepository integrationRepository,
            WooCommerceIntegrationService integrationService,
            RedemptionService redemptionService,
            IdempotencyService idempotencyService) {
        this.signatureVerifier = signatureVerifier;
        this.integrationRepository = integrationRepository;
        this.integrationService = integrationService;
        this.redemptionService = redemptionService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader("X-WC-Webhook-Signature") String signature,
            @RequestHeader("X-WC-Webhook-Source") String siteUrlHeader,
            @RequestHeader(value = "X-WC-Webhook-Topic", required = false) String topic,
            @RequestBody String rawPayload) {

        if (siteUrlHeader == null || siteUrlHeader.isBlank()) {
            throw new ApiException(
                    "MISSING_SITE_URL", "X-WC-Webhook-Source header is required", HttpStatus.BAD_REQUEST);
        }

        // Resolve-then-scope: the site URL is the only trusted identifier here, resolved
        // server-side to the owning workspace's connection row -- never a caller-supplied workspace
        // id. Normalized the SAME way WooCommerceConnectController normalized it at connect time,
        // so scheme-case/host-case/trailing-slash differences don't cause a false SITE_NOT_CONNECTED.
        //
        // [SEC: Kabir load-bearing] This lookup MUST happen before signature verification, unlike
        // ShopifyWebhookController -- WooCommerce has no app-level secret to verify against first.
        // This is a header read + indexed DB lookup, not payload parsing, so it does not weaken the
        // "verify raw body before any parsing/dispatch" discipline -- see class javadoc.
        String normalizedSiteUrl = WooCommerceSiteUrl.normalize(siteUrlHeader);
        WooCommerceIntegration integration =
                integrationRepository
                        .findBySiteUrlAndRevokedFalse(normalizedSiteUrl)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SITE_NOT_CONNECTED",
                                                "No active WooCommerce connection for this site",
                                                HttpStatus.NOT_FOUND));

        String secret = integrationService.decryptSecret(integration);
        if (!signatureVerifier.verify(rawPayload, signature, secret)) {
            throw new ApiException(
                    "INVALID_WEBHOOK_SIGNATURE", "Webhook signature verification failed", HttpStatus.UNAUTHORIZED);
        }

        String effectiveTopic = topic == null ? "" : topic;
        if (!TOPIC_ORDER_CREATED.equals(effectiveTopic) && !TOPIC_ORDER_UPDATED.equals(effectiveTopic)) {
            // Unhandled-but-verified topic -- acknowledge, do not process. Never returns non-2xx
            // for a topic we simply don't act on.
            return ResponseEntity.ok().build();
        }

        WooCommerceOrderWebhookPayload order = WooCommerceOrderWebhookPayload.parse(rawPayload);

        if (order.couponCode() == null || order.couponCode().isBlank()) {
            // No coupon on this order -- nothing for RedemptionService to attribute. Not an error;
            // most WooCommerce orders will not carry a creator's tracking coupon.
            return ResponseEntity.ok().build();
        }

        String idempotencyKey = deriveIdempotencyKey(integration.getSiteUrl(), effectiveTopic, order.orderId());

        try {
            idempotencyService.executeOnce(
                    idempotencyKey,
                    integration.getWorkspaceId(),
                    IDEMPOTENCY_SCOPE,
                    () ->
                            redeemViaWooCommerceOrder(
                                    integration.getWorkspaceId(),
                                    order.couponCode(),
                                    order.orderId(),
                                    order.total(),
                                    idempotencyKey));
        } catch (IdempotencyService.AlreadyCompletedException | IdempotencyService.AlreadyInProgressException replay) {
            // Clean, idempotent no-op -- this exact webhook delivery (or a concurrent duplicate of
            // it) was already handled.
            log.info(
                    "WooCommerce webhook replay/duplicate for site={} topic={} order={} — no-op",
                    integration.getSiteUrl(),
                    effectiveTopic,
                    order.orderId());
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Delegates to the WORKSPACE-SCOPED {@link RedemptionService#redeem(String, String, String,
     * java.math.BigDecimal, String, String)} overload, passing the resolved {@code workspaceId} of
     * the {@link WooCommerceIntegration} that this webhook's signature proved sent it, and the SAME
     * derived idempotency key this controller reserved via {@link IdempotencyService#executeOnce}.
     *
     * <p><b>[SEC: D1 lesson applied from the start, not a follow-up fix]</b> this NEVER calls the
     * legacy global {@code RedemptionService#redeem(String, String, BigDecimal, String, String)}
     * overload -- doing so would reopen exactly the cross-tenant coupon redemption gap Kabir found
     * in D1 (see {@code wiki/errors/wave-d1-shopify-integration-D1-final-verdict.md}): since coupon
     * codes are only unique per-workspace ({@code UNIQUE(workspace_id, code)}), a webhook signed by
     * Brand A's own connected site (a real, legitimately-obtained signature) could otherwise redeem
     * a coupon code that happens to belong to Brand B. Passing {@code workspaceId} here means {@link
     * RedemptionService} validates the coupon belongs to THIS site's own workspace before ever
     * mutating anything, rejecting a cross-workspace match with the same {@code INVALID_CODE} 404
     * used for "code doesn't exist" -- no new enumeration signal. See {@code
     * WooCommerceWebhookControllerTest#receive_hostileWebhook_crossTenantCouponCode_isRejected} for
     * the regression test proving this, mirroring D1's own regression test.
     */
    private CouponRedemption redeemViaWooCommerceOrder(
            String workspaceId, String couponCode, String orderId, java.math.BigDecimal orderTotal, String idempotencyKey) {
        return redemptionService.redeem(workspaceId, couponCode, orderId, orderTotal, null, idempotencyKey);
    }

    /**
     * Derives a stable idempotency key for one webhook delivery from fields WooCommerce itself
     * guarantees are stable across retries of the SAME delivery: the site url, the topic, and the
     * order id. Hashed (not a plain concatenation) purely to keep the key within {@code
     * idempotency_keys.idempotency_key}'s column bound, same defensive-length reasoning as {@code
     * ShopifyWebhookController#deriveIdempotencyKey} -- this key is never attacker-suppliable (no
     * caller-supplied idempotency key on this endpoint at all), so there is no squatting vector to
     * defend against, only a length bound to satisfy.
     */
    private static String deriveIdempotencyKey(String siteUrl, String topic, String orderId) {
        String canonical = siteUrl + "|" + topic + "|" + orderId;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "woocommerce:" + hex;
        } catch (Exception e) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_DERIVATION_FAILED",
                    "Failed to derive idempotency key",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
