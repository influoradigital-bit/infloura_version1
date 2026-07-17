-- PlatformFeeAdminController support (wiki/decisions/admin-pending-tasks-directive.md item #5 —
-- Swapnil-approved fee structure, Rohan's proposal: 10% brand / 15% creator, Option A = platform
-- absorbs Razorpay processing costs). Owner: Vikram (Backend).
--
-- NOTE ON MIGRATION NUMBERING: a concurrent session already claimed V40 twice
-- (V40__reviews.sql and what is now V41__platform_fee_config.sql, both originally saved as
-- "V40__..." — a genuine Flyway version collision that would have failed to boot). The latter
-- was renamed V40 -> V41 in this same batch to resolve that collision; this migration was
-- written as V42 to land after both. See SHARED_CONTEXT.md for the cross-session note.
--
-- V41__platform_fee_config.sql already created `platform_fee_config` as a SINGLETON row
-- (id='default') storing the CREATOR-side fee only, in basis points (`default_fee_bps`), and
-- PlatformFeeService already reads/writes that exact row shape to deduct the creator fee at
-- escrow release (out of scope for this task per the admin-pending-tasks-directive — "do NOT
-- build the brand/creator escrow-charging integration"). Rather than create a second, competing
-- `platform_fee_config`-shaped table (which would leave two disagreeing sources of truth for
-- "the creator fee"), this migration EXTENDS that existing table/entity with exactly the fields
-- the CEO directive asks for that don't exist yet: the brand-side fee (kept in the same bps unit
-- as the existing `default_fee_bps` column, for consistency within one table), the
-- Razorpay-cost-absorption flag, and who/when approved the current values. `default_fee_bps`
-- itself is untouched — it already holds 1500 (15.00%), which happens to already match the CEO's
-- creator-fee ruling.
ALTER TABLE platform_fee_config
  ADD COLUMN brand_fee_bps                 INT NOT NULL DEFAULT 1000 AFTER default_fee_bps,
  ADD COLUMN razorpay_absorbed_by_platform BOOLEAN NOT NULL DEFAULT TRUE AFTER allow_high_fee,
  ADD COLUMN approved_by                   VARCHAR(255) NULL AFTER razorpay_absorbed_by_platform,
  ADD COLUMN effective_at                  TIMESTAMP NULL AFTER approved_by;

-- Stamp the existing singleton row with the CEO-approved values so
-- `GET /admin/platform-fee-config` has real, correctly-attributed data from the first request —
-- matches the column defaults above but spelled out explicitly (and sets approved_by/
-- effective_at, which have no DEFAULT) rather than relying on the ALTER's implicit backfill.
UPDATE platform_fee_config
SET brand_fee_bps                 = 1000,
    razorpay_absorbed_by_platform = TRUE,
    approved_by                   = 'Swapnil Maruti (CEO)',
    effective_at                  = CURRENT_TIMESTAMP
WHERE id = 'default';
