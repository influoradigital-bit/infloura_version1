-- T-MEERA-CREATOR-PHASE-B / B0-09 (SPEC.md 2.4) -- every message Meera composes for a creator but
-- does not send. Level 0 is draft-only by definition, so this table IS the product at level 0: a
-- draft is written here, the creator reads it, and nothing leaves the platform until she approves.
--
-- FIVE NULLABLE PARENT IDS, NO FKs ON FOUR OF THEM. A draft can be about a deal thread
-- (collaboration_id), a pasted brief (brief_id), an open campaign application (campaign_id), or
-- none of those; conversation_id records which ai_conversations turn produced it. Only
-- creator_profile_id is mandatory and FK-enforced, because a draft always belongs to exactly one
-- creator and must die with her profile. The others are deliberately unconstrained: a draft is the
-- creator's own record of what Meera suggested, and it must not be cascade-deleted when a brand
-- cancels a campaign or a collaboration row is cleaned up. It is also why they are not composite:
-- kind, not the id set, is what tells a reader which parent matters.
--
-- ONLY THREE OF DraftStatus's FIVE VALUES ARE EVER WRITTEN. Creation writes PENDING, SPEC.md 3.7's
-- approve writes SENT, discard writes DISCARDED. Nothing in Phase B writes APPROVED or EDITED, and
-- nothing should be added that does -- see the `edited` note below for what replaced them. The
-- enum keeps all five because DraftStatus is persisted as a string and removing declared values
-- from a Java enum that a stored row could theoretically hold is a worse trade than carrying two
-- inert names.
--
-- WHY `edited` IS A COLUMN AND NOT DERIVED (SPEC.md 14.6 must-fix 1, findings W21/W22).
-- SPEC.md 3.7's approve computes `edited = !text.equals(draft.text)` as a LOCAL boolean and, before
-- this column existed, discarded it. Phase B0's gate metric 3 (14.5.c) has two thresholds --
-- ">= 50% of non-PENDING drafts SENT" and ">= 25% approved UNEDITED" -- and the second one had no
-- backing column anywhere in the schema, so a number Swapnil is being asked to sign off on could
-- not be computed at all. It cannot be reconstructed after the fact either: once the creator's
-- edited text is saved over the draft, the original Meera text is gone and "did she change it" is
-- unanswerable. Adding the column now is free (this migration is unapplied); adding it later costs
-- a second migration in the middle of the 14-day measurement window and leaves a hole in the data
-- for every draft sent before it landed.
-- DEFAULT 0 is correct rather than merely convenient: a draft that has not been approved yet has
-- not been edited, and a draft sent verbatim is exactly the 0 case metric 3 counts.
--
-- proposed_amount is DECIMAL(12,2), matching collaborations.agreed_rate and the three
-- creator_agent_preferences floors -- a COUNTER draft's number is compared against those directly,
-- and a float would make the below-floor guard (B0-46) wrong at the boundary.
--
-- deal_terms_json is a frozen DealTermsDto snapshot for the same reason creator_briefs' three
-- _json columns are frozen: the counter form is prefilled from what the creator approved, not from
-- what the model would generate today.
--
-- sent_message_id is REPLY-only and nullable (SPEC.md B0-47): counter and reject return the
-- collaboration, not a message row, so there is no id to record for those kinds.
--
-- The index is (creator_profile_id, created_at DESC) -- the list query is
-- findByCreatorProfileIdAndStatusOrderByCreatedAtDesc, i.e. "my pending drafts", newest first.
--
-- No CHAR(n) (SPEC.md 0.5) -- ddl-auto=validate rejects CHAR against @Column(length=N).
CREATE TABLE meera_drafts (
    id                   VARCHAR(26)  NOT NULL,
    creator_profile_id   VARCHAR(26)  NOT NULL,
    conversation_id      VARCHAR(26)  NULL,                   -- ai_conversations.id that produced it
    collaboration_id     VARCHAR(26)  NULL,                   -- deal thread target, if any
    brief_id             VARCHAR(26)  NULL,
    campaign_id          VARCHAR(26)  NULL,                   -- for application drafts (B7)
    kind                 VARCHAR(24)  NOT NULL,               -- REPLY | COUNTER | DECLINE | APPLICATION | ROUTINE
    intent               VARCHAR(32)  NULL,                   -- RoutineIntent name when kind = ROUTINE
    text                 TEXT         NOT NULL,
    proposed_amount      DECIMAL(12,2) NULL,
    deal_terms_json      TEXT         NULL,
    status               VARCHAR(16)  NOT NULL,               -- PENDING | APPROVED | EDITED | DISCARDED | SENT (only PENDING, SENT and DISCARDED are ever written -- see header)
    edited               TINYINT(1)   NOT NULL DEFAULT 0,     -- set by 3.7 approve: 1 when the creator changed the text before sending
    approved_at          TIMESTAMP    NULL,
    sent_message_id      VARCHAR(26)  NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_meera_drafts_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_meera_drafts_creator_created (creator_profile_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
