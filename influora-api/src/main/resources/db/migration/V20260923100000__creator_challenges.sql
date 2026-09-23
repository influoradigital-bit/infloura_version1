-- Creator 7-day challenge v1 (CHALLENGE-SPEC.md, Swapnil 2026-09-23) -- a reason for a creator to
-- open Influora every day: today's task, a streak, and a number that moved, built only on their
-- real Instagram data (media_metrics via MediaMetricsRepository.findNewestSnapshotPerPostSince).
--
-- ONE ACTIVE CHALLENGE PER CREATOR, ENFORCED BY THE DATABASE ITSELF. active_key holds the
-- creator's OWN user id while the row is ACTIVE, and is set back to NULL the moment it stops being
-- ACTIVE (COMPLETED on read past day 6, or ENDED early). Because active_key is exactly the
-- creator_user_id (not a synthetic constant), the UNIQUE index only ever has to reject a SECOND row
-- for the SAME creator holding that SAME value -- a different creator's own user id is a different
-- value, so this never blocks two different creators from each having their own active challenge.
-- MySQL (like every SQL dialect) treats NULL as distinct from every other NULL under UNIQUE, so any
-- number of non-ACTIVE rows can coexist. This is the same "let the constraint do the work, don't
-- trust a check-then-insert in application code" reasoning as V70's viewed_dedup_key.
--
-- creator_profile_id (not creator_user_id) carries the FK, matching creator_briefs/CreatorMetric --
-- creator_user_id is stored alongside it (denormalized) purely so the daily email job and the GET
-- read path never need a creator_profiles join just to resolve the owner for auth/repository
-- lookups that are naturally keyed by user id elsewhere in this codebase.
CREATE TABLE creator_challenges (
    id                  VARCHAR(26)  NOT NULL,
    creator_user_id     VARCHAR(26)  NOT NULL,
    creator_profile_id  VARCHAR(26)  NOT NULL,
    started_on          DATE         NOT NULL,
    status              VARCHAR(12)  NOT NULL,            -- ACTIVE | COMPLETED | ENDED
    active_key          VARCHAR(64)  NULL,                 -- = creator_user_id while ACTIVE, else NULL
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at            TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_creator_challenges_active_key (active_key),
    CONSTRAINT fk_creator_challenges_profile FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_creator_challenges_creator_started (creator_user_id, started_on DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per planned day (7 rows per challenge, day_index 0-6). PK is the composite
-- (challenge_id, day_index) -- no synthetic id column, exactly as CHALLENGE-SPEC.md's Backend &sect;1
-- specifies -- since a day is only ever addressed through its owning challenge, never on its own.
--
-- window_label/window_from/window_to/window_source are NULL on a REST day (planned_type = REST):
-- there is no posting window to plan for a day the creator is not asked to post.
--
-- done_media_id/posted_type/matched_type/done_at are all NULL until the lazy tick-off on a GET
-- (CreatorChallengeService) finds a real post that lands on this day's IST calendar date. Any post
-- counts toward done_at (consistency is the point, CHALLENGE-SPEC.md &sect;3); matched_type records,
-- separately, whether its media_type matched planned_type -- REELS/VIDEO satisfies REEL,
-- CAROUSEL_ALBUM satisfies CAROUSEL, IMAGE satisfies POST (facts already verified, see spec).
-- done_media_id is a media_metrics.media_id, deliberately not FK'd: media_metrics is an
-- append-only poll snapshot table with no unique constraint on media_id alone (many rows per post),
-- so there is no single row an FK could target.
CREATE TABLE creator_challenge_days (
    challenge_id     VARCHAR(26)  NOT NULL,
    day_index        INT          NOT NULL,               -- 0-6 (INT, not TINYINT: the entity's int must pass ddl-auto=validate on MySQL)
    date             DATE         NOT NULL,
    planned_type     VARCHAR(12)  NOT NULL,                -- REEL | CAROUSEL | POST | REST
    window_label     VARCHAR(12)  NULL,                    -- e.g. "evening"
    window_from      TIME         NULL,
    window_to        TIME         NULL,
    window_source    VARCHAR(12)  NULL,                    -- your_posts | suggestion
    done_media_id    VARCHAR(50)  NULL,
    posted_type      VARCHAR(20)  NULL,                    -- the raw media_metrics.media_type actually posted
    matched_type     BOOLEAN      NULL,
    done_at          TIMESTAMP    NULL,
    PRIMARY KEY (challenge_id, day_index),
    CONSTRAINT fk_creator_challenge_days_challenge FOREIGN KEY (challenge_id) REFERENCES creator_challenges(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
