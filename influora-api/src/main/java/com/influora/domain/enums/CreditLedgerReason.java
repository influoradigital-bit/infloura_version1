package com.influora.domain.enums;

/** T-CREATOR-CREDITS-V2 (SPEC.md §3) — {@code creator_credit_ledger.reason}. */
public enum CreditLedgerReason {
    GRANT_SIGNUP,
    GRANT_MONTHLY,
    GRANT_PURCHASE,
    GRANT_ADMIN,
    DEBIT_TURN,
    DEBIT_VOICE,
    DEBIT_BRIEF,
    REFUND,
    EXPIRE,
    /**
     * K-15 fix — a zero-delta marker written inside the SAME physical transaction as the
     * write-back's ASSISTANT {@code ai_messages} insert (see {@code
     * CreatorCreditService#markWritebackPersisted} and {@code
     * MeeraSessionService#doPersistAssistantWriteback}), under the same account row lock. A
     * concurrent {@code release(TURN)} takes that same lock, so by the time it can read the
     * ledger it is guaranteed to see this row if and only if the write-back actually committed —
     * unlike consulting {@code IdempotencyService}, whose completion write lands in a SEPARATE
     * {@code REQUIRES_NEW} transaction after the write-back's own commit and lock release,
     * leaving a real window where a release lands after the reply is durably persisted but before
     * the idempotency row flips to COMPLETED.
     */
    WRITEBACK_MARKER
}
