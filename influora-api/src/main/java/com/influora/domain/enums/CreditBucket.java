package com.influora.domain.enums;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §4) — which allowance a {@code creator_credit_grants} row belongs
 * to. Spend order (R4, {@code GrantOrder#sort}) is free before paid: FREE_MONTHLY/FREE_SIGNUP/ADMIN
 * before PAID, soonest {@code expires_at} first within a group.
 */
public enum CreditBucket {
    /** The once-ever 40 credits granted on signup + Instagram connect. Never expires. */
    FREE_SIGNUP,
    /** The 15 credits granted on the 1st of every IST month. Expires at the next month's start. */
    FREE_MONTHLY,
    /** A Rs 249 / 60-credit pack. Expires 90 days after payment. */
    PAID,
    /** A manual grant issued by an admin (no automatic expiry unless one is set). */
    ADMIN
}
