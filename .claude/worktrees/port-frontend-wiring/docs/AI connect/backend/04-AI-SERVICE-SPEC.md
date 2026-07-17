# 04 — PYTHON AI SERVICE SPEC (Meera Reasoner)

> **Author:** Vikram (Backend) · **Date:** 2026-07-05 · **Milestone:** M2.5
> **Status:** BUILD SPEC — implements the ruling in `BACKEND-ARCHITECTURE-DECISION.md`.
> **Companions:** `02-API-CONTRACT-BRAND.md` (Spring `/internal/meera/*` executors — parallel agent), `03-*` (Kabir security spec), `PRD-MEERA-AI-COFOUNDER.md`, `FRONTEND-BUILD-SPEC-MEERA.md` §5A (voice).

**The one-line contract this service lives by:** *Python proposes, Spring disposes.* This runtime reasons, streams, scrapes, and talks. It never reads MySQL, never sees raw PII, never holds a money key, and never authorizes a rupee. Spring gates auth + credits **before** any request reaches here.

---

## 1. SERVICE OVERVIEW

### Runtime
| Item | Choice |
|---|---|
| Language / runtime | **Python 3.12** |
| Framework | **FastAPI** + Uvicorn (ASGI), single async worker pool |
| HTTP client | `httpx` (async, HTTP/2, per-provider timeouts) |
| Streaming | native SSE via `StreamingResponse` (`text/event-stream`) |
| Containerization | one Docker image, **non-root, read-only FS**, egress-restricted to approved LLM endpoints + Spring internal (Kabir guardrail #6) |
| State | **stateless per request** — no DB, no session store, no local disk writes. Conversation history arrives in the request body from Spring. |
| Scaling | horizontal; any replica can serve any turn because nothing is sticky |

**One runtime, three capabilities** (per the ruling — one extra container, not three):
1. **Chat orchestration** — Claude Sonnet + prompt caching + the 5-tool function-calling loop.
2. **Website analyzer** — Playwright (headless Chromium) scrape → Gemini 2.0 Flash classify (niche, tone dial, catalog).
3. **Voice** — Sarvam STT (Hinglish) + grammar-cleanup + Sarvam TTS, cascaded with Claude as the brain.

If the scraper or voice subsystem dies, chat degrades gracefully (paste-a-link / fall back to text). No money impact — those paths touch no money.

### Endpoints

All endpoints require a valid **Spring-issued scoped service token** (§1.1). All carry `workspace_id` in the body and in the auth token's `aud`/claims; the two must match or the request is rejected (tenant isolation, Kabir guardrail #4).

| Method | Path | Purpose | Response |
|---|---|---|---|
| `POST` | `/chat` | Main Meera turn. Runs the function-calling loop, streams tokens. | **SSE** (`text/event-stream`) |
| `POST` | `/analyze-site` | Scrape + classify a brand website. Called by Spring's async onboarding job. | JSON (structured brand profile) |
| `POST` | `/voice/transcribe` | Audio → Hinglish STT → grammar-cleaned English (edit-first). | JSON `{ raw_transcript, cleaned_text, lang_detected }` |
| `POST` | `/voice/speak` | Text → Sarvam TTS audio. | audio stream (`audio/mpeg` or `audio/wav`) or SSE audio chunks |
| `GET`  | `/healthz` | Liveness (no auth) — process up. | JSON |
| `GET`  | `/readyz` | Readiness (no auth) — providers reachable, keys loaded. | JSON |

> `/chat` may also be exposed as `POST /chat` returning SSE where the browser connects **directly** (Python→browser) using the short-lived scoped stream token Spring issued after it did auth + credit decrement. Spring stays out of the token path (see §4).

### 1.1 Auth — validate the Spring-issued scoped token on every call

The browser **never** calls Python with a user JWT. Two token shapes reach Python, both minted by Spring:

- **Service token (server-to-server)** for `/analyze-site`, `/voice/*`, and Spring-proxied `/chat`: short-lived signed JWT (**≤5 min TTL**, `aud`-scoped to this service), or mTLS mesh identity. NOT a lone static shared key (Kabir guardrail #2).
- **Scoped stream token (browser→Python `/chat` direct SSE)**: short-lived, single-`workspace_id`, single-conversation, `scope=chat:stream`. Issued by Spring **after** it authenticated the user JWT and decremented credits.

Validation on **every** request, before any provider call:
1. Verify signature against Spring's rotating JWKS (cache JWKS, honor `kid`); reject expired / wrong `aud` / wrong `iss`.
2. Assert `token.workspace_id == body.workspace_id`. Mismatch → `403`.
3. Assert scope matches the endpoint (`chat:stream` cannot call `/analyze-site`).
4. On any failure → `401/403`, structured error, **no provider call, no token spend**.

Python holds **no** authority to mint tokens and **no** money/DB credentials. Its only secrets are the Claude / Gemini / Sarvam API keys, in a secrets manager, blast-radius-isolated from Razorpay/DB creds.

---

## 2. PROMPT ARCHITECTURE / PER-BRAND CUSTOMIZATION

**Spring owns brand context; Python owns prompt shape.** On each `/chat` turn, Spring sends a **sanitized** brand-context object. Python assembles it into the layered prompt below and calls Claude. Python never reads MySQL and never receives raw PII.

### The sanitized brand-context object (from Spring)
Field-level **allow-list** (Kabir guardrail #3) — Spring strips everything else before Python sees it:

```jsonc
{
  "workspace_id": "01J...ULID",
  "prompt_version": "meera-2026.07.05",     // Spring echoes the active version it expects
  "brand": {
    "display_name": "BeautyByPriya",         // greeting name only, not full PII
    "niche_tags": ["skincare", "d2c-beauty"],
    "tone_dial": { "formality": 0.3, "energy": 0.8, "emoji_ok": true,
                   "cultural_context": "festive-north-india" },
    "brand_color": "#C2185B",
    "product_catalog": [                      // sanitized: names + price bands, no inventory/PII
      { "name": "Vitamin C Brightening Serum", "price": 899, "currency": "INR" }
    ],
    "past_campaign_summary": "Last campaign: 10 creators, ~45K reach, skincare."
  },
  "credit_state": { "mode": "unlimited|metered|paused", "credits_remaining": 84 },
  "conversation": [                           // full history, replayed each turn (stateless)
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "...", "tool_calls": [...] }
  ]
}
```

**Forbidden fields (Spring never sends, Python never asks for):** PAN, KYC, bank/UPI, full creator PII, wallet balances, escrow internals, raw addresses. Aggregate reach = allowed.

### Prompt layering for Anthropic prompt caching (the ~65% cost lever, PRD §6)

Three ordered blocks, so the expensive prefix is cached across the ~16 turns where cost concentrates:

```
┌── BLOCK A · STABLE PREFIX (cache_control: ephemeral) ─────────────┐
│  • Meera persona (sharp, warm, honest, never pushy)              │
│  • The 5 tool / function JSON-schemas (§3)                       │
│  • Global rails: sentence-case, contractions, no "!", verb-first │
│    CTAs, never claim to move money — money tools are proposals   │
│  TENANT-AGNOSTIC. Identical for every brand → maximum cache hit. │
└──────────────────────────────────────────────────────────────────┘
┌── BLOCK B · PER-BRAND CACHED (cache_control: ephemeral) ─────────┐
│  • brand profile + tone_dial "register dial" + product_catalog  │
│  • past_campaign_summary + brand_color + credit_state summary   │
│  Stable within a session → caches across the conversation's      │
│  ~16 turns. Keyed per workspace_id.                             │
└──────────────────────────────────────────────────────────────────┘
┌── BLOCK C · VOLATILE SUFFIX (uncached) ─────────────────────────┐
│  • conversation history + newest user turn                      │
└──────────────────────────────────────────────────────────────────┘
```

**Cache-key discipline (Kabir guardrail #4):** the cached prefix (Block A) contains **only** tenant-agnostic persona/tools. **All** brand data sits in Block B / C keyed by `workspace_id`. A regression test asserts Brand B's reply never contains Brand A's data. Python is stateless per request; caching is Anthropic-side, keyed by content + our `workspace_id` marker.

### Prompt & tool-schema versioning
- Persona text + the 5 tool JSON-schemas are **code, versioned in git**, in this service (`app/prompts/` + `app/tools/`).
- A single `PROMPT_VERSION` constant (e.g. `meera-2026.07.05`) identifies the active template set.
- Python stamps `prompt_version` on **every** message it returns; Spring persists it on the `ai_messages.metadata` row so every money-affecting recommendation is auditable to the exact prompt that produced it.
- **Tool definitions live in exactly one place.** The schema Claude sees here and the Spring executor's expected shape must never drift — CI diff-checks the shared schema (§7).

---

## 3. FUNCTION-CALLING LOOP

Meera calls typed functions rather than parsing intent from free text (PRD §5). **Python holds NO money authority** — money tools (`create_campaign`, `request_payment`, `confirm_launch`) are *proposals* forwarded to Spring, which re-derives every amount and re-authorizes the human JWT before executing.

### The 5 tool definitions (JSON-schema, Block A)

```jsonc
[
  {
    "name": "show_creators",
    "description": "Render matched creators in the canvas. Read-only, no money.",
    "input_schema": {
      "type": "object",
      "properties": {
        "niche": { "type": "string" },
        "count": { "type": "integer", "minimum": 1, "maximum": 100 },
        "city":  { "type": "string", "description": "optional city filter" }
      },
      "required": ["niche", "count"]
    }
  },
  {
    "name": "calculate_budget",
    "description": "Suggest pool + per-reel rate from product price and goal. Read-only, no money.",
    "input_schema": {
      "type": "object",
      "properties": {
        "product_price": { "type": "number" },
        "goal": { "type": "string", "enum": ["awareness","launch","conversion","review"] }
      },
      "required": ["product_price", "goal"]
    }
  },
  {
    "name": "create_campaign",
    "description": "PROPOSE building a campaign from conversation intent. Spring re-derives all amounts and re-authorizes before creating anything.",
    "input_schema": {
      "type": "object",
      "properties": {
        "product_name": { "type": "string" },
        "campaign_type": { "type": "string", "enum": ["HYPE","DIRECT","REVIEW"] },
        "creator_count": { "type": "integer" },
        "creator_ids": { "type": "array", "items": { "type": "string" } }
      },
      "required": ["product_name", "campaign_type", "creator_count"]
    }
  },
  {
    "name": "request_payment",
    "description": "PROPOSE a payment. NEVER authoritative. Spring re-derives the amount server-side from persisted state; the human confirms in Razorpay. The AI-supplied amount (if any) is advisory display text only and is discarded by Spring.",
    "input_schema": {
      "type": "object",
      "properties": {
        "campaign_intent_id": { "type": "string" },
        "display_amount_hint": { "type": "number", "description": "for chat copy only; Spring ignores it for authorization" }
      },
      "required": ["campaign_intent_id"]
    }
  },
  {
    "name": "confirm_launch",
    "description": "PROPOSE launching (send creator invites) once escrow is funded. Spring verifies escrow state before acting; produces a pending action the human confirms.",
    "input_schema": {
      "type": "object",
      "properties": { "campaign_intent_id": { "type": "string" } },
      "required": ["campaign_intent_id"]
    }
  }
]
```

### The loop

```
Claude returns tool_use(name, input)
        │
        ▼
Python maps name → Spring endpoint (02-API-CONTRACT-BRAND.md):
   show_creators     → POST /internal/meera/show-creators      (read)
   calculate_budget  → POST /internal/meera/calculate-budget   (read)
   create_campaign   → POST /internal/meera/create-campaign    (money/state, idempotent)
   request_payment   → POST /internal/meera/request-payment    (money, proposal→pending)
   confirm_launch    → POST /internal/meera/confirm-launch      (money/state, idempotent)
        │
        │  forward with: service token, workspace_id, on-behalf-of user context,
        │  idempotency_key (Python generates a stable key per tool_use id),
        │  and the tool input AS-PROPOSED (no amounts trusted)
        ▼
Spring: re-derive amount from persisted state · re-authorize human JWT (on-behalf-of)
        · @Transactional execute (or return a human-confirm pending action) · idempotent
        ▼
tool_result (JSON) ──► Python appends as tool_result, continues Claude
        ▼
Claude produces next text / next tool_use → repeat until final assistant text
        ▼
stream final text tokens to browser
```

**Hard rules encoded in the loop:**
- Python **never** reads `amount` from Claude and forwards it as authoritative. `request_payment` amounts are display hints only; Spring re-derives (Kabir guardrail #1).
- Every money/state forward carries an **idempotency key** derived from the Claude `tool_use.id` + `workspace_id`, so a retried stream can't double-execute.
- If Spring returns a `pending-human-confirm` result (Razorpay flow for `request_payment`/`confirm_launch`), Python surfaces that as canvas state + chat copy — it does **not** loop to "done" on its own.
- Loop guard: cap tool-use iterations per turn (e.g. 6) to prevent runaway; on cap, return a graceful "let's confirm this step" message.

---

## 4. STREAMING (SSE)

`/chat` streams Server-Sent Events. Partial assistant text streams to the browser **while** tool-calls resolve, so Meera never shows a blank spinner (T3 "shows her work").

### Direct Python→browser SSE
Per the ruling: the browser opens the SSE connection to Python directly, presenting the **short-lived scoped stream token** Spring already issued (Spring did auth + credit decrement first). This keeps blocking Spring MVC out of the token path.

### Event protocol (SSE `event:` types)
```
event: token        data: {"text": "Got it — Vitamin C"}          // incremental assistant text
event: thinking     data: {"step": "Scanning 300 creators", "done": false}   // T3 log line
event: tool_start   data: {"name": "show_creators", "input": {...}}          // canvas glue
event: tool_result  data: {"name": "show_creators", "status": "ok"}          // stage advance
event: prompt_meta  data: {"prompt_version": "meera-2026.07.05"}
event: done         data: {"finish_reason": "stop"}
event: error        data: {"code": "provider_timeout", "fallback": "text"}
```

- During a tool round-trip, Python emits `thinking` lines (the "Scanning → Filtering → Ranking → Done" log) and `tool_start`/`tool_result` so the frontend drives Living-Canvas stage transitions from the same stream.
- Text before and after a tool call both stream as `token` events — the user reads Meera's lead-in while `show_creators` resolves against Spring.
- Heartbeat comment (`: ping`) every ~15s to keep the connection warm through proxies.
- On client disconnect, Python cancels in-flight provider calls (async cancellation) — no wasted tokens.

### Latency targets
| Metric | Target |
|---|---|
| Time-to-first-token (chat, cache hit) | **≤ 1.2 s p50 / ≤ 2.5 s p95** |
| Inter-token cadence | smooth, no >2 s stalls except during a tool round-trip |
| Tool round-trip (Python→Spring read tool) | ≤ 600 ms p95 |
| `/analyze-site` end-to-end | ≤ 45 s (async job; PRD says 30–60 s) |
| Voice STT (`/voice/transcribe`) | ≤ 2.5 s for ≤30 s clip |
| Voice TTS first-audio | ≤ 1.5 s |

---

## 5. VOICE PIPELINE — cascaded Claude (brain) + Sarvam (ears/mouth)

Per Frontend Spec §5A: **voice-first with a text safety net.** Claude is always the brain; Sarvam is only ears (STT) and mouth (TTS). Any voice failure → silent fallback to text, zero dead ends.

### Input flow (`/voice/transcribe`, edit-first)
```
audio (Hinglish, tap-and-talk)
   → Sarvam STT (Hinglish / mixed Hindi-English aware)   → raw_transcript
   → grammar-cleanup pass (light Claude/Gemini-Flash pass, or Sarvam's normalized output)
        · fixes grammar + clarity ONLY, never reinterprets intent
        · "10 creator Mumbai serum promote" → "Promote the serum with 10 Mumbai creators"
   → return { raw_transcript, cleaned_text, lang_detected }
   → frontend shows cleaned_text in the composer EDIT-FIRST (user tweaks, then sends)
```
The cleaned text is **not** auto-sent. It lands in the composer for the user to confirm — the trust rule. Only when the user hits send does a normal `/chat` turn run.

### Reply flow (optional TTS, `/voice/speak`)
```
Claude reply text  → (if voice output enabled)  Sarvam TTS  → audio stream
   · text renders in chat IMMEDIATELY; audio plays alongside if enabled and working
   · never block a reply on audio
```

### Graceful fallback (mandatory, every stage)
| Failure | Behavior |
|---|---|
| Mic / audio unsupported, permission denied | stay on text composer, no error wall |
| Sarvam STT fails / low confidence | `"Didn't catch that — type it instead?"` → text |
| Grammar-cleanup pass errors | fall back to `raw_transcript` in the composer (still edit-first) |
| Sarvam TTS fails | disable voice-output silently, text reply already rendered |
| Any provider timeout | SSE `error` event with `fallback:"text"`; UI continues in text |

Text is always fully functional on its own; voice is an enhancement layered on top.

### Credit weighting (PRD §7 / Frontend §5A cost note)
Voice actions spend credits like any other Meera action, weighted for the extra STT/TTS steps:
| Action | Credit cost |
|---|---|
| Text chat exchange | 1 |
| **Voice input** (STT + cleanup) | **3** |
| **Voice reply** (TTS) | **4** |
| Website analysis | 10 |

**Spring meters and gates these credits BEFORE calling Python** (§6). Python computes nothing about wallets/credits — it only performs the requested work if the token that arrived is valid.

---

## 6. PROVIDER ROUTING & COST CONTROLS

### Routing
| Workload | Provider | Why |
|---|---|---|
| Meera chat / cofounder feel | **Claude Sonnet + prompt caching** | quality matters; caching is the ~65% cost lever |
| Website analysis + niche/tone classify | **Gemini 2.0 Flash** | bulk, cheap, quality-tolerant (PRD §6) |
| Creator-side deliverable pre-screen (later, M3) | **Gemini 2.0 Flash** | volume 100–500 reels/campaign |
| Voice STT / TTS (Hinglish) | **Sarvam** | Python-first, latency-sensitive, India-region |

Grammar-cleanup pass may run on Gemini Flash (cheap) or a tiny Claude call — configurable, defaults to Flash.

### Resilience (per provider, in `httpx`)
| Control | Setting |
|---|---|
| Connect / read timeouts | tight, per provider (e.g. chat first-token 8 s, tool round-trip 5 s, STT 10 s, scrape 30 s) |
| Retries | idempotent GET-like calls only; exponential backoff, max 2, jitter. **No blind retry on money-tool forwards** — those are idempotency-keyed and Spring owns dedupe. |
| Circuit breaker | per provider; open on sustained failures → serve degraded path (scrape down → "paste a link"; voice down → text; chat provider down → surfaced error, credits **not** consumed) |
| Cancellation | client disconnect cancels in-flight LLM calls |

### The credit gate rule (non-negotiable)
**Spring gates credits BEFORE calling Python.** A cost attack cannot reach this AI service directly — the only entry is a Spring-minted scoped token issued after Spring authenticated the user and decremented/authorized credits. Rohan's cost meter is a hard circuit-breaker in Spring, not a report. Python **assumes** credits are already gated and does not re-check wallets (it has no DB access to do so).

### Cost hygiene inside Python
- Maximize cache hits: never let per-turn dynamic data leak into Block A/B ordering.
- Cap output tokens per turn; cap tool-use iterations (§3).
- Emit token-usage + cache-hit-ratio metrics per turn (no PII) for Rohan's model.

---

## 7. CONFIG, OBSERVABILITY & BUILD OUTLINE

### Model / prompt config
```
app/
  config.py            # provider models, timeouts, retry/breaker params, PROMPT_VERSION
  prompts/
    persona.md         # Block A persona (versioned)
  tools/
    schema.py          # the 5 tool JSON-schemas — SINGLE SOURCE, shared-checked vs Spring
  chat/loop.py         # function-calling loop + SSE emitter
  analyze/site.py      # Playwright + Gemini Flash classify
  voice/stt.py voice/tts.py voice/cleanup.py
  auth/token.py        # Spring JWKS validation, workspace_id assert, scope check
  providers/           # claude.py gemini.py sarvam.py (httpx clients + breakers)
```
- `PROMPT_VERSION` bumped on any persona/tool-schema change; stamped on every message (§2).
- Provider models pinned by version string in `config.py`; India/approved regions only (Kabir guardrail #3).

### Observability — **no PII in logs**
- Structured JSON logs keyed by `workspace_id` + `request_id` + `prompt_version`.
- **Never log full prompts, brand catalog contents, transcripts, or audio.** Log shapes/lengths/counts, not values (Kabir guardrail #3).
- Metrics: TTFT, inter-token latency, tool round-trip latency, cache-hit ratio, token usage, provider error rates, circuit-breaker state, per-endpoint p50/p95.
- Traces span Python→Spring `/internal/meera/*` calls with `request_id` correlation.
- Redaction filter on the logging pipeline as a backstop.

### Build task outline
1. **Skeleton** — FastAPI app, Docker (non-root, read-only FS, egress allow-list), `/healthz` `/readyz`, config loader, secrets-manager wiring for LLM keys.
2. **Auth** — Spring JWKS validation, `workspace_id`/scope asserts, reject paths. Tests for expired/wrong-aud/tenant-mismatch.
3. **Prompt assembly** — Block A/B/C layering + `cache_control`, `PROMPT_VERSION` stamping. Tenant-isolation regression test (Brand B never sees Brand A data).
4. **Chat loop + SSE** — Claude Sonnet call, 5-tool loop, Spring `/internal/meera/*` forwarding with idempotency keys, SSE event protocol, TTFT target.
5. **Website analyzer** — Playwright scrape + Gemini Flash classify → structured brand profile; scrape-fail → graceful signal.
6. **Voice** — Sarvam STT (Hinglish), grammar-cleanup (meaning-preserving), edit-first response; Sarvam TTS; fallbacks at every stage; credit weighting surfaced to Spring's meter.
7. **Resilience** — per-provider timeouts, retries, circuit breakers, cancellation.
8. **Observability** — no-PII structured logs, metrics, traces, redaction backstop.
9. **Shared-schema CI check** — diff the tool JSON-schema here against Spring's executor contract (`02-API-CONTRACT-BRAND.md`) so they never drift.
10. **Hardening / Kabir gate** — verify guardrails #1–#6, then QA (Kavya) → build verify (Meera/DevOps) → security (Kabir) → Priya sign-off.

---

## APPENDIX — INVARIANTS (do not violate)
1. Python **never** reads MySQL, **never** sees raw PII, **never** holds a money/DB key.
2. LLM tool-calls are **proposals**; Spring re-derives amounts and re-authorizes the human JWT (Kabir #1).
3. Every call carries a valid Spring-issued scoped token; `workspace_id` matches token and body (Kabir #2, #4).
4. Cached prompt prefix is tenant-agnostic; all brand data lives in the per-tenant suffix (Kabir #4).
5. No full prompts / transcripts / PII in logs (Kabir #3).
6. Spring gates auth + credits **before** Python is reachable (Kabir #5).
7. Voice failure → silent text fallback, always. Text path stands alone.
