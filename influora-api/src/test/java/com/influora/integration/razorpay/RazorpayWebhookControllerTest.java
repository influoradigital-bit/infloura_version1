package com.influora.integration.razorpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.influora.common.ApiException;
import com.influora.service.notification.event.InvoiceReadyEvent;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import com.influora.service.notification.event.SubscriptionPaymentFailedEvent;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * W1-6 wiring tests for {@link RazorpayWebhookController}'s {@code subscription.*} routing
 * (previously entirely unrouted — C5). Mirrors {@code ShopifyWebhookControllerTest}'s
 * direct-Java-call style and {@code SubscriptionDunningJobTest}'s bare-mock {@code
 * PlatformTransactionManager} pattern (a Mockito mock's {@code getTransaction} returns {@code
 * null} by default, which {@link org.springframework.transaction.support.TransactionTemplate}
 * happily threads through to the callback — no stubbing needed for the callback body to run).
 *
 * <p>Money-lifecycle code: mandatory Kabir gate after Kavya (per Arjun's routing note), same as
 * every other subscription-billing test in this module.
 */
@ExtendWith(MockitoExtension.class)
class RazorpayWebhookControllerTest {

    private static final String VALID_SIGNATURE = "valid-signature-stub";
    private static final String SUBSCRIPTION_ID = "sub_ABC123";
    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";
    private static final String PLAN_ID = "plan_XYZ789";

    @Mock private WebhookSignatureVerifier signatureVerifier;
    @Mock private EscrowService escrowService;
    @Mock private PayoutReconciliationService payoutReconciliationService;
    @Mock private WalletTopUpService walletTopUpService;
    @Mock private com.influora.service.credits.CreatorCreditOrderService creatorCreditOrderService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private InvoiceService invoiceService;
    @Mock private BrandContextService brandContextService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private RazorpayWebhookController controller;

    @BeforeEach
    void setUp() {
        controller =
                new RazorpayWebhookController(
                        signatureVerifier,
                        escrowService,
                        payoutReconciliationService,
                        walletTopUpService,
                        creatorCreditOrderService,
                        subscriptionService,
                        invoiceService,
                        brandContextService,
                        idempotencyService,
                        eventPublisher,
                        transactionManager);
        when(signatureVerifier.verify(anyString(), anyString())).thenReturn(true);
    }

    /** Stubs idempotencyService.executeOnce to genuinely invoke the supplied action, mirroring the real bean's non-duplicate-delivery behavior. */
    @SuppressWarnings("unchecked")
    private void stubIdempotencyServiceRunsAction() {
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(3)).get());
    }

    private static String activatedPayload() {
        return "{"
                + "\"event\":\"subscription.activated\","
                + "\"payload\":{\"subscription\":{\"entity\":{"
                + "\"id\":\""
                + SUBSCRIPTION_ID
                + "\",\"plan_id\":\""
                + PLAN_ID
                + "\",\"status\":\"active\","
                + "\"current_start\":1700000000,\"current_end\":1702592000,"
                + "\"notes\":{\"workspaceId\":\""
                + WORKSPACE_ID
                + "\"}}}},"
                + "\"created_at\":1700000005}";
    }

    private static String chargedPayload() {
        return "{"
                + "\"event\":\"subscription.charged\","
                + "\"payload\":{"
                + "\"subscription\":{\"entity\":{"
                + "\"id\":\""
                + SUBSCRIPTION_ID
                + "\",\"plan_id\":\""
                + PLAN_ID
                + "\",\"status\":\"active\","
                + "\"current_start\":1702592000,\"current_end\":1705270400,"
                + "\"notes\":{\"workspaceId\":\""
                + WORKSPACE_ID
                + "\"}}},"
                + "\"payment\":{\"entity\":{\"id\":\"pay_CHARGE1\",\"amount\":499900,\"currency\":\"INR\"}}"
                + "},"
                + "\"created_at\":1702592005}";
    }

    private static String haltedPayload() {
        return "{"
                + "\"event\":\"subscription.halted\","
                + "\"payload\":{\"subscription\":{\"entity\":{"
                + "\"id\":\""
                + SUBSCRIPTION_ID
                + "\",\"plan_id\":\""
                + PLAN_ID
                + "\",\"status\":\"halted\","
                + "\"notes\":{\"workspaceId\":\""
                + WORKSPACE_ID
                + "\"}}}},"
                + "\"created_at\":1705270405}";
    }

    private static String pendingPayload() {
        return "{"
                + "\"event\":\"subscription.pending\","
                + "\"payload\":{\"subscription\":{\"entity\":{"
                + "\"id\":\""
                + SUBSCRIPTION_ID
                + "\",\"plan_id\":\""
                + PLAN_ID
                + "\",\"status\":\"pending\","
                + "\"notes\":{\"workspaceId\":\""
                + WORKSPACE_ID
                + "\"}}}},"
                + "\"created_at\":1705000000}";
    }

    private static String cancelledPayload() {
        // [Mutation-testing fix, Priya's 2nd-round review] Real Razorpay `subscription.cancelled`
        // payloads always carry current_start/current_end (the subscription entity's period
        // fields don't disappear on cancellation) — omitting them here made
        // receive_subscriptionCancelled_appliesCancelledStatus's isNull()/isNull() assertion
        // unfalsifiable: SubscriptionWebhookEvent.parse() returned null for these fields
        // regardless of whether the handler correctly passes updatePeriod=false, so a bug that
        // flipped updatePeriod to true for this event would have gone undetected.
        return "{"
                + "\"event\":\"subscription.cancelled\","
                + "\"payload\":{\"subscription\":{\"entity\":{"
                + "\"id\":\""
                + SUBSCRIPTION_ID
                + "\",\"plan_id\":\""
                + PLAN_ID
                + "\",\"status\":\"cancelled\","
                + "\"current_start\":1703000000,\"current_end\":1705592000,"
                + "\"notes\":{\"workspaceId\":\""
                + WORKSPACE_ID
                + "\"}}}},"
                + "\"created_at\":1706000000}";
    }

    private static String completedPayload() {
        // Same fix as cancelledPayload() above, for the same reason.
        return "{"
                + "\"event\":\"subscription.completed\","
                + "\"payload\":{\"subscription\":{\"entity\":{"
                + "\"id\":\""
                + SUBSCRIPTION_ID
                + "\",\"plan_id\":\""
                + PLAN_ID
                + "\",\"status\":\"completed\","
                + "\"current_start\":1703000010,\"current_end\":1705592010,"
                + "\"notes\":{\"workspaceId\":\""
                + WORKSPACE_ID
                + "\"}}}},"
                + "\"created_at\":1706000010}";
    }

    private static String activatedPayloadMissingWorkspaceId() {
        return "{"
                + "\"event\":\"subscription.activated\","
                + "\"payload\":{\"subscription\":{\"entity\":{"
                + "\"id\":\""
                + SUBSCRIPTION_ID
                + "\",\"plan_id\":\""
                + PLAN_ID
                + "\",\"status\":\"active\","
                + "\"current_start\":1700000000,\"current_end\":1702592000"
                + "}}},"
                + "\"created_at\":1700000005}";
    }

    @Test
    @DisplayName("subscription.activated: applies ACTIVE status + period via SubscriptionService, never touches InvoiceService")
    void receive_subscriptionActivated_appliesActiveStatusWithPeriod() {
        stubIdempotencyServiceRunsAction();

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, activatedPayload());

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq(SUBSCRIPTION_ID),
                        eq(WORKSPACE_ID),
                        eq(PLAN_ID),
                        eq(SubscriptionStatus.ACTIVE),
                        eq(Instant.ofEpochSecond(1700000000)),
                        eq(Instant.ofEpochSecond(1702592000)),
                        eq(Instant.ofEpochSecond(1700000005)));
        verify(invoiceService, never()).generateInvoiceFromWebhook(any(), any(), anyLong(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("subscription.charged: applies ACTIVE status + period, generates the invoice via the EXISTING InvoiceService path, and publishes InvoiceReadyEvent")
    void receive_subscriptionCharged_generatesInvoiceAndPublishesEvent() {
        stubIdempotencyServiceRunsAction();
        when(brandContextService.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BillingRecipient("user_1", "brand@example.com"));
        Invoice invoice =
                Invoice.builder()
                        .id("01HINVOICE00000000000001")
                        .subscriptionId(SUBSCRIPTION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .amount(499900)
                        .build();
        when(invoiceService.generateInvoiceFromWebhook(
                        eq(WORKSPACE_ID),
                        eq("pay_CHARGE1"),
                        eq(499900L),
                        eq(Instant.ofEpochSecond(1702592000)),
                        eq(Instant.ofEpochSecond(1705270400)),
                        eq(Instant.ofEpochSecond(1702592005))))
                .thenReturn(invoice);
        when(invoiceService.getInvoiceDownloadUrl(invoice)).thenReturn("https://r2.example/invoice.pdf");

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, chargedPayload());

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq(SUBSCRIPTION_ID),
                        eq(WORKSPACE_ID),
                        eq(PLAN_ID),
                        eq(SubscriptionStatus.ACTIVE),
                        any(),
                        any(),
                        any());
        verify(invoiceService)
                .generateInvoiceFromWebhook(
                        eq(WORKSPACE_ID), eq("pay_CHARGE1"), eq(499900L), any(), any(), any());

        ArgumentCaptor<InvoiceReadyEvent> captor = ArgumentCaptor.forClass(InvoiceReadyEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("user_1", captor.getValue().userId());
        assertEquals(WORKSPACE_ID, captor.getValue().workspaceId());
        assertEquals("01HINVOICE00000000000001", captor.getValue().entityId());
        assertEquals("brand@example.com", captor.getValue().recipientEmail());
        assertEquals(499900L, captor.getValue().amountInPaise());
    }

    @Test
    @DisplayName("subscription.halted: applies HALTED status with NO period update, and publishes SubscriptionHaltedEvent")
    void receive_subscriptionHalted_appliesHaltedStatusAndPublishesEvent() {
        stubIdempotencyServiceRunsAction();
        when(brandContextService.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BillingRecipient("user_1", "brand@example.com"));

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, haltedPayload());

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq(SUBSCRIPTION_ID),
                        eq(WORKSPACE_ID),
                        eq(PLAN_ID),
                        eq(SubscriptionStatus.HALTED),
                        isNull(),
                        isNull(),
                        eq(Instant.ofEpochSecond(1705270405)));
        verify(invoiceService, never()).generateInvoiceFromWebhook(any(), any(), anyLong(), any(), any(), any());

        ArgumentCaptor<SubscriptionHaltedEvent> captor = ArgumentCaptor.forClass(SubscriptionHaltedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(SUBSCRIPTION_ID, captor.getValue().entityId());
        assertEquals("brand@example.com", captor.getValue().recipientEmail());
    }

    @Test
    @DisplayName("[Track B, P2] subscription.pending: applies PAST_DUE status with NO period update, and publishes SubscriptionPaymentFailedEvent")
    void receive_subscriptionPending_appliesPastDueStatusAndPublishesEvent() {
        stubIdempotencyServiceRunsAction();
        when(brandContextService.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BillingRecipient("user_1", "brand@example.com"));

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, pendingPayload());

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq(SUBSCRIPTION_ID),
                        eq(WORKSPACE_ID),
                        eq(PLAN_ID),
                        eq(SubscriptionStatus.PAST_DUE),
                        isNull(),
                        isNull(),
                        eq(Instant.ofEpochSecond(1705000000)));
        verify(invoiceService, never()).generateInvoiceFromWebhook(any(), any(), anyLong(), any(), any(), any());

        ArgumentCaptor<SubscriptionPaymentFailedEvent> captor =
                ArgumentCaptor.forClass(SubscriptionPaymentFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(SUBSCRIPTION_ID, captor.getValue().entityId());
        assertEquals(WORKSPACE_ID, captor.getValue().workspaceId());
        assertEquals("brand@example.com", captor.getValue().recipientEmail());
    }

    @Test
    @DisplayName("BL-2 fix [BrandF.md §98]: subscription.cancelled applies CANCELLED status with NO period update — this event was previously discarded entirely by the default no-op")
    void receive_subscriptionCancelled_appliesCancelledStatus() {
        stubIdempotencyServiceRunsAction();

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, cancelledPayload());

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq(SUBSCRIPTION_ID),
                        eq(WORKSPACE_ID),
                        eq(PLAN_ID),
                        eq(SubscriptionStatus.CANCELLED),
                        isNull(),
                        isNull(),
                        eq(Instant.ofEpochSecond(1706000000)));
        verify(invoiceService, never()).generateInvoiceFromWebhook(any(), any(), anyLong(), any(), any(), any());
        // No cancellation email/event is published by this fix — only status/allotment reconciliation.
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("BL-2 fix [BrandF.md §98]: subscription.completed also applies CANCELLED status with NO period update (terminal event, same treatment as cancelled)")
    void receive_subscriptionCompleted_appliesCancelledStatus() {
        stubIdempotencyServiceRunsAction();

        ResponseEntity<Void> response = controller.receive(VALID_SIGNATURE, completedPayload());

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionService)
                .applySubscriptionWebhookUpdate(
                        eq(SUBSCRIPTION_ID),
                        eq(WORKSPACE_ID),
                        eq(PLAN_ID),
                        eq(SubscriptionStatus.CANCELLED),
                        isNull(),
                        isNull(),
                        eq(Instant.ofEpochSecond(1706000010)));
        verify(invoiceService, never()).generateInvoiceFromWebhook(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("[SEC: WebhookSignatureVerifier] an invalid/unverified signature is rejected (400) before any parsing/dispatch — fail-closed, applies to subscription.* the same as every other event type")
    void receive_invalidSignature_rejectsBeforeAnyDispatch() {
        when(signatureVerifier.verify(anyString(), anyString())).thenReturn(false);

        ApiException ex =
                Assertions.assertThrows(
                        ApiException.class, () -> controller.receive("bad-signature", pendingPayload()));

        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.getCode());
        verify(subscriptionService, never())
                .applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(anyString(), any(), anyString(), any(Supplier.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("[B11 lesson] a subscription.* webhook with no resolvable notes.workspaceId is acknowledged (200) without action — never thrown as a retry-storm-inducing error")
    void receive_subscriptionEventMissingWorkspaceId_isAcknowledgedWithoutAction() {
        ResponseEntity<Void> response =
                controller.receive(VALID_SIGNATURE, activatedPayloadMissingWorkspaceId());

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionService, never())
                .applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any());
        verify(idempotencyService, never()).executeOnce(anyString(), any(), anyString(), any(Supplier.class));
    }

    @Test
    @DisplayName("replay: a duplicate subscription.charged delivery is deduped by IdempotencyService and never double-invokes SubscriptionService/InvoiceService")
    void receive_subscriptionChargedReplay_isDedupedAndNeverDoubleApplied() {
        // First delivery genuinely runs the action; the second, identical delivery simulates
        // IdempotencyService's real AlreadyCompletedException replay behavior.
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(3)).get())
                .thenThrow(new IdempotencyService.AlreadyCompletedException("razorpay-webhook:subscription.charged"));
        when(brandContextService.resolveBillingRecipient(WORKSPACE_ID))
                .thenReturn(new BillingRecipient("user_1", "brand@example.com"));
        Invoice invoice =
                Invoice.builder().id("01HINVOICE00000000000001").workspaceId(WORKSPACE_ID).amount(499900).build();
        when(invoiceService.generateInvoiceFromWebhook(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(invoice);

        controller.receive(VALID_SIGNATURE, chargedPayload());
        ResponseEntity<Void> secondResponse = controller.receive(VALID_SIGNATURE, chargedPayload());

        assertEquals(200, secondResponse.getStatusCode().value());
        verify(subscriptionService, times(1))
                .applySubscriptionWebhookUpdate(any(), any(), any(), any(), any(), any(), any());
        verify(invoiceService, times(1)).generateInvoiceFromWebhook(any(), any(), anyLong(), any(), any(), any());
        verify(eventPublisher, times(1)).publishEvent(any(InvoiceReadyEvent.class));
    }

    // ---------------------------------------------------------------------------------------
    // F-0809 — shared Razorpay account (Influora + Snapsby on one set of keys).
    //
    // Razorpay delivers every event on an account to every registered webhook URL, so this
    // endpoint receives Snapsby's payments. Those name no Influora record. Before the fix they
    // reached escrowService.confirmFunded and threw ESCROW_NOT_FOUND -> non-2xx -> Razorpay
    // retries forever, and sustained non-2xx is how Razorpay disables a webhook (which would take
    // Influora's own crediting down with it).
    //
    // The third test is the one that matters. The danger in this fix is not that a foreign event
    // throws -- it is that the catch is too wide and swallows a REAL failure to credit one of our
    // own orders, which Razorpay would then never retry: F-0808 rebuilt, money captured and never
    // credited, silently. Mutate isUnknownToThisProduct to `return true` and ONLY that third test
    // goes red; the two ACK tests stay green. That asymmetry is the point.
    // ---------------------------------------------------------------------------------------

    private static String orderPaidPayload(String receipt) {
        return "{"
                + "\"event\":\"order.paid\","
                + "\"payload\":{"
                + "\"payment\":{\"entity\":{\"id\":\"pay_FOREIGN1\",\"amount\":100000,\"currency\":\"INR\"}},"
                + "\"order\":{\"entity\":{\"receipt\":\"" + receipt + "\"}}"
                + "},"
                + "\"created_at\":1700000005}";
    }

    @Test
    @DisplayName(
            "F-0809: an order.paid for another product on the shared Razorpay account is ACKed 200,"
                    + " not retried forever")
    void foreignEscrowShapedReceiptIsAcknowledged() {
        when(escrowService.confirmFunded(anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new ApiException("ESCROW_NOT_FOUND", "Secured payment not found", HttpStatus.NOT_FOUND));

        ResponseEntity<Void> response =
                controller.receive(VALID_SIGNATURE, orderPaidPayload("snapsby-order-9f3c21"));

        assertEquals(200, response.getStatusCode().value());
        verify(walletTopUpService, never()).confirmCredited(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "F-0809: a topup-prefixed receipt that names no Influora top-up is ACKed 200 as well")
    void foreignTopUpShapedReceiptIsAcknowledged() {
        when(walletTopUpService.confirmCredited(anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new ApiException("TOPUP_NOT_FOUND", "Wallet top-up order not found", HttpStatus.NOT_FOUND));

        ResponseEntity<Void> response =
                controller.receive(
                        VALID_SIGNATURE,
                        orderPaidPayload(WalletTopUpService.RECEIPT_PREFIX + "01NOTOURSXXXXXXXXXXXXXXXXX"));

        assertEquals(200, response.getStatusCode().value());
        verify(escrowService, never()).confirmFunded(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "F-0809/F-0808: a GENUINE failure to credit one of OUR top-ups still propagates -- it"
                    + " must NOT be swallowed, or Razorpay never retries and the payment is lost")
    void realCreditFailureOnOurOwnOrderStillThrows() {
        // Any code that is not TOPUP_NOT_FOUND / ESCROW_NOT_FOUND means the order IS ours and
        // crediting failed for a real reason. Swallowing this would ACK to Razorpay, stopping the
        // retry that is the only thing standing between a captured payment and an uncredited
        // wallet -- exactly the F-0808 defect this fix must not reintroduce.
        when(walletTopUpService.confirmCredited(anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(
                        new ApiException(
                                "WEBHOOK_AMOUNT_MISMATCH",
                                "Webhook amount does not match the order",
                                HttpStatus.BAD_REQUEST));

        ApiException thrown =
                Assertions.assertThrows(
                        ApiException.class,
                        () ->
                                controller.receive(
                                        VALID_SIGNATURE,
                                        orderPaidPayload(
                                                WalletTopUpService.RECEIPT_PREFIX + "01OURSXXXXXXXXXXXXXXXXXXXX")));

        assertEquals("WEBHOOK_AMOUNT_MISMATCH", thrown.getCode());
    }

    @Test
    @DisplayName("F-0809: a genuine Influora top-up still credits normally after the fix")
    void ourOwnTopUpStillCredits() {
        String topUpId = "01M2D29W3KSVFK9DKHF7KV76P9";

        ResponseEntity<Void> response =
                controller.receive(
                        VALID_SIGNATURE, orderPaidPayload(WalletTopUpService.RECEIPT_PREFIX + topUpId));

        assertEquals(200, response.getStatusCode().value());
        verify(walletTopUpService, times(1))
                .confirmCredited(eq(topUpId), eq("pay_FOREIGN1"), eq(100000L), eq("INR"));
    }

    // ---------------------------------------------------------------------------------------
    // T-CREATOR-CREDITS-V2 round 2 (SPEC.md A28, A29, design-kabir.md K-07 CRITICAL, K-24)
    // ---------------------------------------------------------------------------------------

    private static String creatorCreditOrderPaidPayload(String receipt, String razorpayOrderId, String paymentId, long amountPaise) {
        return "{"
                + "\"event\":\"order.paid\","
                + "\"payload\":{"
                + "\"payment\":{\"entity\":{\"id\":\""
                + paymentId
                + "\",\"amount\":"
                + amountPaise
                + ",\"currency\":\"INR\"}},"
                + "\"order\":{\"entity\":{\"id\":\""
                + razorpayOrderId
                + "\",\"receipt\":\""
                + receipt
                + "\"}}"
                + "},"
                + "\"created_at\":1700000006}";
    }

    private static String creatorCreditPaymentCapturedPayload(String receipt, String razorpayOrderId, String paymentId, long amountPaise) {
        return "{"
                + "\"event\":\"payment.captured\","
                + "\"payload\":{"
                + "\"payment\":{\"entity\":{\"id\":\""
                + paymentId
                + "\",\"amount\":"
                + amountPaise
                + ",\"currency\":\"INR\"}},"
                + "\"order\":{\"entity\":{\"id\":\""
                + razorpayOrderId
                + "\",\"receipt\":\""
                + receipt
                + "\"}}"
                + "},"
                + "\"created_at\":1700000007}";
    }

    @Test
    @DisplayName(
            "A28/K-07 CRITICAL: a signed order.paid (and payment.captured) carrying a ccr:<id> receipt"
                    + " routes to CreatorCreditOrderService.confirmPaid BEFORE the escrow fallback —"
                    + " escrowService is NEVER called for either event type; an unknown ccr:<id> order is"
                    + " ACKed 200 (never retried), also without ever falling through to escrow")
    void creatorCreditReceiptRoutesBeforeEscrow() {
        String orderId1 = "01HCREDITORDER00000000001";
        String receipt1 = com.influora.service.credits.CreatorCreditOrderService.RECEIPT_PREFIX + orderId1;

        ResponseEntity<Void> response1 =
                controller.receive(
                        VALID_SIGNATURE,
                        creatorCreditOrderPaidPayload(receipt1, "order_rzp_ccred1", "pay_CCRED1", 24900));

        assertEquals(200, response1.getStatusCode().value());
        verify(creatorCreditOrderService)
                .confirmPaid(eq(orderId1), eq("pay_CCRED1"), eq("order_rzp_ccred1"), eq(24900L), eq("INR"));
        verify(escrowService, never()).confirmFunded(any(), any(), any(), any());
        verify(walletTopUpService, never()).confirmCredited(any(), any(), any(), any());

        // payment.captured shares dispatchFundingEventIfResolvable — must route identically.
        String orderId2 = "01HCREDITORDER00000000002";
        String receipt2 = com.influora.service.credits.CreatorCreditOrderService.RECEIPT_PREFIX + orderId2;
        ResponseEntity<Void> response2 =
                controller.receive(
                        VALID_SIGNATURE,
                        creatorCreditPaymentCapturedPayload(receipt2, "order_rzp_ccred2", "pay_CCRED2", 24900));

        assertEquals(200, response2.getStatusCode().value());
        verify(creatorCreditOrderService)
                .confirmPaid(eq(orderId2), eq("pay_CCRED2"), eq("order_rzp_ccred2"), eq(24900L), eq("INR"));
        verify(escrowService, never()).confirmFunded(any(), any(), any(), any());

        // Falsify note: deleting the ccr: branch (routing this receipt through the escrow fallback
        // instead) makes escrowService.confirmFunded get called and the never()s above go red —
        // exactly K-07's failure mode: money captured, credits never granted, silently ACKed (F8).

        // An unknown ccr:<id> order (CREDIT_ORDER_NOT_FOUND) is ACKed 200 like every other
        // not-ours receipt on the shared Razorpay account (F-0809) — Razorpay must never be told to
        // retry an order id that will never exist.
        String unknownReceipt =
                com.influora.service.credits.CreatorCreditOrderService.RECEIPT_PREFIX + "01UNKNOWNXXXXXXXXXXXXXXXXX";
        when(creatorCreditOrderService.confirmPaid(any(), any(), any(), any(), any()))
                .thenThrow(
                        new ApiException(
                                "CREDIT_ORDER_NOT_FOUND", "Creator credit order not found", HttpStatus.NOT_FOUND));

        ResponseEntity<Void> response3 =
                controller.receive(
                        VALID_SIGNATURE,
                        creatorCreditOrderPaidPayload(unknownReceipt, "order_rzp_unknown", "pay_UNKNOWN1", 24900));

        assertEquals(200, response3.getStatusCode().value());
        verify(escrowService, never()).confirmFunded(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "A29/K-24: with CREATOR_CREDITS_ENABLED=false, a signed webhook for a PENDING creator-"
                    + " credit order still credits 60 — confirmPaid is never flag-gated (only order"
                    + " creation/charging/the Buy UI are), so a flag flip after purchase can never strand"
                    + " already-paid money")
    void creatorCreditWebhookIgnoresFlag() {
        // Wires a REAL CreatorCreditOrderService (not the class-level mock) with
        // CreatorCreditProperties stubbed DISABLED — this is the only way this test can actually go
        // red if someone later adds an `if (!creditProperties.isEnabled())` guard inside
        // confirmPaid itself, exactly the K-24 regression this criterion exists to catch.
        var orderRepository = mock(com.influora.repository.CreatorCreditOrderRepository.class);
        var packRepository = mock(com.influora.repository.CreatorCreditPackRepository.class);
        var creatorCreditService = mock(com.influora.service.credits.CreatorCreditService.class);
        var accountInitializer = mock(com.influora.service.credits.CreatorCreditAccountInitializer.class);
        var invoiceApplier = mock(com.influora.service.credits.CreatorCreditInvoiceApplier.class);
        var razorpayClient = mock(com.influora.integration.razorpay.RazorpayClient.class);
        var razorpayProperties = mock(com.influora.config.RazorpayProperties.class);
        var creditProperties = mock(com.influora.config.CreatorCreditProperties.class);
        org.mockito.Mockito.lenient().when(creditProperties.isEnabled()).thenReturn(false);
        java.time.Clock clock =
                java.time.Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), java.time.ZoneOffset.UTC);

        var realOrderService =
                new com.influora.service.credits.CreatorCreditOrderService(
                        orderRepository,
                        packRepository,
                        creatorCreditService,
                        accountInitializer,
                        invoiceApplier,
                        razorpayClient,
                        razorpayProperties,
                        creditProperties,
                        clock);

        var pendingOrder = mock(com.influora.domain.entity.CreatorCreditOrder.class);
        when(pendingOrder.getId()).thenReturn("order-flagoff-1");
        when(pendingOrder.getStatus())
                .thenReturn(com.influora.domain.enums.CreatorCreditOrderStatus.PENDING);
        when(pendingOrder.getRazorpayOrderId()).thenReturn("order_rzp_flagoff1");
        when(pendingOrder.getAmountPaise()).thenReturn(24900);
        when(pendingOrder.getCurrency()).thenReturn("INR");
        when(orderRepository.findByIdForUpdate("order-flagoff-1")).thenReturn(java.util.Optional.of(pendingOrder));
        when(creatorCreditService.creditPurchase(eq(pendingOrder), any())).thenReturn("grant-flagoff-1");

        RazorpayWebhookController controllerWithRealCreditService =
                new RazorpayWebhookController(
                        signatureVerifier,
                        escrowService,
                        payoutReconciliationService,
                        walletTopUpService,
                        realOrderService,
                        subscriptionService,
                        invoiceService,
                        brandContextService,
                        idempotencyService,
                        eventPublisher,
                        transactionManager);

        String receipt = com.influora.service.credits.CreatorCreditOrderService.RECEIPT_PREFIX + "order-flagoff-1";
        ResponseEntity<Void> response =
                controllerWithRealCreditService.receive(
                        VALID_SIGNATURE,
                        creatorCreditOrderPaidPayload(receipt, "order_rzp_flagoff1", "pay_FLAGOFF1", 24900));

        assertEquals(200, response.getStatusCode().value());
        verify(creatorCreditService).creditPurchase(pendingOrder, clock.instant());
        verify(pendingOrder).markCredited(eq("pay_FLAGOFF1"), eq("grant-flagoff-1"), eq(clock.instant()));
        verify(orderRepository).saveAndFlush(pendingOrder);
    }
}
