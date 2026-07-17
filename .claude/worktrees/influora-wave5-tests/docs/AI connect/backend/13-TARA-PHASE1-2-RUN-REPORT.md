# 13 — Tara Run Report: Phase 1 (Money Core Remainder) + Phase 2 (AI/Meera Data Layer) Parallel Build

**Date:** 2026-07-05
**Scope:** Phase 1 — escrow holds, contracts/milestones, Razorpay integration, wallet/escrow/contract/payout services and controllers (Domain A remainder). Phase 2 — brand profiles, AI conversations/messages, campaign intents, AI credits/tool-call ledger, Meera read-only chat session layer (Domain C non-executor parts).
**Scoped by:** Priya (CTO), per `10-VIKRAM-FILE-MANIFEST.md`, run in parallel as two deliberately non-overlapping file tracks, gate pattern per the V8 precedent in `12-TARA-V8-RUN-REPORT.md`.
**Reporter:** Tara (read-only; no code touched in producing this report; all claims below cross-checked against source on disk under `influora-api/src/main/`)

---

## What Shipped

### Phase 1 — Money Core (escrow, contracts, Razorpay)

| File | Path | Verified |
|---|---|---|
| Migration | `src/main/resources/db/migration/V9__escrow_holds.sql` | present |
| Migration | `src/main/resources/db/migration/V10__contracts_and_milestones.sql` | present |
| Entity | `domain/entity/EscrowHold.java` | present |
| Entity | `domain/entity/Contract.java` | present |
| Entity | `domain/entity/PaymentMilestone.java` | present |
| Enum | `domain/enums/EscrowStatus.java` | present |
| Enum | `domain/enums/ContractStatus.java` | present |
| Enum | `domain/enums/MilestoneStatus.java` | present |
| Repository | `repository/EscrowHoldRepository.java` | present |
| Repository | `repository/ContractRepository.java` | present |
| Repository | `repository/PaymentMilestoneRepository.java` | present |
| Service | `service/WalletService.java`, `service/PlatformWalletService.java`, `service/EscrowService.java`, `service/ContractService.java`, `service/PayoutService.java` | present |
| Controller | `web/WalletController.java`, `web/EscrowController.java`, `web/ContractController.java` | present |
| Razorpay integration | `integration/razorpay/RazorpayClient.java`, `RazorpayXClient.java`, `RazorpayWebhookController.java`, `WebhookSignatureVerifier.java`, `RazorpayIntegrationException.java` | present |
| Config | `config/RazorpayProperties.java` | present |
| DTOs | `web/dto/money/MoneyDtos.java` | present |

Plumbing added to existing files:
- `repository/WalletRepository.java` — `findByOwnerId` added.
- `domain/entity/Wallet.java` — `forUser(...)` factory added.
- `config/SecurityConfig.java` — `permitAll` for `/webhooks/razorpay`.

Razorpay is hand-rolled on `java.net.http.HttpClient` — the `com.razorpay:razorpay-java` SDK is not yet an approved `pom.xml` dependency, so this is not yet typed/tested against the live Razorpay API. That sign-off is explicitly deferred to Priya.

### Phase 2 — AI/Meera Data Layer + Read-Only Chat

| File | Path | Verified |
|---|---|---|
| Migrations | `V11__brand_profiles.sql` → `V14__ai_credits_tool_calls.sql` | all 4 present, no gaps |
| Entities | `domain/entity/{BrandProfile,AiConversation,AiMessage,CampaignIntent,BrandAiCredit,MeeraToolCall}.java` | all 6 present |
| Enums | `domain/enums/{AnalysisStatus,ConversationStatus,MessageRole,CampaignIntentType,IntentStatus,MeeraToolName,ToolCallStatus,ToolResultRefType}.java` | all 8 present |
| Repositories | `repository/{BrandProfileRepository,AiConversationRepository,AiMessageRepository,CampaignIntentRepository,BrandAiCreditRepository,MeeraToolCallRepository}.java` | all 6 present, all tenant-scoped by `workspaceId` |
| Services | `service/meera/{MeeraSessionService,BrandContextAssembler,AICreditService,StreamTokenService}.java` | all present |
| Controllers | `web/MeeraController.java` (real), `web/MeeraInternalController.java` (stub — tool executors are Phase 4, returns 501) | both present |
| DTOs | `web/dto/meera/MeeraDtos.java` | present |
| Config | `config/MeeraStreamProperties.java` | present |

`MeeraSessionService.sendTurn` persists a placeholder/echo ASSISTANT message — no real LLM call exists yet. That's Domain D (separate Python service), not built in this loop.

## Cross-Track Integration Check

Priya ran a combined `mvn compile` across both tracks and found exactly one seam issue, at the Phase 2 → existing-entity boundary:

- `service/meera/BrandContextAssembler.java` called `Workspace.getWebsiteUrl()` / `Workspace.getIndustry()`. The backing fields existed on `Workspace`, but the getters didn't — this codebase has no Lombok, so getters are hand-written.
- Fix: two getters added directly to `domain/entity/Workspace.java`. Confirmed present at lines 109 (`getWebsiteUrl()`) and 113 (`getIndustry()`), matching the existing getter pattern in the same file.
- Re-ran `mvn compile`: BUILD SUCCESS, 143 source files. Tara independently re-counted Java sources under `influora-api/src/main/java` at time of this report: **143 files**, matching Priya's figure exactly.

No other cross-track collisions were found — SHARED_CONTEXT.md's scope note for the Phase 2 track explicitly confirms it did not touch `service/{Wallet,Escrow,Contract,Payout}Service.java`, `domain/entity/{Wallet,EscrowHold,Contract,PaymentMilestone}.java`, `web/dto/money/`, or `integration/razorpay/` — consistent with the deliberate non-overlapping scoping.

## Review Gates

**Gate 1 — Kavya (QA), structural review: PASS (both tracks)**
Column/entity mapping verified against `01-DATA-MODEL.md` §0 for all new migrations V9–V14. Enum STRING usage confirmed. Tenant-scoping confirmed on every Domain C repository finder (no unscoped `findAll`-style leak). Confirmed `EscrowService`/`ContractService`/`PayoutService` route all money movement through the existing `WalletLedgerService.post()` rather than mutating balances directly — no bypass of the V8 ledger discipline. Confirmed `BrandContextAssembler`'s PII exclusion list.

**Gate 2 — Kabir (Red-Team), adversarial review: split result**

*AI/Meera track (Phase 2): SOUND, no findings.*
- MF-1-compliant DTOs (no client-controlled trust fields).
- Tenant isolation confirmed on the repository layer.
- PII allow-list confirmed by reading the code (not just trusting a comment): `BrandContextAssembler` allow-lists only `workspaceId`, `brandName`, `industry`, `websiteUrl`, plus `BrandProfile`'s catalog/aesthetic/tone/niche/competitor JSON — explicitly excludes `Workspace.billingEmail/gstin/pan/kycGstinDocUrl/kycPanDocUrl` and all `User` PII (email/phone/passwordHash).
- `StreamTokenService` confirmed to use a signing key distinct from the main JWT secrets.

*Money track (Phase 1): 2 findings.*
- **HIGH** — `EscrowService.confirmFunded` never cross-validated the webhook's captured payment amount/currency against the escrow hold's expected amount before transitioning to `FUNDED`. No server-side sanity check on the money figure. Compounding issue: `RazorpayWebhookController.WebhookEvent.parse` was a brittle flat string-search, unlikely to survive Razorpay's real nested payload shape (`payload.payment.entity.*`, `payload.order.entity.*`).
- **MEDIUM** — `RazorpayClient`/`RazorpayXClient` built outgoing JSON request bodies via raw string concatenation. Currently low risk (all interpolated values are server-generated), but not resilient if a workspace-controlled value is ever added to a request body.
- Confirmed sound at the same time: MF-1 (`EscrowFundRequest` carries no client amount), payee resolution in release/refund resolves server-side from `Collaboration.creatorId` and never from client input, webhook HMAC verification runs before any business logic, idempotent replay protection exists, and Razorpay credentials are read from config rather than hardcoded.

**Fixes (Vikram), confirmed in source by this report:**
- `RazorpayWebhookController.WebhookEvent.parse` rewritten to use Jackson `ObjectMapper`/`JsonNode` against the real nested Razorpay shape — confirmed at lines 94–129, reading `payload.payment.entity` / `payload.order.entity` via `JsonNode.path(...)`.
- `EscrowService.confirmFunded` now calls `validateWebhookAmount(hold, webhookAmountInPaise, webhookCurrency)` (confirmed call site at line 195) before any ledger posting. `validateWebhookAmount` (confirmed defined at line 376) converts the webhook's paise amount to rupees and asserts equality against `hold.getAmount()`/`hold.getCurrency()`, throwing `ApiException("ESCROW_AMOUNT_MISMATCH", ..., 409)` (confirmed at line 407) on mismatch instead of silently accepting it.
- `RazorpayClient` and `RazorpayXClient` both now build request bodies via `LinkedHashMap<String,Object>` + `ObjectMapper.writeValueAsString(...)` — confirmed `LinkedHashMap`/`ObjectMapper` usage in both files, no remaining string-concat JSON construction. No new Maven dependency was needed — Jackson is already transitive via `spring-boot-starter-web`.

Priya independently spot-checked the fix — confirmed `validateWebhookAmount` is actually invoked before the `FUNDED` transition (not merely present in the file) — and re-ran `mvn compile` independently: BUILD SUCCESS, exit 0.

**Gate 3 — Meera (DevOps), formal build-verification pass: PASS**
`mvn compile` SUCCESS. Confirmed the Flyway migration sequence V1–V14 has no gaps and correct dependency ordering (contracts after escrow, AI conversations after brand profiles). Migration file listing on disk confirms V1 through V14 present with no missing numbers.

## Current Build Status

**GREEN.** `mvn compile` succeeds cleanly across both tracks combined. The one cross-track integration gap (missing `Workspace` getters) and both Red-Team findings on the money track are resolved and verified directly in source by this report. 143 Java source files under `influora-api/src/main/java`, confirmed by independent recount matching Priya's and Meera's figures.

Caveats:
- No live MySQL datasource was available in this environment. V9–V14 are verified at code/entity level against `01-DATA-MODEL.md` only, not executed against a live database — runtime DDL correctness (constraint names, index behavior, FK ordering under load) is unverified.
- The repository has no automated tests. All verification above is compile-level and manual/adversarial code review, not test-driven.
- Razorpay integration is hand-rolled on `java.net.http.HttpClient`; the official `com.razorpay:razorpay-java` SDK is not yet an approved dependency. Real Razorpay API calls cannot be typed/tested against the live API until Priya signs off on that dependency.
- `MeeraSessionService` persists a placeholder/echo AI response — no real LLM integration exists. That's Domain D (the separate Python service), still unbuilt.

## Explicitly Still Open / Unbuilt

Per `10-VIKRAM-FILE-MANIFEST.md`, remaining after this loop:
- **Domain B** — notifications (39 files), untouched.
- **Phase 4** — the tool-executors that let Meera actually call money endpoints (~13 files). Blocked on Phase 1 being done, which it now is, so this is unblocked but not started. `MeeraInternalController` currently stubs this with 501s.
- **Domain D** — the Python AI service itself (21 files), unbuilt. `MeeraSessionService`'s echo behavior is the placeholder standing in for this.
- **Domain E** — full security hardening (~17 files), unbuilt, though idempotency-equivalent logic already exists inline via unique constraints (per the V8 precedent) rather than as a dedicated `IdempotencyService`.

---

**Ready for Priya sign-off: YES** — Phase 1 (escrow/contracts/Razorpay) and Phase 2 (AI data layer + read-only Meera chat) are both complete, the one cross-track integration gap is fixed and verified in source, both Red-Team findings on the money track are fixed and verified in source (webhook amount validation before ledger posting, real nested-JSON webhook parsing, injection-resistant JSON body construction), and the combined real build is green (143 files, exit code 0). Sign-off is scoped to Phase 1 + Phase 2 only. It does not cover: live-DB migration execution (no DB available), test coverage (none exists in the repo), the `razorpay-java` SDK dependency decision (Razorpay calls remain hand-rolled HTTP pending Priya's approval), or readiness of Domain B, Phase 4 tool-executors, Domain D (Python AI service), or Domain E (security hardening) — all of which remain unbuilt and out of scope for this loop.
