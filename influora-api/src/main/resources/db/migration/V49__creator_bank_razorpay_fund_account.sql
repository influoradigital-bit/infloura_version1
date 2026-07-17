-- P2-12: Store Razorpay fund_account_id mapping for payout KYC lookup.
-- Links our encrypted CreatorBankAccount to Razorpay's fund account reference.

ALTER TABLE creator_bank_accounts
ADD COLUMN razorpay_contact_id VARCHAR(64) COLLATE utf8mb4_unicode_ci NULL AFTER display_mask,
ADD COLUMN razorpay_fund_account_id VARCHAR(64) COLLATE utf8mb4_unicode_ci NULL AFTER razorpay_contact_id;

CREATE INDEX idx_creator_bank_razorpay_fund ON creator_bank_accounts (razorpay_fund_account_id);
