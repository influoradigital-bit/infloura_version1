package com.influora.domain.enums;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.5.
 *
 * <p>Lifecycle of a creator credit-pack order. Mirrors {@code WalletTopUpStatus}'s
 * PENDING/CREDITED split: a Razorpay order has been created and is awaiting webhook confirmation
 * ({@code PENDING}), or the webhook-verified payment has been credited to the creator's {@code
 * purchased_balance} ({@code CREDITED}). No subscription, no wallet posting -- credits are not
 * money and never enter {@code wallet_transactions} (CREDITS-SPEC §1 R6).
 */
public enum CreatorCreditOrderStatus {
    PENDING,
    CREDITED
}
