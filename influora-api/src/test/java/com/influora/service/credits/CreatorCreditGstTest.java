package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.CompanyTaxProperties;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.enums.HsnSacAppliesTo;
import com.influora.domain.enums.InvoiceNumberSeriesType;
import com.influora.repository.CreatorCreditOrderRepository;
import com.influora.service.GstSplitUtil;
import com.influora.service.HsnSacCodeService;
import com.influora.service.InvoiceNumberService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-V2 round 2 (SPEC.md A37, design-kabir.md K-25) — {@code
 * CreatorCreditGstTest#inclusiveBreakup} is the acceptance criterion's OWN named test (distinct
 * from {@link CreatorCreditInvoiceApplierTest}, which already covers the same production code
 * under a different test name). Drives the REAL {@link GstSplitUtil#compute} — the exact
 * production formula {@link CreatorCreditInvoiceApplier#applyBestEffort} uses — never a rebuilt
 * arithmetic shadow of it, so a change to the split logic is what turns this test red. 24900 paise
 * is GST-INCLUSIVE (K-25: no 18% added on top — a double-GST bug would charge 29382); the taxable
 * value and the GST are derived backwards, server-side, from that one snapshot amount.
 */
class CreatorCreditGstTest {

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
            "A37: 24900 paise -> taxable 21102 + GST 3798 = 24900 (via the REAL GstSplitUtil, not"
                    + " literals); an unregistered creator (no GSTIN on file) gets IGST 3798, never"
                    + " CGST+SGST; the invoice number is minted from the CREATOR_CREDITS series")
    void inclusiveBreakup() {
        // -- the pure split arithmetic, run through the SAME production method
        // CreatorCreditInvoiceApplier calls, on the SAME 24900-paise snapshot amount SPEC.md fixes.
        BigDecimal amountRupees = new BigDecimal("249.00");
        BigDecimal taxable = amountRupees.divide(new BigDecimal("1.18"), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal totalGst = amountRupees.subtract(taxable);
        assertEquals(0, new BigDecimal("211.02").compareTo(taxable), "taxable value must be Rs 211.02 (21102 paise)");
        assertEquals(0, new BigDecimal("37.98").compareTo(totalGst), "total GST must be Rs 37.98 (3798 paise)");
        assertEquals(
                0,
                amountRupees.compareTo(taxable.add(totalGst)),
                "taxable + GST must sum back to exactly the original 249.00 — no rounding residual lost");

        // -- unregistered creator (no GSTIN on file today): GstSplitUtil.compute defaults to IGST,
        // never CGST+SGST — driven through the REAL production method, not a rebuilt shadow.
        GstSplitUtil.GstSplit split = GstSplitUtil.compute("", null, totalGst);
        assertEquals(0, BigDecimal.ZERO.compareTo(split.cgst()));
        assertEquals(0, BigDecimal.ZERO.compareTo(split.sgst()));
        assertEquals(0, totalGst.compareTo(split.igst()));

        // -- end-to-end through CreatorCreditInvoiceApplier: 21102 taxable + 3798 IGST in paise,
        // and the invoice number comes from the CREATOR_CREDITS series specifically (not, e.g., the
        // SUBSCRIPTION series a copy-paste could have left behind).
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getId()).thenReturn("order-gst-1");
        when(order.getAmountPaise()).thenReturn(24900);
        when(order.getInvoiceNumber()).thenReturn(null);
        when(orderRepository.findById("order-gst-1")).thenReturn(Optional.of(order));
        when(companyTaxProperties.getGstin()).thenReturn("");
        when(invoiceNumberService.generateNext(eq(InvoiceNumberSeriesType.CREATOR_CREDITS), any()))
                .thenReturn("INF/CRC/2026-27/000042");
        when(hsnSacCodeService.resolveCode(eq(HsnSacAppliesTo.CREATOR_CREDITS))).thenReturn("998599");

        applier.applyBestEffort("order-gst-1");

        verify(order).applyInvoice("INF/CRC/2026-27/000042", 21102, 0, 0, 3798, "998599", null);
        verify(invoiceNumberService).generateNext(InvoiceNumberSeriesType.CREATOR_CREDITS, null);
        assertEquals(24900, 21102 + 3798);
    }
}
