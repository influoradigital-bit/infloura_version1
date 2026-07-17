-- Portfolio analytics events (Priya CTO ruling, 2026-07-18). One append-only table behind the
-- creator portfolio-analytics numbers, typed by event_type, instead of one table per metric.
--   VIEW               -> makes "Page views (30d)" real (was hardcoded 0 in PortfolioService#analytics)
--   MEDIA_KIT_DOWNLOAD -> makes the "media kit downloads" count real (also a hardcoded 0 before this)
--   LINK_CLICK         -> reserved for the custom-link click pass (still a stub today)
-- Unblocked by the SecurityConfig fix that made GET /portfolio/{username} publicly reachable — before
-- that no anonymous request ever reached the backend to be recorded.
--
-- Immutable rows (append-only, same discipline as audience_demographics / creator_scores — never
-- updated in place). creator_profile_id is the creator_profiles.id (NOT users.id): the service
-- records with profile.getId() and reads back with the same key, so the two agree. (Contrast the
-- collaboration counts in the same service, keyed by users.id — the two id spaces are deliberately
-- not interchangeable; see the CollaborationRepository javadoc.)
--
-- event_type is a VARCHAR discriminator (not a DB ENUM) so a new event kind is an app-layer change,
-- never a schema migration — matches how platform/data_source are stored elsewhere. visitor_hash is
-- a nullable salted hash of (ip + user-agent) reserved for a later unique-visitor/dedup pass; v1
-- counts raw events. Recording NEVER blocks page serving: the write runs in its own transaction off
-- the read path and any failure is swallowed at the controller.
--
-- idx (creator_profile_id, event_type, occurred_at) powers all the analytics counts: per-type
-- all-time totals and the trailing-window / preceding-window range counts used for deltaPercent.

CREATE TABLE portfolio_events (
  id                  VARCHAR(26) PRIMARY KEY,
  creator_profile_id  VARCHAR(26) NOT NULL,
  event_type          VARCHAR(24) NOT NULL,
  occurred_at         DATETIME(6) NOT NULL,
  visitor_hash        VARCHAR(64) NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_portfolio_events_profile_type_time (creator_profile_id, event_type, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
