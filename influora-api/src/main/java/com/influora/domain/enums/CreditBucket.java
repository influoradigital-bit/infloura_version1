package com.influora.domain.enums;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.5.
 *
 * <p>The two buckets a creator's credit balance is split across (CREDITS-SPEC §1 R2): {@code
 * MONTHLY} resets to the monthly allotment on the 1st (not cumulative); {@code PURCHASED} never
 * expires and holds packs, the signup grant, and admin grants. A single debit can spend from both
 * buckets in one transaction (monthly first, then purchased), which is why {@code
 * creator_credit_ledger}'s unique key includes {@code bucket} -- one turn can produce two ledger
 * rows, one per bucket.
 */
public enum CreditBucket {
    MONTHLY,
    PURCHASED
}
