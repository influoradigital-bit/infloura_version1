package com.influora.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * CGST+SGST (intra-state) vs IGST (inter-state) split, shared by every D14 PDF renderer that
 * needs a GST breakup (commission invoices, and the Wave 9 subscription-invoice retrofit).
 * Determined by comparing the first two digits (GSTIN state code) of the supplier's and
 * customer's GSTINs — the standard GST rule. Public — consumed from {@code
 * com.influora.service.billing} (subscription invoicing) as well as this package.
 */
public final class GstSplitUtil {

    private GstSplitUtil() {}

    public record GstSplit(BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {}

    /**
     * @param supplierGstin the invoice's supplier GSTIN (may be null/blank — unregistered supplier)
     * @param customerGstin the invoice's customer GSTIN (may be null/blank — unregistered customer)
     * @param totalGst the total GST amount to split (already computed, e.g. 18% of the taxable value)
     */
    public static GstSplit compute(String supplierGstin, String customerGstin, BigDecimal totalGst) {
        if (totalGst == null || totalGst.signum() <= 0) {
            return new GstSplit(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        String supplierState = stateCode(supplierGstin);
        String customerState = stateCode(customerGstin);
        boolean intraState = supplierState != null && supplierState.equals(customerState);
        if (intraState) {
            BigDecimal half = totalGst.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            // Any single-paisa rounding residual from splitting an odd total is absorbed into
            // SGST rather than left unaccounted — CGST+SGST must sum to exactly totalGst.
            BigDecimal sgst = totalGst.subtract(half);
            return new GstSplit(half, sgst, BigDecimal.ZERO);
        }
        // Unknown supplier/customer state (missing GSTIN) defaults to IGST treatment — the
        // conservative default when intra-state cannot be affirmatively established.
        return new GstSplit(BigDecimal.ZERO, BigDecimal.ZERO, totalGst);
    }

    private static String stateCode(String gstin) {
        if (gstin == null || gstin.isBlank() || gstin.length() < 2) {
            return null;
        }
        return gstin.substring(0, 2);
    }
}
