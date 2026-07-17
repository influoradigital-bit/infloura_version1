package com.influora.domain.enums;

/**
 * D14-C (2026-07-15, Rohan): statutory invoice numbering series. Each type maps to a distinct,
 * per-financial-year continuous sequence — never a shared/global counter, per GST requirements.
 *
 * <ul>
 *   <li>{@link #SUBSCRIPTION} — Doc#1, {@code INF/SUB/<FY>/<seq>}, one platform-wide series.
 *   <li>{@link #COMMISSION_BRAND} — Doc#3a, {@code INF/CMB/<FY>/<seq>}, one platform-wide series.
 *   <li>{@link #COMMISSION_CREATOR} — Doc#3b, {@code INF/CMC/<FY>/<seq>}, one platform-wide series.
 *   <li>{@link #CAMPAIGN_SERVICE} — Doc#2, {@code <CreatorInvoiceCode>/<FY>/<seq>}, one series
 *       PER creator (each creator is their own supplier of record and therefore needs their own
 *       unbroken statutory series — NOT a global counter).
 * </ul>
 */
public enum InvoiceNumberSeriesType {
    SUBSCRIPTION,
    COMMISSION_BRAND,
    COMMISSION_CREATOR,
    CAMPAIGN_SERVICE
}
