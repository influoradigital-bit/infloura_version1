-- Festival Box Phase 4 (T-FESTIVALBOX-0905) -- coupon_codes.creator_id becomes NULLABLE to support
-- a second kind of coupon: ONE brand-level ("page-exclusive") code per campaign, alongside the
-- existing per-creator codes.
--
-- [RELATIONSHIP TO V24 SECTION 10 -- READ THIS BEFORE TOUCHING creator_id AGAIN] V24's header
-- documents that section 10 ("Unique Coupons Per Creator (CRITICAL FIX)") deliberately made
-- creator_id NOT NULL, overriding an earlier design that allowed a nullable creator_id/campaign-
-- wide code, because "same coupon code for multiple creators -> broken attribution." THIS
-- MIGRATION DOES NOT REVERT THAT FIX AND DOES NOT REOPEN THAT HOLE.
--
-- Section 10's problem was N *creators* silently sharing ONE code -- an ambiguous split of credit
-- among several real people. What this migration adds is a different case in kind, not a
-- relaxation of that one: a coupon with NO creator at all, where "this sale has no creator to
-- attribute to -- it is a brand-only, page-exclusive code" is the entire point, not an accident or
-- a shortcut back to campaign-wide multi-creator sharing. A NULL-creator coupon can never be
-- confused for a shared per-creator code: every consumer (CouponCode#isBrandLevel(),
-- AffiliateEarningsService#recordEarning, CouponCodeService) treats creator_id IS NULL as its own
-- explicit, intentional branch, never as "creator unknown/TBD". Section 10's own constraint --
-- UNIQUE(campaign_id, creator_id), untouched below -- still means no two *creators* can ever share
-- a code on one campaign. This migration only ADDS a second, narrower uniqueness rule (step 3
-- below) for the new NULL-creator case that section 10's constraint structurally cannot cover:
-- MySQL treats every NULL in a UNIQUE key as distinct from every other NULL, so without step 3,
-- nothing would stop unlimited brand-level (NULL-creator) rows being inserted for one campaign --
-- exactly the "which one is real" ambiguity section 10 was written to prevent, just relocated to
-- the brand-level case instead of the per-creator case.
--
-- [VERIFICATION NOTE] Docker's daemon is down in this environment -- every statement below has been
-- read/reasoned against the MySQL 8.0 Reference Manual (Online DDL "Changing the NULL/NOT NULL
-- attribute" and "Adding a generated column" support tables; the generated-column rule that an
-- expression may reference other columns of the SAME row, just not other rows/subqueries/
-- non-deterministic functions) but has NOT been executed against a real MySQL server. It has not
-- been run against H2 either -- H2's MySQL compatibility mode does not reliably reproduce MySQL 8's
-- STORED generated-column + composite-UNIQUE-with-NULL semantics this migration depends on, so a
-- green H2 test here would not actually prove this constraint works; see
-- FestivalBoxCouponConstraintIntegrationTest (Testcontainers-MySQL, extends AbstractIntegrationTest,
-- auto-skipped without Docker via DockerAvailableCondition) for the test that proves it for real
-- the next time Docker/CI is available.

-- 1) creator_id becomes nullable. This is a nullability-only MODIFY COLUMN -- it does not change
-- the column's name, type, or length, so it does not require (and does not silently drop) the
-- fk_coupon_creator FOREIGN KEY or any of the existing indexes on this column
-- (uq_coupon_campaign_creator, idx_coupon_creator) -- those are separate catalog objects keyed off
-- the column, not attributes of the column definition itself, and MySQL's ALTER TABLE does not
-- require restating them for a plain nullability flip. A NULL creator_id on an FK column is
-- standard SQL FK semantics (MySQL only supports MATCH SIMPLE): the FK is simply not evaluated for
-- a NULL value, so inserting a NULL creator_id never needs a matching creator_profiles row.
ALTER TABLE coupon_codes
  MODIFY COLUMN creator_id VARCHAR(26) NULL;                          -- was NOT NULL (V24 section 10); FK fk_coupon_creator + indexes below are untouched

-- 2) A STORED generated column that evaluates to a constant marker (1) exactly when creator_id IS
-- NULL, and to NULL for every ordinary per-creator row. Referencing another column of the SAME row
-- in a generated-column expression is standard, supported MySQL 8 (CASE/IF are on the deterministic
-- allow-list for generated columns).
ALTER TABLE coupon_codes
  ADD COLUMN brand_level_marker TINYINT
    GENERATED ALWAYS AS (CASE WHEN creator_id IS NULL THEN 1 ELSE NULL END) STORED;  -- 1 for a brand-level code, NULL for every per-creator code

-- 3) The actual schema-level enforcement for landmine 2 (see task brief): MySQL treats every NULL
-- in a UNIQUE key as distinct from every other NULL, so UNIQUE(campaign_id, brand_level_marker)
-- places NO limit on how many per-creator rows (brand_level_marker = NULL) one campaign can have --
-- uq_coupon_campaign_creator above already governs those -- but it DOES limit brand-level rows
-- (brand_level_marker = 1) to at most ONE per campaign, enforced by the schema itself, not only by
-- an application-level existence check. A concurrent double-insert of two brand-level codes for the
-- same campaign hits this constraint (surfaced by CouponCodeService as BRAND_CODE_EXISTS, 409),
-- not a race window in a service-layer SELECT-then-INSERT.
ALTER TABLE coupon_codes
  ADD UNIQUE KEY uq_coupon_campaign_brand_level (campaign_id, brand_level_marker);
