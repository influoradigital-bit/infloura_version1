-- F-4 (wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6) — backfills a Free/ACTIVE `subscriptions`
-- row for every existing BRAND workspace that does not already have one.
--
-- Before this migration, the ONLY thing that ever created a `subscriptions` row was
-- SubscriptionService#getOrCreateFreeSubscription, reached lazily from GET /billing/plan. A brand
-- that never opened billing settings had zero subscription rows, and
-- UsageCounterService#resolvePeriodStart silently fell back to a calendar-month anchor
-- (LocalDate.now(UTC).withDayOfMonth(1), recomputed on every call) instead of the billing-period
-- anchor a real subscription row carries. App code (AuthService#brandRegister,
-- FestivalSponsorProvisioningService#provision) now provisions the row eagerly at workspace
-- creation going forward; this migration is the one-time catch-up for every workspace created
-- before that change shipped.
--
-- Anchor value matches the app exactly, so this backfill cannot orphan an in-flight usage
-- counter: SubscriptionService#currentBillingCycleStart() computes
-- `LocalDate.now(UTC).withDayOfMonth(1)` at midnight UTC, byte-identical to the fallback
-- UsageCounterService#resolvePeriodStart already uses for these same workspaces today. A brand
-- who used a Free-tier metered feature (e.g. TRACKED_CREATOR) earlier in this SAME UTC calendar
-- month keeps resolving to the identical period_start after this migration runs; only a brand
-- whose usage predates the current UTC month sees period_start move forward, which is exactly
-- what the normal monthly rollover would already do to them regardless of this migration.
--
-- Scoped to `type = 'BRAND'` ONLY — mirrors AICreditResetJob's fix for the identical class of bug
-- (see that job's class javadoc): an earlier unfiltered sweep there created spurious
-- BrandAiCredit rows for AGENCY workspaces via AICreditService#ensureInitialized.
-- BrandAiCredit/subscriptions are a brand-only concept; an AGENCY workspace must never be given
-- one, spuriously or otherwise.
--
-- Idempotent and non-destructive:
--   * The NOT EXISTS guard means a workspace that already has a row (lazily created, comp'd,
--     or already backfilled by a prior run of this same migration in a lower environment) is
--     never touched -- Free, Pro, comp, or any other plan/status is left exactly as it is.
--   * `subscriptions.workspace_id` carries its own UNIQUE constraint (V54__subscription_billing.sql)
--     as a second, DB-enforced backstop against ever inserting a duplicate row for one workspace.
--   * Safe to re-run: after the first run, no BRAND workspace lacks a subscriptions row, so this
--     INSERT's SELECT matches zero rows on any subsequent run.
--
-- id is a 26-char opaque string, matching the `CHAR(26)` PK shape `subscriptions.id` expects
-- (com.influora.common.Ulids never parses stored ids back into a real ULID -- ordering/timestamp
-- extraction from this column happens nowhere in the codebase -- so a hex string of the right
-- length is exactly as valid here as an app-generated Crockford-base32 ULID would be).
-- The candidate-workspace-id list is computed in its own derived table (`bw`) rather than a
-- NOT EXISTS clause sitting directly in this INSERT's outer SELECT. MySQL's documented
-- restriction ("target table of INSERT ... SELECT cannot appear in the FROM clause of the SELECT")
-- is unambiguously satisfied once the reference to `subscriptions` is inside a derived table that
-- MySQL materializes before the INSERT begins, which is MySQL's own documented workaround for
-- this exact error class (ERROR 1093) — no live MySQL is reachable from this environment to
-- confirm empirically (no Docker; see this repo's own recurring "Docker still absent" notes), so
-- this migration deliberately takes the form MySQL's documentation states is always safe rather
-- than the form that might or might not be, for a statement nobody will get a second chance to
-- test before it runs against production data.
INSERT INTO subscriptions (
  id, workspace_id, plan_id, status, current_period_start, current_period_end
)
SELECT
  UPPER(SUBSTRING(REPLACE(UUID(), '-', ''), 1, 26)),
  bw.id,
  (SELECT id FROM plans WHERE code = 'FREE' LIMIT 1),
  'ACTIVE',
  DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'),
  DATE_FORMAT(UTC_TIMESTAMP() + INTERVAL 1 MONTH, '%Y-%m-01 00:00:00')
FROM (
  SELECT w.id
  FROM workspaces w
  WHERE w.type = 'BRAND'
    AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.workspace_id = w.id)
) AS bw;
