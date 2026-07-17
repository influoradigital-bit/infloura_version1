# 14 — Remaining Backend Tasks (What's Left)

> **Owner:** Vikram (Backend) · **Date:** 2026-07-05
> **Status:** PLANNING — remaining scope after V8 + Phase 1 + Phase 2
> **Sources:** `10-VIKRAM-FILE-MANIFEST.md` (file counts), `05-VIKRAM-WORK-TASKS.md` (phase order), `12-TARA-V8-RUN-REPORT.md` + `13-TARA-PHASE1-2-RUN-REPORT.md` (what's already built), `04-AI-SERVICE-SPEC.md` (Python service breakdown)

Already built and NOT re-listed here: V8 wallet ledger; V9-V10 escrow/contracts/milestones + WalletService/EscrowService/ContractService/PayoutService + controllers + Razorpay hand-rolled client; V11-V14 AI data layer (BrandProfile, AiConversation, AiMessage, CampaignIntent, BrandAiCredit, MeeraToolCall) + MeeraSessionService/BrandContextAssembler/AICreditService/StreamTokenService + MeeraController + MeeraInternalController stub (501s) + MeeraDtos.

---

## Summary Table

| # | Domain / Phase | ~File count | Priority | Blocked by | Status |
|---|---|---:|---|---|---|
| 1 | Domain D — Python AI service (`influora-ai/`) | 21 | 🔴 Highest | none (Phase 1 & 2 already landed) | Not started |
| 2 | Phase 4 — Meera tool executors (Domain C remainder) | 13 | 🔴 High | Phase 1 (done) — now unblocked | Not started |
| 3 | Domain B — Notifications | 39 | 🟡 Medium | none, can start anytime | Not started |
| 4 | Domain E — Security hardening | ~17 + 2 migrations | 🔴 Woven into every phase | none, must accompany 1 & 2 | Not started |
| 5 | Automated tests (cross-cutting) | 1+ per service | 🟡 Medium, growing risk | code existing to test | Not started |
| 6 | Live MySQL migration execution | ops task | 🔴 Blocking real verification | Meera provisioning DB | Not started |
| 7 | Razorpay SDK swap | ~4 files touched | 🟡 Medium | Priya approval (already given) | Not started |

Grand total remaining: **~21 (Domain D) + 13 (Phase 4) + 39 (Domain B) + ~16 (Domain E net-new) = ~89 new/modified files**, plus 2 migrations, plus test classes, plus the live-DB and SDK-swap ops items.

---

## 1. Domain D — Python AI Service (`influora-ai/`, new repo, ~21 files)

**This is the highest priority.** Until it exists, Meera only echoes a placeholder/stub message (confirmed in `13-TARA-PHASE1-2-RUN-REPORT.md`: `MeeraSessionService.sendTurn` persists a placeholder ASSISTANT message, no real LLM call). Owner: Vikram (service code) + AI provider integration (Claude/Gemini/Sarvam keys via Priya/Rohan for cost approval).

- [ ] `app/main.py` — FastAPI app, route registration
- [ ] `app/config.py` — env loading, provider keys (server-side only, never `NEXT_PUBLIC_`-equivalent exposure)
- [ ] `app/auth/service_token.py` — validate Spring's scoped service token `[SEC]`
- [ ] `app/prompt/assembler.py` — cached prompt layout (persona → brand → history), Anthropic prompt-caching layout
- [ ] `app/prompt/persona.py` — Meera system prompt (versioned)
- [ ] `app/providers/claude.py` — chat + streaming client
- [ ] `app/providers/gemini.py` — website scrape/prescreen client
- [ ] `app/providers/sarvam.py` — STT/TTS voice client
- [ ] `app/routes/chat.py` — SSE streaming chat endpoint
- [ ] `app/routes/analyze_site.py` — Playwright + Gemini site analyzer `[SEC: SsrfGuard]`
- [ ] `app/routes/voice.py` — transcribe/speak endpoints
- [ ] `app/tools/loop.py` — function-call loop; proposes `tool_use`, calls back to Spring `/internal/meera/*` (Python never moves money — proposes only)
- [ ] `app/tools/schemas.py` — the 5 tool JSON schemas (must match `06-MEERA-PERMISSIONS-MATRIX.md`)
- [ ] `app/security/ssrf_guard.py` — URL allowlist, blocks private IPs / `169.254.169.254` `[SEC]`
- [ ] `app/security/redaction.py` — log redaction, no secrets/PII in logs
- [ ] `app/clients/spring.py` — signed callback client to Spring internal endpoints
- [ ] `requirements.txt`
- [ ] `Dockerfile`
- [ ] `.env.example`
- [ ] `tests/` — at least smoke tests for chat streaming, SSRF guard, tool loop

**Dependency notes:** Depends on Phase 2 (already done — `StreamTokenService`, `BrandContextAssembler` exist). Blocks Phase 4's `T3.5` (tool loop needs Spring executors to call into) and Phase 5 (voice). Kabir must gate `SsrfGuard` and `service_token.py` before this touches the internet (RT-G6: no keys ever reach frontend).

**Company agents involved:** Vikram builds; Kabir security-gates SsrfGuard + token validation; Meera provisions the container/deployment; Kavya QA's the streaming behavior.

---

## 2. Phase 4 — Meera Tool Executors (Domain C remainder, ~13 files)

Now **unblocked** since Phase 1 (money core) landed per `13-TARA-PHASE1-2-RUN-REPORT.md`. `MeeraInternalController` currently returns 501 stubs — this phase makes it real. Owner: Vikram, Kabir gates every write-tier executor.

- [ ] `service/meera/tool/ShowCreatorsExecutor.java` — read-only tool
- [ ] `service/meera/tool/CalculateBudgetExecutor.java` — read-only tool
- [ ] `service/meera/tool/CreateCampaignExecutor.java` — write tool, idempotent via `meera_tool_calls` dedupe
- [ ] `service/meera/tool/RequestPaymentExecutor.java` — write tool, **amount re-derived server-side, produces PENDING human-confirm state only, never moves money directly** `[SEC: Kabir G1]`
- [ ] `service/meera/tool/ConfirmLaunchExecutor.java` — write tool: invites + escrow-hold + credit reset, idempotent
- [ ] `service/meera/tool/ToolCallValidator.java` — validates every tool-call against `06-MEERA-PERMISSIONS-MATRIX.md` `[SEC]`
- [ ] Wire `MeeraInternalController` — replace the 5 stub 501 endpoints (`/internal/meera/show_creators`, `/calculate_budget`, `/create_campaign`, `/request_payment`, `/confirm_launch`) with real calls to the executors above
- [ ] mTLS / signed-service-token auth + network isolation between Python and `/internal/*` (`T4.5`)
- [ ] On-behalf-of JWT resolution for read tools (`T4.1`)

Also bundled here per scope note — the Razorpay SDK swap belongs adjacent to this phase since it touches the same money-execution surface:
- [ ] Swap hand-rolled `RazorpayClient.java` / `RazorpayXClient.java` (currently `java.net.http.HttpClient`) for the approved `com.razorpay:razorpay-java` SDK (Priya-approved dependency, logged in `wiki/tech/approved-deps.md` per T&C)
- [ ] Re-test webhook signature verification against real SDK response shapes

**Dependency notes:** Depends on Phase 1 (done). Blocks Domain D's `T3.5` (Python tool loop has nothing to call until this exists) and all of Phase 5 voice tool-calls.

**Acceptance (Kabir gate, launch-blocking):** injected tool-args cannot move money; amount tampering rejected; idempotency replay safe; tenant isolation holds; RT-G1/G2/G4 all pass; 25-row acceptance checklist green.

**Company agents involved:** Vikram builds; Kabir gates (launch-blocking gate, not optional); Kavya QA's idempotency/replay behavior; Priya signs off on the SDK swap.

---

## 3. Domain B — Notifications (39 files)

No money or AI dependency — can start anytime in parallel. Owner: Vikram.

**Migrations (3):**
- [ ] `V15__notifications.sql`
- [ ] `V16__email_outbox.sql`
- [ ] `V17__email_preferences.sql`

**Entities (3):**
- [ ] `domain/entity/Notification.java`
- [ ] `domain/entity/EmailOutbox.java`
- [ ] `domain/entity/EmailPreference.java`

**Repositories (3):**
- [ ] `NotificationRepository.java`
- [ ] `EmailOutboxRepository.java`
- [ ] `EmailPreferenceRepository.java`

**Services + eventing (3 + 22 event records):**
- [ ] `service/notification/NotificationService.java` — create in-app + queue email, idempotent
- [ ] `service/notification/NotificationListener.java` — `@EventListener` across all 22 domain events
- [ ] `service/notification/EmailWorker.java` — `@Scheduled(30s)` outbox poller, exponential backoff
- [ ] `service/notification/event/*.java` — 22 event records (`CampaignCreatedEvent`, `ProposalSentEvent`, `ShipmentCreatedEvent`, etc. — see `07-NOTIFICATION-SYSTEM-SPEC.md` for full list)

**Integration (1):**
- [ ] `integration/msg91/Msg91EmailClient.java` — template send

**Controller + DTOs (4):**
- [ ] `web/NotificationController.java` — GET list, POST read, unsubscribe
- [ ] `web/dto/notification/*` — ~3 DTOs

**Dependency notes:** No blockers. Rides alongside Phase 1 per the original build-order (`05-VIKRAM-WORK-TASKS.md`), but Phase 1 shipped without it — this is now pure backlog.

**Company agents involved:** Vikram builds; Kavya QA's the email-outbox retry/backoff logic; Aditya may want hooks here for SEO-adjacent lifecycle emails (not in scope unless requested).

---

## 4. Domain E — Security Hardening (~17 files + 2 migrations)

Woven into every phase above, not a final bolt-on — but several items have no other phase to ride on and need explicit tracking. Source: `09-ADVANCED-SECURITY-MEASURES.md` (Kabir). Owner: Vikram builds, Kabir specifies + gates.

**Net-new classes:**
- [ ] `InternalServiceTokenFilter` — validates scoped service tokens on `/internal/*`
- [ ] `OnBehalfAuthResolver`
- [ ] `InternalRequestVerifier` — HMAC/nonce verification
- [ ] `TenantGuard` + `TenantScopedRepository` base class (shared with Domain A/C repos already built — verify all repos actually extend it)
- [ ] `IdempotencyService` + `idempotency_keys` table migration
- [ ] `LedgerInvariantValidator` (shared with `WalletService`)
- [ ] `EscrowStateMachine` (shared with `EscrowService`)
- [ ] `PayoutStateMachine` (shared with `PayoutService`)
- [ ] `AuditLogService` + `audit_log` table migration (immutable append-only)
- [ ] `SecurityHeadersFilter` — HSTS/CSP/X-Frame-Options
- [ ] `CorsConfig` — explicit allowlist
- [ ] `RateLimitService` — per-IP + per-user + per-endpoint, distributed store (not in-memory)
- [ ] `RefreshTokenReuseDetector`
- [ ] `JwtHardeningConfig` — algorithm pinning, iss/aud/skew validation
- [ ] `SecretsConfig` — blast-radius separation between services

**MODIFY (existing live classes with confirmed gaps — Kabir `[LIVE-GAP]`):**
- [ ] `security/JwtService.java` — alg-pinning, iss/aud/exp/skew validation
- [ ] `security/JwtAuthenticationFilter.java` — stop swallowing exceptions silently; log rejected tokens (Phase 0 task T0.2 — verify this actually landed, it predates Phase 1/2 reports)
- [ ] `security/AuthRateLimitFilter.java` — move from in-memory to distributed store
- [ ] `config/SecurityConfig.java` — wire in all new filters above, CORS, headers

**Dependency notes:** `SsrfGuard`, `WebhookSignatureVerifier`, and `ToolCallValidator` are already counted in Domains A/C/D above — do not double-build. Migrations here (`idempotency_keys`, `audit_log`) are separate from V8-V17.

**Acceptance:** Kabir's gate applies per-phase — no money file (Domain A, Phase 4 write-tier) ships without RT-G1..G6 + MF-1..4 + LB-1..LB-9 green.

**Company agents involved:** Vikram builds; Kabir specifies and gates every phase (not optional, not a final step); Priya arbitrates any architecture conflicts.

---

## 5. Cross-cutting: Automated Tests

Zero test classes exist today across the built money/AI services — Swapnil flagged this as a growing risk in review. Owner: Vikram, Kavya QA reviews coverage.

- [ ] Test class for `WalletService` (double-entry invariant, concurrent double-spend attempt)
- [ ] Test class for `EscrowService` (state machine transitions, idempotency replay)
- [ ] Test class for `ContractService` (PDF hash integrity)
- [ ] Test class for `PayoutService` (state machine)
- [ ] Test class for `AICreditService` (circuit-breaker gate, monthly reset)
- [ ] Test class for `MeeraSessionService` (turn persistence)
- [ ] Test class for each Phase 4 tool executor (especially `RequestPaymentExecutor` amount tampering)
- [ ] `influora-ai/tests/` — SSRF guard, service-token validation, streaming smoke test

**Dependency notes:** Can start immediately against already-built code (Domains A, C) without waiting on Domain D or Phase 4. Should run alongside Phase 4 and Domain D as they're built, not deferred to the end.

**Company agents involved:** Vikram writes; Kavya reviews coverage against standards; Meera runs them in CI/local verification.

---

## 6. Cross-cutting: Live MySQL Migration Execution

V8 through V14 are only code-verified (confirmed via `mvn compile` per Tara's reports) — **never run against a live database.** This is a real gap: Flyway checksum issues, column-type mismatches, or index conflicts won't surface until migrations actually execute.

- [ ] Provision a live MySQL datasource (dev/staging)
- [ ] Run `flyway:migrate` for V8-V14 (already-built schema)
- [ ] Verify migrations for V15-V17 (notifications, once built) run cleanly on top
- [ ] Verify `idempotency_keys` and `audit_log` migrations (Domain E) run cleanly
- [ ] Smoke-test basic CRUD against each new table post-migration

**Dependency notes:** Blocking for any real verification of Domains A/B/C/E. This is Meera's territory (DB/DevOps), not Vikram's to execute — but Vikram should flag it as done via `wiki/processes/schema-changes.md` logging once it happens.

**Company agents involved:** Meera provisions + runs migrations; Vikram logs each migration in `wiki/processes/schema-changes.md`; Kavya verifies data integrity post-migration.

---

## 7. Razorpay SDK Integration

Already noted under item 2 above (Phase 4) since it shares the money-execution surface, but tracked separately here because it's a standalone approved dependency swap, not new functionality.

- [ ] Add `com.razorpay:razorpay-java` to `pom.xml` (Priya already approved — confirm entry exists in `wiki/tech/approved-deps.md`)
- [ ] Replace hand-rolled HTTP calls in `RazorpayClient.java` and `RazorpayXClient.java` with SDK calls
- [ ] Re-verify `WebhookSignatureVerifier` against SDK's webhook payload shape
- [ ] Re-run Kabir's money-endpoint gate after the swap (SDK changes response parsing — do not assume it's a no-op)

**Company agents involved:** Vikram builds; Priya already approved the dependency; Kabir re-gates after swap.

---

## Recommended Build Order

Reflecting the dependency graph — Domain D and Phase 4 unlock Meera actually working, so they come first; notifications have no blockers and can ride anywhere; security and tests are woven throughout, not sequenced at the end.

1. **Domain D (Python AI service) + Phase 4 (Meera tool executors) — build in parallel.** These two together are what make Meera functional instead of an echo stub. Domain D's tool-loop (`T3.5`) and Phase 4's executors are mutually dependent — coordinate the `/internal/meera/*` contract between them early (Vikram owns both sides, so this is an internal coordination task, not a cross-team handoff).
2. **Razorpay SDK swap** — do this alongside Phase 4 since Phase 4 touches the same `PayoutService`/`RequestPaymentExecutor` surface; cheaper to swap once than twice.
3. **Domain E security items with no other phase to ride on** (`AuditLogService`, `RateLimitService`, `RefreshTokenReuseDetector`, `JwtHardeningConfig`, the 4 MODIFY hardening items) — run concurrently with step 1, gated by Kabir before Domain D goes live to real traffic.
4. **Domain B (notifications)** — no blockers, can be picked up by Vikram in parallel with the above once bandwidth allows, or immediately if a second backend cycle is available.
5. **Automated tests** — write incrementally alongside steps 1-4, not deferred. Prioritize `RequestPaymentExecutor` and `WalletService` tests given they are the highest-risk money paths.
6. **Live MySQL migration execution** — Meera should provision and run this as soon as possible, independent of the above (it validates work already done in V8-V14) and again after each new migration (V15-V17, idempotency_keys, audit_log) lands.
7. **Final Kabir gate** — full 25-row acceptance checklist green across Domains A, C (incl. Phase 4), D, and E before Priya sign-off → Swapnil final review.
