package com.influora.domain.enums;

/**
 * Structured content-usage channel a brand may claim rights over on a {@code Collaboration}
 * (T-MEERA-CREATOR-PHASE-A SPEC.md 1.1, A2). Stored as a comma-separated list of these names in
 * {@code collaborations.usage_channels} — see {@link com.influora.domain.entity.Collaboration}.
 */
public enum UsageChannel {
    ORGANIC,
    PAID_ADS,
    WHITELISTING,
    WEBSITE,
    OFFLINE
}
