package com.influora.integration.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.service.EscrowService;
import com.influora.service.PayoutReconciliationService;
import com.influora.service.WalletTopUpService;
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
 * Razorpay webhook receiver. This is the ONLY path that can move an escrow hold from PENDING to
 * FUNDED — the client's order-creation response is never trusted for that transition (API
 * contract §1.4: "escrow only funds on webhook verification").
 *
 * <p>[SEC: WebhookSignatureVerifier] Every request is HMAC-verified against the raw body before
 * any parsing/dispatch. An unverified or malformed payload is rejected with 400 and never reaches
 * {@code EscrowService}/{@code PayoutService}.
 *
 * <p>This endpoint is intentionally NOT under {@code /internal/meera/*} — it is Razorpay-facing,
 * not Meera-facing, and carries its own independent trust boundary (webhook signature, not a
 * service token / on-behalf JWT).
 */
@RestController
@RequestMapping("/webhooks/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final EscrowService escrowService;
    private final PayoutReconciliationService payoutReconciliationService;
    private final WalletTopUpService walletTopUpService;

    public RazorpayWebhookController(
            WebhookSignatureVerifier signatureVerifier,
            EscrowService escrowService,
            PayoutReconciliationService payoutReconciliationService,
            WalletTopUpService walletTopUpService) {
        this.signatureVerifier = signatureVerifier;
        this.escrowService = escrowService;
        this.payoutReconciliationService = payoutReconciliationService;
        this.walletTopUpService = walletTopUpService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader("X-Razorpay-Signature") String signature, @RequestBody String rawPayload) {
        if (!signatureVerifier.verify(rawPayload, signature)) {
            throw new ApiException(
                    "INVALID_WEBHOOK_SIGNATURE", "Webhook signature verification failed", HttpStatus.BAD_REQUEST);
        }

        WebhookEvent event = WebhookEvent.parse(rawPayload);
        switch (event.eventType()) {
            case "order.paid" -> dispatchFundingEvent(event);
            // [B11/H-8] `payment.captured` previously routed through the exact same
            // dispatchFundingEvent as `order.paid`, which REQUIRES payload.order.entity.receipt to
            // resolve which escrow hold/top-up to confirm. Razorpay's `payment.captured` delivery
            // commonly carries only payload.payment.entity (no order entity at all) — receipt then
            // resolves to null, escrowService.confirmFunded(null, ...) throws ESCROW_NOT_FOUND, this
            // method returns a non-2xx, and Razorpay retries the SAME unresolvable event forever.
            // `order.paid` already carries the full order entity (receipt always resolvable) and
            // fires for the same underlying payment, so a `payment.captured` that cannot be
            // resolved is now a safe no-op, never a thrown exception.
            case "payment.captured" -> dispatchFundingEventIfResolvable(event);
            case "payout.processed", "payout.reversed", "payout.rejected", "payout.cancelled" ->
                    dispatchPayoutEvent(event.eventType(), rawPayload);
            default -> {
                // Unhandled event types are acknowledged (200) but not acted on, per Razorpay's
                // webhook contract — returning a non-2xx for unknown-but-valid events causes
                // Razorpay to retry indefinitely.
            }
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Routes a captured-payment event to whichever domain created the underlying Razorpay order.
     * The order's {@code receipt} (round-tripped here as {@code event.entityId()}) is set at order
     * creation time: escrow orders ({@code EscrowService#initiateFund}) use the bare {@code
     * EscrowHold} id as the receipt; wallet top-up orders ({@code
     * WalletTopUpService#initiateTopUp}) prefix theirs with {@value
     * WalletTopUpService#RECEIPT_PREFIX} specifically so this dispatch can tell the two apart
     * without an extra DB lookup or a try/catch-based guess. A receipt that matches neither shape
     * falls through to the (pre-existing) escrow path, which will itself reject it with
     * {@code ESCROW_NOT_FOUND} rather than this method silently swallowing an unroutable event.
     */
    private void dispatchFundingEvent(WebhookEvent event) {
        String receipt = event.entityId();
        if (receipt != null && receipt.startsWith(WalletTopUpService.RECEIPT_PREFIX)) {
            String topUpId = receipt.substring(WalletTopUpService.RECEIPT_PREFIX.length());
            walletTopUpService.confirmCredited(
                    topUpId, event.paymentId(), event.amountInPaise(), event.currency());
            return;
        }
        escrowService.confirmFunded(
                event.entityId(), event.paymentId(), event.amountInPaise(), event.currency());
    }

    /**
     * [B11/H-8] Same routing as {@link #dispatchFundingEvent}, but ACKs instead of throwing when
     * the receipt cannot be resolved — see the {@code payment.captured} case comment in {@link
     * #receive}.
     */
    private void dispatchFundingEventIfResolvable(WebhookEvent event) {
        if (event.entityId() == null || event.entityId().isBlank()) {
            log.debug(
                    "payment.captured webhook carried no resolvable order receipt (paymentId={}) —"
                            + " acknowledged without action; order.paid is the authoritative funding trigger",
                    event.paymentId());
            return;
        }
        dispatchFundingEvent(event);
    }

    /** [B11/C-6] Parses payload.payout.entity and reconciles the matching {@code Payout} row. */
    private void dispatchPayoutEvent(String eventType, String rawPayload) {
        PayoutWebhookEvent event = PayoutWebhookEvent.parse(rawPayload);
        if (event.payoutId() == null || event.payoutId().isBlank()) {
            log.warn("{} webhook missing payload.payout.entity.id — acknowledged, not applied", eventType);
            return;
        }
        String status = event.status() != null ? event.status() : statusFromEventType(eventType);
        payoutReconciliationService.confirmExecuted(event.payoutId(), status, rawPayload);
    }

    private static String statusFromEventType(String eventType) {
        // Fallback only — payload.payout.entity.status is always preferred when present.
        return switch (eventType) {
            case "payout.processed" -> "processed";
            case "payout.reversed" -> "reversed";
            case "payout.rejected" -> "rejected";
            case "payout.cancelled" -> "cancelled";
            default -> eventType;
        };
    }

    /**
     * Event envelope extracted from Razorpay's real (nested) webhook payload shape via Jackson:
     *
     * <pre>{@code
     * {
     *   "event": "payment.captured",
     *   "payload": {
     *     "payment": { "entity": { "id": "...", "amount": 100000, "currency": "INR", ... } },
     *     "order":   { "entity": { "id": "...", "receipt": "<escrowHoldId>", "amount": 100000, ... } }
     *   }
     * }
     * }</pre>
     *
     * <p>{@code receipt} on the order entity is the escrow hold id — set by {@code
     * RazorpayClient.createOrder}'s {@code receiptId} param when the order was created, so it
     * round-trips back here as the entity to confirm.
     *
     * <p>A real integration would use the razorpay-java SDK's typed webhook event model once the
     * dependency is approved (see RazorpayClient's flagged gap); this Jackson-based extraction
     * keeps the module compiling without it while parsing the actual payload shape correctly.
     */
    record WebhookEvent(
            String eventType, String entityId, String paymentId, Long amountInPaise, String currency) {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        static WebhookEvent parse(String rawJson) {
            JsonNode root;
            try {
                root = MAPPER.readTree(rawJson);
            } catch (Exception e) {
                throw new ApiException(
                        "INVALID_WEBHOOK_PAYLOAD", "Webhook payload is not valid JSON", HttpStatus.BAD_REQUEST);
            }
            if (root == null || root.isMissingNode()) {
                throw new ApiException(
                        "INVALID_WEBHOOK_PAYLOAD", "Webhook payload is empty", HttpStatus.BAD_REQUEST);
            }

            String eventType = textOrNull(root.path("event"));

            JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
            JsonNode orderEntity = root.path("payload").path("order").path("entity");

            String paymentId = textOrNull(paymentEntity.path("id"));
            String receipt = textOrNull(orderEntity.path("receipt"));

            Long amountInPaise =
                    paymentEntity.hasNonNull("amount")
                            ? paymentEntity.path("amount").asLong()
                            : (orderEntity.hasNonNull("amount") ? orderEntity.path("amount").asLong() : null);
            String currency =
                    paymentEntity.hasNonNull("currency")
                            ? textOrNull(paymentEntity.path("currency"))
                            : textOrNull(orderEntity.path("currency"));

            return new WebhookEvent(eventType, receipt, paymentId, amountInPaise, currency);
        }

        private static String textOrNull(JsonNode node) {
            return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asText();
        }
    }

    /**
     * [B11/C-6] {@code payload.payout.entity} extraction for {@code payout.*} events —
     * RazorpayX's payout webhook shape:
     *
     * <pre>{@code
     * { "event": "payout.reversed", "payload": { "payout": { "entity": { "id": "pout_...", "status": "reversed", ... } } } }
     * }</pre>
     */
    record PayoutWebhookEvent(String payoutId, String status) {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        static PayoutWebhookEvent parse(String rawJson) {
            JsonNode root = readTreeOrThrow(rawJson);
            JsonNode payoutEntity = root.path("payload").path("payout").path("entity");
            return new PayoutWebhookEvent(textOrNull(payoutEntity.path("id")), textOrNull(payoutEntity.path("status")));
        }

        private static String textOrNull(JsonNode node) {
            return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asText();
        }

        private static JsonNode readTreeOrThrow(String rawJson) {
            try {
                JsonNode root = MAPPER.readTree(rawJson);
                if (root == null || root.isMissingNode()) {
                    throw new ApiException(
                            "INVALID_WEBHOOK_PAYLOAD", "Webhook payload is empty", HttpStatus.BAD_REQUEST);
                }
                return root;
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException(
                        "INVALID_WEBHOOK_PAYLOAD", "Webhook payload is not valid JSON", HttpStatus.BAD_REQUEST);
            }
        }
    }
}
