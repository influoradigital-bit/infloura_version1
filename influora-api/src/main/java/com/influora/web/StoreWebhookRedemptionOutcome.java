package com.influora.web;

import com.influora.common.ApiException;
import java.util.Set;

/**
 * Classifies a {@link com.influora.service.tracking.RedemptionService#redeem} failure as TERMINAL
 * (this delivery will never succeed, however many times it is re-sent) or TRANSIENT (a retry can
 * legitimately succeed). Shared by {@link ShopifyWebhookController} and {@link
 * WooCommerceWebhookController} — the two endpoints whose caller is a third-party retry engine
 * rather than a client that reads our response.
 *
 * <p><b>[F-0725] Why this exists.</b> Both controllers wrapped the redemption in a {@code try} that
 * caught only {@code AlreadyCompleted}/{@code AlreadyInProgress}. Every other failure escaped the
 * handler and was mapped straight to a non-2xx by {@code GlobalExceptionHandler}. The failure mode
 * is not the rare case, it is the ordinary one: the controller has already decided to act because
 * the order carries SOME discount code, but most codes on a real store are the merchant's own —
 * {@code FREESHIP}, a sale code that expired last week, a promo at its usage cap — and none of
 * those is an Influora coupon. Each produced {@code INVALID_CODE} 404 / {@code CODE_EXPIRED} 400 /
 * {@code CODE_LIMIT_REACHED} 400.
 *
 * <p>Shopify and WooCommerce both treat any non-2xx as a failed delivery and retry it, and Shopify
 * removes a webhook subscription after roughly 48h of continuous failures. Nothing dampened the
 * loop either: {@code IdempotencyService#executeOnce} marks the reservation FAILED and {@code
 * reclaimFailedForRetry} hands it back on the next attempt (IdempotencyService.java:141-153), so
 * every retry genuinely re-ran the redemption and re-failed identically. A store connected to
 * Influora would therefore hammer this endpoint until the platform disconnected it — and the brand
 * would see only that tracking had stopped.
 *
 * <p><b>Why not simply swallow everything.</b> A blanket {@code catch (Exception)} returning 200
 * would also swallow a database outage, a decryption failure, or any other genuine fault — the
 * exact cases where the platform's retry is the recovery mechanism we want. So the split is by
 * whether a retry could ever change the outcome:
 *
 * <ul>
 *   <li>TERMINAL — the coupon is not ours, is expired, is at its cap, or carries a discount type we
 *       cannot price. Re-sending the identical order body produces the identical result forever.
 *       Acknowledge 200 and log; there is nothing to recover.
 *   <li>TRANSIENT — everything else, explicitly including {@code IDEMPOTENCY_KEY_IN_PROGRESS}
 *       (RedemptionService.java:203-206), which resolves the moment the concurrent attempt commits.
 *       Let it escape and keep the non-2xx so the platform retries.
 * </ul>
 *
 * <p>This mirrors the decision the controllers already make one branch earlier, where an order with
 * NO discount code returns 200 rather than an error — the same "nothing to attribute, not a
 * failure" judgment, applied consistently to an order whose code simply is not ours.
 *
 * <p><b>Deliberately not applied to {@code ConversionWebhookController}.</b> That endpoint's caller
 * is the brand's own server calling our documented API, not a platform retry engine; a brand
 * posting an unknown coupon code should be told so with a 404. Converting its errors to 200 would
 * hide a real integration mistake from the only party able to fix it.
 */
final class StoreWebhookRedemptionOutcome {

    /**
     * Error codes from {@code RedemptionWriter#validateCode} / {@code
     * RedemptionWriter#calculateDiscount} that describe the ORDER, not our availability. Every one
     * of these is a pure function of the request body and the coupon row, so an identical redelivery
     * returns an identical error.
     *
     * <p>{@code INVALID_CODE} covers both "no such coupon" and the cross-workspace rejection
     * (RedemptionWriter.java:194-196), which deliberately share one code so the endpoint leaks no
     * enumeration signal — that shared code is exactly why this must be terminal for both.
     *
     * <p>{@code UNSUPPORTED_DISCOUNT_TYPE} is a 500 and genuinely indicates bad coupon data, but a
     * retry cannot repair a malformed row either. It is terminal here and logged at ERROR so it
     * stays visible, rather than being converted into an endless retry that also buries it.
     */
    private static final Set<String> TERMINAL_CODES =
            Set.of("INVALID_CODE", "CODE_EXPIRED", "CODE_LIMIT_REACHED", "UNSUPPORTED_DISCOUNT_TYPE");

    private StoreWebhookRedemptionOutcome() {}

    /**
     * {@code true} when re-delivering the same webhook body could never produce a different result,
     * so the delivery should be acknowledged 200 instead of driving a retry loop.
     */
    static boolean isTerminal(ApiException e) {
        return e != null && TERMINAL_CODES.contains(e.getCode());
    }

    /**
     * {@code true} for a terminal outcome that also indicates bad data on our side rather than an
     * ordinary non-Influora coupon — logged at ERROR by the controllers so acknowledging the
     * delivery does not make it invisible.
     */
    static boolean isDataDefect(ApiException e) {
        return e != null && "UNSUPPORTED_DISCOUNT_TYPE".equals(e.getCode());
    }
}
