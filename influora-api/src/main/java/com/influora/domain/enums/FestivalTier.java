package com.influora.domain.enums;

/**
 * Sponsorship tier a brand selected on /festival-box (T-FESTIVALBOX-0905). Mirrors the three
 * tiers published on the page; the prices themselves live in the frontend copy, NOT here, so
 * that an Edition-02 reprice is a copy change and not a migration.
 *
 * <p>{@code UNDECIDED} is a real answer, not a null stand-in: the form offers it explicitly so a
 * brand that wants a call before committing can still submit. A {@code CREATOR} enquiry stores
 * {@code null} instead — it was never asked.
 */
public enum FestivalTier {
    /** Gifting Partner — product-value entry tier. */
    GIFTING,
    /** Featured Brand — the mid tier marked "most popular" on the page. */
    FEATURED,
    /** Title Sponsor — the Queen Bee campaign tier. */
    TITLE,
    /** Brand explicitly asked to discuss tiers on a call. */
    UNDECIDED
}
