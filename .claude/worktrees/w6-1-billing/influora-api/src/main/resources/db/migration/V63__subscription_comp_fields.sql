-- Admin billing console (Task 25 subscription-billing Phase 4b, AdminBillingController) — comp
-- grant support. Next free slot is V63 (highest existing is V62__creator_bank_account_primary.sql;
-- V40/V60 intentionally skipped, same convention noted in V54's header). Owner: Vikram (Backend).
--
-- `POST /admin/billing/comp` (grant complimentary Pro) and `POST /admin/billing/override` (admin
-- plan reassignment, narrow first-pass scope — see AdminBillingController class javadoc) both
-- write a `subscriptions` row with `razorpay_subscription_id = NULL`, same as the lazily-created
-- Free-tier row (SubscriptionService#createFreeSubscription). `razorpay_subscription_id IS NULL`
-- alone is therefore NOT a reliable "this is a comp" signal — it's also true for every ordinary
-- Free-tier workspace. These columns make "admin-granted, not backed by a real Razorpay
-- subscription" an explicit, queryable fact instead of an inferred one, which matters for at least
-- two real consumers: (1) MRR must exclude comp'd Pro subscriptions (they generate zero revenue —
-- see AdminBillingService#getMetrics), and (2) the admin console needs to visibly flag which rows
-- are comps vs. genuine paid subscriptions.
ALTER TABLE subscriptions
    ADD COLUMN is_comp BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'TRUE if this row was admin-granted (comp or plan override) rather than activated from a real Razorpay subscription webhook',
    ADD COLUMN comp_reason VARCHAR(500) NULL
        COMMENT 'Mandatory admin-supplied justification for the grant — mirrored into admin_audit_log.reason, kept here too so the reason travels with the row itself',
    ADD COLUMN comp_granted_by VARCHAR(26) NULL
        COMMENT 'admin_users.id of the granting SUPER_ADMIN — server-derived from AuthPrincipal, never client-supplied (AUDIT-LOG-WRITE-SPEC.md Rule 1)',
    ADD COLUMN comp_expires_at TIMESTAMP NULL
        COMMENT 'Optional planned expiry entered by the admin. NOT currently auto-enforced by any job — SubscriptionRenewalResetJob/SubscriptionDunningJob do not distinguish comp rows and will not automatically downgrade a comp past this date. See AdminBillingController class javadoc "Known gap" note.',
    ADD INDEX idx_subscriptions_is_comp (is_comp),
    ADD CONSTRAINT fk_subscriptions_comp_granted_by FOREIGN KEY (comp_granted_by) REFERENCES admin_users(id);
