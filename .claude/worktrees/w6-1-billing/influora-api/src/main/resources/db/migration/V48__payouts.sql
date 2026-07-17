-- P2-12: Real RazorpayX payout tracking — no more documented no-ops.
-- Payout state reconciliation for PayoutService.confirmExecuted webhook path.

CREATE TABLE payouts (
    id                  VARCHAR(26)  COLLATE utf8mb4_unicode_ci NOT NULL PRIMARY KEY,
    milestone_id        VARCHAR(26)  COLLATE utf8mb4_unicode_ci NOT NULL,
    creator_user_id     VARCHAR(26)  COLLATE utf8mb4_unicode_ci NOT NULL,
    razorpay_payout_id  VARCHAR(64)  COLLATE utf8mb4_unicode_ci NOT NULL UNIQUE,
    fund_account_id     VARCHAR(64)  COLLATE utf8mb4_unicode_ci NOT NULL,
    amount              DECIMAL(12,2) NOT NULL,
    currency            VARCHAR(3)   COLLATE utf8mb4_unicode_ci NOT NULL,
    status              VARCHAR(32)  COLLATE utf8mb4_unicode_ci NOT NULL,
    idempotency_key     VARCHAR(64)  COLLATE utf8mb4_unicode_ci NOT NULL UNIQUE,
    webhook_payload     TEXT         COLLATE utf8mb4_unicode_ci NULL,
    created_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    confirmed_at        TIMESTAMP(3) NULL,
    CONSTRAINT fk_payout_milestone FOREIGN KEY (milestone_id) REFERENCES payment_milestones (id),
    CONSTRAINT fk_payout_creator FOREIGN KEY (creator_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_payout_milestone ON payouts (milestone_id);
CREATE INDEX idx_payout_creator ON payouts (creator_user_id);
CREATE INDEX idx_payout_razorpay_id ON payouts (razorpay_payout_id);
