# 15 — What We Have Actually Built — Backend (as of end of Phase 2)

**Owner:** Tara (Operations & Reporting)
**Date:** 2026-07-05
**Scope:** Everything actually landed on disk in `influora-api/` across three build loops (V8 ledger, Phase 1 money core, Phase 2 AI/Meera data layer). Read-only report — every file/capability below was confirmed present on disk under `influora-api/src/main/` before being listed.

---

## Build Loops Summary

| Loop | What | Owner(s) | Status | Report doc |
|---|---|---|---|---|
| **Loop 1 — V8** | Wallet double-entry ledger (foundational money rails) | Vikram (build) · Priya (scope/verify) · Kavya (QA) · Kabir (security) · Meera (build) · Swapnil (sign-off) | ✅ GREEN, signed off | `12-TARA-V8-RUN-REPORT.md` |
| **Loop 2 — Phase 1 (Track A)** | Escrow, contracts/milestones, Razorpay integration, wallet/escrow/contract/payout services + controllers | Vikram · Priya · Kavya · Kabir · Meera · Swapnil | ✅ GREEN, signed off | `13-TARA-PHASE1-2-RUN-REPORT.md` |
| **Loop 3 — Phase 2 (Track B)** | Brand profiles, AI conversations/messages, campaign intents, AI credits/tool-call ledger, read-only Meera chat | Vikram · Priya · Kavya · Kabir · Meera · Swapnil | ✅ GREEN, signed off | `13-TARA-PHASE1-2-RUN-REPORT.md` |

Loops 2 and 3 ran in parallel as two deliberately non-overlapping file tracks and were compiled together at the gate.

---

## Loop 1 — V8 Wallet Ledger (foundational money rails)

- ✅ V8 migration (`resources/db/migration/V8__wallet_transactions.sql`) — **built by Vikram**, **Kavya**-QA'd for column/entity mapping, **Meera**-build-verified, **Priya** signed off.
- ✅ `WalletTransaction` entity (`domain/entity/WalletTransaction.java`) — **built by Vikram**, **Kavya** confirmed enum STRING + naming.
- ✅ 4 ledger enums — `domain/enums/TxnDirection.java`, `WalletTransactionType.java`, `TransactionStatus.java`, `TxnReferenceType.java` — **built by Vikram**.
- ✅ `WalletTransactionRepository` (`repository/WalletTransactionRepository.java`) — **built by Vikram**.
- ✅ V8 double-entry ledger `post()` with idempotency + currency-match (`service/WalletLedgerService.java`) — **built by Vikram**; security-hardened after **Kabir** found the idempotency race (HIGH) and missing currency-match (MEDIUM) — Vikram added the `DataIntegrityViolationException` catch-and-refetch (`409 LEDGER_POSTING_CONFLICT`) and the explicit `CURRENCY_MISMATCH` (400) check; **Meera**-verified, **Priya** independently re-verified, **Swapnil** signed off.
- ✅ `Wallet.applyBalanceDelta()` (`domain/entity/Wallet.java`) — **built by Vikram**.
- ✅ `WalletRepository.findByIdForUpdate` pessimistic lock (`repository/WalletRepository.java`) — **built by Vikram**.
- ✅ 3 pre-existing compile errors (unrelated to the ledger) in `security/JwtAuthenticationFilter.java` and `service/CampaignService.java` — caught by **Meera** (build veto), fixed by **Vikram**.

---

## Loop 2 — Phase 1 Money Core (escrow, contracts, Razorpay)

- ✅ V9 migration (`resources/db/migration/V9__escrow_holds.sql`) and V10 migration (`V10__contracts_and_milestones.sql`) — **built by Vikram**, **Kavya**-QA'd, **Meera**-verified, **Priya** signed off.
- ✅ Entities: `domain/entity/EscrowHold.java`, `Contract.java`, `PaymentMilestone.java` — **built by Vikram**.
- ✅ Enums: `domain/enums/EscrowStatus.java`, `ContractStatus.java`, `MilestoneStatus.java` — **built by Vikram**.
- ✅ Repositories: `repository/EscrowHoldRepository.java`, `ContractRepository.java`, `PaymentMilestoneRepository.java` — **built by Vikram**.
- ✅ Services: `service/WalletService.java`, `PlatformWalletService.java`, `EscrowService.java`, `ContractService.java`, `PayoutService.java` — **built by Vikram**; **Kavya** confirmed all money movement routes through `WalletLedgerService.post()` (no balance-mutation bypass of the V8 ledger).
- ✅ Controllers: `web/WalletController.java`, `EscrowController.java`, `ContractController.java` — **built by Vikram**.
- ✅ Razorpay integration: `integration/razorpay/RazorpayClient.java`, `RazorpayXClient.java`, `RazorpayWebhookController.java`, `WebhookSignatureVerifier.java`, `RazorpayIntegrationException.java`, plus `config/RazorpayProperties.java` — **built by Vikram** (hand-rolled on `java.net.http.HttpClient`).
- ✅ Money DTOs (`web/dto/money/MoneyDtos.java`) — **built by Vikram**.
- ✅ Security hardening on the money track — **Kabir** found: missing webhook amount/currency cross-check before `FUNDED` (HIGH) + brittle flat-string webhook parser + string-concat JSON body construction (MEDIUM). **Vikram** fixed: `EscrowService.validateWebhookAmount(...)` now runs before ledger posting (throws `ESCROW_AMOUNT_MISMATCH` 409 on mismatch); `RazorpayWebhookController` webhook parser rewritten to Jackson `JsonNode` against the real nested payload shape; `RazorpayClient`/`RazorpayXClient` request bodies rebuilt via `LinkedHashMap` + `ObjectMapper`. **Priya** independently spot-checked that `validateWebhookAmount` is actually invoked (not merely present), **Meera**-build-verified, **Swapnil** signed off.
- ✅ Razorpay SDK dependency `com.razorpay:razorpay-java` — **approved by Priya** and logged in `wiki/tech/approved-deps.md` (2026-07-05), to replace the hand-rolled HTTP scaffolding before any live Razorpay call; swap not yet executed.

---

## Loop 3 — Phase 2 AI/Meera Data Layer + Read-Only Chat

- ✅ Migrations V11–V14 (`V11__brand_profiles.sql`, `V12__ai_conversations_messages.sql`, `V13__campaign_intents.sql`, `V14__ai_credits_tool_calls.sql`) — **built by Vikram**, **Kavya**-QA'd, **Meera** confirmed Flyway V1–V14 gap-free, **Priya** signed off.
- ✅ Entities: `domain/entity/BrandProfile.java`, `AiConversation.java`, `AiMessage.java`, `CampaignIntent.java`, `BrandAiCredit.java`, `MeeraToolCall.java` — **built by Vikram**.
- ✅ 8 enums: `domain/enums/AnalysisStatus.java`, `ConversationStatus.java`, `MessageRole.java`, `CampaignIntentType.java`, `IntentStatus.java`, `MeeraToolName.java`, `ToolCallStatus.java`, `ToolResultRefType.java` — **built by Vikram**.
- ✅ Tenant-scoped repositories: `repository/BrandProfileRepository.java`, `AiConversationRepository.java`, `AiMessageRepository.java`, `CampaignIntentRepository.java`, `BrandAiCreditRepository.java`, `MeeraToolCallRepository.java` — **built by Vikram**; **Kavya** confirmed every finder is scoped by `workspaceId` (no unscoped leak).
- ✅ Services: `service/meera/MeeraSessionService.java`, `BrandContextAssembler.java`, `AICreditService.java`, `StreamTokenService.java` — **built by Vikram**; **Kabir** cleared the AI track as sound (PII allow-list confirmed in `BrandContextAssembler`, tenant isolation confirmed, `StreamTokenService` uses a signing key distinct from the main JWT secret).
- ✅ Controllers: `web/MeeraController.java` (real, read-only chat) + `web/MeeraInternalController.java` (501 stub — tool executors are Phase 4) — **built by Vikram**.
- ✅ Meera DTOs (`web/dto/meera/MeeraDtos.java`) and stream config (`config/MeeraStreamProperties.java`) — **built by Vikram**.
- ✅ Cross-track compile seam — `BrandContextAssembler` needed `Workspace.getWebsiteUrl()` / `getIndustry()` getters that didn't exist (no Lombok in this codebase). Gap caught and fixed directly by **Priya** (added both getters to `domain/entity/Workspace.java`), then combined `mvn compile` re-run to BUILD SUCCESS.

---

## Who Did What (across all three loops)

| Person | Role | Contribution |
|---|---|---|
| **Vikram** | Backend Dev | Wrote all Java/SQL code across all three loops (V8 ledger, Phase 1 money core, Phase 2 AI data layer) and executed every fix round from QA, Red-Team, and DevOps findings. |
| **Priya** | CTO | Scoped each loop and wrote the plans; caught and directly fixed the cross-track `Workspace`-getters compile gap; independently re-ran `mvn compile` at each gate (did not trust self-reports); approved the `razorpay-java` dependency. |
| **Kavya** | QA Lead | Structural QA on every slice — column/entity mapping, enum STRING usage, naming, tenant-scoping, and ledger-discipline (no balance-mutation bypass). PASS on all. |
| **Kabir** | Red-Team / Security | Adversarial audit. Found and got fixed: V8 idempotency race (HIGH) + currency-mismatch (MEDIUM); Phase 1 missing webhook amount cross-check (HIGH) + brittle JSON parser + string-concat JSON injection risk (MEDIUM). Cleared the AI track as sound. |
| **Meera** | DevOps / build verifier | Ran the real `mvn compile` at each loop; caught 3 pre-existing compile errors in the V8 loop (JwtAuthenticationFilter + CampaignService, unrelated to the ledger) that Vikram then fixed; confirmed Flyway V1–V14 sequence gap-free. |
| **Tara** | Ops / Reporting | Wrote the run reports (docs `12`, `13`, and this one). |
| **Swapnil** | CEO | Final business sign-off on each loop — both APPROVED. |

---

## Current Status

Build **GREEN** — 143 Java source files, `mvn compile` exit 0. Migrations **V1–V14 gap-free**. Roughly **~40% of backend infra landed**; **0% of the AI/Python layer** (Domain D) built.

**Honest caveats:**
- **No live-DB run** — no MySQL datasource was available; V8–V14 migrations verified at code/spec level against `01-DATA-MODEL.md` only, not executed against a live database. Runtime DDL correctness (constraint names, index behavior, FK ordering under load) is unverified.
- **No automated tests** — the repository has zero test coverage; all verification above is compile-level and manual/adversarial code review, not test-driven.
- **`MeeraSessionService` still echoes a placeholder** — it persists a placeholder/echo ASSISTANT message; there is no real LLM call yet. That's Domain D (the separate Python AI service), still unbuilt.
- Razorpay remains hand-rolled HTTP; the approved `razorpay-java` SDK swap is not yet executed. Phase 4 tool-executors (`MeeraInternalController` 501 stubs), Domain B (notifications), and Domain E (security hardening) remain unbuilt.
