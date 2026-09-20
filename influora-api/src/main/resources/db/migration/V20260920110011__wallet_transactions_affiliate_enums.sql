-- EV-011 -- widen wallet_transactions.type and .reference_type so the affiliate settlement chain
-- can actually post its ledger rows.
--
-- THE DEFECT. V8__wallet_transactions.sql:6-12 froze two MySQL ENUM columns:
--     type           ENUM('DEPOSIT','WITHDRAWAL','ESCROW_HOLD','ESCROW_RELEASE',
--                         'ESCROW_REFUND','PLATFORM_FEE','PAYOUT','ADJUSTMENT')
--     reference_type ENUM('COLLABORATION','ESCROW_HOLD','MILESTONE','CAMPAIGN',
--                         'DEPOSIT_ORDER','MANUAL')
-- [F-0402] later added WalletTransactionType.AFFILIATE_COMMISSION
-- (domain/enums/WalletTransactionType.java:16) and TxnReferenceType.AFFILIATE_EARNING
-- (domain/enums/TxnReferenceType.java:28) on the Java side, and made
-- AffiliateSettlementWriter#creditCreatorWallet post with them -- but no migration ever widened
-- the columns. On real MySQL that INSERT fails (strict mode: "Data truncated for column 'type'"),
-- the exception propagates out of AffiliateSettlementWriter#doSettleCreator, its @Transactional
-- rolls the whole creator's batch back, and AffiliateSettlementJob logs the creator as failed.
-- Net effect since F-0402 shipped: affiliate commission has NEVER been credited to any wallet on
-- MySQL. Unit tests never saw it because they run on H2 with ddl-auto (which emits VARCHAR for
-- @Enumerated(EnumType.STRING), accepting any string) or mock the ledger entirely.
--
-- WHY THIS IS APPEND-ONLY. MySQL stores an ENUM value as its 1-based ordinal index, not its text.
-- Reordering or inserting a value anywhere but the END silently re-labels every existing row
-- (an ADJUSTMENT row would start reading back as the value that took index 8). Both new members
-- are therefore appended after the last existing member, and every prior member is restated in
-- its original V8 order, character for character.
--
-- WHY MODIFY COLUMN RESTATES NOT NULL / NULL. MySQL's MODIFY COLUMN replaces the entire column
-- definition; any attribute not restated is dropped. `type` was NOT NULL in V8 and stays NOT NULL;
-- `reference_type` was NULL (nullable, no default) in V8 and stays nullable. Neither column has a
-- DEFAULT in V8, so none is restated. Indexes and the uq_wtx_idem/fk_wtx_wallet constraints are
-- untouched by MODIFY COLUMN and are deliberately not restated.
--
-- EXISTING ROWS. This is a widening only -- no existing value is removed, renamed or reordered, so
-- every already-persisted wallet_transactions row keeps the exact value it had. No backfill, no
-- data rewrite, and the statement is safe to run against a populated table. There are zero rows to
-- repair for the new members specifically: because the INSERT always failed, no AFFILIATE_*
-- wallet_transactions row has ever existed to be fixed.
--
-- Guarded against recurrence by WalletTransactionEnumConformanceTest, which parses THIS migration
-- chain and fails if any @Enumerated(EnumType.STRING) field mapped to a DB ENUM column has a Java
-- constant the column does not list.

ALTER TABLE wallet_transactions
  MODIFY COLUMN type ENUM('DEPOSIT','WITHDRAWAL','ESCROW_HOLD','ESCROW_RELEASE',
                          'ESCROW_REFUND','PLATFORM_FEE','PAYOUT','ADJUSTMENT',
                          'AFFILIATE_COMMISSION') NOT NULL;

ALTER TABLE wallet_transactions
  MODIFY COLUMN reference_type ENUM('COLLABORATION','ESCROW_HOLD','MILESTONE','CAMPAIGN',
                                    'DEPOSIT_ORDER','MANUAL',
                                    'AFFILIATE_EARNING') NULL;
