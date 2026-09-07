-- F-0751 — creator Meera conversations could not be persisted.
-- Ruling: wiki/decisions/2026-09-07-f0751-priya-ruling.md (Option B, amended).
--
-- V12__ai_conversations_messages.sql:11 constrained workspace_id to workspaces(id), written when
-- ai_conversations was brand-only. V74__meera_creator_conversations.sql later redesignated the
-- table as "the shared (BRAND + CREATOR) ai_conversations table" in its own header, and the
-- creator path stores a users.id in workspace_id (MeeraSessionService.java:197-198). Nobody
-- re-read V12:11, so every POST /api/v1/creator/meera/sessions died on the foreign key:
--
--   Cannot add or update a child row: a foreign key constraint fails
--   (`influora`.`ai_conversations`, CONSTRAINT `fk_conv_workspace`
--    FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`))
--
-- V74's decision is NOT reversed here. The defect is that a decision about an INDEX was allowed
-- to imply a decision about a FOREIGN KEY.
--
-- Why not make workspace_id nullable and add a creator_id (the relationally "correct" shape):
-- OnBehalfAuthResolver.resolveForWorkspace rejects a null bodyWorkspaceId outright
-- (OnBehalfAuthResolver.java:89-92) and MeeraInternalController.java:325 feeds it
-- conversation.getWorkspaceId(). Nulling that column for creator rows makes every creator
-- ASSISTANT write-back throw ON_BEHALF_WORKSPACE_MISMATCH — no message persisted, no
-- recordTurnForUser rollup, and the DPDP export V74 exists to serve permanently empty. Silent.
-- It would also drop creator rows out of uq_conv_active (V12:9) with nothing replacing the guard.

ALTER TABLE ai_conversations DROP FOREIGN KEY fk_conv_workspace;

ALTER TABLE ai_conversations
  ADD COLUMN tenant_type ENUM('WORKSPACE','CREATOR') NOT NULL DEFAULT 'WORKSPACE'
  COMMENT 'WORKSPACE -> workspace_id is a workspaces.id. CREATOR -> workspace_id is a users.id.';

-- Every row that exists before this migration is a BRAND conversation: creator rows have never
-- been insertable, which is the defect. Verified on production 2026-09-07 — 4 rows, all brand.
UPDATE ai_conversations SET tenant_type = 'WORKSPACE';

-- Remove the default now the backfill is done. A permanent DEFAULT is the same failure shape as
-- the one that caused F-0751: an unset field that quietly means something wrong.
--
-- BUT REMOVING THE DEFAULT IS NOT SUFFICIENT, and it is worth being precise about why, because the
-- obvious reading is wrong. Measured against real MySQL 8.0.40 under
-- sql_mode=STRICT_TRANS_TABLES with column_default=NULL (F-0754): an INSERT that omits this column
-- still SUCCEEDS and lands the FIRST enum value, 'WORKSPACE'. MySQL gives a NOT NULL ENUM an
-- implicit default of its first member, and strict mode does not object. So a creator row written
-- by code that forgot the discriminator would be silently labelled a BRAND row -- exactly the
-- outcome the removal was meant to prevent, in exactly the DPDP export/deletion path V74 serves.
-- The pair of CHECK constraints below is what actually closes it.
ALTER TABLE ai_conversations
  MODIFY COLUMN tenant_type ENUM('WORKSPACE','CREATOR') NOT NULL
  COMMENT 'WORKSPACE -> workspace_id is a workspaces.id. CREATOR -> workspace_id is a users.id.';

-- Recovers most of what dropping fk_conv_workspace costs. ai_conversations already carries a
-- second FK — fk_conv_user on started_by REFERENCES users(id) (V12:12) — and for a creator row
-- workspace_id and started_by are written with the SAME value by construction:
--   CreatorContextService.java:47-51   requireCreatorProfile resolves via findByUserId(principal)
--   CreatorMeeraController.java:160-162 passes profile.getUserId() and principal.getUserId()
--   MeeraSessionService.java:197-198    .workspaceId(creatorUserId).startedBy(userId)
-- So a CREATOR row's tenant key is transitively FK-checked against users(id) through started_by.
-- MySQL 8.0.16+ enforces CHECK rather than parsing and ignoring it; this box is 8.4 (verified).
ALTER TABLE ai_conversations
  ADD CONSTRAINT ck_conv_creator_tenant_is_starter
  CHECK (tenant_type <> 'CREATOR' OR workspace_id = started_by);

-- The inverse, and the constraint that makes the omitted-discriminator case fail loudly (F-0754).
-- For a BRAND row, workspace_id is a workspaces.id and started_by is a users.id: two disjoint ULID
-- spaces, so they are never equal by construction. For a CREATOR row they are always equal (see
-- above). Therefore "tenant_type says WORKSPACE but the two columns match" can only mean a creator
-- row that lost its discriminator to MySQL's implicit-first-enum-value default -- which is now
-- rejected at INSERT instead of silently persisting as a brand conversation.
ALTER TABLE ai_conversations
  ADD CONSTRAINT ck_conv_workspace_tenant_is_not_starter
  CHECK (tenant_type <> 'WORKSPACE' OR workspace_id <> started_by);
