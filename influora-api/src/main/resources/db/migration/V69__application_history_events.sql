-- Persistent application-history event timeline. Append-only: no updated_at column and nothing
-- in the application layer ever issues an UPDATE or DELETE against this table (see
-- ApplicationHistoryEvent's javadoc — the entity exposes no setters).
--
-- application_id is always the backing collaborations.id (an application and, once accepted, the
-- deal room share the same row). deal_room_id stays NULL until APPLICATION_ACCEPTED; from there
-- on it carries the same id, so a reader can tell whether an event happened before or after the
-- deal room existed without cross-referencing collaborations.status.
--
-- event_type/event_status/actor_type deliberately mirror collaborations.status's ENUM values
-- rather than a free-text column, matching this schema's existing convention (see collaborations,
-- deal_messages). No UNIQUE constraint on (application_id, event_type): CAMPAIGN_APPLIED /
-- APPLICATION_ACCEPTED / APPLICATION_REJECTED / APPLICATION_WITHDRAWN legitimately recur across a
-- withdraw-then-reapply cycle (F-0225 revive) — only APPLICATION_VIEWED needs first-write-wins
-- semantics, and that rule is enforced in application code
-- (ApplicationHistoryService#recordViewIfAbsent), not the schema.
--
-- APPLICATION_WITHDRAWN is a deliberate deviation from the requirements doc's 13-value list — the
-- creator's own withdrawal path (DealService#doReject, role == CREATOR) is distinct from the
-- brand's decline (same method, role == BRAND, still APPLICATION_REJECTED). See
-- ApplicationHistoryEventType's javadoc for why recording both as APPLICATION_REJECTED was wrong
-- in the audit ledger, not just on screen.
--
-- sequence_no is the ordering tiebreak for created_at (TIMESTAMP(3) — millisecond precision).
-- CAMPAIGN_APPLIED and APPLICATION_RECEIVED are written back-to-back inside one method
-- (CreatorCampaignService#recordApplicationHistory), so a same-millisecond collision is a real,
-- not theoretical, case. `id ASC` is NOT a valid tiebreak here: ids come from
-- Ulids.newUlid() -> UlidCreator.getUlid(), the non-monotonic ULID variant, so two ids minted in
-- the same millisecond sort in no particular order. A real AUTO_INCREMENT column, guaranteed
-- monotonic by MySQL on every insert regardless of clock/PRNG, was chosen over switching to a
-- monotonic ULID factory: the latter needs a single shared generator instance to guarantee
-- ordering across concurrent inserts, which this codebase has no established pattern for wiring
-- as a singleton bean, while an AUTO_INCREMENT column is ordering-safe by construction with no
-- new shared state to get wrong.
CREATE TABLE application_history_events (
  id              VARCHAR(26) PRIMARY KEY,
  sequence_no     BIGINT NOT NULL AUTO_INCREMENT,
  campaign_id     VARCHAR(26) NOT NULL,
  application_id  VARCHAR(26) NOT NULL,
  deal_room_id    VARCHAR(26),
  -- Order follows the requirements doc's section 7 workflow (escrow before deliverables), which
  -- also matches the already-shipped stepper (deal-room-step-progress.tsx) — section 4's own
  -- event-type list orders these the other way round. See ApplicationHistoryEventType's javadoc.
  event_type      ENUM('CAMPAIGN_APPLIED','APPLICATION_RECEIVED','APPLICATION_VIEWED',
                  'APPLICATION_ACCEPTED','APPLICATION_REJECTED','APPLICATION_WITHDRAWN',
                  'DEAL_ROOM_ACTIVATED','CONTRACT_GENERATED','CONTRACT_SIGNED','FUND_ESCROW',
                  'DELIVERABLE_SUBMITTED','DELIVERABLE_APPROVED','DELIVER','PAY') NOT NULL,
  event_status    ENUM('INVITED','APPLIED','SHORTLISTED','IN_NEGOTIATION','TERMS_AGREED',
                  'CONTRACT_PENDING','CONTRACTED','IN_PROGRESS','REVIEW_PENDING',
                  'REVISION_REQUESTED','COMPLETED','CANCELLED','DISPUTED') NOT NULL,
  actor_type      ENUM('CREATOR','BRAND','SYSTEM') NOT NULL,
  actor_id        VARCHAR(26),
  description     TEXT NOT NULL,
  metadata        TEXT,
  target_route    VARCHAR(255),
  target_id       VARCHAR(26),
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_app_history_sequence (sequence_no),
  INDEX idx_app_history_application (application_id, created_at, sequence_no),
  INDEX idx_app_history_campaign (campaign_id),
  INDEX idx_app_history_deal_room (deal_room_id),
  INDEX idx_app_history_application_event_type (application_id, event_type),
  CONSTRAINT fk_app_history_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
  CONSTRAINT fk_app_history_application FOREIGN KEY (application_id) REFERENCES collaborations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
