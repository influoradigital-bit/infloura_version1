# AI Code Blueprint (Meera + TrendSpark)

> The Python AI service — how Meera reasons, calls tools, and stays safe. Sourced from code.
> Lead: Ash (AI/ML).

The AI service (`/influora-ai`, FastAPI, 34 files, ~4,902 LOC) is a **reasoner** that lets brands run campaigns in natural language ("Meera") and powers TrendSpark nudges + brand-safety checks. It never has its own users — Spring brokers every call.

---

## 1. Structure

```
app/main.py                 FastAPI app + routes registration
app/config.py               all env-driven settings
app/routes/
  chat.py                   Meera conversational endpoint (tool loop)
  trendspark.py             trend nudge generation
  brand_safety.py           caption / content safety scoring
  analyze_site.py           brand site analysis (SSRF-guarded fetch)
  voice.py                  voice input
app/providers/
  claude.py                 Anthropic Claude
  gemini.py                 Google Gemini
  sarvam.py                 Sarvam (India-focused)
app/tools/
  loop.py                   agentic tool-calling loop
  schemas.py                tool JSON schemas
app/prompt/
  persona.py, assembler.py  system-prompt assembly
  brand_safety.py, trendspark.py
  untrusted.py              isolates untrusted user input in prompts
app/clients/spring.py       signed callbacks to Spring internal API
app/auth/
  service_token.py          validates Spring-minted tokens (FIRST gate)
  service_token_minter.py
app/security/
  ssrf_guard.py             blocks internal-network fetches
  redaction.py              strips PII before logging/model calls
app/costs/
  gate.py, pricing.py, spend_tracker.py   per-workspace budget enforcement
```

### Endpoints (6)
`POST /chat`, `POST /trendspark`, `POST /brand_safety`, `POST /analyze_site`, `POST /voice`, `GET /health` (+ readiness).

---

## 2. Providers & models

Multi-provider with fallback: **Claude** (`CLAUDE_MODEL`), **Gemini** (`GEMINI_MODEL`), **Sarvam** (`SARVAM_API_KEY`). Region-gated via `APPROVED_LLM_REGIONS`. TrendSpark uses `TRENDSPARK_MODEL` + `TRENDSPARK_PERSONA_NAME`.

---

## 3. The Meera tool loop (how AI runs a campaign)

Meera can drive the real platform through tools that hit `MeeraInternalController` (`/api/v1/internal/meera`):

| Tool | Backend action | Note |
|---|---|---|
| `show_creators` | discovery search | returns candidate creators |
| `calculate_budget` | budget estimate | pricing math |
| `create_campaign` | creates a real campaign | writes to DB |
| `request_payment` | proposes escrow funding | **only ever returns `PENDING_CONFIRM`** — never moves money |
| `confirm_launch` | launches after human confirm | gated |
| `messages` | persists assistant messages | conversation state |

**Human-in-the-loop money rule:** `request_payment` cannot release funds; a human must confirm. This is enforced server-side, not just in the prompt.

Loop control: `TOOL_LOOP_MAX_ITERATIONS` caps runaway loops.

---

## 4. Security & cost controls (Ash + Kabir)

**Auth — first gate on every non-health call (`app/auth/service_token.py`):**
- Two token shapes, both minted by Spring, never by the browser:
  - **Service token** — `aud=influora-internal`, TTL ≤ 5 min.
  - **Scoped stream token** — `scope=chat:stream`, single `workspace_id`.
- Checks: token valid → `workspace_id` matches body → scope matches endpoint. Any failure → 401/403, **no provider call, no token spend.**

**SSRF guard (`security/ssrf_guard.py`):** `analyze_site` cannot fetch internal/private ranges; `SSRF_FETCH_TIMEOUT_SECONDS`, `SSRF_MAX_REDIRECTS`, `SSRF_MAX_RESPONSE_BYTES`.

**PII redaction (`security/redaction.py`):** strips PII before model calls / logs.

**Untrusted-input isolation (`prompt/untrusted.py`):** user text is wrapped so it can't override system instructions (prompt-injection defense).

**Cost gate (`costs/`):** per-workspace soft cap (`AI_WORKSPACE_DAILY_SOFT_CAP_USD`), daily ceiling (`AI_DAILY_SPEND_CEILING_USD`), and a kill switch (`AI_SPEND_KILL_SWITCH`). Fail-closed on budget.

---

## 5. Spring-side AI integration

- `MeeraController` `/meera` — public (JWT) session/credits/brand-profile.
- `MeeraInternalController` `/internal/meera` — service-token only, the tool endpoints above.
- `integration/ai/` — `BrandSafetyAiClient`, `TrendSparkAiClient` (Spring → AI).
- Entities: `AiConversation`, `AiMessage`, `MeeraToolCall`, `BrandAiCredit`, `Trend`, `SnapsbyCatalogVideo`.
- Config: `MeeraStreamProperties`, `BrandSafetyAiProperties`, `TrendSparkAiProperties`, `InternalServiceTokenProperties`.

See `08-USER-GUIDE-CAMPAIGN-BID-AI.md` for the brand-facing "how to use AI" walkthrough.
