-- Optimistic-locking + cross-delivery webhook-ordering guard columns for subscriptions.
-- Kabir red-team, wiki/errors/subscription-phase2-kabir-redteam.md (Phase 2 Task 19/20 audit,
-- CONDITIONAL PASS -- MEDIUM-1 and MEDIUM-2). Owner: Vikram (Backend).
--
-- MEDIUM-2 (lost-update race): applySubscriptionWebhookUpdate's UPDATE branch had no @Version
-- and no row lock -- two genuinely concurrent webhook deliveries with different idempotency keys
-- (e.g. subscription.activated and subscription.charged firing near-simultaneously for the same
-- first payment) could both read the same pre-update row and lost-update each other with no
-- error, no log. Same pattern as V44__platform_fee_config_version.sql / V53__disputes_version.sql.
--
-- MEDIUM-1 (no cross-delivery ordering guard): Razorpay guarantees webhook delivery ordering only
-- within retries of the SAME event, never across distinct events for the same subscription. A
-- delayed retry of an older event (e.g. a subscription.charged queued before an outage) landing
-- after a genuinely newer event (e.g. subscription.halted) was already applied could flip status
-- back and/or reset the billing period to stale values. last_webhook_event_at stores the envelope's
-- own created_at from the most-recently-*applied* delivery so a new delivery can be compared
-- against it and skipped (logged, not applied) when older.
--
-- Forward-only: extends V54__subscription_billing.sql rather than editing it in place.
ALTER TABLE subscriptions
  ADD COLUMN last_webhook_event_at TIMESTAMP NULL AFTER cancel_at_period_end,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER seats_purchased;
