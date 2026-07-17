package com.influora.service.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Invoice;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.InvoiceStatus;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.InvoiceRepository;
import com.influora.repository.PlanRepository;
import com.influora.repository.SubscriptionRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.InvoicePdfService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MP-1 wiring tests for {@link InvoiceService#generateInvoiceFromWebhook} — Task 24, wires the
 * Phase 1 stub. Verifies the amount is taken from the webhook-supplied value (never recomputed),
 * the row is created with PAID status, PDF storage is attempted, and a replayed payment reference
 * short-circuits to the existing row rather than creating a duplicate invoice.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE00000001";
    private static final String SUBSCRIPTION_ID = "01HWXYZSUB0000000000001";
    private static final String PLAN_ID = "01HWXYZPLANPRO0000000001";
    private static final String PAYMENT_REF = "pay_test123";

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlanRepository planRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private InvoicePdfService invoicePdfService;
    @Mock private R2StorageService r2StorageService;

    private InvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        invoiceService =
                new InvoiceService(
                        invoiceRepository,
                        subscriptionRepository,
                        planRepository,
                        workspaceRepository,
                        invoicePdfService,
                        r2StorageService);
    }

    @Test
    @DisplayName("wiring: generates a PAID invoice with the webhook-supplied amount (never recomputed from Plan.priceInr)")
    void testGeneratesInvoiceWithServerDerivedAmount() {
        when(invoiceRepository.findByRazorpayInvoiceId(PAYMENT_REF)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(subscriptionRow()));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(proPlan()));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspaceRow()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(r2StorageService.isAvailable()).thenReturn(false);

        Instant periodStart = Instant.now().minusSeconds(86400);
        Instant periodEnd = Instant.now().plusSeconds(2592000);
        Instant paidAt = Instant.now();

        // Deliberately DIFFERENT from Plan.priceInr (499900) — proves the amount actually stored
        // is the webhook's own value, not silently recomputed from the plan.
        long webhookAmountInPaise = 449900L;

        Invoice invoice =
                invoiceService.generateInvoiceFromWebhook(
                        WORKSPACE_ID, PAYMENT_REF, webhookAmountInPaise, periodStart, periodEnd, paidAt);

        assertEquals(449900, invoice.getAmount());
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        assertEquals(SUBSCRIPTION_ID, invoice.getSubscriptionId());
        assertEquals(PAYMENT_REF, invoice.getRazorpayInvoiceId());
        assertNotNull(invoice.getPaidAt());

        ArgumentCaptor<Invoice> savedCaptor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository, times(1)).save(savedCaptor.capture());
        assertEquals(449900, savedCaptor.getValue().getAmount());
    }

    @Test
    @DisplayName("wiring: PDF render + R2 store are actually attempted when R2 is available")
    void testStoresGeneratedPdfToR2WhenAvailable() {
        when(invoiceRepository.findByRazorpayInvoiceId(PAYMENT_REF)).thenReturn(Optional.empty());
        when(subscriptionRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(subscriptionRow()));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(proPlan()));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspaceRow()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoicePdfService.render(any(), any(), any(), any())).thenReturn(new byte[] {1, 2, 3});
        when(r2StorageService.isAvailable()).thenReturn(true);

        Invoice invoice =
                invoiceService.generateInvoiceFromWebhook(
                        WORKSPACE_ID,
                        PAYMENT_REF,
                        449900L,
                        Instant.now(),
                        Instant.now().plusSeconds(2592000),
                        Instant.now());

        verify(r2StorageService).putBytes(anyString(), any(byte[].class), eq("application/pdf"));
        assertNotNull(invoice.getPdfR2Key());
        verify(invoiceRepository, times(2)).save(any(Invoice.class)); // initial PAID row + pdfR2Key update
    }

    @Test
    @DisplayName("idempotency: a replayed payment reference returns the existing invoice instead of creating a duplicate")
    void testReplayedPaymentReferenceReturnsExistingInvoice() {
        Invoice existing =
                Invoice.builder()
                        .id("01HWXYZINVOICE000000001")
                        .subscriptionId(SUBSCRIPTION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .razorpayInvoiceId(PAYMENT_REF)
                        .amount(449900)
                        .status(InvoiceStatus.PAID)
                        .periodStart(Instant.now())
                        .periodEnd(Instant.now().plusSeconds(2592000))
                        .build();
        when(invoiceRepository.findByRazorpayInvoiceId(PAYMENT_REF)).thenReturn(Optional.of(existing));

        Invoice result =
                invoiceService.generateInvoiceFromWebhook(
                        WORKSPACE_ID,
                        PAYMENT_REF,
                        449900L,
                        Instant.now(),
                        Instant.now().plusSeconds(2592000),
                        Instant.now());

        assertEquals(existing, result);
        verify(invoiceRepository, never()).save(any(Invoice.class));
        verify(subscriptionRepository, never()).findByWorkspaceId(anyString());
    }

    private Subscription subscriptionRow() {
        return Subscription.builder()
                .id(SUBSCRIPTION_ID)
                .workspaceId(WORKSPACE_ID)
                .planId(PLAN_ID)
                .status(SubscriptionStatus.ACTIVE)
                .razorpaySubscriptionId("sub_test")
                .currentPeriodStart(Instant.now().minusSeconds(86400))
                .currentPeriodEnd(Instant.now().plusSeconds(2592000))
                .build();
    }

    private Plan proPlan() {
        return Plan.builder()
                .id(PLAN_ID)
                .code(PlanCode.PRO)
                .name("Pro")
                .priceInr(499900)
                .aiMonthlyAllotment(400)
                .active(true)
                .build();
    }

    private Workspace workspaceRow() {
        return Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "media", "1-10");
    }
}
