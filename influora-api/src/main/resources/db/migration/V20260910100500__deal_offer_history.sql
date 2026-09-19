-- T-MEERA-CREATOR-PHASE-B / B0-09 (SPEC.md 2.6) -- append-only negotiation ledger: who offered
-- what, in what order, and whether Meera drafted it. Collaboration itself cannot answer this --
-- agreed_rate is OVERWRITTEN on every counter, so after three rounds the first two offers are
-- gone. This is the same gap application_history_events (V69) fills for the application journey.
--
-- meera_drafted IS THE WHOLE POINT OF THE TABLE FOR B0. Gate metric "meeraAnchoredShare"
-- (SPEC.md 14.1.d) is the share of negotiations where a Meera-drafted counter appeared, and it is
-- the number the Phase-B0 -> B1 decision turns on. Without a per-event authorship stamp captured
-- at write time it is not recoverable later.
--
-- UNIQUE KEY uk_doh_collab_seq (collaboration_id, sequence_no) -- NOT the plain INDEX SPEC.md
-- 2.6's DDL block shows, and NOT both. SPEC.md 13.2 risk 6 / B0-09 rule on this. The uniqueness is
-- the enforcement half of the derivation rule below: sequence_no is derived as
--     countByCollaborationId(collaborationId) + 1
-- INSIDE the transaction that already holds the collaboration row lock (DealService's existing
-- @Transactional write paths -- createProposal, doCounter, doAccept, doReject -- all take that lock
-- before reaching the recordOffer helper). Never a separate `SELECT max(sequence_no)` read, which
-- would be a TOCTOU exactly like the one V20260905190000__abuse_throttle_counters.sql documents.
-- The row lock makes the derivation correct; this unique key makes a mistake in it LOUD -- a second
-- writer that somehow slips outside the lock gets a duplicate-key failure instead of silently
-- writing a second row with the same sequence number and corrupting the ordering forever. That is
-- PRIYA-COMPAT-0904 section 7 condition 3, and it is load-bearing, not defensive.
-- A plain INDEX would serve the same read query (findByCollaborationIdOrderBySequenceNoAsc) but
-- would enforce nothing, so keeping both would be redundant: the UNIQUE KEY is itself a usable
-- index for that ordered read.
--
-- THIS KEY IS (collaboration_id, sequence_no) AND NOT (collaboration_id, event). Several
-- MEERA_COUNTER rows on one collaboration are LEGAL and expected on any negotiation with more than
-- one Meera-drafted counter. It follows that a row COUNT over this table is not a collaboration
-- count: computing 14.1.d's share from one produces values above 1.0. The repository therefore
-- exposes findDistinctCollaborationIdsByEvent and deliberately NOT a
-- countByCollaborationIdInAndEvent (SPEC.md 2.6, PRIYA note, finding W4).
--
-- APPEND-ONLY: no updated_at, no setters on the entity. A negotiation event happened or it did
-- not; it is never revised. Same discipline as ApplicationHistoryEvent.
--
-- currency is VARCHAR(3) DEFAULT 'INR', matching creator_agent_preferences.floor_currency -- amount
-- without a currency is the decorative-column bug V20260903170000 was written to repair.
-- amount is NULLABLE because REJECT carries no number.
--
-- No CHAR(n) (SPEC.md 0.5) -- ddl-auto=validate rejects CHAR against @Column(length=N).
CREATE TABLE deal_offer_history (
    id                   VARCHAR(26)  NOT NULL,
    collaboration_id     VARCHAR(26)  NOT NULL,
    sequence_no          INT          NOT NULL,
    actor                VARCHAR(16)  NOT NULL,               -- BRAND | CREATOR | SYSTEM
    event                VARCHAR(24)  NOT NULL,               -- OFFER | COUNTER | MEERA_COUNTER | ACCEPT | REJECT
    amount               DECIMAL(12,2) NULL,
    currency             VARCHAR(3)   NOT NULL DEFAULT 'INR',
    meera_drafted        TINYINT(1)   NOT NULL DEFAULT 0,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_doh_collab FOREIGN KEY (collaboration_id) REFERENCES collaborations(id) ON DELETE CASCADE,
    UNIQUE KEY uk_doh_collab_seq (collaboration_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
