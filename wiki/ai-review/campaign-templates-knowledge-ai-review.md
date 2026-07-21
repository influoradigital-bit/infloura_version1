# AI Review: Campaign Templates × Meera knowledge & voice

Reviewer: Ash · 2026-07-20 · Scope: how campaign-template knowledge reaches (or fails to reach) the AI, and how to make Meera more powerful via knowledge + voice.

## How It Works (traced flow)

**Campaign templates (Spring, no AI involvement today):**
- Table + 4 seeded SYSTEM presets: `influora-api/src/main/resources/db/migration/V20260714150000__campaign_templates.sql` (Brand Awareness/HYPE ₹10–50k, Sales/DIRECT ₹15–75k, UGC/STANDARD ₹5–20k, Affiliate/REVIEW ₹0–30k — each with platforms, content_types, objectives, requirements, hashtags, target_audience, brand_guidelines).
- CRUD: `CampaignTemplateService.java` (SYSTEM visible to all, CUSTOM workspace-owned, save Pro-gated, `saveAsTemplate` snapshots a real campaign).

**Meera prompt (influora-ai):**
- Block A stable prefix: `app/prompt/persona.py` + 7 tool schemas (`app/tools/schemas.py`) — prompt-cached, tenant-agnostic.
- Block B per-brand cached: `app/prompt/assembler.py::build_block_b` — brand name, niches, tone_dial, product_catalog, past_campaign_summary, credit_state.
- Block C: conversation history, untrusted-wrapped.
- Voice: `app/providers/sarvam.py` (STT/TTS, fail-open to text) + `app/routes/voice.py`; persona is already written voice-first (2–3 sentence hard cap, no lists).

## Integration Map
- `assemble_prompt(body, ...)` in `app/routes/chat.py:156` builds Block B **from the browser's request body**.
- `src/hooks/useMeeraStream.ts` sends only `workspace_id, conversation_id, turn_id, onbehalf_jwt, conversation`. No `brand` object. Nothing in `src/` references `brand_context`/`niche_tags`/`product_catalog` for the chat body.
- No Spring code injects brand context into the stream path either.
- `campaign_templates` is referenced **nowhere** in `influora-ai/app` — zero template knowledge reaches the model.

## Findings

### [P1] Block B brand knowledge is dead in production
**Where:** `app/routes/chat.py:156`, `src/hooks/useMeeraStream.ts`
**Issue:** The whole per-brand knowledge layer (niches, catalog, tone, past campaigns, credit state) is only populated if the *client* sends it — and the client never does. Every real chat turn runs with Block B = `"Brand context for workspace <id>:"` and nothing else. Meera compensates by calling `analyze_site` every conversation (extra Gemini cost + latency) and has no memory of past campaigns.
**Bonus security note:** because Block B is built from the request body, a hostile client can inject arbitrary text into a **system** block (angle brackets are neutralized, but instruction-shaped prose still lands at system trust). Server-sourcing fixes both. Tag Kabir.
**Fix:** make Python fetch context server-side at conversation start: new Spring endpoint `GET /internal/meera/context?workspace_id=...` (service-token + HMAC mesh gate, same as tool forwards, via `app/clients/spring.py`), returning the field-allow-listed brand profile + credit state + template digest (below). Ignore any `brand` key in the browser body.
**Gain:** Meera actually knows the brand from turn 1; fewer analyze_site calls; removes client-controlled system-block text.

### [P1] Campaign templates never reach Meera — the best structured knowledge in the product is invisible to the AI
**Where:** `app/prompt/assembler.py`, `app/tools/schemas.py`, `CreateCampaignExecutor`
**Issue:** The 4 curated SYSTEM presets encode exactly what Meera currently improvises: campaign_type mapping, budget bands, platform/content-type combos, compliance requirements ("Include tracked link/coupon", "Disclose partnership"), hashtags, audience. CUSTOM templates are the brand's own proven configs. None of it is in the prompt or reachable by tool.
**Fix (recommended, cheapest first):**
1. Inject a compact template digest into Block B (SYSTEM always + the workspace's CUSTOM ones), ~300–500 tokens, cache-friendly since templates are stable within a session. Format: one line per template — name, campaign_type, budget band, key requirements.
2. Add optional `template_id` to the `create_campaign` tool schema; Spring's `CreateCampaignExecutor` validates visibility (reuse `CampaignTemplateService.requireVisible` logic) and copies requirements/hashtags/audience/brand_guidelines into the draft. Budget stays null — money rails unchanged. Requires updating both docs + the CI shared-schema diff-check per `schemas.py` header, and a `PROMPT_VERSION` bump.
3. Persona addition (1 line): "When the brand's goal matches a campaign template, recommend it by name via present_options and pass its template_id to create_campaign."
**Gain:** grounded recommendations (real budget bands instead of guessed ones pre-`calculate_budget`), compliance requirements land in drafts automatically, and `present_options` cards get real content. Meera goes from improviser to operator with a playbook.

### [P1] No knowledge flywheel — `past_campaign_summary` slot exists but nothing fills it
**Where:** `build_block_b` (slot exists), no Spring producer
**Issue:** The system already captures perfect training signal: campaigns saved as templates (= brand said "this worked"), `present_options` picks, funded vs abandoned drafts, `meera_tool_calls` ledger. None of it feeds back.
**Fix (now):** have the new `/internal/meera/context` endpoint compute a 2–3 line `past_campaign_summary` (last N campaigns: type, creator count, funded or not). **(next)** log which `present_options` option the user tapped vs Meera's `recommended` flag — that's a free eval set for recommendation quality. **(later)** at ~10k logged turns, mine accepted campaign configs into per-niche few-shot examples in Block B.
**Gain:** each brand's Meera gets smarter with use — the actual "improve our AI with knowledge" ask.

### [P2] Voice: numbers and template names will be spoken badly
**Where:** `app/routes/voice.py` / TTS input path
**Issue:** Template-grounded replies will contain "₹15,000–₹75,000", "#shopnow", "UGC". Sarvam TTS reads currency symbols, ranges, and hashtags poorly; persona bans symbols but tool results inject them.
**Fix:** a small `speakable()` normalizer before TTS: `₹15,000` → "fifteen thousand rupees", strip `#`, expand "UGC" → "U G C". Also confirm `lang_detected` from STT steers reply language (Hinglish in → Hinglish out) — persona has no language-matching rail today; add one line.
**Gain:** voice replies stay natural once real template/budget data starts flowing.

### [P2] `calculate_budget` is blind to template budget bands
**Where:** `CalculateBudgetExecutor` (Spring)
**Issue:** Budget suggestions derive only from product price + goal; the curated per-category bands (e.g. UGC ₹5–20k) are ignored, so Meera can recommend a UGC campaign and then quote a Sales-band budget.
**Fix:** pass campaign category into the executor and clamp/anchor the suggestion to the matching SYSTEM template band. Pure read-tier change, no money-rail impact.

## Data & Training Roadmap
- **Now:** server-side context endpoint + template digest in Block B; log `present_options` tap-vs-recommended.
- **Next:** `past_campaign_summary` producer; 10-case golden eval set (product → expected template recommendation) run on every `PROMPT_VERSION` bump.
- **Later:** at ~10k logged tool-call turns, mine funded-campaign configs into per-niche few-shot blocks; revisit fine-tuning only after that.

## Cost
Template digest + brand context ≈ 400–700 tokens in Block B, prompt-cached per conversation → roughly ₹0.02–0.05/conversation added; offset by fewer analyze_site (Gemini) calls and fewer clarification turns.

## Verdict: SHIP WITH P1 FIXES
Nothing here blocks (money rails untouched, guardrails intact), but the AI is running with an empty knowledge layer while a curated knowledge base sits one JOIN away. The three P1s are one Spring endpoint + one schema field + one prompt line — high impact, low effort.
