# Database Schema Change Log

> Owner: Vikram (Backend). Every Flyway migration gets one entry here at the time it's authored.

---

## V11 — `brand_profiles`
- **Date:** 2026-07-05 · **Author:** Vikram · **Milestone:** M2.5 (Phase 2 — Domain C, no money)
- **File:** `influora-api/src/main/resources/db/migration/V11__brand_profiles.sql`
- **Adds:** `brand_profiles` (1:1 per workspace) — website analyzer output (product catalog, brand
  aesthetic, tone profile, niche tags, competitor URLs), `analysis_status` enum, `analysis_error`
  for the paste-a-link fallback.
- **FKs:** `workspace_id → workspaces(id)`.
- **Notes:** No money dependency. Entity: `BrandProfile.java`. Repo: `BrandProfileRepository`
  (`findByWorkspaceId`, tenant-scoped).

## V12 — `ai_conversations` + `ai_messages`
- **Date:** 2026-07-05 · **Author:** Vikram · **Milestone:** M2.5
- **File:** `influora-api/src/main/resources/db/migration/V12__ai_conversations_messages.sql`
- **Adds:** `ai_conversations` (session per workspace, `status` enum, at-most-one-ACTIVE enforced in
  service layer, not a DB partial unique — MySQL can't do those), `ai_messages` (turn log with
  `role`, `metadata` JSON for `prompt_version`/tool_use/token_usage, `credits_charged`).
- **FKs:** `ai_conversations.workspace_id → workspaces(id)`, `.started_by → users(id)`;
  `ai_messages.conversation_id → ai_conversations(id)`.
- **Notes:** Entities: `AiConversation.java`, `AiMessage.java`. Repos tenant-scoped
  (`findByWorkspaceIdAndStatus`, `findByIdAndWorkspaceId`, `findByConversationIdOrderByCreatedAtAsc`).

## V13 — `campaign_intents`
- **Date:** 2026-07-05 · **Author:** Vikram · **Milestone:** M2.5
- **File:** `influora-api/src/main/resources/db/migration/V13__campaign_intents.sql`
- **Adds:** `campaign_intents` — the conversation→campaign bridge. `proposed_budget` is AI-supplied
  and explicitly NOT authoritative; `campaign_id` is stamped only by the server-side confirm flow.
- **FKs:** `conversation_id → ai_conversations(id)`, `workspace_id → workspaces(id)`,
  `campaign_id → campaigns(id)` (nullable until confirmed).
- **Notes:** Entity: `CampaignIntent.java`. Repo tenant-scoped (`findByConversationIdAndWorkspaceId`,
  `findByIdAndWorkspaceId`).

## V14 — `brand_ai_credits` + `meera_tool_calls`
- **Date:** 2026-07-05 · **Author:** Vikram · **Milestone:** M2.5
- **File:** `influora-api/src/main/resources/db/migration/V14__ai_credits_tool_calls.sql`
- **Adds:** `brand_ai_credits` (1:1, PK = workspace_id, the credit-meter circuit-breaker) and
  `meera_tool_calls` (idempotency + audit ledger for future `/internal/meera/*` executors —
  `idempotency_key UNIQUE`, schema-only in this phase, no executor writes it yet).
- **FKs:** both `→ workspaces(id)`; `meera_tool_calls.conversation_id → ai_conversations(id)`.
- **Notes:** Entities: `BrandAiCredit.java`, `MeeraToolCall.java`. `BrandAiCreditRepository` has an
  atomic `@Modifying` decrement (`tryDecrement`, `WHERE credits_remaining >= cost`) backing
  `AICreditService.tryConsume` (Guardrail 5 circuit-breaker). `MeeraToolCallRepository` exposes
  `findByIdempotencyKey` for the Phase 4 executors — not called by anything yet.

---

**Sequencing note:** V8–V10 (wallet ledger, escrow, contracts/milestones) are the parallel
money-core track (Domain A) — NOT touched by this log or by Vikram's Phase 2 work. V11–V14 have
zero money-table dependency and were built to run alongside Domain A per
`docs/AI connect/backend/01-DATA-MODEL.md` §1.

## V64 — `collaborations.usage_rights`
- **Date:** 2026-07-14 · **Author:** Vikram · **Task:** A7-U1 (silent data-drop fix)
- **File:** `influora-api/src/main/resources/db/migration/V64__collaboration_usage_rights.sql`
- **Adds:** `usage_rights TEXT NULL` on `collaborations` — the raw usage-rights string submitted on
  `POST /deals` (`DealDtos.CreateDealRequest.usageRights`) was accepted by the DTO but never
  persisted anywhere. Minimum-viable fix: store the raw submitted value; not a structured
  rights model.
- **FKs:** none (existing table).
- **Notes:** Entity: `Collaboration.java` (`getUsageRights()`/`setUsageRights()`). Written from
  `DealService.createProposal` only, sanitized via `TextSanitizer.sanitizePlainText`.

## V20260714150000 — `campaign_templates`
- **Date:** 2026-07-14 · **Author:** Vikram · **Task:** B5 (Campaign Templates backend)
- **File:** `influora-api/src/main/resources/db/migration/V20260714150000__campaign_templates.sql`
- **Adds:** `campaign_templates` — mirrors `campaigns`' writable field set (no dates/status) plus
  `name`/`description`/`category` (AWARENESS/SALES/UGC/AFFILIATE/CUSTOM)/`scope`
  (SYSTEM/CUSTOM)/`workspace_id` (NULL for SYSTEM)/`created_by`. Seeds 4 SYSTEM rows (fixed ULIDs,
  idempotent) — Awareness/Sales/UGC/Affiliate presets.
- **FKs:** `workspace_id → workspaces(id)` (nullable).
- **Notes:** Entity: `CampaignTemplate.java`. Repo: `CampaignTemplateRepository`
  (`findByScope`, tenant-scoped `findByScopeAndWorkspaceId`/`findByIdAndWorkspaceId` for CUSTOM
  rows). Timestamp-named migration (sequential V63 was the latest `Vxx`, timestamp convention
  avoids any collision — see the V202607131...-prefixed migrations already in this directory).
  Save-as-CUSTOM is Pro-gated live via `@RequiresPlan(PlanFeature.CAMPAIGN_TEMPLATES)` on
  `CampaignTemplateController.saveAsTemplate` (the plan-gate filter already exists in this
  codebase, not a TODO stub).
