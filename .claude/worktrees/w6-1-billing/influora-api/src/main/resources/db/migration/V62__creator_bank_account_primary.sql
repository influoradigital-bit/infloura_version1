-- Adds explicit primary-instrument selection to creator_bank_accounts.
-- Previously PayoutService.resolveFundAccountForCreator picked the most-recently-created row as
-- an implicit "primary" (findByCreatorUserIdOrderByCreatedAtDesc().get(0)). This column makes
-- primary selection explicit so a creator can add a new instrument without silently switching
-- their real payout destination, and so they can switch back to an older instrument if needed.
ALTER TABLE creator_bank_accounts
    ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: for any creator with existing rows, mark exactly one row primary — the most recently
-- created, matching the exact selection PayoutService used before this migration (no behavior
-- change for any account that already existed). Tie-broken on id (ULIDs are creation-order
-- monotonic) so two rows sharing a created_at timestamp can never both win: the correlated
-- subquery's ORDER BY ... LIMIT 1 always resolves to a single row per creator_user_id, unlike a
-- MAX(created_at) join (which matches every tied row and would incorrectly mark more than one
-- row primary per creator — QA-caught, fixed here before this ever ran against a real database).
UPDATE creator_bank_accounts cba
SET cba.is_primary = TRUE
WHERE cba.id = (
    SELECT winner_id FROM (
        SELECT id AS winner_id
        FROM creator_bank_accounts cba2
        WHERE cba2.creator_user_id = cba.creator_user_id
        ORDER BY cba2.created_at DESC, cba2.id DESC
        LIMIT 1
    ) AS winner
);

CREATE INDEX idx_creator_bank_accounts_primary ON creator_bank_accounts (creator_user_id, is_primary);

-- DB-level backstop: at most one primary row per creator, enforced independent of application
-- logic. MySQL has no native partial/filtered unique index, so this uses the standard
-- generated-column trick — primary_marker is NULL for every non-primary row (NULLs don't collide
-- in a UNIQUE index) and equals creator_user_id only when is_primary is true, so a second row for
-- the same creator racing to become primary hits a duplicate-key violation instead of silently
-- leaving two rows primary at once.
ALTER TABLE creator_bank_accounts
    ADD COLUMN primary_marker CHAR(26)
        GENERATED ALWAYS AS (CASE WHEN is_primary THEN creator_user_id ELSE NULL END) STORED;

ALTER TABLE creator_bank_accounts
    ADD UNIQUE INDEX uq_creator_bank_accounts_primary_marker (primary_marker);
