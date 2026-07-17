# 🗂️ VIKRAM — COMPLETE FILE MANIFEST (build straight from this)

> **Owner:** Priya (CTO) · **Date:** 2026-07-05 · **For:** Vikram (Backend)
> **Inputs:** `08-CODEBASE-INVENTORY.md` (Tara — what exists) · `09-ADVANCED-SECURITY-MEASURES.md` (Kabir — 24 security files) · `01`–`07` (schema, API, security, AI, notifications).
> **Purpose:** Every file Vikram must create or modify, counted and specified, so code can be written directly. Nothing here is optional interpretation.

---

## ⚠️ PHASE 0 BLOCKER (Tara finding — fix before ANY build)

Two working-copy files are **truncated on disk**:
- `influora-api/src/main/resources/application.yml` — cuts off mid-`msg91` block.
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java` — cuts off mid-statement → **will not compile**.

**Action:** Restore canonical copies from git (once `git init` + first commit exists — Phase 0 task T0.4) or reconstruct. Vikram cannot build against a non-compiling `SecurityConfig`. This is the very first task.

---

## HOW TO READ THIS

- **CREATE** = new file. **MODIFY** = edit existing file (per Tara's inventory).
- Java files follow the existing package convention `com.influora.<layer>`.
- Every money/AI file cross-references its security control in `09-ADVANCED-SECURITY-MEASURES.md` (e.g. `[SEC: IdempotencyService]`).
- **File counts** are given per domain and totaled at the end.

---

## DOMAIN A — MONEY CORE (M2) · escrow, ledger, contracts, payments

### A1. Migrations (CREATE) — `src/main/resources/db/migration/`
| File | Creates |
|---|---|
| `V8__wallet_transactions.sql` | double-entry ledger (source of truth) |
| `V9__escrow_holds.sql` | escrow holds + `idempotency_key UNIQUE` |
| `V10__contracts_and_milestones.sql` | `contracts`, `payment_milestones` |

### A2. Entities (CREATE) — `domain/entity/`
`WalletTransaction.java` · `EscrowHold.java` · `Contract.java` · `PaymentMilestone.java` — ULID keys, `@Enumerated(STRING)`, `Instant` timestamps, builder pattern (match `Campaign.java`).

### A3. Repositories (CREATE) — `repository/`
`WalletTransactionRepository.java` · `EscrowHoldRepository.java` · `ContractRepository.java` · `PaymentMilestoneRepository.java` — all extend a new `TenantScopedRepository` base `[SEC: TenantGuard]`.

### A4. Services (CREATE) — `service/`
| File | Key methods | Security |
|---|---|---|
| `WalletService.java` | `debit`, `credit`, `getBalance` — `SELECT … FOR UPDATE`, `@Transactional` | `[SEC: LedgerInvariantValidator]` sum=0 |
| `EscrowService.java` | `hold(campaignId)` amount server-derived, `release`, `refund` | `[SEC: EscrowStateMachine]`, `[SEC: IdempotencyService]` |
| `ContractService.java` | `generate(termsId)` → PDF + SHA-256, `recordSignature` | tamper-hash |
| `PayoutService.java` | `queuePayout`, `executePayout` (out-of-band confirm) | `[SEC: PayoutStateMachine]` |

### A5. Controllers (CREATE) — `web/`
`WalletController.java` (GET balance) · `EscrowController.java` (fund/status) · `ContractController.java` (generate/sign) — all `@PreAuthorize` workspace-scoped.

### A6. Integration (CREATE) — `integration/razorpay/`
`RazorpayClient.java` (Route/split) · `RazorpayXClient.java` (payouts) · `RazorpayWebhookController.java` · `WebhookSignatureVerifier.java` `[SEC]` (HMAC verify).

### A7. DTOs (CREATE) — `web/dto/money/`
~8 records: `EscrowFundRequest/Response`, `WalletBalanceResponse`, `ContractResponse`, `PayoutRequest/Response`, `MilestoneDto`, `WebhookPayload`. All Bean-Validation annotated. **Note:** `EscrowFundRequest` carries NO `amount` field `[SEC: MF-1]`.

**Domain A total: 3 migrations + 4 entities + 4 repos + 4 services + 3 controllers + 4 integration + 8 DTOs = 30 files.**

---

## DOMAIN B — NOTIFICATIONS (M2) · in-app + email outbox

### B1. Migrations (CREATE)
`V15__notifications.sql` · `V16__email_outbox.sql` · `V17__email_preferences.sql`

### B2. Entities (CREATE) — `domain/entity/`
`Notification.java` · `EmailOutbox.java` · `EmailPreference.java`

### B3. Repositories (CREATE)
`NotificationRepository.java` · `EmailOutboxRepository.java` · `EmailPreferenceRepository.java`

### B4. Services + eventing (CREATE) — `service/notification/`
| File | Purpose |
|---|---|
| `NotificationService.java` | create in-app + queue email (idempotent) |
| `NotificationListener.java` | `@EventListener` on all 22 domain events → NotificationService |
| `EmailWorker.java` | `@Scheduled(30s)` poll outbox, send, exponential backoff |
| `event/*.java` (22 event records) | `CampaignCreatedEvent`, `ProposalSentEvent`, `ShipmentCreatedEvent`, … |

### B5. Integration (CREATE) — `integration/msg91/`
`Msg91EmailClient.java` (template send).

### B6. Controller + DTO (CREATE)
`NotificationController.java` (GET list, POST read, unsubscribe) · `web/dto/notification/*` (~3 DTOs).

**Domain B total: 3 migrations + 3 entities + 3 repos + 3 services + 22 event classes + 1 integration + 1 controller + 3 DTOs = 39 files.**
*(The 22 event classes are tiny records; group them in one `event/` package.)*

---

## DOMAIN C — AI / MEERA (M2.5) · Spring side

### C1. Migrations (CREATE)
`V11__brand_profiles.sql` · `V12__ai_conversations_messages.sql` · `V13__campaign_intents.sql` · `V14__ai_credits_tool_calls.sql`

### C2. Entities (CREATE) — `domain/entity/`
`BrandProfile.java` · `AiConversation.java` · `AiMessage.java` · `CampaignIntent.java` · `BrandAiCredit.java` · `MeeraToolCall.java`

### C3. Repositories (CREATE)
6 repos, one per entity above, tenant-scoped.

### C4. Services (CREATE) — `service/meera/`
| File | Purpose | Security |
|---|---|---|
| `AICreditService.java` | gate + decrement + reset hook | `[SEC: G5 credit circuit-breaker]` |
| `MeeraSessionService.java` | start session, persist turns | tenant scope |
| `BrandContextAssembler.java` | build SANITIZED context for Python | `[SEC: G3 PII allow-list]` |
| `StreamTokenService.java` | mint ≤60s single-use SSE token | `[SEC: G2]` |

### C5. Controllers (CREATE) — `web/`
`MeeraController.java` (public: session, send-turn, credit status, profile poll) · `MeeraInternalController.java` (`/internal/meera/*` executors).

### C6. Tool executors (CREATE) — `service/meera/tool/`
`ShowCreatorsExecutor.java` (R) · `CalculateBudgetExecutor.java` (R) · `CreateCampaignExecutor.java` (D) · `RequestPaymentExecutor.java` (C, PENDING-only) · `ConfirmLaunchExecutor.java` (C) · `ToolCallValidator.java` `[SEC]` (validates every tool-call against `06-MEERA-PERMISSIONS-MATRIX.md`).

### C7. DTOs (CREATE) — `web/dto/meera/`
~10 records: `SessionStartResponse`, `SendTurnRequest/Response`, `StreamTokenResponse`, `ToolCallRequest`, `CreateCampaignRequest`, `RequestPaymentRequest`, `CreditStatusResponse`, `BrandProfileResponse`, `AnalyzeSiteCallback`.

**Domain C total: 4 migrations + 6 entities + 6 repos + 4 services + 2 controllers + 6 tool files + 10 DTOs = 38 files.**

---

## DOMAIN D — PYTHON AI SERVICE (M2.5) · new repo `influora-ai/`

Stateless FastAPI. No DB, no money. See `04-AI-SERVICE-SPEC.md`.

| File | Purpose |
|---|---|
| `app/main.py` | FastAPI app, routes |
| `app/config.py` | env, provider keys |
| `app/auth/service_token.py` | validate Spring's scoped token `[SEC]` |
| `app/prompt/assembler.py` | cached prompt layout (persona→brand→history) |
| `app/prompt/persona.py` | Meera system prompt (versioned) |
| `app/providers/claude.py` | chat + streaming |
| `app/providers/gemini.py` | scrape/prescreen |
| `app/providers/sarvam.py` | STT/TTS (voice) |
| `app/routes/chat.py` | SSE stream endpoint |
| `app/routes/analyze_site.py` | Playwright + Gemini · `[SEC: SsrfGuard]` |
| `app/routes/voice.py` | transcribe/speak |
| `app/tools/loop.py` | function-call loop → Spring `/internal/meera/*` |
| `app/tools/schemas.py` | 5 tool JSON schemas |
| `app/security/ssrf_guard.py` | URL allowlist, block private IPs/169.254.169.254 `[SEC]` |
| `app/security/redaction.py` | log redaction, no secrets/PII |
| `app/clients/spring.py` | signed callback client |
| `requirements.txt`, `Dockerfile`, `.env.example`, `tests/` | infra |

**Domain D total: ~17 Python files + 4 infra = 21 files.**

---

## DOMAIN E — SECURITY FILES (24, from Kabir `09`) — cross-cutting

These are the mandatory security classes. Some overlap with domain files above (marked ↔); build them as the shared implementation.

`InternalServiceTokenFilter` · `OnBehalfAuthResolver` · `InternalRequestVerifier` (HMAC/nonce) · `TenantGuard` + `TenantScopedRepository` ↔A3/C3 · `IdempotencyService` + `idempotency_keys` table (migration) ↔A4 · `LedgerInvariantValidator` ↔A4 · `EscrowStateMachine` ↔A4 · `PayoutStateMachine` ↔A4 · `WebhookSignatureVerifier` ↔A6 · `SsrfGuard` ↔D · `ToolCallValidator` ↔C6 · `AuditLogService` + `audit_log` table (immutable) · `SecurityHeadersFilter` (HSTS/CSP/XFO) · `CorsConfig` (allowlist) · `RateLimitService` (per-IP+user+endpoint, distributed) · `RefreshTokenReuseDetector` · `JwtHardeningConfig` (alg-pin, iss/aud/skew) · `SecretsConfig` (blast-radius separation) · plus the 4 MODIFY hardenings below.

**Plus MODIFY (existing live classes, Kabir [LIVE-GAP]):**
| File | Fix |
|---|---|
| `security/JwtService.java` | alg-pinning, iss/aud/exp/skew validation |
| `security/JwtAuthenticationFilter.java` | stop swallowing exceptions; log rejects (MF-3/C-22) |
| `security/AuthRateLimitFilter.java` | move from in-memory to distributed store |
| `config/SecurityConfig.java` | restore + wire new filters, CORS, headers |

**Domain E net-new (not already counted in A–D): ~10 files + 2 migrations (idempotency_keys, audit_log) + 4 MODIFY = ~16 items.**

---

## GRAND TOTAL

| Domain | New files | Modify |
|---|---:|---:|
| A — Money core | 30 | — |
| B — Notifications | 39 | — |
| C — AI/Meera (Spring) | 38 | — |
| D — Python AI service | 21 | — |
| E — Security (net-new) | ~12 | 4 |
| **TOTAL** | **~140 files** | **4** |

Plus **~18 Flyway migrations** (V8–V17 + idempotency_keys + audit_log), test classes per service (target ≥1 per money/AI service), and the `git init`.

> **Realistic scope for Vikram:** ~140 new files across two services. The 22 notification event records and the DTOs are small; the heavy engineering is the money core (Domain A) and the tool-executor + boundary security (C6 + E). Build order follows `05-VIKRAM-WORK-TASKS.md` phases — do NOT build in manifest order.

---

## BUILD-ORDER REMINDER (from `05`)

Phase 0 (fixes + restore truncated files + git) → Phase 1 (Domain A money) ∥ Phase 2 (Domain C data + read-only Meera) ∥ Phase 3 (Domain D Python) → Phase 4 (Domain C executors, needs A) → Phase 5 (voice). Domain B (notifications) rides alongside Phase 1. Domain E security is woven into every phase — **not a final bolt-on.**

**Gate:** no money file (Domain A, C6 commit-tier) ships without Kabir's re-test (RT-G1..G6 + MF-1..4 + LB-1..LB-9 green).
