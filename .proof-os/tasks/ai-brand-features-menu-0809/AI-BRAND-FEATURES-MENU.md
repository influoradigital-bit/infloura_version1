# Influora — AI Features & Menus for Brands

> Source files read: `src/components/brand/brand-layout.tsx` · `influora-ai/app/routes/chat.py` · `influora-ai/app/routes/brand_safety.py` · `influora-ai/app/routes/creator_suggestion.py` · `influora-ai/app/routes/analyze_site.py` · `influora-ai/app/routes/trendspark.py` · `influora-ai/app/routes/voice.py` · `influora-ai/app/tools/schemas.py`
> Date: 2026-08-09

---

## 1 · Brand Sidebar Navigation — All Menus

| # | Menu Label | Route | Group | AI-Powered? |
|---|-----------|-------|-------|-------------|
| 1 | Home | `/brand/dashboard` | Main | No |
| 2 | **Meera** | `/brand/meera` | Main | ✅ Yes — AI copilot (Claude SSE stream) |
| 3 | Campaigns | `/brand/campaigns` | Main | Partial — AI can draft via Meera |
| 4 | Creators | `/brand/discover` | Main | Partial — AI scores/filters via Brand Safety |
| 5 | Deals | `/brand/deals` | Main | No |
| 6 | Messages | `/brand/messages` | Main | No |
| 7 | Wallet | `/brand/wallet` | Main | No |
| 8 | Pipeline | `/brand/pipeline` | Manage | No |
| 9 | Contracts | `/brand/contracts` | Manage | No |
| 10 | Analytics | `/brand/analytics` | Manage | Partial — AI surfaces via `get_campaign_performance` tool |
| 11 | Reviews | `/brand/reviews` | Manage | No |
| 12 | Disputes | `/brand/disputes` | Manage | No |

> Icon `Sparkles` is used for Meera in the sidebar — the only nav item with an AI-specific icon.
> Source: `brand-layout.tsx:97`

---

## 2 · AI Features Used by Brands

| Feature | Endpoint | AI Model | Triggered From | What It Does |
|---------|----------|----------|----------------|--------------|
| **Meera Chat** | `POST /chat` | Claude (Sonnet-class, streaming SSE) | `/brand/meera` page | Brand conversational copilot — answers questions, runs tools, creates campaigns, requests payments. Heartbeat every 15 s; on client disconnect provider call is cancelled (no wasted tokens). |
| **Voice Transcribe** | `POST /voice/transcribe` | Sarvam STT → Gemini grammar cleanup | Within Meera chat | Converts brand voice input to text (edit-first — lands in composer, never auto-sent). Falls back to text prompt if Sarvam fails. |
| **Voice Speak** | `POST /voice/speak` | Sarvam TTS (≤ 500 char cap) | Within Meera chat | Reads Meera's reply aloud. Silently disabled on failure. |
| **Analyze Site** | `POST /analyze-site` | Gemini (GEMINI_MODEL) | Brand onboarding (async Spring job) **and** Meera's in-process `analyze_site` tool (when brand pastes a product URL) | Scrapes brand website, strips active content, extracts product name/price/currency via schema.org/OpenGraph. Returns `niche_tags`, `tone_dial`, `brand_color`, `product_catalog`. SSRF-guarded. Results feed Meera's brand context. |
| **TrendSpark Tagger** | `POST /internal/trendspark/tag` | Claude (TREND_TAG_MODEL) | n8n pipeline (not brand-direct, but feeds brand nudges) | LLM Recovery Tagger — tags trend text when the primary rule-based tagger has insufficient signal. Feeds the trend catalog that TrendSpark Nudge draws from. |
| **Brand Safety** | `POST /internal/brand-safety` | Claude (BRAND_SAFETY_MODEL) | Creator discovery / background scoring | Classifies creator content captions using GARM taxonomy + sentiment. Output shown to brands evaluating creators in `/brand/discover`. Internal-only; called by Java `BrandSafetyAiClient`. |
| **TrendSpark Nudge** | `POST /internal/trendspark/nudge` | Claude (TRENDSPARK_MODEL — Haiku-class) | Campaign manager nudge UI | Generates a short AI phrasing for a trend-matched campaign nudge sent to the brand's workspace. Deterministic fallback on any failure — always HTTP 200. |

---

## 3 · Meera AI Tools (what Meera can do on behalf of a Brand)

| # | Tool Name | Tier | Spring Executor | What It Does |
|---|-----------|------|----------------|--------------|
| 1 | `show_creators` | Read (R) | `/internal/meera/show_creators` | Finds and renders creators by niche + optional city. Read-only, no money. |
| 2 | `calculate_budget` | Read (R) | `/internal/meera/calculate_budget` | Suggests campaign pool + per-reel rate from product price + goal. Read-only, no money. |
| 3 | `create_campaign` | Draft (D) | `/internal/meera/create_campaign` | Proposes a campaign draft (Spring re-authorises before creating). Can use a saved template via `template_id`. |
| 4 | `request_payment` | Commit (C) | `/internal/meera/request_payment` | Proposes a payment request to a creator. Proposal only — Spring verifies wallet balance before executing. |
| 5 | `confirm_launch` | Commit (C) | `/internal/meera/confirm_launch` | Proposes campaign launch. Spring verifies campaign is FUNDED before going live. |
| 6 | `get_campaign_performance` | Read (R) | `/internal/meera/get_campaign_performance` | Returns live campaign analytics (Phase 2). Read-only, no money. |
| 7 | `analyze_site` | Local (no Spring) | Python-native in-process | Fetches a brand-supplied URL (SSRF-guarded), extracts product facts, returns structured context to Meera. Used when brand pastes a product URL in chat. |
| 8 | `present_options` | Local (display only) | Python-native in-process | Renders a small set of choices as tappable cards in the chat canvas. Writes nothing; Spring never sees it. |

> **Tier legend:** R = read-only, no money moves · D = state-write (draft), no money moves · C = commit-tier, proposal only — Spring re-derives amounts and re-authorises.
> Source: `influora-ai/app/tools/schemas.py:68-85`

---

## 4 · AI Spend Gate (applies to all brand AI calls)

> Enforcement order in `check_spend_gate()` — source: `influora-ai/app/costs/gate.py`

| Guard | Key / Default | Blocking? | What It Does |
|-------|--------------|-----------|--------------|
| Kill switch | `AI_KILL_SWITCH` | ✅ Yes — hard | Hard-blocks ALL AI calls across all workspaces when enabled |
| **Global** daily ceiling | `AI_DAILY_SPEND_CEILING_USD` ($15 default) | ✅ Yes — hard | Blocks all AI calls once platform-wide daily spend reaches $15 |
| Per-workspace hard cap | `WORKSPACE_DAILY_HARD_CAP_USD` | ✅ Yes — if set | Blocks AI calls for one workspace; **unset by default** |
| Per-workspace daily soft cap | `AI_WORKSPACE_DAILY_SOFT_CAP_USD` | ⚠️ Warning only | Logs a warning after a successful call — does **not** block |
| Per-call reservation | `AI_RESERVATION_PER_CALL_USD` ($0.02 × 6 iter) | ✅ Yes — pre-held | Holds a credit reservation per chat turn before provider call, closes read-then-spend race |
| Credit charge at send-time | Spring-side | ✅ Yes | Spring charges AI credit at send; `/chat` tells Spring to persist (success) or release/refund (failure / blank turn) |
| Tool-loop cap | `TOOL_LOOP_MAX_ITERATIONS` | ✅ Yes | Max tool iterations per turn; exceeded cap streams honest fallback and refunds the charge |

---

## 5 · AI Models in Use (Brand-facing)

> `GEMINI_MODEL` is a hardcoded constant (`config.py:67`: `"gemini-2.5-flash"`), not an env-overridable key — pinned deliberately after a retired model ID caused 404s. All others are `os.getenv`-overridable.

| Key / Constant | Model Class | Used For |
|----------------|-------------|----------|
| `CLAUDE_MODEL` | Sonnet-class (Claude) | Meera chat turns |
| `BRAND_SAFETY_MODEL` | Sonnet (inherits `CLAUDE_MODEL`; Haiku flip gated on GARM A/B test) | Brand-safety GARM classification — highest cost at scale |
| `TRENDSPARK_MODEL` | Haiku-class (Claude) | TrendSpark nudge phrasing |
| `TREND_TAG_MODEL` | Claude | Trend-Spark LLM Recovery Tagger (feeds brand nudges) |
| `GEMINI_MODEL` *(constant)* | `gemini-2.5-flash` | Analyze-site classification + Voice grammar cleanup |
| Sarvam | External API | Voice STT (transcribe) + TTS (speak) |

---

*Produced by: arjun · Task: ai-brand-features-menu-0809 · 2026-08-09*
*Confirmed by: priya · fresh-context review · 4 corrections applied · APPROVED*
*Done_When: Complete .md in table format — AI features & menu — Priya confirm*
