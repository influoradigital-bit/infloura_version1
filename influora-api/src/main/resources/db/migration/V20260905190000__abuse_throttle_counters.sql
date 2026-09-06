-- T-FESTIVALBOX-0905 phase 9 [Kabir F-1c / F-3] — a single shared atomic counter table backing
-- every hour-bucketed abuse throttle in the app that needs to survive concurrent requests, not
-- just a single-threaded happy path.
--
-- WHY THIS TABLE EXISTS: FestivalEnquiryService#enforceThrottle (V20260905130000) was
-- `SELECT COUNT(*) ... ; SELECT COUNT(*) ...` followed by `save()`, all inside one
-- @Transactional method at Spring's default isolation (REPEATABLE READ on MySQL/InnoDB). Under
-- concurrency that is a classic TOCTOU: 100 parallel requests each run their COUNT against a
-- snapshot taken before any of the 100 inserts commit, every one reads "0 so far", and all 100
-- pass the cap. This table replaces the read-then-decide shape with one atomic statement per
-- attempt: `INSERT ... ON DUPLICATE KEY UPDATE request_count = request_count + 1` is executed and
-- resolved by MySQL as a single, row-locked operation, so a second concurrent caller for the SAME
-- key genuinely waits behind the first one's row lock instead of reading a stale count. See
-- AbuseThrottleCounterRepository#increment and AbuseThrottleService for the read-your-own-write
-- follow-up SELECT that reports the post-increment count back to the caller.
--
-- WHY NOT FestivalSponsorProvisioningService's PESSIMISTIC_WRITE row lock: that lock exists to
-- protect a multi-step read-modify-write (read provisionedWorkspaceId, decide, then insert several
-- related rows) against a second caller racing between the read and the decision. A throttle
-- counter has no such decision step — "add one, then look at the total" is exactly what a single
-- SQL statement plus a same-transaction read already gives you atomically, with no lock held
-- across a round trip to application code. Same reasoning FestivalCouponCopyRepository#recordCopy
-- (V20260905170000) already documents for the identical shape.
--
-- ONE TABLE, MANY CALLERS: `throttle_key` is a free-form, caller-namespaced string (e.g.
-- "festival-ip:<sha256>", "festival-email:<canonical-address>", "portfolio-contact:<creatorId>")
-- rather than a foreign key to any one domain table, specifically so FestivalEnquiryService's
-- IP/email caps and PortfolioService#contact's per-creator-recipient cap (phase 9, Kabir F-3) can
-- share one mechanism instead of each service inventing its own counter table. A caller that picks
-- a colliding key by mistake shares a budget with an unrelated caller — namespacing the key string
-- (a fixed literal prefix per call site) is the caller's responsibility, not something the schema
-- can enforce.
--
-- FIXED HOUR BUCKETS, NOT A SLIDING WINDOW: `window_start` is the current window truncated down to
-- a fixed boundary (e.g. the top of the clock hour for an hourly cap), not "now minus one hour".
-- This is a deliberate behaviour change from the sliding-window COUNT query it replaces: a fixed
-- window can admit a short-lived double burst across a boundary (e.g. 6 requests at 12:59 and 6
-- more at 13:01), which a true sliding window would have refused. That trade is what makes the cap
-- atomically checkable in one upsert instead of a range scan, and matches the fixed-bucket shape
-- festival_coupon_copies already uses (per sponsor per DAY there; per throttle-key per HOUR here).
-- A row is naturally bounded in number: at most one per (throttle_key, window) that ever saw
-- traffic, so old rows accumulate but never multiply per-request the way a row-per-attempt table
-- would; a scheduled cleanup of rows older than a few windows is a reasonable future addition and
-- is NOT implemented here (out of scope for this fix).
--
-- STORES NO REQUEST PAYLOAD: only the key, the bucket, and a count. A refused attempt still
-- increments this row (see AbuseThrottleService javadoc) — that is intentional: an attempt that
-- reached the throttle check already cost the caller a request, and letting a refused attempt be
-- "free" is exactly the pre-fix bug (reviewer note: rejected festival_enquiries submissions never
-- reached the old COUNT-based check because it read the festival_enquiries table itself, so a
-- request that failed honeypot/validation was already excluded upstream of the throttle -- what
-- changes here is only that a request which DOES reach the cap check and is REFUSED by it now
-- still consumes budget, instead of the pre-fix world where a refused save() also never
-- incremented anything).
--
-- DIALECT: MySQL 8.0 (application.yml -> jdbc:mysql, MySQLDialect). VARCHAR(26) ULID id,
-- ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci, matching the newest V2026*
-- CREATE TABLE migrations (V20260905170000__festival_coupon_copies.sql).
CREATE TABLE abuse_throttle_counters (
    id                  VARCHAR(26)   NOT NULL,
    throttle_key        VARCHAR(255)  NOT NULL,
    window_start        TIMESTAMP     NOT NULL,
    request_count       BIGINT        NOT NULL DEFAULT 0,

    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    -- The whole design: one bucket per throttle key per fixed window. This is the constraint the
    -- upsert's ON DUPLICATE KEY UPDATE fires against, and it is what makes the increment atomic --
    -- MySQL resolves "does a row already exist for this key" and "increment it" as one operation
    -- against this index, with no gap for a concurrent caller to read a stale count in between.
    UNIQUE KEY uq_abuse_throttle_counters_key_window (throttle_key, window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
