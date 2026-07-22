-- [SEC: Kabir C-18] Forward-fix for V7__seed_discoverable_creators.sql, which runs in EVERY
-- environment (including prod) and inserts 5 ACTIVE, email-verified "creator" accounts that all
-- share the bcrypt of the plaintext-committed password "Password@123". This migration removes
-- those 5 accounts and their dependent rows from every environment. `DevSeedCreatorsRunner`
-- (dev-profile only, @Profile("dev")) re-inserts the identical rows after Flyway runs, so local
-- development is unaffected — only real environments lose the fabricated inventory.
--
-- Deletes are scoped to the exact fixed `01SEED*` ids V7 inserted (child tables first, to satisfy
-- FK constraints), so this is safe/idempotent to run whether or not those rows are still present.

DELETE FROM platform_stats WHERE id IN (
  '01SEEDPL00000000000000001',
  '01SEEDPL00000000000000002',
  '01SEEDPL00000000000000003',
  '01SEEDPL00000000000000004',
  '01SEEDPL00000000000000005',
  '01SEEDPL00000000000000006',
  '01SEEDPL00000000000000007',
  '01SEEDPL00000000000000008',
  '01SEEDPL00000000000000009'
);

DELETE FROM creator_profiles WHERE id IN (
  '01SEEDCR00000000000000001',
  '01SEEDCR00000000000000002',
  '01SEEDCR00000000000000003',
  '01SEEDCR00000000000000004',
  '01SEEDCR00000000000000005'
);

DELETE FROM users WHERE id IN (
  '01SEED00000000000000000001',
  '01SEED00000000000000000002',
  '01SEED00000000000000000003',
  '01SEED00000000000000000004',
  '01SEED00000000000000000005'
);
