-- V71 — manual payout rail (admin-recorded, out-of-band bank transfer).
--
-- WHY: RazorpayX is not provisioned, so money leaves the business through a human doing a NEFT/UPI
-- transfer from the company bank account. Without somewhere to record that, the creator's Influora
-- balance keeps showing money they have already been paid: the ledger disagrees with the bank, the
-- creator is told they are owed money twice, and nothing marks the amount as settled.
--
-- These three columns are what a bank transfer has and a RazorpayX payout does not:
--   payout_method  — how the money left. Existing rows are all gateway payouts, hence the default.
--   bank_reference — the UTR / transaction reference from the bank. This is the ONLY audit link
--                    between a `payouts` row and an actual movement of money, so a manual payout
--                    without one is unverifiable after the fact.
--   tds_amount     — TDS deducted at source. Captured HERE, at the moment of payment, because the
--                    platform has no TDS engine: if the admin records gross and keeps the deduction
--                    in a spreadsheet, commission invoice Doc#3b disagrees with the bank statement
--                    from the first payout and the gap compounds silently. NULL means "no TDS
--                    applied", which is a different statement from 0.00 ("TDS considered, none due").
--
-- Nullable + defaulted so this is a pure additive migration: no backfill, no lock on a hot table,
-- and every existing gateway payout keeps its current meaning untouched.

ALTER TABLE payouts
    ADD COLUMN payout_method  VARCHAR(16)    NOT NULL DEFAULT 'GATEWAY',
    ADD COLUMN bank_reference VARCHAR(64)    NULL,
    ADD COLUMN tds_amount     DECIMAL(12, 2) NULL;

-- Finding every manual payout is an accounting question that will be asked often (reconciliation
-- against the bank statement, TDS filing), and it is always a small slice of the table.
CREATE INDEX idx_payouts_method ON payouts (payout_method);

-- A UTR identifies exactly one real bank transfer. Recording it twice means either a duplicate
-- payment was made or an admin double-submitted the form, and both must fail loudly rather than
-- silently double-debit a creator. Partial-unique via NULL: gateway payouts leave it NULL and are
-- unaffected, since MySQL/MariaDB unique indexes permit repeated NULLs.
CREATE UNIQUE INDEX uq_payouts_bank_reference ON payouts (bank_reference);
