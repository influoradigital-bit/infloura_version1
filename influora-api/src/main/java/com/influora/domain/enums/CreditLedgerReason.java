package com.influora.domain.enums;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.5.
 *
 * <p>Every reason a row can appear in {@code creator_credit_ledger}. The column is {@code
 * reason VARCHAR(32)}, not a MySQL {@code ENUM} -- adding a constant here is a Java-only change,
 * no migration and no {@code ALTER} required (CREDITS-SPEC §2.5 note after this enum block). The
 * longest constant, {@code SEARCH_REFUND}, is 13 characters, well inside the 32-char column.
 *
 * <p>{@code SEARCH_DEBIT}, {@code SEARCH_REFUND} and {@code FREE_SEARCH} are Amendment A3 (K12,
 * §4A) -- web search. {@code FREE_SEARCH} always writes a {@code delta = 0} row against {@code
 * bucket = MONTHLY} (it spends the free weekly allowance); it is not itself a debit or a refund.
 */
public enum CreditLedgerReason {
    SIGNUP_GRANT,
    MONTHLY_RESET,
    PACK_PURCHASE,
    ADMIN_GRANT,
    TURN_DEBIT,
    TURN_REFUND,
    VOICE_DEBIT,
    VOICE_REFUND,
    BRIEF_DEBIT,
    BRIEF_REFUND,
    // Amendment A3 (Priya, 2026-09-20) -- K12, §4A web search.
    SEARCH_DEBIT,
    SEARCH_REFUND,
    FREE_SEARCH
}
