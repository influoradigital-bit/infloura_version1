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

## D14 marketplace invoicing — 6 migrations, 2026-07-15, Author: Vikram
Per `INVOICING-GST-SPEC-D14-2026-07-15.md` (decision log §9, all D14-A…E DECIDED 2026-07-15).
Timestamp-format versions per `wiki/tech/adr-flyway-migration-versioning.md` (last sequential `V`
was V64 at authoring time).

### V20260715120000 — `creator_profiles` tax identity
- **File:** `V20260715120000__creator_tax_identity.sql`
- **Adds:** `gstin VARCHAR(15) NULL`, `pan VARCHAR(10) NULL`, `tax_registration_status VARCHAR(20)
  DEFAULT 'UNREGISTERED'`, `creator_invoice_code VARCHAR(12) NULL` (unique when set — anchors the
  creator's own statutory numbering series). Nullable per D14-B (schema captures the fields;
  enforcement is a runtime toggle, not a schema-blocking constraint).
- **Notes:** Entity: `CreatorProfile.java` (`applyTaxIdentity`). Enum: `CreatorTaxRegistrationStatus`.

### V20260715130000 — `campaign_service_invoices` (Doc#2, Creator → Brand)
- **File:** `V20260715130000__campaign_service_invoice.sql`
- **Adds:** one row per released escrow hold — `invoice_number` (unique, per-creator statutory
  series), `gross_amount DECIMAL(14,2)` (deliberately NOT `Invoice.java`'s int-paise), `creator_gstin`
  snapshot, `tcs_amount` (report-only v1), `hsn_sac_code`, `status`.
- **FKs:** `collaboration_id → collaborations(id)`, `campaign_id → campaigns(id)`,
  `escrow_hold_id → escrow_holds(id)` (UNIQUE — also the release-time idempotency gate),
  `creator_user_id → users(id)`, `brand_workspace_id → workspaces(id)`.
- **Notes:** Entity: `CampaignServiceInvoice.java`. Repo: `CampaignServiceInvoiceRepository`. Created
  inside `EscrowService.release` / `adminReleaseForDispute` / `adminSplitForDispute` via
  `CampaignServiceInvoiceService.createAtRelease`, gated on the ledger posting having succeeded.

### V20260715140000 — `platform_commission_invoices` (Doc#3, split brand/creator legs)
- **File:** `V20260715140000__platform_commission_invoice.sql`
- **Adds:** `leg` (BRAND/CREATOR), `commission_amount`/`gst_amount DECIMAL(14,2)`, `ledger_txn_id`
  traceability, `hsn_sac_code`. CHECK constraint enforces leg-shape (BRAND has workspace+no hold,
  CREATOR has user+hold). A generated column (`brand_leg_campaign_marker`, same trick as V62's
  `primary_marker`) gives exactly-one-BRAND-leg-per-campaign without also constraining the
  many-CREATOR-legs-per-campaign case.
- **FKs:** `campaign_id → campaigns(id)`, `escrow_hold_id → escrow_holds(id)` (nullable, BRAND leg),
  `counterparty_workspace_id → workspaces(id)`, `counterparty_user_id → users(id)`.
- **Notes:** Entity: `PlatformCommissionInvoice.java`. Repo: `PlatformCommissionInvoiceRepository`.
  3a created in `BrandCampaignFeeService.chargeOnPublish`, 3b in `PlatformFeeService.deductAtRelease`,
  both via `CommissionInvoiceService`, both gated on the `PLATFORM_FEE` posting having succeeded.
- **⚠️ MySQL note:** the `CHECK` constraint requires MySQL 8.0.16+ to actually be enforced (silently
  parsed-but-ignored on older MySQL 8.0.x) — Meera to confirm the deployed MySQL version.

### V20260715150000 — `invoice_number_sequences` (D14-C statutory numbering)
- **File:** `V20260715150000__invoice_number_sequences.sql`
- **Adds:** one row per `(series_type, fiscal_year, creator_invoice_code)` — `next_seq`, row-locked
  and incremented by `InvoiceNumberService.generateNext`. Seeds the 3 platform-wide series for
  FY2026-27; per-creator `CAMPAIGN_SERVICE` rows are created on-demand at first issuance.
- **Notes:** Entity: `InvoiceNumberSequence.java`. Repo: `InvoiceNumberSequenceRepository`
  (`findForUpdateGlobal`/`findForUpdatePerCreator`, `PESSIMISTIC_WRITE`).

### V20260715160000 — `hsn_sac_codes` (Rohan build-flag #4, configurable lookup)
- **File:** `V20260715160000__hsn_sac_codes.sql`
- **Adds:** `code`/`applies_to` (unique) rows, seeded: SAC 998397 (CREATOR_SERVICE), SAC 998599
  (PLATFORM_COMMISSION and, as a placeholder pending CA confirmation, SUBSCRIPTION).
- **Notes:** Entity: `HsnSacCode.java`. Repo: `HsnSacCodeRepository`. Service: `HsnSacCodeService`
  (`@Cacheable("hsnSacCodes")`, backed by the existing `RedisCacheConfig` `@EnableCaching`).

### V20260715170000 — `invoices` GST retrofit (Doc#1, Rohan build-flag #5 live gap)
- **File:** `V20260715170000__subscription_invoice_gst.sql`
- **Adds:** `invoice_number` (nullable, NOT backfilled — a real statutory number is immutable once
  issued, so historical rows stay null rather than being retroactively numbered),
  `base_amount`/`cgst_amount`/`sgst_amount`/`igst_amount DECIMAL(14,2)` (backfilled from `amount`,
  IGST-default since the historical brand/Influora GSTIN state comparison can't be reconstructed in
  a migration — CA to confirm), `hsn_sac_code`.
- **Notes:** Entity: `Invoice.java` (`applyGstBreakup`) — `amount` stays `int` paise unchanged (a
  full unit migration to `DECIMAL(14,2)` was assessed as a separate, higher-risk change and is
  explicitly OUT of this ticket's scope; the new GST fields are rupees, computed FROM `amount`).
  Populated going forward in `InvoiceService.generateInvoiceFromWebhook` (best-effort — a
  numbering/lookup failure logs loudly but never blocks recording the already-paid charge).

### V20260715180000 — `payouts.milestone_id` nullable (B10, integration-consolidate money-path port)
- **File:** `V20260715180000__payout_milestone_nullable.sql`
- **Change:** `ALTER TABLE payouts MODIFY COLUMN milestone_id VARCHAR(26) NULL` (was `NOT NULL`).
- **Why:** `WalletService.requestCreatorWithdrawal` now persists a `Payout` row for a lump-sum
  creator wallet withdrawal that is not tied to any single `payment_milestones` row (unlike
  `PayoutService.queuePayout`'s milestone-linked payouts, which always set this). The existing
  `fk_payout_milestone` FK tolerates NULL values in MySQL; only the NOT NULL constraint needed
  lifting.
- **Notes:** Entity: `Payout.java` (`milestoneId` column javadoc updated). Ported from
  `influora-prod-readiness-audit-bc5269`'s Wave-2 B10/C-5/M-6 fix as part of the money-path
  consolidation pass (B2/B6/B7/B10/B11).

### V20260715190000 — `creator_profiles` identity KYC (N1, Wave 6 creator onboarding)
- **File:** `V20260715190000__creator_identity_kyc.sql`
- **Change:** `ALTER TABLE creator_profiles ADD COLUMN identity_kyc_status VARCHAR(20) NOT NULL
  DEFAULT 'UNVERIFIED', ADD COLUMN aadhaar_last4 VARCHAR(4) NULL, ADD COLUMN selfie_url VARCHAR(500)
  NULL`.
- **Why:** `POST /onboarding/creator/kyc` (new `CreatorOnboardingController`/
  `CreatorOnboardingService`) needed somewhere to persist the personal-identity KYC step
  (PAN + last-4 Aadhaar + selfie), deferred to first withdrawal per creator-onboarding.tsx. This is
  deliberately distinct from the D14 `tax_registration_status`/`gstin` columns (business GST
  registration) — `pan` itself is NOT duplicated, the D14 column
  (`V20260715120000__creator_tax_identity.sql`) is reused since it's the same real-world PAN
  number. Only full Aadhaar's last 4 digits are ever captured/stored (no full Aadhaar number
  anywhere in this schema).
- **Notes:** Entity: `CreatorProfile.java` (`applyIdentityKyc`, reuses `VerificationStatus`
  enum — UNVERIFIED/PENDING/VERIFIED/REJECTED — rather than introducing a new status enum).

### D6 — `file_uploads` mapped (no new migration; Wave 6 N2 generic uploads)
- **File:** `V1__file_uploads.sql` (pre-existing, unchanged) — no new migration added.
- **Change:** None to the schema. `file_uploads` was genuinely orphaned (migration existed, zero
  entity/repository/callers anywhere in the codebase) — per Priya's D6 decision, mapped it with a
  real `FileUpload` entity (`com.influora.domain.entity.FileUpload`) +
  `FileUploadRepository` and wired it as the metadata store backing the new `POST /uploads`
  (`UploadController`/`UploadService`), instead of inventing a second parallel upload-metadata
  table. Columns used as-is: `owner_id`/`owner_type` (mapped `USER`/`WORKSPACE` via new
  `FileOwnerType` enum — this endpoint always writes `USER`, `owner_id = principal.getUserId()`,
  since a generic multipart upload has no workspace context at upload time), `purpose` (fixed
  `"GENERIC"` for this endpoint — no purpose param on the client today), `r2_bucket`/`r2_key`/
  `mime_type`/`size_bytes`/`etag`/`public_url`, `status` (new `FileUploadStatus` enum, defaults
  `READY` on successful upload via `FileUpload.create`).
