package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.CompanyTaxProperties;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.enums.InvoiceNumberSeriesType;
import com.influora.repository.CreatorCreditOrderRepository;
import com.influora.service.HsnSacCodeService;
import com.influora.service.InvoiceNumberService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md A37, F-14/F-22, F-27) — {@link CreatorCreditInvoiceApplier} in
 * isolation. Unlike the deleted {@code gstBreakupSumsToOriginalAmount} in {@code
 * CreatorCreditOrderServiceTest} (pure arithmetic on hardcoded literals, exercising zero
 * production code), this drives the REAL {@link com.influora.service.GstSplitUtil#compute} —
 * nothing here is stubbed except the order repository and the two upstream lookups
 * (invoice-number sequence, HSN/SAC code) that have their own dedicated tests.
 */
class CreatorCreditInvoiceApplierTest {

    private CreatorCreditOrderRepository orderRepository;
    private InvoiceNumberService invoiceNumberService;
    private HsnSacCodeService hsnSacCodeService;
    private CompanyTaxProperties companyTaxProperties;
    private CreatorCreditInvoiceApplier applier;

    @BeforeEach
    void setUp() {
        orderRepository = mock(CreatorCreditOrderRepository.class);
        invoiceNumberService = mock(InvoiceNumberService.class);
        hsnSacCodeService = mock(HsnSacCodeService.class);
        companyTaxProperties = mock(CompanyTaxProperties.class);
        applier =
                new CreatorCreditInvoiceApplier(
                        orderRepository, invoiceNumberService, hsnSacCodeService, companyTaxProperties);
    }

    @Test
    @DisplayName(
            "A37: 24900 paise (Rs 249, GST-inclusive) splits via the REAL GstSplitUtil to 21102"
                    + " taxable + 3798 IGST (no supplier GSTIN on file today -> unregistered -> IGST),"
                    + " summing back to the original amount")
    void appliesRealGstBreakup() {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getId()).thenReturn("order-1");
        when(order.getAmountPaise()).thenReturn(24900);
        when(order.getInvoiceNumber()).thenReturn(null);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(companyTaxProperties.getGstin()).thenReturn("");
        when(invoiceNumberService.generateNext(eq(InvoiceNumberSeriesType.CREATOR_CREDITS), any()))
                .thenReturn("INF/CRC/2026-27/000001");
        when(hsnSacCodeService.resolveCode(any())).thenReturn("998599");

        applier.applyBestEffort("order-1");

        verify(order).applyInvoice("INF/CRC/2026-27/000001", 21102, 0, 0, 3798, "998599", null);
        assertEquals(24900, 21102 + 3798);
    }

    @Test
    @DisplayName("F-14/F-22: a numbering failure is swallowed here and never rethrown — this bean runs after the credit already committed")
    void numberingFailureIsSwallowed() {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getId()).thenReturn("order-1");
        when(order.getInvoiceNumber()).thenReturn(null);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
        when(invoiceNumberService.generateNext(any(), any())).thenThrow(new RuntimeException("boom"));

        applier.applyBestEffort("order-1"); // must not throw

        verify(order, never()).applyInvoice(any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("applyBestEffort is idempotent: an order that already carries an invoice number is left alone")
    void alreadyAppliedIsNoOp() {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getId()).thenReturn("order-1");
        when(order.getInvoiceNumber()).thenReturn("INF/CRC/2026-27/000001");
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));

        applier.applyBestEffort("order-1");

        org.mockito.Mockito.verifyNoInteractions(invoiceNumberService, hsnSacCodeService);
    }
}
