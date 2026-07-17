CREATE TABLE wallet_transactions (
  id                VARCHAR(26) PRIMARY KEY,                 -- ULID
  wallet_id         VARCHAR(26) NOT NULL,                    -- FK wallets(id)
  group_id          VARCHAR(26) NOT NULL,                    -- ties the two legs of one movement
  direction         ENUM('DEBIT','CREDIT') NOT NULL,
  type              ENUM('DEPOSIT','WITHDRAWAL','ESCROW_HOLD','ESCROW_RELEASE',
                    'ESCROW_REFUND','PLATFORM_FEE','PAYOUT','ADJUSTMENT') NOT NULL,
  amount            DECIMAL(14,2) NOT NULL,                  -- always positive; direction carries sign
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  balance_after     DECIMAL(14,2) NOT NULL,                  -- wallet balance snapshot post-apply (audit)
  status            ENUM('PENDING','COMPLETED','FAILED','REVERSED') NOT NULL DEFAULT 'COMPLETED',
  reference_type    ENUM('COLLABORATION','ESCROW_HOLD','MILESTONE','CAMPAIGN','DEPOSIT_ORDER','MANUAL') NULL,
  reference_id      VARCHAR(26) NULL,
  description       VARCHAR(300),
  idempotency_key   VARCHAR(64) NOT NULL,                    -- one key per logical event; UNIQUE
  gateway_ref       VARCHAR(120) NULL,                       -- razorpay order/payment id
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_wtx_idem (idempotency_key),
  INDEX idx_wtx_wallet (wallet_id),
  INDEX idx_wtx_group (group_id),
  INDEX idx_wtx_reference (reference_type, reference_id),
  CONSTRAINT fk_wtx_wallet FOREIGN KEY (wallet_id) REFERENCES wallets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
