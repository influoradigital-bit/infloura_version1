-- Completes V20260718130000 (ULID CHAR->VARCHAR repair): that migration converted the id/FK
-- columns but missed the four remaining CHAR columns. Hibernate ddl-auto=validate maps every
-- @Column(length=N) to VARCHAR(N) and no entity in this codebase declares a char
-- columnDefinition, so boot fails with "wrong column type ... found [char]" on the first of
-- these it validates (campaign_service_invoices.currency, observed on live boot 2026-07-18).
-- None of these are FK endpoints, so no FOREIGN_KEY_CHECKS toggle is needed; MODIFY preserves
-- existing indexes (incl. the functional-unique marker indexes) and data.

ALTER TABLE campaign_service_invoices MODIFY COLUMN currency VARCHAR(3) NOT NULL;
ALTER TABLE creator_bank_accounts MODIFY COLUMN primary_marker VARCHAR(26) NULL;
ALTER TABLE platform_commission_invoices MODIFY COLUMN brand_leg_campaign_marker VARCHAR(26) NULL;
ALTER TABLE workspace_member_invites MODIFY COLUMN invite_token_hash VARCHAR(64) NOT NULL;
