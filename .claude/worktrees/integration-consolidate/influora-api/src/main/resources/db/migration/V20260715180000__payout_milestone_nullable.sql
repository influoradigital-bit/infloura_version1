-- B10 — WalletService.requestCreatorWithdrawal now persists a Payout row for a lump-sum creator
-- wallet withdrawal that has no linked payment_milestones row (unlike PayoutService.queuePayout's
-- milestone-linked payouts, which always set this). The FK to payment_milestones already tolerates
-- NULL values in MySQL; only the NOT NULL constraint needs to be lifted.
ALTER TABLE payouts
    MODIFY COLUMN milestone_id VARCHAR(26) COLLATE utf8mb4_unicode_ci NULL;
