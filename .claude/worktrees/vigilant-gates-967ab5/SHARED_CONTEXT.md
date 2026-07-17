## ACTIVE TASK

**VIKRAM → PRIYA | Phase 2: Domain C (AI/Meera data layer + read-only chat) | DONE | NEXT: Kavya QA, then Phase 4 wires real executors once Domain A lands**

Files (grouped, all under `influora-api/src/main/`):
- Migrations: `resources/db/migration/V11__brand_profiles.sql`, `V12__ai_conversations_messages.sql`, `V13__campaign_intents.sql`, `V14__ai_credits_tool_calls.sql`
- Enums: `java/com/influora/domain/enums/{AnalysisStatus,ConversationStatus,MessageRole,CampaignIntentType,IntentStatus,MeeraToolName,ToolCallStatus,ToolResultRefType}.java`
- Entities: `java/com/influora/domain/entity/{BrandProfile,AiConversation,AiMessage,CampaignIntent,BrandAiCredit,MeeraToolCall}.java`
- Repositories: `java/com/influora/repository/{BrandProfileRepository,AiConversationRepository,AiMessageRepository,CampaignIntentRepository,BrandAiCreditRepository,MeeraToolCallRepository}.java`
- Services: `java/com/influora/service/meera/{MeeraSessionService,BrandContextAssembler,AICreditService,StreamTokenService}.java`
- Controllers: `java/com/influora/web/{MeeraController,MeeraInternalController}.java`
- DTOs: `java/com/influora/web/dto/meera/MeeraDtos.java`
- Config: `java/com/influora/config/MeeraStreamProperties.java` (+ registered in `InfluoraApiApplication.java`, `resources/application.yml`)
- Docs: `wiki/processes/schema-changes.md`, `wiki/processes/api-docs.md`

**Tenant scoping:** every repo finder takes `workspaceId` (or is 1:1 keyed on it) — confirmed on all 6 repos. No unscoped `findAll`-style leak.

**PII exclusion (Guardrail 3):** `BrandContextAssembler` allow-lists only `workspaceId`, `brandName`, `industry`, `websiteUrl` + `BrandProfile`'s catalog/aesthetic/tone/niche/competitor JSON. Explicitly excludes `Workspace.billingEmail/gstin/pan/kycGstinDocUrl/kycPanDocUrl` and all `User` PII (email/phone/passwordHash) — none of those fields are read by the assembler at all.

**Known gap:** `MeeraSessionService.sendTurn` persists a placeholder ASSISTANT echo — no real Claude/Gemini call. That's Domain D (Python service), separate task. Documented in code javadoc + `wiki/processes/api-docs.md`.

**Scope discipline confirmed:** did not touch `service/{Wallet,Escrow,Contract,Payout}Service.java`, `domain/entity/{Wallet,EscrowHold,Contract,PaymentMilestone}.java`, `web/dto/money/`, or `integration/razorpay/` — all being built concurrently by the money-core track (V8–V10 already present, zero collisions observed).

**No `mvn` available in this environment to compile-verify** — Meera should run `mvn -f influora-api compile` as the local verification step.

[Tara] Wrote Phase 1+2 run report: docs/AI connect/backend/13-TARA-PHASE1-2-RUN-REPORT.md — build GREEN, 143 files, ready for Priya sign-off: YES.

---

## ACTIVE TASK

**SWAPNIL | Remaining-work packets (16/17/18/19) + Rohan's cost review (20) — SIGNED OFF | NEXT: Vikram starts Domain D with the Gemini re-pin as line 1; eval harness built alongside per doc 19 R-1**

Verdict: `docs/AI connect/backend/21-SWAPNIL-SIGNOFF.md`. Prior thread archived: `wiki/decisions/2026-07-05-remaining-work-signoff.md`.
