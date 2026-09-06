-- T-FESTIVALBOX-0905 phase 6 -- coupon-copy demand-signal tracking for the public Festival Box
-- page (POST /festival/coupon-copied), fired when a shopper taps a coupon code to copy it.
--
-- WHY THIS IS THE ONLY DEMAND SIGNAL FOR SOME SPONSORS: a shopper who copies a code and buys on
-- Amazon (or any marketplace we do not run a redemption webhook against) leaves NO other trace in
-- this system -- there is no order webhook, no click-to-purchase attribution, nothing. For a
-- Shopify/WooCommerce sponsor a copy sits ABOVE redemption in the funnel, so it is an additional
-- intent metric, not a replacement for one. Either way: a copy is INTENT, never a sale, and this
-- table must never be blended with or read as redemption/sales data.
--
-- WHY ONE ROW PER (edition, sponsor_slug, bucket_day) WITH A COUNTER, NOT ONE ROW PER COPY EVENT:
-- this is written from a PUBLIC, UNAUTHENTICATED endpoint (see FestivalCouponCopyController /
-- FestivalCouponCopyService) -- exactly the same trust boundary as festival_enquiries
-- (V20260905130000), except this one has no honeypot and is fire-and-forget by design (the caller
-- must never be blocked or told anything useful). A row-per-event table under those two
-- constraints is an unbounded-growth attack surface: nothing stops a script from calling this
-- endpoint in a tight loop and growing the table forever, and there is no honeypot/cross-field
-- check here to slow it down the way festival_enquiries has. A daily bucket keyed on
-- (edition, sponsor_slug, bucket_day) is bounded instead -- at most one row added per known
-- edition per known sponsor per day -- while still giving the Day-20 report the exact time series
-- it needs (copies per sponsor per day). See FestivalCouponCopyService for how BOTH key components
-- are enforced app-side: an unrecognized sponsor_slug OR an unrecognized edition is silently
-- dropped (204, no row), specifically so this bound holds even under abuse with garbage values.
--
-- [Kabir H-1] This paragraph used to claim the table was "bounded by (recognized sponsors x days),
-- not by request volume" while the very next sentence admitted edition was validated by charset and
-- length only. Both cannot be true: edition is part of the unique key, so an attacker-chosen
-- edition string meant one new row per request over a 36^64 keyspace, and the bound this whole
-- table shape exists to provide did not hold. Editions are now checked against
-- com.influora.domain.FestivalEditions, and the bound above is real.
--
-- STORES NOTHING ABOUT THE VISITOR. No IP, no IP hash, no user agent, no session id -- nothing
-- that could re-identify who tapped copy. THIS TABLE MUST NEVER GROW A visitor-identifying COLUMN.
-- That invariant is unchanged and is the one that matters.
--
-- [Kabir M-1/M-2] The paragraph here used to continue: "this table's ONLY defenses are the daily
-- bucket above and the edge rate limit ... the daily bucket already caps how much damage a flood
-- can do (it can only inflate copy_count on rows that already exist for a real sponsor+day, never
-- grow the table itself)." The parenthetical is true and the conclusion drawn from it was wrong.
-- The daily bucket bounds ROW growth. It does not bound the COUNTER, and the counter is the
-- product -- it is the demand signal Influora reports to a paying sponsor. copy_count was an
-- unauthenticated `+ 1` behind a 60-second edge bucket that resets forever, so any caller could
-- drive any sponsor's number arbitrarily high (M-2), and because sponsorSlug is caller-chosen and
-- every slug is printed on the public page, a brand could do it to a COMPETITOR's number (M-1).
--
-- There is now a third defense: a per-origin, per-sponsor, per-day influence cap in
-- FestivalCouponCopyService#recordCopy. It does NOT contradict the invariant above -- the salted
-- IP hash lives in abuse_throttle_counters (the same shared throttle table festival_enquiries
-- already uses), never in a column here.
--
-- It DOES deviate from this header's old instruction that such a counter "belongs in the rate
-- limiter's own in-memory state, not here", and deliberately: AuthRateLimitFilter is documented as
-- per-instance and explicitly not global under horizontal scaling, so an in-memory cap would be
-- multiplied by the instance count and would reset on every deploy. A bound on how far someone can
-- move a number sold to a customer has to survive both.
--
-- What is still NOT true, and must not be claimed: that copy_count is trustworthy. It is a count
-- of taps self-reported by a browser on a public page. The cap makes forgery expensive, not
-- impossible -- a distributed caller still moves it. See CouponCopyMetricsResponse on the read
-- side, which carries that caveat to whoever renders the number.
--
-- coupon_code is NOT part of the unique key. A sponsor can have more than one coupon code (e.g.
-- rotated mid-edition), and the bucket is intentionally per SPONSOR per DAY, not per code -- so
-- the column simply reflects the most recently copied code for that sponsor/day (the upsert sets
-- it on every hit); it is informational only and is never used to compute copy_count.
--
-- ATOMIC INCREMENT, NOT read-then-write: FestivalCouponCopyRepository#recordCopy is a single
-- native `INSERT ... ON DUPLICATE KEY UPDATE copy_count = copy_count + 1` -- see that method's
-- javadoc for why this table deliberately does NOT reuse FestivalSponsorProvisioningService's
-- PESSIMISTIC_WRITE row-lock pattern (a counter has no read-modify-write step to protect against;
-- the DB does the arithmetic in the same statement that finds the row, so there is no window for a
-- concurrent copy to be lost).
--
-- DIALECT: MySQL 8.0 (application.yml -> jdbc:mysql, MySQLDialect). VARCHAR(26) ULID id +
-- ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci, matching the newest V2026*
-- CREATE TABLE migrations (V20260905130000__festival_enquiries.sql).
CREATE TABLE festival_coupon_copies (
    id                  VARCHAR(26)   NOT NULL,
    edition             VARCHAR(64)   NOT NULL,
    sponsor_slug        VARCHAR(80)   NOT NULL,
    coupon_code         VARCHAR(50)   NOT NULL,
    bucket_day          DATE          NOT NULL,   -- NOT "day": reserved word in H2, which breaks every @DataJpaTest
    copy_count          BIGINT        NOT NULL DEFAULT 0,

    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    -- The whole design: one bucket per sponsor per edition per day. This is the constraint the
    -- upsert's ON DUPLICATE KEY UPDATE fires against.
    UNIQUE KEY uq_festival_coupon_copies_edition_sponsor_day (edition, sponsor_slug, bucket_day),
    -- Backs the admin metrics read (GET /admin/festival-metrics/copies?edition=): per-sponsor
    -- totals and the daily time series are both filtered on edition first, then ordered by day.
    INDEX idx_festival_coupon_copies_edition_day (edition, bucket_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
