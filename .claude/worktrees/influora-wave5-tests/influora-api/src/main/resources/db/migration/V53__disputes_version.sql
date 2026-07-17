-- Optimistic-locking column for disputes (Kabir security review, phase1-admin-panel-escrow-security.md
-- High finding #1): DisputeService.resolveDispute loaded the Dispute via a plain findById with no
-- row lock and no @Version. Two concurrent /admin/disputes/:id/resolve calls (double-click, two
-- admin tabs, or a race) could both read the dispute while still OPEN, both pass the in-memory
-- isActive() check, and the second call's stale object would blind-overwrite the first's committed
-- status/resolvedByAdminId/resolutionNotes on save -- even though the escrow money movement itself
-- is already race-safe via EscrowHold's per-hold pessimistic lock. Owner: Vikram (Backend).
--
-- Forward-only, same pattern as V44__platform_fee_config_version.sql: extends the existing table
-- rather than editing V45__disputes.sql in place.
ALTER TABLE disputes
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER updated_at;
