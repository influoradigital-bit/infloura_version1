package com.influora.integration.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.domain.entity.Invoice;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.service.BrandContextService;
import com.influora.service.BrandContextService.BillingRecipient;
import com.influora.service.EscrowService;
import com.influora.service.IdempotencyService;
import com.influora.service.PayoutReconciliationService;
import com.influora.service.WalletTopUpService;
import com.influora.service.billing.InvoiceService;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.notification.event.InvoiceReadyEvent;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import com.influora.service.notification.event.SubscriptionPaymentFailedEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    /** [W1-6] IdempotencyService scope for subscription.* webhook deliveries — distinct from every other scope in this codebase so a colliding raw key string can never shadow an unrelated caller (see IdempotencyService's own composite-key javadoc). */
    private static final String SUBSCRIPTION_WEBHOOK_IDEMPOTENCY_SCOPE = "razorpay.subscription.webhook";

    private final WebhookSignatureVerifier signatureVerifier;
    private final EscrowService escrowService;
    private final PayoutReconciliationService payoutReconciliationService;
    private final WalletTopUpService walletTopUpService;
    private final SubscriptionService subscriptionService;
    private final InvoiceService invoiceService;
    private final BrandContextService brandContextService;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public RazorpayWebhookController(
            WebhookSignatureVerifier signatureVerifier,
            EscrowService escrowService,
            PayoutReconciliationService payoutReconciliationService,
            WalletTopUpService walletTopUpService,
            SubscriptionService subscriptionService,
            InvoiceService invoiceService,
            BrandContextService brandContextService,
            IdempotencyService idempotencyService,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.signatureVerifier = signatureVerifier;
        this.escrowService = escrowService;
        this.payoutReconciliationService = payoutReconciliationService;
        this.walletTopUpService = walletTopUpService;
        this.subscriptionService = subscriptionService;
        this.invoiceService = invoiceService;
        this.brandContextService = brandContextService;
        this.idempotencyService = idempotencyService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
            // [W1-6] subscription.activated/charged both leave (or bring) the subscription ACTIVE
            // and carry a real billing period, so both re-sync the period; only subscription.charged
            // additionally produces an Invoice (a real Razorpay charge happened). subscription.halted
            // never touches the period — Razorpay exhausted its own dunning retries, this is a
            // status-only transition (see SubscriptionDunningJob's own halt path for the local
            // safety-net equivalent).
            // [Track B, P2] subscription.pending is now routed too — Razorpay fires this when a
            // renewal charge attempt fails but its own dunning retries have not yet been exhausted
            // (that terminal state is subscription.halted, above). Same status-only shape as halted
            // (no period touched — see SubscriptionDunningJob's currentPeriodEnd-anchor javadoc,
            // which already documents this case), but the target status is PAST_DUE and the
            // publisher is SubscriptionPaymentFailedEvent, whose listener
            // (NotificationListener.on(SubscriptionPaymentFailedEvent)) was already wired ahead of
            // this follow-up.
            case "subscription.activated" ->
                    handleSubscriptionEvent(rawPayload, SubscriptionStatus.ACTIVE, true, false);
            case "subscription.charged" ->
                    handleSubscriptionEvent(rawPayload, SubscriptionStatus.ACTIVE, true, true);
            case "subscription.halted" ->
                    handleSubscriptionEvent(rawPayload, SubscriptionStatus.HALTED, false, false);
            case "subscription.pending" ->
                    handleSubscriptionEvent(rawPayload, SubscriptionStatus.PAST_DUE, false, false);
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
     * [W1-6] Shared entry point for {@code subscription.activated}/{@code subscription.charged}/
     * {@code subscription.halted}. Parses {@code payload.subscription.entity} (and, for a charge,
     * {@code payload.payment.entity}), then applies the transition via {@link
     * SubscriptionService#applySubscriptionWebhookUpdate} — the ONLY place local subscription state
     * is written from Razorpay's side (class javadoc there).
     *
     * <p>[B11 lesson] A subscription webhook with no resolvable {@code subscription.entity.id} or
     * {@code notes.workspaceId} is a structurally unroutable event shape — exactly the failure mode
     * that caused the {@code payment.captured} retry storm this codebase already fixed once (see
     * {@link #dispatchFundingEventIfResolvable}). It is acknowledged (200, no-op) here for the same
     * reason, BEFORE any idempotency-key reservation, rather than thrown as a 4xx that Razorpay would
     * retry forever. A downstream failure once the event IS resolvable (e.g. a real {@code
     * WORKSPACE_NOT_FOUND}) is a genuine anomaly and is deliberately left to propagate as a non-2xx
     * — that case is not a payload-shape problem and SHOULD be retried/investigated.
     *
     * <p><b>Idempotency:</b> wrapped in {@link IdempotencyService#executeOnce}, keyed on {@code
     * eventType + subscriptionId + the webhook envelope's own created_at} — {@code created_at} is
     * stable across Razorpay's retries of the SAME delivery (already relied on for staleness
     * detection in {@code SubscriptionService#applySubscriptionWebhookUpdate}), so this reservation
     * is a genuine per-delivery dedupe, not just per-subscription. This is on top of (not a
     * replacement for) {@code applySubscriptionWebhookUpdate}'s own idempotent upsert and {@code
     * InvoiceService#generateInvoiceFromWebhook}'s own dedupe-by-{@code razorpayPaymentReference} —
     * matching the same deliberate two-layer defense {@code ShopifyWebhookController} documents.
     *
     * <p><b>Transaction/event-publish ordering:</b> the whole sequence (status write, optional
     * invoice generation, optional notification event) runs inside one explicit {@link
     * #transactionTemplate}-managed transaction — {@code NotificationListener}'s handlers are {@code
     * @TransactionalEventListener(AFTER_COMMIT)} (D2), which silently never fires for an event
     * published with no transaction active; this controller method itself carries no {@code
     * @Transactional} (a private-method annotation here would be a no-op under Spring AOP
     * self-invocation — the exact trap W1-7 fixes elsewhere), so the explicit template is required.
     * Mirrors {@code SubscriptionDunningJob#publishHaltedEmail}'s identical idiom.
     *
     * @param updatePeriod whether this event carries an authoritative new billing period —
     *     true for activated/charged (a real period boundary), false for halted/pending
     *     (status-only, the existing period is left untouched — {@code SubscriptionDunningJob}'s
     *     javadoc documents why {@code subscription.pending} must not touch the period).
     * @param generateInvoice true only for {@code subscription.charged} — a real Razorpay charge
     *     happened and must produce an {@link Invoice} row via the existing Phase 4 path.
     */
    private void handleSubscriptionEvent(
            String rawPayload, SubscriptionStatus targetStatus, boolean updatePeriod, boolean generateInvoice) {
        SubscriptionWebhookEvent event = SubscriptionWebhookEvent.parse(rawPayload);

        if (event.subscriptionId() == null
                || event.subscriptionId().isBlank()
                || event.workspaceId() == null
                || event.workspaceId().isBlank()) {
            log.warn(
                    "{} webhook missing payload.subscription.entity.id or notes.workspaceId —"
                            + " acknowledged without action to avoid a Razorpay retry storm on an"
                            + " unresolvable event shape",
                    event.eventType());
            return;
        }

        String idempotencyKey = deriveSubscriptionIdempotencyKey(event);
        try {
            idempotencyService.executeOnce(
                    idempotencyKey,
                    event.workspaceId(),
                    SUBSCRIPTION_WEBHOOK_IDEMPOTENCY_SCOPE,
                    () -> {
                        applySubscriptionEventTransactionally(event, targetStatus, updatePeriod, generateInvoice);
                        return null;
                    });
        } catch (IdempotencyService.AlreadyCompletedException
                | IdempotencyService.AlreadyInProgressException replay) {
            log.info(
                    "Razorpay subscription webhook replay/duplicate: eventType={}, subscriptionId={},"
                            + " workspaceId={} — no-op",
                    event.eventType(),
                    event.subscriptionId(),
                    event.workspaceId());
        }
    }

    private void applySubscriptionEventTransactionally(
            SubscriptionWebhookEvent event,
            SubscriptionStatus targetStatus,
            boolean updatePeriod,
            boolean generateInvoice) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    Instant periodStart = updatePeriod ? event.currentPeriodStart() : null;
                    Instant periodEnd = updatePeriod ? event.currentPeriodEnd() : null;

                    subscriptionService.applySubscriptionWebhookUpdate(
                            event.subscriptionId(),
                            event.workspaceId(),
                            event.razorpayPlanId(),
                            targetStatus,
                            periodStart,
                            periodEnd,
                            event.createdAt());

                    if (generateInvoice) {
                        generateInvoiceAndNotify(event);
                    }
                    if (targetStatus == SubscriptionStatus.HALTED) {
                        publishSubscriptionHaltedEmail(event.workspaceId(), event.subscriptionId());
                    } else if (targetStatus == SubscriptionStatus.PAST_DUE) {
                        publishSubscriptionPaymentFailedEmail(event.workspaceId(), event.subscriptionId());
                    }
                });
    }

    /**
     * {@code subscription.charged} only — generates the {@link Invoice} row via the existing Phase
     * 4 path ({@link InvoiceService#generateInvoiceFromWebhook}, which already carries its own
     * dedupe-by-{@code razorpayPaymentReference}) and, if a billing recipient can be resolved,
     * publishes {@link InvoiceReadyEvent}. A charged event with no resolvable payment id/amount is
     * logged and skipped — the subscription's status/period were still correctly applied by the
     * caller; only the invoice-and-email side effect is missing and needs manual follow-up.
     */
    private void generateInvoiceAndNotify(SubscriptionWebhookEvent event) {
        if (event.paymentId() == null || event.paymentId().isBlank() || event.amountInPaise() == null) {
            log.warn(
                    "subscription.charged webhook for subscription {} (workspace {}) carried no"
                            + " resolvable payload.payment.entity id/amount — subscription status was"
                            + " still updated, but no Invoice row was generated; needs manual follow-up",
                    event.subscriptionId(),
                    event.workspaceId());
            return;
        }

        Invoice invoice =
                invoiceService.generateInvoiceFromWebhook(
                        event.workspaceId(),
                        event.paymentId(),
                        event.amountInPaise(),
                        event.currentPeriodStart(),
                        event.currentPeriodEnd(),
                        event.createdAt());

        BillingRecipient recipient = brandContextService.resolveBillingRecipient(event.workspaceId());
        if (recipient != null && recipient.email() != null) {
            String downloadUrl = invoiceService.getInvoiceDownloadUrl(invoice);
            eventPublisher.publishEvent(
                    new InvoiceReadyEvent(
                            recipient.userId(),
                            event.workspaceId(),
                            invoice.getId(),
                            recipient.email(),
                            downloadUrl,
                            invoice.getAmount()));
        }
    }

    /**
     * {@code subscription.halted} only — mirrors {@code SubscriptionDunningJob#publishHaltedEmail}
     * exactly, including sharing the same {@link SubscriptionHaltedEvent} shape so a genuine
     * double-fire (this webhook AND the local dunning safety net both eventually firing for the
     * same halt) naturally dedupes at the {@code EmailOutbox} idempotency-key layer instead of
     * double-emailing the brand.
     */
    private void publishSubscriptionHaltedEmail(String workspaceId, String subscriptionId) {
        BillingRecipient recipient = brandContextService.resolveBillingRecipient(workspaceId);
        if (recipient != null && recipient.email() != null) {
            eventPublisher.publishEvent(
                    new SubscriptionHaltedEvent(recipient.userId(), workspaceId, subscriptionId, recipient.email()));
        }
    }

    /**
     * [Track B, P2] {@code subscription.pending} only — Razorpay attempted a renewal charge and it
     * failed, but Razorpay's own dunning retries are not yet exhausted (that terminal state is
     * {@code subscription.halted}, handled by {@link #publishSubscriptionHaltedEmail}). Mirrors that
     * method's shape exactly, publishing {@link SubscriptionPaymentFailedEvent} instead — see that
     * event's own javadoc for why the listener ({@code
     * NotificationListener.on(SubscriptionPaymentFailedEvent)}) was already wired ahead of this.
     */
    private void publishSubscriptionPaymentFailedEmail(String workspaceId, String subscriptionId) {
        BillingRecipient recipient = brandContextService.resolveBillingRecipient(workspaceId);
        if (recipient != null && recipient.email() != null) {
            eventPublisher.publishEvent(
                    new SubscriptionPaymentFailedEvent(
                            recipient.userId(), workspaceId, subscriptionId, recipient.email()));
        }
    }

    /**
     * Stable per-delivery idempotency key: {@code eventType + subscriptionId + created_at}. {@code
     * created_at} is the webhook envelope's own timestamp — stable across Razorpay's retries of the
     * SAME delivery (the same assumption {@code SubscriptionService#applySubscriptionWebhookUpdate}
     * already relies on for its staleness check), so this reserves per-delivery, not merely
     * per-subscription — two genuinely distinct charges for the same subscription get distinct keys.
     */
    private static String deriveSubscriptionIdempotencyKey(SubscriptionWebhookEvent event) {
        String createdAtPart =
                event.createdAt() != null ? String.valueOf(event.createdAt().getEpochSecond()) : "unknown";
        return "razorpay-webhook:" + event.eventType() + ":" + event.subscriptionId() + ":" + createdAtPart;
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

            // [W1-6 incidental fix] NOT a ternary -- `cond ? primitiveLong : (cond2 ? primitiveLong2
            // : null)` silently compiles to primitive `long` per JLS 15.25's boxing-conditional rule
            // (one branch is primitive long, the other branch's static type becomes boxed Long, so
            // the OUTER conditional's type collapses back to primitive long), which unconditionally
            // unboxes the `null` case and throws NPE. This was latent in every payload that reached
            // this shared parser (order.paid/payment.captured) because those always carried an
            // order/payment "amount" field; it was never exercised until a payload shape with
            // NEITHER (e.g. a subscription.* event, which does not flow through this parser's
            // fields at all but still passes through this SAME shared parse() call at the top of
            // receive() before dispatch) hit it. Plain if/else avoids the JLS pitfall entirely.
            Long amountInPaise = null;
            if (paymentEntity.hasNonNull("amount")) {
                amountInPaise = paymentEntity.path("amount").asLong();
            } else if (orderEntity.hasNonNull("amount")) {
                amountInPaise = orderEntity.path("amount").asLong();
            }
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

    /**
     * [W1-6] {@code payload.subscription.entity} (and, for {@code subscription.charged}, {@code
     * payload.payment.entity}) extraction for {@code subscription.*} events — Razorpay Subscriptions'
     * real webhook shape:
     *
     * <pre>{@code
     * {
     *   "event": "subscription.charged",
     *   "payload": {
     *     "subscription": { "entity": { "id": "sub_...", "plan_id": "plan_...", "status": "active",
     *                                    "current_start": 1700000000, "current_end": 1702592000,
     *                                    "notes": { "workspaceId": "01H..." } } },
     *     "payment":      { "entity": { "id": "pay_...", "amount": 499900, "currency": "INR" } }
     *   },
     *   "created_at": 1700000005
     * }
     * }</pre>
     *
     * <p>{@code notes.workspaceId} round-trips the value {@code SubscriptionService#initiateCheckout}
     * sets at subscription-creation time ({@code RazorpayClient#createSubscription}'s {@code notes}
     * param) — the same "server sets it, webhook echoes it back" correlation discipline every other
     * webhook-driven flow in this codebase uses (e.g. {@code WalletTopUpService}'s receipt prefix).
     * {@code payload.payment.entity} is only present on {@code subscription.charged} — absent (all
     * null) on {@code subscription.activated}/{@code subscription.halted}, which is expected, not an
     * error.
     */
    record SubscriptionWebhookEvent(
            String eventType,
            String subscriptionId,
            String workspaceId,
            String razorpayPlanId,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            Instant createdAt,
            String paymentId,
            Long amountInPaise,
            String currency) {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        static SubscriptionWebhookEvent parse(String rawJson) {
            JsonNode root = readTreeOrThrow(rawJson);

            String eventType = textOrNull(root.path("event"));
            JsonNode subscriptionEntity = root.path("payload").path("subscription").path("entity");
            JsonNode paymentEntity = root.path("payload").path("payment").path("entity");

            String subscriptionId = textOrNull(subscriptionEntity.path("id"));
            String workspaceId = textOrNull(subscriptionEntity.path("notes").path("workspaceId"));
            String razorpayPlanId = textOrNull(subscriptionEntity.path("plan_id"));
            Instant currentPeriodStart = epochSecondsOrNull(subscriptionEntity.path("current_start"));
            Instant currentPeriodEnd = epochSecondsOrNull(subscriptionEntity.path("current_end"));
            Instant createdAt = epochSecondsOrNull(root.path("created_at"));
            String paymentId = textOrNull(paymentEntity.path("id"));
            Long amountInPaise = paymentEntity.hasNonNull("amount") ? paymentEntity.path("amount").asLong() : null;
            String currency = textOrNull(paymentEntity.path("currency"));

            return new SubscriptionWebhookEvent(
                    eventType,
                    subscriptionId,
                    workspaceId,
                    razorpayPlanId,
                    currentPeriodStart,
                    currentPeriodEnd,
                    createdAt,
                    paymentId,
                    amountInPaise,
                    currency);
        }

        private static Instant epochSecondsOrNull(JsonNode node) {
            return (node != null && node.isIntegralNumber()) ? Instant.ofEpochSecond(node.asLong()) : null;
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
