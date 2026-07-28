-- [FIX: escrow-frozen-hold-fix-spec, Fix 4] Backfills `escrow_holds.collaboration_id` for every
-- existing hold that was created through the ordinary brand escrow flow (POST
-- /wallet/escrow/fund) and never had it bound. `EscrowService.initiateFund` built the hold
-- (:201-211, pre-fix) without ever setting `collaboration_id` -- the only caller of
-- `EscrowHold.bindCollaboration` in the entire codebase was `ConfirmLaunchExecutor:501`
-- (Meera's AI launch tool) -- so every ordinary-flow hold funded before this migration has
-- `collaboration_id = NULL`, linked to its collaboration only indirectly via
-- `payment_milestones.collaboration_id` (NOT NULL on that table).
--
-- App-layer Fix 1 (EscrowService#resolveHoldsForCollaboration) already makes every read path
-- robust to that null column via the milestone fallback, so this backfill is not required for
-- correctness going forward -- but it makes the `escrow_holds` row itself tell the truth, matches
-- Fix 2 (new holds are now bound at creation time), and removes the need for every future reader
-- of this table to know about the fallback at all.
--
-- Idempotent and non-destructive:
--   * Only touches rows where collaboration_id IS NULL -- a hold that already carries a value
--     (e.g. bound by ConfirmLaunchExecutor, or already backfilled by a prior run of this same
--     migration in a lower environment) is never overwritten.
--   * Only resolvable rows are touched -- a hold with no milestone_id (campaign-level pool
--     funding, e.g. initiateFund called with milestoneId == null) has no
--     payment_milestones row to join against and is correctly left NULL, matching Fix 2's
--     documented "genuinely cannot be resolved yet" case.
--   * Safe to re-run: after the first run, no row still has both collaboration_id IS NULL and a
--     resolvable milestone_id, so the UPDATE's WHERE clause matches zero rows on any subsequent
--     run.
UPDATE escrow_holds eh
INNER JOIN payment_milestones pm ON pm.id = eh.milestone_id
SET eh.collaboration_id = pm.collaboration_id
WHERE eh.collaboration_id IS NULL
  AND eh.milestone_id IS NOT NULL;
