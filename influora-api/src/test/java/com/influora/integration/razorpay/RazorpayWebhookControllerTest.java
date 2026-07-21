package com.influora.integration.razorpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
}
