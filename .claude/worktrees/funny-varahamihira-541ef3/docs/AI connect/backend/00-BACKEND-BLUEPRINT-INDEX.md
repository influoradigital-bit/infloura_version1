# 🏗️ BRAND + AI BACKEND BLUEPRINT — INDEX

> **Owner:** Priya (CTO) · **Date:** 2026-07-05 · **Milestone:** M1 → M2 → M2.5
> **Authority:** Built on `../BACKEND-ARCHITECTURE-DECISION.md` (Swapnil's ruling). Security-gated by Kabir.
> **Purpose:** The complete backend plan for the brand side with the Meera AI cofounder — data model, API contract, security, the Python AI service, and Vikram's build tasks.

---

## THE RULING THIS BLUEPRINT IMPLEMENTS (one paragraph)

**Spring Boot (Java 21) owns everything that touches money, state, auth, and credits. ONE Python (FastAPI) service does AI reasoning, token streaming, website scraping, and voice — stateless, no DB, no money authority. Python *proposes* tool-calls; Spring *disposes* — re-deriving every amount server-side, re-authorizing the human's JWT, executing idempotently in a transaction. The LLM can never move a rupee.** All keys are ULID `VARCHAR(26)` (not `BIGINT`). Escrow (M2) is a hard prerequisite for Meera's money tool-calls (M2.5).

---

## THE BLUEPRINT FILES

| # | File | Owner | What it defines |
|---|------|-------|-----------------|
| 00 | `00-BACKEND-BLUEPRINT-INDEX.md` | Priya | This index — reading order, sequencing, ownership |
| 01 | `01-DATA-MODEL.md` | Vikram | Flyway migrations V8→V14 (ULID), tables, entities, repositories, idempotency |
| 02 | `02-API-CONTRACT-BRAND.md` | Vikram | Public brand endpoints + internal `/internal/meera/*` executors + streaming design |
| 03 | `03-SECURITY-SPEC.md` | Kabir | Threat model, 6 guardrails + red-team tests, immediate must-fixes, 25-row acceptance checklist |
| 04 | `04-AI-SERVICE-SPEC.md` | Vikram | Python FastAPI service: prompt assembly/caching, function-call loop, streaming, voice, providers |
| 05 | `05-VIKRAM-WORK-TASKS.md` | Priya | The sequenced, dependency-ordered build task list for Vikram |
| 06 | `06-MEERA-PERMISSIONS-MATRIX.md` | Kabir | What Meera may/may not do (R/D/C/Forbidden tiers) — the tool-call contract |
| 07 | `07-NOTIFICATION-SYSTEM-SPEC.md` | Priya | 26-event brand↔creator email + in-app notification system, outbox pattern |
| 08 | `08-CODEBASE-INVENTORY.md` | Tara | What already exists in `influora-api` (so the manifest counts only NEW files) |
| 09 | `09-ADVANCED-SECURITY-MEASURES.md` | Kabir | Defense-in-depth controls per layer + 24 mandatory security files |
| 10 | `10-VIKRAM-FILE-MANIFEST.md` | Priya | **Every file to create (~140), counted, with path/purpose/methods/security refs** |
| 11 | `11-AI-FLOW-DETAILED.md` | Priya | End-to-end Meera request lifecycle, every hop + security checkpoint |

**Reading order for Vikram:** 00 → 08 (what exists) → 03 + 09 (security, constrains everything) → 06 (AI permissions) → 01 → 02 → 04 → 11 (AI flow) → 07 → 10 (file manifest) → 05 (task sequence).

**⚠️ Phase 0 blocker (Tara):** `application.yml` and `SecurityConfig.java` are truncated on disk — `SecurityConfig` won't compile. Restore from git before any build. See `10` Phase 0.

---

## SYSTEM SHAPE (one diagram)

```
Browser ─(JWT)─► SPRING BOOT (money/state/auth/credits, single source of truth)
   ▲                 │ auth + credit gate + decrement + mint short-lived stream token
   │  direct SSE      ▼        + assemble SANITIZED brand context (no PII)
   └──────────  PYTHON AI SERVICE (FastAPI, stateless, no DB, no money)
                      • Claude Sonnet chat (prompt caching) · Gemini Flash scrape/prescreen · Sarvam voice
                      • parses tool_use  ──► money/state tool? ──► SPRING /internal/meera/* (idempotent,
                        human-JWT re-authorized, amount re-derived, @Transactional) ──► MySQL
```

---

## MIGRATION MAP (from 01-DATA-MODEL.md)

| Migration | Tables | Milestone | Money? |
|---|---|---|---|
| V8 | `wallet_transactions` (double-entry ledger) | M2 | ✅ |
| V9 | `escrow_holds` (funding = "go live", fires credit-reset) | M2 | ✅ |
| V10 | `contracts`, `payment_milestones` | M2 | ✅ |
| V11 | `brand_profiles` (website analysis output) | M2.5 | ❌ |
| V12 | `ai_conversations`, `ai_messages` | M2.5 | ❌ |
| V13 | `campaign_intents` | M2.5 | ❌ |
| V14 | `brand_ai_credits`, `meera_tool_calls` (idempotency) | M2.5 | ❌ |

**Key insight:** V11–V14 have no money dependency → build in parallel with M2. V8–V10 (escrow) gate Meera's `request_payment`/`confirm_launch`.

---

## SECURITY GATES (from 03-SECURITY-SPEC.md) — non-negotiable

The 6 guardrails (G1–G6) and 4 immediate must-fixes (MF-1…MF-4) are launch-blocking. Headline:
- **G1** LLM never authorizes money — amounts re-derived server-side; `request_payment` → PENDING human-confirm.
- **G2** Network-isolate + short-lived signed service tokens (NOT a static shared key).
- **G3** No PII to prompts — field allow-list; Spring strips before Python.
- **G4** Tenant isolation on every call + every cache key (Brand-A-can't-leak-to-Brand-B test under concurrency).
- **G5** Credits/rate limits enforced in Spring BEFORE Python is reachable.
- **G6** LLM keys never reach frontend; secrets segregated by blast radius.

**Must-fix now (live-code defects):** MF-3 JWT dev-default secret has no startup guard; `JwtAuthenticationFilter` silently swallows JWT exceptions. MF-1/MF-2/MF-4 are build contracts for the not-yet-built money endpoints.

---

## OWNERSHIP

| Layer | Owner |
|---|---|
| Spring Boot money/domain core, all DB writes | Vikram |
| Python AI service | Vikram (+ AI provider integration) |
| Security sign-off (gate) | Kabir |
| Architecture / this blueprint | Priya |
| Business decisions, final review | Swapnil |

Pipeline per feature: Vikram build → Kavya QA → Meera/DevOps build+verify → **Kabir security gate** → Priya sign-off → Swapnil.
