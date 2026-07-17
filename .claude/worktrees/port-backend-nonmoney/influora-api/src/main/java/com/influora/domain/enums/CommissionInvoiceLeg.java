package com.influora.domain.enums;

/**
 * D14-E (2026-07-15, Rohan): commission invoices are SPLIT, not a combined 1:1 pair.
 *
 * <ul>
 *   <li>{@link #BRAND} — Doc#3a, campaign-scoped, issued at publish on {@code
 *       Campaign.getBudgetMax()}. One per campaign.
 *   <li>{@link #CREATOR} — Doc#3b, escrow-hold-scoped, issued at release. One per escrow hold.
 * </ul>
 */
public enum CommissionInvoiceLeg {
    BRAND,
    CREATOR
}
