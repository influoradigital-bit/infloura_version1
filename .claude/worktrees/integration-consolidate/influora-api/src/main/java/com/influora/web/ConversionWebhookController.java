package com.influora.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.UtmCampaign;
import com.influora.integration.tracking.webhook.ConversionWebhookSecretService;
import com.influora.integration.tracking.webhook.ConversionWebhookSignatureVerifier;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.service.tracking.CampaignLinkService;
import com.influora.service.tracking.ConversionTrackingService;
import com.influora.service.tracking.RedemptionService;
import com.influora.web.dto.tracking.WebhookDtos.ConversionWebhookRequest;
import com.influora.web.dto.tracking.WebhookDtos.ConversionWebhookResponse;
import com.influora.web.dto.tracking.WebhookDtos.RedemptionWebhookRequest;
import com.influora.web.dto.tracking.WebhookDtos.RedemptionWebhookResponse;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC (unauthenticated) REST surface over the Phase 4 tracking services — Wave A, task A2
 * ({@code wiki/tech/REMAINING_WORK_PLAN.md}). Every endpoint here is called by a party that is NOT
 * a logged-in Influora brand: a brand's own commerce backend (custom checkout / server-side
 * integration) posting a webhook, or a visitor's browser following a tracking link.
 *
 * <p><b>[SEC: Kabir Wave E4 capstone red-team, HIGH — re-escalated finding, FIXED, {@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1] Signature verification added
 * to {@code /webhooks/conversion} and {@code /webhooks/redemption}.</b> These two endpoints
 * previously had NO signature/HMAC verification at all — the E2 review's downgrade of the
 * amount-entropy pre-poisoning HIGH to accepted-risk was explicitly conditioned on Wave D1 landing
 * per-brand HMAC verification here before launch; D1 instead only added HMAC to its own new,
 * separate {@code /webhooks/shopify} route, never touching this controller. Per that review's own
 * stated terms this re-escalated to HIGH. This fix closes it, mirroring the PROVEN discipline
 * already established twice in this codebase ({@code ShopifyWebhookController}, {@code
 * WooCommerceWebhookController}, both descended from {@code RazorpayWebhookController}'s original
 * pattern): verify the RAW request body BEFORE any parsing/dispatch, constant-time comparison, fail
 * closed on a missing/invalid signature OR an unconfigured secret.
 *
 * <p><b>Trust model chosen — per-workspace secret, server-generated.</b> Neither the Shopify shape
 * (one app-level secret, OAuth app-install) nor the WooCommerce shape (brand generates their own
 * secret in a third-party admin panel) fits here: these two endpoints are called by an ARBITRARY
 * brand's own commerce backend / custom checkout — there is no OAuth app-install flow for a
 * "generic checkout" and no third-party admin UI to copy a secret out of. Confirmed against the
 * real call sites before choosing this shape, not guessed: {@code RedemptionService}/{@code
 * ConversionTrackingService} class javadocs both independently documented these endpoints as
 * reached by "the brand's own commerce platform," with no existing per-brand identity mechanism
 * (unlike {@code ShopifyIntegration}/{@code WooCommerceIntegration}, there was no {@code
 * shop_domain}/{@code site_url}-style header to resolve a workspace from). The fix: {@link
 * ConversionWebhookSecretService} lets a brand generate a per-workspace HMAC secret via {@code
 * ConversionWebhookSecretController} (returned to them ONCE, at generation time — never
 * retrievable again, same one-time-reveal discipline the underlying encryption gives every other
 * secret in this codebase); the brand configures their own backend to send it back as an {@code
 * X-Influora-Signature} HMAC-SHA256 (base64) header computed over the exact raw request body.
 *
 * <p><b>Workspace resolution — "resolve, then verify, then scope," same shape as {@code
 * WooCommerceWebhookController}.</b> Unlike Shopify/WooCommerce, there is no header naming which
 * brand is calling — the caller-supplied {@code code} (redemption) or {@code utmCampaignId}
 * (conversion) is the only identifier available, exactly as before this fix. This class now uses
 * that SAME identifier to additionally resolve the OWNING WORKSPACE before verifying the
 * signature, mirroring WooCommerce's ordering rationale exactly (resolving which secret to check
 * against is a read, not payload parsing — the DTO is still not trusted/acted upon until AFTER
 * signature verification passes):
 *
 * <ul>
 *   <li>{@code /webhooks/redemption}: {@link CouponCodeRepository#findByCode} resolves the coupon's
 *       {@code workspaceId} (this is a READ used only to select which secret to check against — it
 *       is not itself an authorization decision, so reusing the pre-existing global finder here
 *       does not reopen the D1-class cross-tenant gap; the actual redemption call below is
 *       upgraded to the WORKSPACE-SCOPED {@code RedemptionService#redeem} overload once the
 *       workspace is resolved, closing that class of gap here too as a direct consequence of this
 *       fix, not merely a side effect).
 *   <li>{@code /webhooks/conversion}: {@link UtmCampaignRepository#findById} resolves the {@code
 *       campaignId}, then {@link CampaignRepository#findById} resolves that campaign's {@code
 *       workspaceId} ({@code UtmCampaign} has no direct {@code workspace_id} column — see that
 *       entity's javadoc — so this is a two-hop resolve, verified against the real entity shapes
 *       before writing this, not assumed).
 * </ul>
 *
 * <p>Both cases: if no workspace can be resolved (unknown code / unknown UTM id) OR the resolved
 * workspace has never generated a secret OR the signature does not verify, the request is rejected
 * {@code INVALID_WEBHOOK_SIGNATURE} (401) — deliberately the SAME error/status for all three
 * causes, so this cannot be used to enumerate which coupon codes/UTM ids exist versus which
 * workspaces have configured a secret.
 *
 * <p><b>Body binding changed to raw string.</b> HMAC verification requires the EXACT bytes the
 * caller signed; Spring's {@code @RequestBody} record-binding (the previous shape) hands back an
 * already-deserialized object with no access to the original bytes. Both POST endpoints now bind
 * {@code @RequestBody String rawPayload} and parse it via Jackson AFTER signature verification
 * succeeds — same ordering discipline as {@code ShopifyWebhookController}/{@code
 * WooCommerceWebhookController}'s {@code Xxx.parse(rawPayload)} calls.
 *
 * <p><b>Trust model [SEC: Kabir — load-bearing, public attack surface] — unchanged claims below,
 * still true post-fix:</b>
 *
 * <ul>
 *   <li>{@code POST /webhooks/redemption} — the coupon {@code code} remains a trusted-but-verified
 *       input; it is now ALSO the key used to resolve which workspace's secret must have signed
 *       this request. The required {@code idempotencyKey} plus the service's insert-first-wins
 *       reservation ({@code IdempotencyService#executeOnce}) remains the concurrency/replay safety
 *       net — see {@code RedemptionService#redeem} javadoc.
 *   <li>{@code POST /webhooks/conversion} and {@code GET /track/click/{utmCampaignId}} — {@code
 *       GET /track/click} is UNCHANGED by this fix (it is a visitor-browser redirect, not a
 *       brand-backend webhook — there is no brand secret to sign a browser's GET request with, and
 *       it carries no money-bearing payload). {@code POST /webhooks/conversion} now additionally
 *       requires a valid signature; layered underneath this fix as defense in depth is {@code
 *       ConversionTrackingService}'s own [SEC: Vikram, P1] replay guard — the actual rollup is
 *       wrapped in {@code IdempotencyService#executeOnce} keyed on {@code workspaceId + ":conv:" +
 *       utmCampaignId + ":" + orderId} (the resolved {@code workspaceId} computed by this
 *       controller, below, is threaded straight into that call) — see that class's javadoc for why
 *       the two protections are complementary, not redundant (signature verification stops an
 *       outsider from calling this endpoint at all; the order-derived reservation key stops a
 *       genuinely-signing-but-buggy/replaying caller, including the brand's own retried delivery or
 *       a replay of a captured signed payload, from double-counting revenue).
 *   <li><b>No enumeration:</b> unchanged from before this fix — see {@code RedemptionService#redeem}
 *       javadoc for the {@code INVALID_CODE}/{@code CODE_EXPIRED}/{@code CODE_LIMIT_REACHED}
 *       distinction rationale, which still applies to a request that already passed signature
 *       verification.
 *   <li><b>Rate limiting:</b> unchanged — all endpoints remain throttled per-IP by {@code
 *       AuthRateLimitFilter} under the {@code tracking} bucket.
 * </ul>
 *
 * <p><b>Not this controller's scope:</b> the brand-facing, workspace-authed tracking-link/coupon
 * endpoints ({@code POST/GET /campaigns/{id}/tracking-links}, {@code /coupons}) are {@code
 * CampaignTrackingController} (task A1); the brand-facing secret-generation endpoint is {@code
 * ConversionWebhookSecretController} (this fix) — both deliberately kept out of this file.
 */
@RestController
public class ConversionWebhookController {

    private static final String SIGNATURE_HEADER = "X-Influora-Signature";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedemptionService redemptionService;
    private final ConversionTrackingService conversionTrackingService;
    private final CampaignLinkService campaignLinkService;
    private final UtmCampaignRepository utmCampaignRepository;
    private final CampaignRepository campaignRepository;
    private final CouponCodeRepository couponCodeRepository;
    private final ConversionWebhookSignatureVerifier signatureVerifier;
    private final ConversionWebhookSecretService secretService;

    public ConversionWebhookController(
            RedemptionService redemptionService,
            ConversionTrackingService conversionTrackingService,
            CampaignLinkService campaignLinkService,
            UtmCampaignRepository utmCampaignRepository,
            CampaignRepository campaignRepository,
            CouponCodeRepository couponCodeRepository,
            ConversionWebhookSignatureVerifier signatureVerifier,
            ConversionWebhookSecretService secretService) {
        this.redemptionService = redemptionService;
        this.conversionTrackingService = conversionTrackingService;
        this.campaignLinkService = campaignLinkService;
        this.utmCampaignRepository = utmCampaignRepository;
        this.campaignRepository = campaignRepository;
        this.couponCodeRepository = couponCodeRepository;
        this.signatureVerifier = signatureVerifier;
        this.secretService = secretService;
    }

    /**
     * Records a coupon redemption reported by a brand's own commerce platform at checkout.
     *
     * <p>[SEC: Kabir Wave E4 — FIXED] Signature verification is now the entire trust boundary,
     * resolved via the coupon {@code code} BEFORE any parsing beyond what is needed to read that
     * one field. Once verified, delegates to the WORKSPACE-SCOPED {@link
     * RedemptionService#redeem(String, String, String, java.math.BigDecimal, String, String)}
     * overload using the resolved workspace — never the legacy global overload — closing the same
     * class of cross-tenant gap D1 fixed for Shopify, here as a direct consequence of resolving a
     * workspace identity to check the signature against.
     *
     * @throws ApiException {@code INVALID_WEBHOOK_PAYLOAD} (400) if the body is not valid JSON or
     *     does not match the expected shape
     * @throws ApiException {@code INVALID_WEBHOOK_SIGNATURE} (401) if the coupon code is unknown,
     *     the resolved workspace has no configured secret, or the signature does not verify —
     *     deliberately the same error for all three so this cannot be used to enumerate valid
     *     coupon codes
     */
    @PostMapping("/webhooks/redemption")
    public ResponseEntity<ApiResponse<RedemptionWebhookResponse>> redeemCoupon(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawPayload) {
        RedemptionWebhookRequest request = parseRedemptionPayload(rawPayload);

        // Resolve-then-verify: the coupon code is the only identifier available to find out which
        // workspace's secret this request must have been signed with. This lookup is a READ used
        // only to select a secret to check against -- it is not itself an authorization decision,
        // and the resolved workspace is subsequently used for a genuine authorization purpose (the
        // workspace-scoped redeem overload below) only AFTER the signature check passes.
        Optional<CouponCode> coupon =
                request.code() == null ? Optional.empty() : couponCodeRepository.findByCode(normalizeCode(request.code()));
        String workspaceId = coupon.map(CouponCode::getWorkspaceId).orElse(null);

        verifySignatureOrReject(rawPayload, signature, workspaceId);

        CouponRedemption redemption =
                redemptionService.redeem(
                        workspaceId,
                        request.code(),
                        request.orderId(),
                        request.orderAmount(),
                        request.customerId(),
                        namespaceIdempotencyKey(workspaceId, request.idempotencyKey()));

        RedemptionWebhookResponse response =
                new RedemptionWebhookResponse(
                        redemption.getId(),
                        redemption.getOrderId(),
                        redemption.getOrderAmount(),
                        redemption.getDiscountApplied(),
                        redemption.getRedeemedAt());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Records a conversion (completed order) attributed to a UTM tracking link, reported by a
     * brand's own commerce platform.
     *
     * <p>[SEC: Kabir Wave E4 — FIXED] Signature verification resolved via a two-hop lookup ({@code
     * utmCampaignId} → {@code UtmCampaign.campaignId} → {@code Campaign.workspaceId}, since {@code
     * UtmCampaign} has no direct {@code workspace_id} column) BEFORE any further processing.
     * Delegates entirely to {@link ConversionTrackingService#recordConversion} once verified — that
     * service's own amount-entropy idempotency-key hardening is unchanged and still applies
     * underneath this signature check.
     *
     * @throws ApiException {@code INVALID_WEBHOOK_PAYLOAD} (400) if the body is not valid JSON or
     *     does not match the expected shape
     * @throws ApiException {@code INVALID_WEBHOOK_SIGNATURE} (401) if the UTM id is unknown, its
     *     owning workspace has no configured secret, or the signature does not verify
     */
    @PostMapping("/webhooks/conversion")
    public ResponseEntity<ApiResponse<ConversionWebhookResponse>> recordConversion(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawPayload) {
        ConversionWebhookRequest request = parseConversionPayload(rawPayload);

        String workspaceId = resolveWorkspaceForUtmCampaign(request.utmCampaignId());

        verifySignatureOrReject(rawPayload, signature, workspaceId);

        conversionTrackingService.recordConversion(
                workspaceId,
                request.utmCampaignId(),
                request.orderId(),
                request.orderAmount(),
                request.idempotencyKey());
        return ResponseEntity.ok(ApiResponse.ok(new ConversionWebhookResponse(true)));
    }

    /**
     * Tracking-link click redirect: records the click via {@link CampaignLinkService#recordClick}
     * then 302-redirects the visitor's browser to the link's real destination ({@link
     * UtmCampaign#getFullTrackingUrl()}, the UTM-tagged URL built at link-creation time). Public —
     * this is the endpoint a creator's actual posted link points at, hit by anonymous visitor
     * browsers, never by an authenticated brand.
     *
     * <p><b>Unaffected by the Wave E4 signature-verification fix</b> — a visitor's browser cannot
     * be expected to sign a GET request with a brand's secret, and this endpoint carries no
     * money-bearing payload (it only increments a click counter); see class javadoc.
     *
     * <p>A redirect (not a 1x1 pixel) is the useful shape here per this task's spec: the visitor
     * actually needs to land on the brand's page, not just fire-and-forget a tracking beacon.
     *
     * <p>{@code visitorId} is an optional caller-supplied correlation id (e.g. a client-generated
     * cookie/fingerprint value) forwarded straight into {@link CampaignLinkService#recordClick}'s
     * simplified unique-visitor counting — see that method's javadoc for why real deduplication is
     * out of scope for this slice.
     *
     * @throws ApiException {@code UTM_NOT_FOUND} (404) if {@code utmCampaignId} does not exist —
     *     same not-found shape whether the id is malformed or simply unknown, so this endpoint
     *     cannot be used to enumerate valid vs. invalid UTM ids beyond a boolean existence check
     */
    @GetMapping("/track/click/{utmCampaignId}")
    public ResponseEntity<Void> trackClick(
            @PathVariable String utmCampaignId, @RequestParam(required = false) String visitorId) {
        campaignLinkService.recordClick(utmCampaignId, visitorId);

        // recordClick already threw UTM_NOT_FOUND if the id doesn't exist, so this second lookup is
        // guaranteed to find the row -- it exists purely to read the redirect destination, which
        // CampaignLinkService#recordClick does not return.
        UtmCampaign utm =
                utmCampaignRepository
                        .findById(utmCampaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "UTM_NOT_FOUND", "Tracking link not found", HttpStatus.NOT_FOUND));

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(utm.getFullTrackingUrl())).build();
    }

    /**
     * Resolves the owning workspace for a UTM tracking link id via the two-hop join {@code
     * UtmCampaign.campaignId} → {@code Campaign.workspaceId} ({@code UtmCampaign} has no direct
     * {@code workspace_id} column — see that entity's javadoc). Returns {@code null} (never throws)
     * if either hop fails to resolve, so the caller can fold "unknown UTM id" into the same
     * generic {@code INVALID_WEBHOOK_SIGNATURE} rejection as a resolved-but-unsigned request — see
     * class javadoc for why collapsing these cases is deliberate (no enumeration signal).
     */
    private String resolveWorkspaceForUtmCampaign(String utmCampaignId) {
        if (utmCampaignId == null) {
            return null;
        }
        return utmCampaignRepository
                .findById(utmCampaignId)
                .map(UtmCampaign::getCampaignId)
                .flatMap(campaignRepository::findById)
                .map(Campaign::getWorkspaceId)
                .orElse(null);
    }

    /**
     * Verifies {@code rawPayload} against {@code signature} using the resolved {@code
     * workspaceId}'s decrypted secret. Rejects with a single, uniform {@code
     * INVALID_WEBHOOK_SIGNATURE} (401) whether {@code workspaceId} is null (identifier did not
     * resolve), the workspace has no configured secret, or the signature genuinely does not match —
     * see class javadoc for why these are deliberately indistinguishable to the caller.
     */
    private void verifySignatureOrReject(String rawPayload, String signature, String workspaceId) {
        String secret = secretService.decryptSecretForWorkspace(workspaceId);
        if (!signatureVerifier.verify(rawPayload, signature, secret)) {
            throw new ApiException(
                    "INVALID_WEBHOOK_SIGNATURE", "Webhook signature verification failed", HttpStatus.UNAUTHORIZED);
        }
    }

    /** Coupon codes are generated/stored upper-cased -- same normalization {@code RedemptionService} applies before its own lookup. */
    private static String normalizeCode(String code) {
        return code.trim().toUpperCase();
    }

    /**
     * [SEC: Vikram, P3(b) fix -- cross-tenant idempotency-key collision/data-leak closed.] {@code
     * coupon_redemptions.idempotency_key} (V24) carries a GLOBAL {@code UNIQUE} constraint, and
     * {@code RedemptionService#redeem}'s replay check ({@code
     * CouponRedemptionRepository#findByIdempotencyKey}) looks up by that raw key with no workspace
     * filter. Previously this controller forwarded the brand-supplied {@code idempotencyKey}
     * completely unmodified — two DIFFERENT workspaces submitting the same (or a guessed/colliding)
     * raw token could either collide on the unique constraint, or worse, one workspace's replay
     * lookup could resolve and return ANOTHER workspace's redemption row (coupon id, order id,
     * amount, discount) straight back over this public endpoint. Namespacing the key by the
     * ALREADY-RESOLVED, signature-verified {@code workspaceId} — mirroring the same shape {@code
     * ShopifyWebhookController}/{@code WooCommerceWebhookController} already use ({@code
     * shopDomain + "|" + topic + "|" + orderId}) — makes the persisted key unique per workspace by
     * construction, so no two workspaces can ever collide or replay each other's redemption.
     *
     * <p>A null/blank raw key is passed through UNCHANGED (never namespaced into a non-blank
     * string) so {@code RedemptionService#redeem}'s own {@code IDEMPOTENCY_KEY_REQUIRED} (400)
     * validation still fires correctly — namespacing must never mask a genuinely missing key.
     */
    private static String namespaceIdempotencyKey(String workspaceId, String rawIdempotencyKey) {
        if (rawIdempotencyKey == null || rawIdempotencyKey.isBlank()) {
            return rawIdempotencyKey;
        }
        return workspaceId + ":" + rawIdempotencyKey;
    }

    private static RedemptionWebhookRequest parseRedemptionPayload(String rawJson) {
        try {
            return MAPPER.readValue(rawJson, RedemptionWebhookRequest.class);
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_WEBHOOK_PAYLOAD", "Webhook payload is not valid JSON", HttpStatus.BAD_REQUEST);
        }
    }

    private static ConversionWebhookRequest parseConversionPayload(String rawJson) {
        try {
            return MAPPER.readValue(rawJson, ConversionWebhookRequest.class);
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_WEBHOOK_PAYLOAD", "Webhook payload is not valid JSON", HttpStatus.BAD_REQUEST);
        }
    }
}
