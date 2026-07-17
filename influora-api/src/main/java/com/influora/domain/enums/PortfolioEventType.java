package com.influora.domain.enums;

/**
 * Discriminator for {@code portfolio_events} — the single append-only table behind the creator
 * portfolio-analytics numbers (Priya CTO ruling 2026-07-18). One table, typed rows, so views,
 * media-kit downloads, and link-clicks don't each spawn their own table.
 */
public enum PortfolioEventType {
    /** A public view of {@code GET /portfolio/{username}} — powers "Page views (30d)". */
    VIEW,
    /** A media-kit PDF download — powers the "media kit downloads" analytics count. */
    MEDIA_KIT_DOWNLOAD,
    /** A click on a portfolio custom link — reserved for the link-click analytics pass. */
    LINK_CLICK
}
