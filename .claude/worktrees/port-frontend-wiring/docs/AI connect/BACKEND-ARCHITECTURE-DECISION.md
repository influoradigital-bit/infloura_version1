# 🏛️ BACKEND ARCHITECTURE DECISION — Meera AI (Brand Side)

> **Decision by:** Swapnil (CEO), after a structured debate between Priya (CTO), Vikram (Backend), and Kabir (Red-Team).
> **Date:** 2026-07-04 · **Scope:** Brand-side backend for the Meera AI cofounder (M2.5).
> **Status:** DECIDED — this is the ruling. Priya to reflect it in `TECH-STACK.md`; Vikram builds to it; Kabir gates it.

---

## THE QUESTION

Should we keep normal business logic in Spring Boot (Java 21) and put the AI logic in a separate Python microservice over REST — or is there a better option? Plus: how does prompt customization per brand work?

---

## THE DEBATE (one line each)

- **Priya (CTO):** Java-first. Keep money + orchestration together in Spring Boot; add Python only as thin, stateless sidecars for scraping and voice. Don't distribute a pre-revenue feature before scale forces it.
- **Vikram (Backend):** Python microservice is justified — but only with a strict, disciplined boundary. The honest driver is **streaming + voice latency + SDK/prompt-cache maturity**, not "you can't call an LLM from Java." If voice slipped scope, he'd switch to Java-only.
- **Kabir (Red-Team):** The architecture isn't the risk — **the trust model across the wire is.** Polyglot is safe *if and only if* the Python service is treated as a hostile, credential-less renderer that can **propose but never authorize** money.

**Where all three agree (this is the spine of the decision):**
1. Spring Boot is the **single source of truth and the only actor for money/state.** Non-negotiable.
2. **The LLM never authorizes money.** Tool-calls are proposals; Spring re-derives every amount from persisted state and re-authorizes the human's JWT before executing.
3. Money-touching mutations run **inside Spring, in a transaction, idempotently.**

---

## 🏛️ THE RULING

**We adopt a disciplined two-runtime architecture: Spring Boot owns everything that touches money, state, auth, and credits; ONE Python service (FastAPI) handles AI reasoning, token streaming, website scraping, and voice. The Python service is an untrusted reasoner — it proposes, Spring disposes.**

I'm overruling Priya's "Java-only orchestration" on one specific ground: **voice (Sarvam) and token streaming are core, committed UX promises** (Frontend Spec §5A), and both are Python-first — and this team has never made an outbound HTTP call from Java (per Vikram's audit: even MSG91 is a TODO). Building production LLM streaming + resilience + voice in Java, from zero, is the slower and riskier path for M2.5. **But I'm adopting Priya's discipline wholesale:** exactly ONE extra runtime, not three; stateless; no money; graceful degradation. And I'm making Kabir's guardrails hard launch-blockers, not "later."

### What lives where

| Concern | Owner | Why |
|---|---|---|
| Auth / JWT, credit gate + decrement | **Spring Boot** | Single front door; enforced before Python is ever reached |
| Wallet, escrow holds, milestone release, `wallet_transactions` | **Spring Boot** (`@Transactional`) | Money has exactly one owner and one writer |
| Campaign create, creator match, budget calc, credit service | **Spring Boot** | Domain logic that AI *triggers* but does not *own* |
| **Execution** of every money/state tool-call | **Spring Boot**, in-process, idempotent | Claude proposes `create_campaign`/`request_payment`; Spring re-derives amount, re-authorizes human JWT, executes |
| LLM orchestration, prompt assembly, tool-use loop, token streaming | **Python (FastAPI)** | Streaming-native; SDK + prompt-cache ergonomics are Python-first |
| Website scraping (Playwright) + niche/tone classify | **Python** | JVM headless-browser story is weak; stateless, no money |
| Voice STT + TTS (Sarvam, Hinglish) | **Python** | Python-first, latency-sensitive; fails over to text |
| MySQL — sole writer | **Spring Boot only** | Python never touches the DB directly |

### The flow

```
Browser ─(JWT)─► SPRING BOOT ── auth + credit gate + decrement ──┐
   ▲                                                              │ (authorized, sanitized brand context)
   │  direct SSE token stream (short-lived scoped token)          ▼
   └──────────────────────────────────────────────  PYTHON AI SERVICE (FastAPI)
                                                     • assemble prompt (from Spring-supplied context)
                                                     • Claude / Gemini / Sarvam calls + streaming
                                                     • parse tool_use
                                                          │  money/state tool-call?
                                                          ▼
                                   SPRING /internal/meera/* (idempotent, human-JWT re-authorized,
                                   amount re-derived server-side, @Transactional) ──► MySQL
                                                     • scraper + voice = stateless, no DB, no money
```

- **Credits are gated in Spring before Python is called** — a cost attack can't reach the AI service directly.
- **Token streaming can go direct Python→browser** (Spring issues a short-lived, workspace-scoped stream token; Spring already did the auth + credit decrement). Keeps blocking Spring MVC out of the token path.
- **One Python service, not three** — orchestration + scrape + voice in a single runtime to hold ops cost to one extra container. If scraping or voice dies, Meera degrades gracefully (paste-a-link / fall back to text) — no money impact.

---

## PROMPT CUSTOMIZATION PER BRAND

**Spring owns brand context; Python owns prompt shape.** On each turn, Spring assembles a **sanitized** brand-context object (products, niche, tone-profile "register dial", brand color, past-campaign summaries, live credit state) and hands it to Python. **Python never reads MySQL and never receives raw PII.**

**Prompt ordering to exploit Anthropic caching (the ~65% cost lever, PRD §6):**
1. **Stable, cached prefix:** Meera's persona + the 5 tool/function definitions (`cache_control: ephemeral`) — tenant-agnostic, maximum cache-hit.
2. **Per-brand cached block:** brand profile + tone dial + catalog — stable within a session, so it caches across the ~16 turns where cost concentrates.
3. **Volatile suffix (uncached):** conversation history + newest turn.

**Versioning:** prompt templates + tool JSON-schemas are **code, versioned in git** (in the Python service). Store the active `prompt_version` on each `ai_messages` row (`metadata JSON`) so every money-affecting recommendation is auditable to the exact prompt that produced it. Tool definitions live in one place; the Java executor and the schema Claude sees must never drift.

---

## 🔒 KABIR'S GUARDRAILS — LAUNCH BLOCKERS (all mandatory)

1. **LLM never authorizes money.** Tool-calls are proposals. Spring re-derives every amount from persisted state (never accept `amount` from the AI service), re-authorizes the end-user's JWT (on-behalf-of), and `request_payment`/`confirm_launch` produce a **pending action the human confirms** in the Razorpay flow.
2. **Network-isolate the boundary.** Python service + internal Spring money endpoints on a private network, not internet-routable. **mTLS or short-lived signed service tokens (≤5 min, `aud`-scoped)** — NOT a lone static shared key. Internal endpoints reject requests lacking mesh identity.
3. **No PII to prompts.** Field-level allow-list (products, niche, tone, aggregate reach = yes; PAN/KYC/bank/full creator PII = never). Spring strips before Python sees anything. Never log full prompts containing PII. Pin providers to India/approved regions; get a DPA (DPDP/RBI-adjacent).
4. **Tenant isolation is mandatory on every call and cache key.** `workspace_id` in every request and every cache key; cached prefix contains only tenant-agnostic persona/tools; all brand data in the uncached per-tenant suffix. Python is stateless per request. Add a test asserting Brand B's reply never contains Brand A's data.
5. **Credits + rate limits enforced server-side in Spring** before Python is reachable. Rohan's cost meter is a **hard circuit-breaker**, not just a report.
6. **LLM keys never reach the frontend.** Browser calls only Spring; Python holds Claude/Gemini/Sarvam keys in a secrets manager, isolated from Razorpay/DB creds (separate blast radius). Python container: non-root, read-only FS, egress-restricted to approved LLM endpoints + Spring internal only.

### Immediate must-fixes (independent of this decision)
- **Remove client/caller-supplied `amount` from `POST /wallet/escrow/hold`** (`BACKEND-API-SPEC.md:2016-2037`) and stop `INTERNAL_API_KEY` (`:2613`) being the sole authorization on any money endpoint.
- **Enforce a real ≥256-bit JWT secret in prod** — fail startup if the `application.yml:35` dev default is present.
- **Fix the Meera migrations:** PRD `V15/V16` use `BIGINT AUTO_INCREMENT`; the real schema is **ULID `VARCHAR(26)`**. Rewrite Meera FKs to ULID or they won't join `workspaces`/`campaigns`.

---

## SEQUENCING (Vikram's reality check — respect it)

Meera's money tool-calls depend on tables that **don't exist yet** (escrow is M2, unbuilt).

1. **M2 first:** build `wallet_transactions`, `escrow_holds`, milestone release, Razorpay — with **idempotency keys baked in** on every money endpoint.
2. **In parallel (no money, ships visible progress):** `brand_profiles`, website analyzer (Python scraper + Gemini classify), read-only Meera chat, `ai_conversations`/`ai_messages`, `AICreditService`.
3. **After M2 lands:** wire `request_payment`/`confirm_launch` executors + the escrow-event → credit-reset hook.
4. Voice as part of M2.5 per prior decision; if it slips, the text path is fully functional alone.

**Revised backend estimate (Vikram): ~6–7 weeks** for the two-runtime version — the PRD's 4–5 quietly assumed escrow already existed and no integration tax.

---

## WHY THIS IS THE RIGHT CALL (6-months-out)

- **Money stays boring and safe:** one language, one transaction boundary, one auth model for the 90% of the system where correctness is money-critical.
- **AI stays fast to iterate:** prompt tuning and voice live in Python's hot-reload loop, not a Java recompile.
- **The boundary is disciplined, not chatty:** Python proposes, Spring authorizes — the failure mode everyone fears (a non-idempotent bidirectional mess around escrow) is designed out.
- **We keep the option open:** the AI service is already separable, so we can scale or swap providers (Sarvam → other) without touching the money core — and we can even collapse it back into Java later if the ops cost ever outweighs the benefit.

**One-line:** Spring Boot owns the money and the truth; one disciplined Python service does the thinking, the talking, and the scraping — and it is never, ever trusted to move a rupee.
