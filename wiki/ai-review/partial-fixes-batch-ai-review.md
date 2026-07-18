# AI Review: 7-partial fix batch (spend-gate wiring, live-canvas payloads, brand-safety fan-out)

Reviewer: Ash (AI/ML) · Date: 2026-07-17 · Reviewed AFTER Kavya (QA) + Kabir (red-team), BEFORE ship.
Scope: only the AI-system dimension (model/prompt/data/cost/eval). Correctness + security already cleared by Kavya/Kabir; I do not re-litigate those.

## How It Works (traced flows touched by this batch)

Three AI call-paths changed, none in their *prompt* — the changes are cost accounting, payload plumbing, and enabling a dormant fan-out:

1. **analyze-site** `routes/analyze_site.py` → SSRF fetch → `strip_active_content` + `wrap_untrusted_scrape` (injection defense, unchanged) → `GeminiProvider.classify_site` (`gemini-2.5-flash-lite`, temp 0.2, max 1024, `response_mime_type=application/json`) → JSON parse → DTO. **New:** spend gate before the call, `estimate_cost_usd(GEMINI_MODEL, usage)` after, using new `_usage_from_response`.
2. **voice** `routes/voice.py` → Sarvam STT → `GeminiProvider.cleanup_transcript` (temp 0.1, max 512) → Sarvam TTS (200-char cap). **New:** gate on both endpoints; flat `estimate_sarvam_flat_cost_usd()` = $0.006/call for STT+TTS; Gemini token cost for cleanup.
3. **chat** `tools/loop.py` + `routes/chat.py` → Claude tool loop (Sonnet) → SSE. **New:** usage now *accumulated* across iterations (was: last-only); `tool_result` SSE frame now forwards `event.tool_result_data` as `data`.
4. **brand-safety** (Java) `ScoreCalculationJob` → `BrandSafetyScoreService.scoreCreator` → `BrandSafetyAiClient.classify` → Python `/brand-safety` (Claude **Sonnet**, forced-tool GARM). **New:** wired behind `influora.brand-safety-scoring.enabled=false` + `maxCreatorsPerRun` cap.

## Integration Map

- Prompt files (unchanged this batch): `gemini.py` `_CLASSIFY_/_CLEANUP_SYSTEM_INSTRUCTION`; `prompt/brand_safety.py`; `prompt/trend_tag.py`; `prompt/trendspark.py`.
- Cost path: `costs/pricing.py` `PRICING_TABLE` (Claude 3/15, Gemini 0.10/0.40, Haiku 1/5) + Sarvam flat 0.006 → `costs/gate.py` daily ceiling/kill-switch → `costs/spend_tracker.py` (Redis or in-mem).
- Failure modes: every provider path degrades (breaker → structured `ok=False`); gate-block degrades to the same fallback (voice) or 503 (analyze/chat). Brand-safety fan-out fail-safe = null columns, never "scored safe".
- Est. cost, brand-safety fan-out once enabled: `maxCreatorsPerRun` × (captions/creator chunked) × Sonnet — the single largest new cost lever in this batch; currently OFF, and the Python `/brand-safety` route itself passes through the daily gate, so it is double-bounded.

## Findings

**[P1] No eval harness for any classification/generation output**
Where: `analyze_site` classify, `trend_tag`, `brand_safety`.
Issue: These features are being made *live* (gate-wired, fan-out enabled) with zero regression eval on output *quality* — only unit tests on wiring. A prompt or model bump can silently regress niche-tagging / GARM accuracy and nothing catches it. `tests/.../golden_brands` exists for trendspark; nothing equivalent guards analyze-site or trend_tag.
Fix: 10–15 golden `input → expected-fields` pairs per feature, asserted on every prompt/model change. For analyze_site: 12 real brand pages → expected `niche_tags`/`tone_dial`. For trend_tag: raw trend text → expected closed-vocab themes.
Gain: catches quality regressions the current wiring tests can't see; precondition for any future model swap.

**[P1] Brand-safety GARM runs on Sonnet ($3/$15) — a classification task**
Where: `prompt/brand_safety.py` + `providers/claude.py` `complete_with_forced_tool` (uses `CLAUDE_MODEL`=Sonnet).
Issue: GARM labeling is bounded, schema-locked classification — exactly the Haiku-class job the codebase already routes trend-tag/trendspark to. With the fan-out now enabling (capped, but designed to scale), Sonnet is the biggest avoidable cost. This is *not* a blocker (feature ships OFF), but it's the first thing to fix before turning `enabled=true` at any real cap.
Fix: add `BRAND_SAFETY_MODEL` (default Haiku 4.5), price its row, and A/B Haiku-vs-Sonnet on a GARM golden set before enabling. Keep Sonnet only if Haiku measurably misclassifies.
Gain: ~3× input / 3× output cost cut on the batch's highest-volume path, gated by an eval so quality is proven not assumed.

**[P2] Cost estimates under-count in two spots — directionally weakens the ceiling**
Where: `gemini.py:_usage_from_response`; `pricing.py:SARVAM_FLAT_COST_PER_CALL`.
Issue: (a) Gemini usage reads `candidates_token_count` only — for 2.5 models any `thoughts_token_count` is billed by Google but omitted here. Flash-lite thinking is small, so minor, but it's a systematic *under*-count of a budget-enforcement number. (b) Sarvam flat $0.006 applies to STT and a max-length (200-char) TTS alike; the route's own docstring prices a 200-char TTS at ₹0.60 ≈ $0.0072 — so a max TTS under-bills ~20%.
Fix: (a) add `thoughts_token_count` (if present) into `output_tokens`. (b) either bump the flat to $0.0072 or make TTS cost char-scaled (`chars/10000 × ₹30 → USD`); STT can stay flat.
Gain: the daily ceiling reflects true spend instead of a slight under-estimate; matters most exactly when spend is near the cap.

**[P2] analyze-site classify parses JSON by hand instead of a hard schema**
Where: `gemini.py:classify_site` — `response_mime_type=application/json` but no `response_schema`.
Issue: relies on `json.loads` + `.get(... ) or []` defensively; a malformed object still burns the `unparseable_response` path (a wasted paid call).
Fix: pass a genai `response_schema` (Pydantic/typed dict for niche_tags/tone_dial/brand_color/product_catalog) so the SDK constrains output structurally.
Gain: near-eliminates the unparseable path; removes the hand-rolled `.get` fallbacks.

**[P2] No data flywheel — live outputs flow but nothing is captured for eval/few-shot**
Where: all four paths.
Issue: `tool_result` payloads, analyze-site classifications, and trend_tag recoveries now flow end-to-end, but no `(input → output)` pairs are logged for building the P1 golden sets or future few-shot examples. trend_tag already logs a `trend_tag_dropped`/validated event — closest to a usable signal.
Fix (cheapest start): behind a sampling flag, structured-log analyze-site's `(sanitized_text_hash, niche_tags, tone_dial)` and trend_tag's `(trend_text, validated_themes, recovered)`. That log *is* the seed corpus for the P1 evals.
Gain: turns live traffic into the eval/few-shot data these features need to get smarter — near-zero cost to start.

## Data & Training Roadmap
- **Now:** sampling-flag structured logs of input→output for analyze-site + trend_tag (P2 #5). Stand up the P1 golden sets from real pages/trends.
- **Next:** Haiku-vs-Sonnet GARM A/B on the golden set (P1 #2); add `response_schema` to Gemini classify (P2 #4).
- **Later:** revisit brand-safety fine-tune only past ~10k logged GARM labels with human corrections — not before; Haiku + good prompt should hold well past initial volume.

## Addendum 2026-07-17 (evening): How the AI *looks* — live browser review (mock mode, dev server)

Measured via accessibility tree + computed styles (the session's screenshot channel was unavailable; all numbers below are from the running page, not the source).

**What reads as genuinely premium:**
- **MeeraOrb is real, not decorative theater**: 4 blurred blobs (violet #6d5ae6 / blue #2c7be5 / cyan #22d3ee / pink #ff5c9e) drifting via framer-motion transforms over a deep navy base with screen blending + specular highlight. State machine verified live: aria-label cycles "Meera is idle" → "Meera is thinking" → idle during a turn — the orb *is* the AI status indicator, and it's screen-reader honest. `useReducedMotion` → static disc fallback exists in code.
- **Conversation feel**: quick-reply chips advance the flow; Meera's replies are brand-aware in demo ("Kavala Skincare — vitamin C serum is your hero product"); typing indicator present; canvas stage advances in sync ("Your business, at a glance" → budget).
- **System discipline**: Inter everywhere, one violet primary, 10px radius tokens, tabular alignment in money figures. Send CTA: white on #6d5ae6 = **4.93:1 contrast (WCAG AA pass)**; chat bubbles 16.1:1; no horizontal overflow at 375px; composer + voice affordances ("Speak to Meera", "Voice replies off") visible at mobile width.
- **TrendSparkNudgeCard** (this batch's mount) renders on the brand dashboard as "Suggestion from Meera" with trend copy, Plan-a-campaign CTA and dismiss — correctly framed as a suggestion, not an ad.

**Gaps (logged for backlog, none blocking):**
- **[P2] No dark mode**: OS dark preference is ignored (body stays #faf9fd; no `.dark` variants in meera components). For a "living AI" product whose orb sits on a navy base, a dark theme is the flattering direction — backlog it deliberately rather than never.
- **[P2] Send CTA contrast is bottom-of-AA** (4.93:1). Fine today; any lightening of #6d5ae6 breaks AA. Consider #5b48d6-ish for headroom (ties to the standing brand-CTA-contrast feedback).
- **[P3] Canvas snapshot in mock mode labels itself "Analysing your business…"** while showing final data — minor state-copy mismatch in the demo path.

## Verdict: SHIP WITH P1 FIXES

No P0 in the diff — the batch's cost-accounting, payload plumbing, and fan-out gating are sound, and the injection/XSS surfaces were cleared by Kabir. The two P1s (eval harness; Haiku for GARM before scaling the fan-out) are pre-existing gaps this batch *surfaces* by making the features live — fix this sprint, do not block ship. Re-review the GARM model swap against its golden set before any `brand-safety-scoring.enabled=true` at a non-trivial cap.
