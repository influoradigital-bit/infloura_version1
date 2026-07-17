package com.influora.domain.enums;

/**
 * Status for the new D14 marketplace documents (Doc#2 {@code campaign_service_invoices}, Doc#3
 * {@code platform_commission_invoices}). Deliberately narrower than {@link InvoiceStatus} — both
 * new documents are only ever created at the moment their underlying money movement (escrow
 * release / publish fee charge) has already succeeded, so they are born ISSUED and move straight
 * to PAID; there is no DRAFT/FAILED state to model here (unlike the subscription invoice, which
 * is created from a webhook that can itself represent a failed charge).
 */
public enum MarketplaceInvoiceStatus {
    ISSUED,
    PAID
}
