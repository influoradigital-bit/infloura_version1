-- B0 (wiki/tech/BRAND_EXECUTION_PLAN.md, LOCKED leadership ruling 2026-07-09, item 3): brand
-- wallet top-up via Razorpay. Mirrors escrow_holds' PENDING-order / webhook-confirmed-credit shape
-- (V9__escrow_holds.sql) rather than reusing that table directly, since a top-up has no
-- campaign/milestone/collaboration linkage and a distinct terminal-state pair (PENDING/CREDITED,
-- not the 5-state escrow machine).
--
-- No new WalletTransactionType/TxnReferenceType enum value was needed: WalletTransactionType
-- already has DEPOSIT and TxnReferenceType already has DEPOSIT_ORDER (both pre-existing, unused
-- until this slice) -- the exact semantic fit for "external funds credited into a workspace
-- wallet" that WalletService#deposit's javadoc already described. Reused as-is; see
-- WalletTopUpService for the ledger posting.
CREATE TABLE wallet_topups (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) — brand topping up
  amount            DECIMAL(14,2) NOT NULL,                  -- requested amount; cross-checked against the webhook's captured amount before crediting
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  status            ENUM('PENDING','CREDITED') NOT NULL DEFAULT 'PENDING',
  razorpay_order_id VARCHAR(64) NULL,
  credit_txn_id     VARCHAR(26) NULL,                        -- FK wallet_transactions(id) — the CREDIT leg into the brand wallet
  idempotency_key   VARCHAR(64) NOT NULL,
  credited_at       TIMESTAMP NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_wallet_topup_idem (idempotency_key),
  INDEX idx_wallet_topup_workspace (workspace_id),
  INDEX idx_wallet_topup_status (status),
  INDEX idx_wallet_topup_razorpay_order (razorpay_order_id),
  CONSTRAINT fk_wallet_topup_workspace  FOREIGN KEY (workspace_id)  REFERENCES workspaces(id),
  CONSTRAINT fk_wallet_topup_credittxn  FOREIGN KEY (credit_txn_id) REFERENCES wallet_transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
