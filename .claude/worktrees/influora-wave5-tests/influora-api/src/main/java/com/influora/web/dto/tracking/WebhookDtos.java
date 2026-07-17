package com.influora.web.dto.tracking;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public (unauthenticated) webhook/pixel DTOs for {@code ConversionWebhookController} — Wave A,
 * task A2 ({@code wiki/tech/REMAINING_WORK_PLAN.md}). These requests are sent by external commerce
 * systems (a brand's Shopify/WooCommerce/checkout backend) or by a visitor's browser following a
 * tracking link, never by a logged-in brand — there is no {@code AuthPrincipal} / workspace at
 * these call sites. See {@code ConversionWebhookController} class javadoc for the trust model.
 *
 * <p><b>Not this file's scope:</b> the brand-authenticated tracking-link/coupon-generation DTOs
 * (task A1) live in the separate {@code TrackingDtos.java} — this file only carries the public
 * webhook/redirect surface so the two parallel tasks never touch the same file.
 */
public final class WebhookDtos {

    private WebhookDtos() {}

    // ------------------------------------------------------------------------------------------
    // POST /webhooks/redemption
    // ------------------------------------------------------------------------------------------

    /**
     * Request body for {@code POST /webhooks/redemption}. {@code idempotencyKey} is required (see
     * {@code RedemptionService#redeem} javadoc) — the brand's commerce platform must derive it
     * deterministically (e.g. a hash of {@code code+orderId}) so a retried webhook delivery is a
     * safe no-op rather than a double redemption. {@code customerId} is nullable (anonymous
     * checkouts). No {@code workspaceId} field exists here by design — the coupon {@code code}
     * itself is the only trusted input that scopes this request; a caller-supplied workspace id
     * would be forgeable and is never accepted.
     */
    public record RedemptionWebhookRequest(
            String code,
            String orderId,
            BigDecimal orderAmount,
            String customerId,
            String idempotencyKey) {}

    /**
     * Response for a successful (or idempotently-replayed) redemption. Deliberately minimal — no
     * coupon internals (discount type/value, workspace id, creator id) are echoed back to the
     * calling commerce system beyond what it needs to reconcile the order, avoiding leaking
     * campaign/creator relationship data through a public endpoint.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RedemptionWebhookResponse(
            String redemptionId,
            String orderId,
            BigDecimal orderAmount,
            BigDecimal discountApplied,
            Instant createdAt) {}

    // ------------------------------------------------------------------------------------------
    // POST /webhooks/conversion
    // ------------------------------------------------------------------------------------------

    /**
     * Request body for {@code POST /webhooks/conversion}. {@code utmCampaignId} is the tracking
     * link's own unguessable ULID (see {@code ConversionTrackingService} javadoc) — the only
     * trusted input; no {@code workspaceId} is accepted here either.
     *
     * <p>{@code idempotencyKey} [E2 audit finding #2, HIGH — fixed; QA-REJECTED 2026-07-07, see
     * {@code wiki/errors/idempotency-fixes-E2-review.md}, then reworked here] is OPTIONAL, not
     * required. This is a PUBLIC webhook already reachable by external commerce systems we do not
     * control (see {@code ConversionWebhookController} class javadoc) — making the field required
     * would have been a breaking contract change with no migration window for a brand's checkout
     * integration that has not yet been updated to send it. If the caller supplies {@code
     * idempotencyKey}, it is used as given (should still be derived deterministically, e.g. a hash
     * of {@code utmCampaignId+orderId+orderAmount}) — <b>but MUST NOT start with the reserved
     * {@code "convd:"} prefix</b> ([SEC: Kabir, E2 HIGH-2 — fixed], rejected 400 if it does; see
     * {@code ConversionTrackingService#DERIVED_KEY_PREFIX} javadoc for why) and MUST NOT exceed 128
     * characters ([E2 LOW-1 — fixed], rejected 400 if it does). If omitted (null/blank), the
     * service derives a deterministic fallback key itself — a SHA-256 hash under the reserved
     * {@code "convd:"} namespace over {@code utmCampaignId+orderId+orderAmount} (the money-bearing
     * fields; hashing the amount in is what defeats an attacker pre-poisoning a predicted key with a
     * fake amount) — see {@code ConversionTrackingService#recordConversion} javadoc for the exact
     * derivation — so two keyless deliveries of the SAME order+amount still dedup correctly, while
     * two keyless deliveries of DIFFERENT orders or amounts do not collide.
     */
    public record ConversionWebhookRequest(
            String utmCampaignId, String orderId, BigDecimal orderAmount, String idempotencyKey) {}

    /** Simple acknowledgement — this webhook has no useful entity to hand back to the caller. */
    public record ConversionWebhookResponse(boolean recorded) {}

    // ------------------------------------------------------------------------------------------
    // GET /track/click/{utmCampaignId}
    // ------------------------------------------------------------------------------------------
    // No request/response DTO needed -- this endpoint is a 302 redirect (see
    // ConversionWebhookController#trackClick); the visitor's browser never sees a JSON body on the
    // success path, only a Location header. Errors still go through GlobalExceptionHandler's
    // standard ApiResponse-shaped JSON body.
}
