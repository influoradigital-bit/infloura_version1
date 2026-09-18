-- F-0892 (wiki/decisions/2026-09-18-agency-chooser-removed.md): the Agency workspace chooser is
-- removed by ruling. Converts every AGENCY workspace to BRAND, then gives it the same Free/ACTIVE
-- `subscriptions` row every BRAND workspace is entitled to.
--
-- Ordering against V20260912120000__backfill_free_subscriptions.sql matters: that backfill is
-- scoped to `type = 'BRAND'` ON PURPOSE (subscriptions/BrandAiCredit are a brand-only concept --
-- see its own header) and is not deployed yet. On production it will run BEFORE this migration
-- (lower version) and will therefore skip all 4 AGENCY workspaces, since at that point they are
-- still AGENCY. Step 1 here converts them to BRAND; step 2 then does, for those 4 rows only, the
-- exact insert V20260912120000 already did for every other BRAND workspace -- same derived-table
-- shape (MySQL ERROR 1093: a statement cannot SELECT from the table it INSERTs into directly),
-- same NOT EXISTS guard, same 26-char id, same 1st-of-UTC-month period anchor. Column list checked
-- against V54__subscription_billing.sql plus every later ALTER (V56 last_webhook_event_at/version,
-- V63 is_comp/comp_reason/comp_granted_by/comp_expires_at): all of those either allow NULL or carry
-- a DEFAULT, so the same 6-column insert V20260912120000 used is still complete.
--
-- Idempotent and non-destructive:
--   * Step 1's UPDATE only touches rows still AGENCY -- a no-op on any re-run.
--   * Step 2's NOT EXISTS guard means a workspace that already has a subscriptions row (lazily
--     created, comp'd, or backfilled by an earlier run of this same migration) is never touched.
--   * `subscriptions.workspace_id` carries its own UNIQUE constraint (V54) as a second, DB-enforced
--     backstop against ever inserting a duplicate row for one workspace.
--   * Safe to re-run in any order relative to V20260912120000: whichever of the two runs second
--     finds every workspace it would have inserted for already has a row, and inserts nothing.
--
-- No live MySQL reachable from this environment (no Docker) to run this migration -- reviewed by
-- reading only, matching V20260912120000's own note on the same constraint.

-- Step 1: WorkspaceType.AGENCY is no longer offered or stored (Workspace#applyCompanyDetails now
-- coerces it) -- convert the existing rows. Nothing agency-specific hangs off `workspaces.type`
-- (CreatorAgentPreferences.agency_name is an unrelated creator field), so converting the type
-- strands no data.
UPDATE workspaces SET type = 'BRAND' WHERE type = 'AGENCY';

-- Step 2: same Free/ACTIVE backfill as V20260912120000, limited in practice to the just-converted
-- AGENCY-turned-BRAND workspaces (every other BRAND workspace was already covered there or by the
-- app's eager provisioning at signup).
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
