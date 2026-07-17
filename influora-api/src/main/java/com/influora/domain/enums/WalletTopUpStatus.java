package com.influora.domain.enums;

/**
 * Lifecycle of a brand wallet top-up order (B0 — see wiki/tech/BRAND_EXECUTION_PLAN.md). Mirrors
 * the {@code EscrowStatus} PENDING/FUNDED split, but scoped to just the two states a top-up needs:
 * a Razorpay order has been created and is awaiting webhook confirmation ({@code PENDING}), or the
 * webhook-verified payment has been credited to the brand's wallet ({@code CREDITED}).
 */
public enum WalletTopUpStatus {
    PENDING,
    CREDITED
}
