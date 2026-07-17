# 🔧 VIKRAM — BACKEND WORK TASKS (Brand + Meera AI)

> **From:** Priya (CTO) · **To:** Vikram (Backend) · **Date:** 2026-07-05
> **Read first (in order):** `00-BACKEND-BLUEPRINT-INDEX.md` → `03-SECURITY-SPEC.md` → `01-DATA-MODEL.md` → `02-API-CONTRACT-BRAND.md` → `04-AI-SERVICE-SPEC.md`.
> **Rules:** ULID `VARCHAR(26)` keys everywhere. Spring owns money; Python never moves a rupee. Every money endpoint idempotent + amount re-derived server-side. Kabir gates before any money endpoint ships. Log every dep in `wiki/tech/approved-deps.md`.

---

## PHASE 0 — IMMEDIATE MUST-FIXES (do before anything else) 🔴

These are live-code defects / hard blockers. None depend on Meera.

| ID | Task | File(s) | Gate |
|---|------|---------|------|
| T0.1 | Fail startup if JWT secret is the dev default or < 256-bit | `config`/`JwtService`, `application.yml:53` | Kabir MF-3 |
| T0.2 | Stop `JwtAuthenticationFilter` silently swallowing JWT exceptions — log rejected tokens | `security/JwtAuthenticationFilter.java:42` | Kabir C-22 |
| T0.3 | Rewrite PRD V15/V16 DDL from `BIGINT AUTO_INCREMENT` → ULID `VARCHAR(26)` (superseded by 01-DATA-MODEL migrations) | `01-DATA-MODEL.md` | Kabir MF-4 |
| T0.4 | `git init` + first commit (repo is currently uncommitted files on disk) | repo root | Priya |

**Acceptance:** app refuses to boot with a weak secret; rejected JWTs appear in logs; no `BIGINT` keys anywhere; history exists.

---

## PHASE 1 — M2 MONEY CORE (escrow) — gates Meera's money tools 🟡

Meera's `request_payment`/`confirm_launch` cannot exist until this lands. Build with idempotency baked in from line one.

| ID | Task | Depends on | Deliverable |
|---|------|-----------|-------------|
| T1.1 | Migration V8 `wallet_transactions` (double-entry ledger) + entity + repo | 01 | ledger is source of truth; every credit has a debit |
| T1.2 | `WalletService` + `EscrowService` — all ops `@Transactional`, `SELECT … FOR UPDATE` on wallet rows | T1.1 | no double-spend |
| T1.3 | Migration V9 `escrow_holds` (+ `idempotency_key` UNIQUE); fund = "go live" event | T1.1 | funding fires the credit-reset event Meera listens for |
| T1.4 | Migration V10 `contracts` + `payment_milestones` + `ContractService` (PDF, SHA-256 hash) | T1.1 | e-contract from accepted terms |
| T1.5 | Razorpay Route (split) + RazorpayX (payouts) integration in `integration/razorpay/` | T1.2 | real money rails |
| T1.6 | **Remove client-supplied `amount` from `POST /wallet/escrow/hold`; amount derived server-side** | T1.3 | Kabir MF-1 |
| T1.7 | Kill static `INTERNAL_API_KEY` as sole money-endpoint auth → signed short-lived service token | T1.3 | Kabir MF-2 |

**Acceptance (Kabir gate):** no endpoint accepts a caller-supplied amount; concurrent double-spend test fails to double-spend; idempotency replay is a no-op; RT-G1 passes.

---

## PHASE 2 — M2.5 AI DATA + READ-ONLY MEERA (no money, ships in parallel with Phase 1) 🟢

No money dependency — start immediately alongside Phase 1.

| ID | Task | Depends on | Deliverable |
|---|------|-----------|-------------|
| T2.1 | Migration V11 `brand_profiles` + entity/repo | 01 | website-analysis output store |
| T2.2 | Migration V12 `ai_conversations` + `ai_messages` (+ `prompt_version`, `metadata JSON`) | 01 | conversation persistence |
| T2.3 | Migration V13 `campaign_intents` | 01 | extracted campaign terms |
| T2.4 | Migration V14 `brand_ai_credits` + `meera_tool_calls` (idempotency dedupe) | 01 | credit ledger + tool-call idempotency |
| T2.5 | `AICreditService` — gate + decrement + monthly reset + go-live reset hook | T2.4, T1.3 | credit model §7; hard circuit-breaker (Kabir G5) |
| T2.6 | Public endpoints: Meera session start, send-turn (credit gate + stream-token mint), credit status, brand-profile poll | T2.2, T2.5 | 02-API-CONTRACT |
| T2.7 | Short-lived (≤60s), `aud`-scoped, single-use SSE stream-token issuance | T2.6 | Kabir G2 |
| T2.8 | Sanitized brand-context assembler (field allow-list, strips PII) handed to Python | T2.1 | Kabir G3 |

**Acceptance:** read-only Meera chat works end-to-end (no money); credits gate before Python is reachable; brand context contains zero PII; RT-G3/G5 pass.

---

## PHASE 3 — PYTHON AI SERVICE 🟢

| ID | Task | Depends on | Deliverable |
|---|------|-----------|-------------|
| T3.1 | FastAPI service scaffold: non-root, read-only FS, egress-restricted; scoped-token validation on every call | 04, T2.7 | stateless AI runtime |
| T3.2 | Prompt assembly + Anthropic caching layout ([persona+tools cached]→[brand cached]→[history]) | T2.8 | ~65% cost cut (PRD §6) |
| T3.3 | Claude chat + SSE streaming to browser (token/thinking/tool_start/tool_result/done/error events) | T3.1 | Living-Canvas stream protocol |
| T3.4 | `POST /analyze-site` — Playwright + Gemini Flash (catalog, tone, niche) | T3.1 | feeds V11 `brand_profiles` |
| T3.5 | Function-call loop: 5 tool schemas; tool_use → Spring `/internal/meera/*` → tool_result | T3.3, Phase 4 | Python proposes only |
| T3.6 | Provider routing + timeouts/retries/circuit breaker | T3.1 | resilience |

**Acceptance:** streaming works; cache hit-rate confirmed; site analyzer returns structured profile; RT-G6 (no keys to frontend) passes.

---

## PHASE 4 — INTERNAL EXECUTORS (the money bridge) — needs Phase 1 done 🟡

| ID | Task | Depends on | Deliverable |
|---|------|-----------|-------------|
| T4.1 | `/internal/meera/show_creators`, `/calculate_budget` (reads) — service-token + on-behalf-of JWT | T2.6 | read tools |
| T4.2 | `/internal/meera/create_campaign` (write, idempotent, `meera_tool_calls` dedupe) | T1.*, T2.3 | campaign from intent |
| T4.3 | `/internal/meera/request_payment` — **amount re-derived server-side; produces PENDING human-confirm, never moves money** | T1.3 | Kabir G1 |
| T4.4 | `/internal/meera/confirm_launch` — invites + escrow-hold + credit reset, idempotent | T1.3, T2.5 | go-live |
| T4.5 | mTLS / signed-service-token auth + network isolation between Python and `/internal/*` | T3.1 | Kabir G2 |

**Acceptance (Kabir gate — launch-blocking):** injected tool-args cannot move money; amount tampering rejected; idempotency replay safe; tenant isolation holds; RT-G1/G2/G4 all pass; 25-row acceptance checklist green.

---

## PHASE 5 — VOICE (M2.5, cascaded) 🟢

| ID | Task | Depends on | Deliverable |
|---|------|-----------|-------------|
| T5.1 | `POST /voice/transcribe` — Sarvam STT (Hinglish) + grammar-cleanup, edit-first return | T3.1 | voice input |
| T5.2 | `POST /voice/speak` — Sarvam TTS, streamed | T3.3 | voice output |
| T5.3 | Graceful fallback to text on any voice failure | T5.1 | §5A combo rule |
| T5.4 | Voice credit weighting (input=3, reply=4) in `AICreditService` | T2.5 | cost control |

**Acceptance:** voice round-trip works; any failure falls back to text with no dead end; credits debit correctly.

---

## CRITICAL PATH & PARALLELISM

```
Phase 0 (must-fixes) ──┐
                       ├─► Phase 1 (M2 escrow) ──┐
Phase 2 (AI data) ─────┘                          ├─► Phase 4 (executors) ─► Phase 5 (voice)
Phase 3 (Python svc) ─── (parallel w/ 1 & 2) ─────┘
```

- **Do in parallel:** Phase 2 (AI data, no money) + Phase 3 (Python service) run alongside Phase 1 (escrow).
- **Blocks:** Phase 4 needs Phase 1 (money endpoints exist) AND Phase 3 (Python calls them).
- **Estimate (Vikram, backend only):** ~6–7 weeks, escrow being the long pole. The PRD's 4–5 assumed escrow already existed.

## DEFINITION OF DONE (whole blueprint)
- [ ] Phase 0 must-fixes merged; app won't boot on weak secret; repo under git
- [ ] Escrow core: idempotent, no double-spend, no client-supplied amounts
- [ ] Meera read-only chat live; credits gate before Python; zero PII in context
- [ ] Python service streaming + caching + site analyzer working
- [ ] Internal executors: amounts re-derived, human-confirm for payment, idempotent, tenant-isolated
- [ ] Voice round-trip with text fallback; credit weighting correct
- [ ] **Kabir's 25-row acceptance checklist fully green** before any money endpoint ships
- [ ] Priya sign-off → Swapnil final review
