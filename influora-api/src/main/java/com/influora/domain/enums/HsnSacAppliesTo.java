package com.influora.domain.enums;

/** What a configurable {@code hsn_sac_codes} row is rendered on. Rohan build-flag #4 — never hardcode the code itself. */
public enum HsnSacAppliesTo {
    CREATOR_SERVICE,
    PLATFORM_COMMISSION,
    SUBSCRIPTION,
    /** T-CREATOR-CREDITS-V2 (SPEC.md B1/B21) — the creator AI-credit top-up (Rs 249 for 60 credits). */
    CREATOR_CREDITS
}
