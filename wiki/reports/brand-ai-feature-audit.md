# Brand-Surface AI/Model Feature Audit — Independent Verification

**Auditor:** Ash (AI/ML expert, AI code reviewer) — independent cross-check on Priya's full-surface audit
**Branch audited:** `feat/creator-ai-copilot` (working tree as-is, Phase 2 changeset uncommitted)
**Date:** 2026-07-22
**Method:** end-to-end trace per feature (trigger → model/API call → response parsed → consumed → surfaced). Code is the only source of truth; trackers/comments ignored for verdicts.

---

## Headline

- **AI-feature completion (brand surface, code-alignment): 87.5%** — `14.0 / 16` features.
- **State counts:** ALIGNED 13 · PARTIAL 1 · BUILT-NOT-LIVE 1 · BROKEN 1 · (NOT-BUILT/N-A: RAG-embeddings).
- **Top real breakage:** Phase 2 outcome-digest grounding is **BROKEN** — Java computes and serializes it, the Python assembler can render it, but `chat.py::_fetch_brand_context` never copies `outcome_digest` into the brand dict, so it never reaches a live prompt. The `PROMPT_VERSION` was bumped *for this feature* (`meera-2026.07.21.9`); it ships dead.
- **Live axis (separate from code-alignment):** every ALIGNED provider feature is gated on real keys. `require_boot_secrets()` only checks **non-empty**, so a placeholder value (`REPLACE_ME`) boots green but fails on first live call. No live E2E is provable from code.

---

## Per-feature verdicts

### 1. Meera brand chat — Claude streaming SSE — **ALIGNED** (1.0)
Full chain present:
- `routes/chat.py:296` runs `run_tool_loop` → `providers/claude.py:136` `messages.stream(model=CLAUDE_MODEL, ...)` — real Anthropic SDK, `AAsyncAnthropic`.
- Text deltas + `tool_use` blocks parsed (`claude.py:157-176`), usage captured incl. cache tokens (`claude.py:185-190`).
- SSE events consumed by frontend `useMeeraStream` / `MeeraChatPanel.tsx`.
- `CLAUDE_MODEL = claude-sonnet-4-5-20250929` (`config.py:68`), max_tokens 384 backstop (`config.py:242`).
Cancellation, circuit breaker, per-turn credit charge/refund all wired (`chat.py:421-485`).

### 2. Context assembly Block A/B + prompt caching — **ALIGNED** (1.0)
- `prompt/assembler.py:115` `build_block_a` (tenant-agnostic persona+tools, `cache_control: ephemeral`), `:267` `build_block_b` (per-brand, ephemeral).
- Cache key `(prompt_version, audience, workspace_id, session_id)` — `assembler.py:400`.
- Block B server-sourced via `POST /internal/meera/context` (`chat.py:231`, `clients/spring.py:270`); client-supplied `brand` ignored.
- Untrusted brand text neutralized (`_safe` / `wrap_untrusted`).

### 3. Tool dispatch — 5 Spring tools + 2 local — **ALIGNED** (1.0)
- Schemas single-sourced `tools/schemas.py:91` (`TOOL_SCHEMAS`), offered via `get_tool_schemas()` (`:281`).
- Loop forwards to Spring `/internal/meera/*` with HMAC + service-token + on-behalf JWT + idempotency key (`tools/loop.py:316-337`, `clients/spring.py:130`).
- Local `analyze_site` runs in-process (`loop.py:213-314`); `present_options` echoes + flywheel-logs (`loop.py:218-257`).
- Unknown tools rejected (`loop.py:193`).

### 4. `get_campaign_performance` (the new 6th tool) — **ALIGNED** (1.0)
- Python: constant + tier + Spring path + schema all present (`schemas.py:36,44,74,84,199`).
- Java executor **fully implemented**: `GetCampaignPerformanceExecutor.java` aggregates real DB rows (escrow RELEASED spend, PLATFORM_VERIFIED reach, UTM revenue, ROI, response rate), IDOR-safe via `findByIdAndWorkspaceId`.
- Controller dispatch `MeeraInternalController.java:226-234`.
- Frontend consumes it — dedicated `StagePerformance.tsx` + `LivingCanvas.tsx` + `meera-api.ts` (7 files). Wire name matches byte-for-byte, so no silent client drop.

### 5. AI campaign generation (`create_campaign` + templates) — **ALIGNED** (1.0)
- Schema with `template_id` support (`schemas.py:127-164`); idempotency-required (`schemas.py:89`); Spring re-derives amounts/type. Template digest surfaced in Block B (`assembler.py:140`).

### 6. Phase 2 outcome-digest grounding — **BROKEN** (0.0) ⚠️ TOP FINDING
The digest is built on both ends but severed at the Python chat seam:
- ✅ Java computes it: `BrandContextAssembler.assembleOutcomeDigest` (`:280`), platform-verified only.
- ✅ Java serializes it: `MeeraContextDtos.java:136` `@JsonProperty("outcome_digest")`.
- ✅ Python assembler renders it: `assembler.py:201` `_render_outcome_digest`, called in `build_block_b` (`:301`); `outcome_digest` is in `CONTEXT_PAYLOAD_FIELDS` (`:61`).
- ❌ **Python chat route drops it:** `chat.py::_fetch_brand_context` builds `brand_fields` (`chat.py:126-138`) **without `outcome_digest`**. `build_block_b` reads `brand.get("outcome_digest")` → always `None` → **no digest line ever emitted in live chat.**
- ❌ `clients/spring.py:285-291` docstring listing consumed fields also omits `outcome_digest`.
- ❌ `grep outcome_digest chat.py` = **zero matches**.
Net: the moat's core grounding payload is dead in the live path despite the prompt-version bump made for it. **One-line fix** (add `"outcome_digest": context_data.get("outcome_digest")` to `brand_fields`), but as-is it is not consumed. The offline eval (`evals/datasets/outcome_recommendation.jsonl`) feeds the digest directly into the assembler, so it is green while the live route is dead — the plan's landmine #6 exactly.

### 7–9. Voice (brand chat) — **ALIGNED** (1.0 each); live-gated on `SARVAM_API_KEY`
- **STT** `providers/sarvam.py:307` real POST `/speech-to-text` (saarika:v2). Consumed `voice.py:192`.
- **TTS** `sarvam.py:351` real POST `/text-to-speech` (bulbul:v3, speaker "priya"), chunking + WAV stitch + `speakable()` normalization (₹/hashtag/UGC). Consumed `voice.py:292`.
- **Gemini cleanup** `gemini.py:263` real `generate_content`; consumed `voice.py:213`.
All degrade silently to text on failure. `SARVAM_API_KEY`/`GEMINI_API_KEY` are boot-required (non-empty check only).

### 10. Brand onboarding `analyze_site` — Gemini classify — **ALIGNED** (1.0)
- `routes/analyze_site.py:243` → `gemini.py:187` real `generate_content(model=gemini-2.5-flash)` with `response_schema` + `response_mime_type=json`.
- SSRF-guarded fetch, active-content strip, untrusted wrap all before the model. Result consumed + written back to `BrandProfile` (`loop.py:287`, `spring.py:233`).
- Model id current: `GEMINI_MODEL = gemini-2.5-flash` (`config.py:67`) — the retired `flash-lite`/`2.0-flash` ids are gone.

### 11. Structured price / JSON-LD extraction + merge — **ALIGNED** (1.0)
- `prompt/structured_extract.py` real stdlib JSON-LD/OpenGraph/microdata parse over raw HTML; price sanity clamp (`:80`).
- `analyze_site.py:106` `merge_known_products` **forces `price_source:"scraped"`** for scraped facts and `"inferred"` for model additions — scraped price can't be overwritten by a hallucination. `calculate_budget` schema strips model-supplied `price_source` (`schemas.py:116`), Java re-derives it server-side.

### 12. Brand-safety GARM scoring — Python endpoint — **ALIGNED** (1.0)
- `routes/brand_safety.py:315` real `complete_with_forced_tool` (Claude, `tool_choice` on `analyze_creator_content`), `BRAND_SAFETY_MODEL` = Sonnet.
- Strong defensive validation `_validate_model_result` (`:199`): every GARM category must be scored, enums checked, count/order matched, else typed 502. Genuine structured-output classifier.

### 13. Brand-safety GARM — Java job wiring — **BUILT-NOT-LIVE** (0.5) ⚠️
- Code path complete: `BrandSafetyScoreService.java` (chunk ≤25, fail-safe: any chunk failure → whole creator NULL, worst-score aggregation, `garm_flags` JSON-shape bug already fixed).
- **But disabled by default:** `BrandSafetyScoringProperties.enabled = false` (`:41`); `ScoreCalculationJob.selectBrandSafetyTargets` returns empty set when `!isEnabled()` (`:203`). Until flipped, the 3 brand-safety columns stay `null` on every row.
- **No backfill job:** coverage only rotates forward via `maxCreatorsPerRun` (default 100) on the daily `ScoreCalculationJob`; there is no dedicated historical backfill. Flip → slow forward fill only. Gated on a **config flag**, not a key.

### 14. TrendSpark nudge — AI phrasing — **ALIGNED** (1.0)
- `routes/trendspark.py:244` real `complete_text` (Haiku-class `TRENDSPARK_MODEL`), `parse_and_validate` (≤2 sentences, no petname, no echoed price, `video_ids ⊆ sent_ids` hallucination kill-switch), deterministic fallback on any miss (always 200).
- Invoked by Java `TrendSparkNudgeService` → `TrendSparkAiClient`.

### 15. TrendSpark trend DATA sources (feeds the nudge) — **PARTIAL** (0.5) ⚠️
- **Google Trends = hard STUB:** `trendspark/n8n/trend-pull-workflow.json:185-193` node `code-google-trends-stub` emits `[]` ("pytrends needs Python not in a JS node; SerpAPI needs a key"). No-op.
- **NewsAPI / TMDb / YouTube:** wired in the n8n workflow but keys live in n8n's credential store, **unprovisioned by default** (`EXTERNAL-APIS.md` §B, B1–B4 free-tier, owner "Dev"). Not read by Java/Python at all.
- So the nudge's *input* pipeline is 1 stub + 3 keyless sources + 1 static festival JSON + internal Snapsby catalog. The AI call works; the data feeding it is largely inert until n8n creds are added and Google Trends is actually wired.

### 16. TrendSpark LLM recovery tagger (`trend_tag`) — **ALIGNED** (1.0); live-gated on `TREND_TAG_INGEST_SECRET`
- `routes/trend_tag.py:205` real `complete_text` (`TREND_TAG_MODEL`), closed-vocab validation, drop-on-miss.
- Auth = static shared secret (documented tech-debt exception); **fails closed 503 if `TREND_TAG_INGEST_SECRET` unset** (`:134`). NOT in `require_boot_secrets`, so it's opt-in and dark until the secret + n8n wiring exist.

### N/A. RAG / embeddings on the brand path — **NOT BUILT**
No vector/embedding/RAG code anywhere in `influora-ai` (only false positives: an eval dataset + a validator). Creator matching (`show_creators`) is a Spring DB query, not semantic search. Nothing claims otherwise; excluded from the %.

### Adjacent (creator surface, not counted in brand %): Creator AI Co-pilot suggestion — **ALIGNED**
`routes/creator_suggestion.py:279` real `complete_text` (`CREATOR_COPILOT_MODEL`), keyed on `creator_profile_id`, validation + fallback mirror trendspark. Invoked by Java `CreatorNudgeService`. Solid, but creator-path, not brand.

---

## Score table

| # | Feature | Verdict | Score |
|---|---|---|---|
| 1 | Meera chat — Claude streaming SSE | ALIGNED | 1.0 |
| 2 | Context assembly Block A/B + prompt caching | ALIGNED | 1.0 |
| 3 | Tool dispatch (5 Spring + 2 local) | ALIGNED | 1.0 |
| 4 | `get_campaign_performance` (6th tool) | ALIGNED | 1.0 |
| 5 | `create_campaign` AI generation + templates | ALIGNED | 1.0 |
| 6 | Phase 2 outcome-digest grounding | **BROKEN** | 0.0 |
| 7 | Voice STT (Sarvam) | ALIGNED | 1.0 |
| 8 | Voice TTS (Sarvam) + speakable | ALIGNED | 1.0 |
| 9 | Voice transcript cleanup (Gemini) | ALIGNED | 1.0 |
| 10 | analyze_site Gemini classify | ALIGNED | 1.0 |
| 11 | Structured JSON-LD/price extraction + merge | ALIGNED | 1.0 |
| 12 | Brand-safety GARM — Python endpoint | ALIGNED | 1.0 |
| 13 | Brand-safety GARM — Java job wiring | **BUILT-NOT-LIVE** | 0.5 |
| 14 | TrendSpark nudge AI phrasing | ALIGNED | 1.0 |
| 15 | TrendSpark trend data sources | **PARTIAL** | 0.5 |
| 16 | TrendSpark recovery tagger (trend_tag) | ALIGNED | 1.0 |
| | **Total** | | **14.0 / 16 = 87.5%** |

---

## DISAGREEMENTS-TO-RECONCILE

Flags where my verdict likely differs from Priya's full-surface audit or from the trackers/comments:

1. **Outcome-digest grounding (Phase 2 item 2.1) — trackers say SHIPPED, I say BROKEN in live chat.** Java + assembler + eval all green, but `chat.py::_fetch_brand_context` (lines 126-138) omits `outcome_digest` from `brand_fields`, so it never reaches a live prompt. The `PROMPT_VERSION` bump comment (`config.py:69-75`) claims Block B "gains the outcome_digest section" — it does not, live. **Reconcile:** confirm whether Priya traced the *chat route* seam or only the assembler+Java. If she marks it aligned, we disagree; the one-line omission is the deciding evidence.

2. **Brand-safety GARM — likely marked "done/aligned" elsewhere; I say BUILT-NOT-LIVE.** All code exists and is high quality, but `enabled=false` by default (`BrandSafetyScoringProperties:41`) and there is **no backfill job** — every brand-facing `brand_safety_score`/`garm_flags`/`content_sentiment` is `null` in any default deploy. A "feature complete" claim is code-true but user-false.

3. **TrendSpark end-to-end — the AI nudge is aligned, but the feature is not "working" live.** Google Trends is an explicit `[]` stub and NewsAPI/TMDb/YouTube keys are unprovisioned (n8n cred store). If the tracker says "TrendSpark works", reconcile scope: the *phrasing model* works; the *trend pipeline* is 1 stub + 3 keyless sources.

4. **`get_campaign_performance` — I say fully ALIGNED end-to-end** (Python schema → Java executor → frontend `StagePerformance.tsx`). If any audit lists it as "schema only / no executor", that is wrong — the executor and controller dispatch are present and real.

5. **"Live" vs "green".** Several ALIGNED features are code-complete but live-gated on keys that `require_boot_secrets()` only null-checks (Anthropic/Gemini/Sarvam) or on unset opt-in secrets (`TREND_TAG_INGEST_SECRET`). Any tracker claiming a *live* pass is unverifiable from code; a placeholder key boots green and fails on first call. Offline-green eval ≠ works live.

6. **RAG/embeddings — not built.** If any doc implies semantic creator retrieval or a RAG layer on the brand path, it does not exist in code.
