# AI Review: Making Meera "already know things", and switching Meera on

**Reviewer:** Ash (AI/ML) · **Date:** 2026-09-19 · **For:** Swapnil · **Tree:** `influora-b0` @ `feat/meera-creator-phase-b0`
**Question asked:** "Can we make the AI act like a system that already knows the information we have online or from other sources, so it looks smart, and can we switch everything on for Meera?"

**Short answer:**
- **Knowledge.** Yes, in three layers: what Influora already holds, a curated knowledge pack, and live web search. Build them in that order. None of it needs a vector database or fine-tuning at our size.
- **Switches.** Don't turn everything on. Two switches should go on now and two after a decision. Two must stay off, and one does nothing without a Meta permission. The gaps that most limit how smart Meera looks are unbuilt code, not switches.

---

## How it works today (traced flow)

1. The creator's message goes to Spring `MeeraSessionService`, which checks consent and issues an on-behalf pass scoped to the creator's tools.
2. The AI service builds the prompt (`influora-ai/app/prompt/assembler.py`):
   - **Block A:** rules. `creator_persona.py` plus the capability lines for the tools on offer. It is cached globally.
   - **Block B:** this creator. It holds the 32-field `CreatorContextResponse`: name, city, tier, niche, followers, deal counts, floors, preferences.
3. The model is **Claude Sonnet 4.5** (`CLAUDE_MODEL` default `claude-sonnet-4-5-20250929`, `app/config.py:68`).
4. It can call 5 tools, each a call back to Spring: `get_my_deals`, `get_brief`, `estimate_my_rate`, `get_my_metrics`, `check_deal_risks`. Brand-written text comes back wrapped as untrusted.
5. The reply streams to the browser. Only the final text is saved (`ai_messages`).

**What Meera knows:**
- Her rules, which are strong and heavily tested.
- One creator's profile snapshot.
- The results of 5 tools.

**What Meera does not know:**
- How Influora works, beyond one sentence about Secure Payments (`creator_persona.py:96-99`).
- Market norms: usage-rights durations, exclusivity, barter, whitelisting.
- ASCI rules. She is told to defer all legal and tax questions (`:100-102`).
- The creator's engagement, reach or audience.
- Anything about a brand except what the brief says.
- Anything current: news, trends, events.

**Files:** `influora-ai/app/prompt/{creator_persona,assembler}.py`, `app/tools/{loop,creator_schemas}.py`, `app/providers/claude.py`, `influora-api/.../service/meera/MeeraContextService.java`, `web/dto/meera/MeeraContextDtos.java`.

## Integration map

- **Online access today:** one path only, brand-side. `analyze_site` reads a brand's product URL through `ssrf_guard.guarded_fetch` and classifies it with Gemini (`app/routes/analyze_site.py:39-57,142-149`). The creator side has no web access.
- **Search and retrieval:** nothing uses web search, news search, embeddings or a vector store (`requirements.txt`: `anthropic==0.42.0`, `google-genai==0.8.0`, no retrieval libraries).
- **Trend pipeline:** `TrendPullJob` (NewsAPI India entertainment headlines, TMDb, YouTube) exists **only on the phase-e branch**, and it is off (`TREND_INGEST_ENABLED:false`). It stores only the headline, with a 30-day expiry. Meera has no tool or context field for trends.
- **Evals:** the harness in `influora-ai/evals/` has datasets for brand features only (`analyze_site_*`, `brand_safety_garm`, `campaign_performance`, `outcome_recommendation`, `template_recommendation`, `trend_tag`). There is **none for creator Meera replies or brief reading**, so "smarter" cannot be measured today.
- **Research already done but not wired into code:**
  - `wiki/ai-review/creator-copilot-india-events-calendar-2026.md`: 22 claims verified across 24 sources.
  - `wiki/ai-review/creator-copilot-content-idea-library.md`.
  - A code grep finds no reader for either.
- **Cost and budget:** the creator monthly AI cap is $0.75 (C-1 raises it to $2.00, not done). Every idea below adds tokens or searches, so C-1 comes first.

---

## Findings

**[P1] Meera knows the rules but not the world**
- **Where:** `influora-ai/app/prompt/creator_persona.py`
- **Issue:** the persona is roughly 90 lines of guardrails and no domain knowledge. Ask her "is 6 months of usage normal?", "what does ASCI need?" or "how long until my payout?" and the honest answer she's allowed to give is "I don't have that", or "ask your CA". That is what makes an assistant feel dumb.
- **Fix:** a versioned **knowledge pack**: markdown files in `influora-ai/app/knowledge/creator/`, loaded into cached Block A. Contents v1:
  1. How Influora works for creators: Secure Payments steps, milestones, payout timing, disputes, fees.
  2. ASCI influencer basics, stated as "what the rule says"; the legal call stays with a lawyer.
  3. Deal norms in India: usage rights, exclusivity, barter, whitelisting, revisions.
  4. A glossary of deliverables.
  5. The India events calendar 2026, which is already researched.
  - Facts come from Nisha and Priya; ASCI wording from legal. Bump `PROMPT_VERSION`. Keep the persona rule that knowledge text is guidance, not numbers.
  - Past about 40k tokens, move to a `lookup_knowledge(topic)` tool instead of Block A. Only past a few hundred documents consider retrieval with embeddings.
- **Gain:** "sounds like she knows the business" for the smallest effort. Block A is already cached, so a 15-20k-token pack is read at cache price, not full input price, after the first turn.

**[P1] Engagement and reach are never calculated**
- **Where:** `influora-api/.../job/MetricsPollingJob.java`, `domain/entity/CreatorMetric.java`
- **Issue:** nothing writes `avg_engagement_rate` or `avg_reach_per_post` (no setter or SQL writer in `src/main`). Per-post reach, likes, comments, saves and shares *are* stored in `media_metrics`. The result:
  - Meera says "Engagement: not available".
  - Pricing's engagement factor is always a neutral 1.0.
  - The account-insights call (`InstagramInsightsClient.getAccountInsights`) has no caller.
- **Fix:** in the poll job, roll up the last 25-30 posts into average engagement rate and average reach, and write them on the `creator_metrics` row. Also run one poll right after connect (today the first number can take up to 6 hours).
- **Gain:** Meera and the rate engine finally see the numbers brands actually pay for.

**[P1] Audience data is collected and then ignored**
- **Where:** `job/AudienceDemographicsJob.java`
- **Issue:** a weekly job stores audience city, country and age/gender, and no Meera code reads it. It also requests the legacy `audience_*` metric names. Whether Meta still returns data for those was **not verified against a live response**; if not, the job writes nothing.
- **Fix:** first prove one live response. Then add a short summary to the creator context (top 3 cities, main age band, gender split), rendered by Java as text.
- **Gain:** "this brand wants 25-34 women in Mumbai, and that's 41% of your audience" is the answer that makes Meera look smart.

**[P1] No web access, so she can't check a brand or answer "what's trending"**
- **Where:** the creator tool loop (`app/tools/loop.py`)
- **Fix:** Anthropic's server-side **web search** tool (verified against the docs, 2026-09-19):
  - `web_search_20250305` works on the current Sonnet 4.5.
  - `web_search_20260209` and later add dynamic filtering (fewer tokens), which needs a 4.6+ model.
  - Price: **$10 per 1,000 searches** plus the tokens the results add.
  - Conditions (all required):
    1. `max_uses` 2-3 per turn and `user_location` country IN.
    2. A blocked-domains list.
    3. Search results treated as **untrusted**, the same principle as K-3: a web page can carry prompt injection.
    4. **Citations shown in the UI.** The docs require it whenever outputs are shown to end users.
    5. It counts toward the creator's monthly cap.
    6. Offered only when the creator asks about a brand or a trend.
  - Engineering: upgrade `anthropic` from 0.42.0, which predates server tools. Teach `loop.py` to pass `server_tool_use` / `web_search_tool_result` blocks back unchanged within the turn, including `encrypted_content`. Handle `pause_turn`, and the case where a client tool and web search land in the same parallel group (`stop_reason: tool_use`).
- **Gain:** "Who is GlowCo? Are they legit? Any recent news?" answered with sources. Rough cost: 1,000 active creators × 10 searches a month = 10,000 searches = **$100 (about ₹8,800) a month**, plus result tokens.

**[P1] Trends are fetched but can't be used as reference**
- **Where:** phase-e `TrendPullJob`, `TrendRepository`
- **Issue:**
  - Only the headline is kept, for 30 days: no link, publisher or date.
  - Duplicates are re-inserted every run.
  - Nothing reads an expired row.
  - Meera can't read trends at all.
- **Fix:**
  - Add `url`, `publisher`, `published_at` and a dedupe key.
  - Keep history (expired rows are archived, not invisible).
  - Add a `get_trends(niche, days)` creator tool that returns Java-rendered rows.
  - Let web-search findings Meera cites be saved into the same table as source `web`.
- **Gain:** "store trends for future reference", as asked, and Meera can say "3 of the last 5 skincare trends were festival-led".

**[P1] No eval set for creator Meera**
- **Where:** `influora-ai/evals/datasets/`
- **Fix:** before any change above, add about 50 golden cases: realistic briefs with expected flags, price provenance and a good answer. Add `creator_meera_*.jsonl` to the existing `run_eval.py`, and run it on every `PROMPT_VERSION` bump.
- **Gain:** proof that each change made her smarter, not just different.

**[P1] Chat model**
- **Where:** `app/config.py:68`
- **Issue:** chat runs on Sonnet 4.5. Newer Claude models exist (the Claude 5 family), and 4.6+ unlocks web search's dynamic filtering.
- **Fix:** run the new eval set on Sonnet 4.5 and on a newer Sonnet side by side, then switch with the `CLAUDE_MODEL` environment variable. First confirm the cost table behind `estimate_cost_usd` has a row for the new model, so spend tracking stays correct.

**[P2] Per-creator memory without a vector database**
- **Fix:** have Java compute a short "your history" line from `creator_briefs` and `deal_offer_history`, for example "last 5 briefs: 2 declined for perpetual usage; median quote ₹18,000". Add it to Block B. Embeddings aren't needed at this volume.

**[P2] Brand track record on Influora**
- **Fix:** a `get_brand_track_record(brand)` tool: paid on time, disputes, repeat rate. Computed in Java from deals and payment data, shown only above a minimum count, like the rate bands. This is the one thing no outside manager can know, and it makes Meera uniquely useful.

**[P1, tag Kabir, legal] Privacy policy against the code**
- **Where:** `src/content/legal/privacy-policy.md:44,80`
- **Issue:** the policy says data is used for "training and evaluating AI features (Meera)" and kept "only as long as needed". The code trains nothing and deletes nothing: chats and briefs are kept forever, and survive account deletion.
- **Fix:** get both statements right before launch. Any future learning from chats needs explicit consent.

---

## Switching everything on

Nothing below reaches a creator until **Phase A is deployed (S-2)**. The values live in the environment of the live compose file on the server, so changing them is a deploy step, and deploying is your call.

| Switch | Now | Recommendation | Why |
|---|---|---|---|
| `MEERA_CREATOR_ENABLED` | on | **Keep on** | The creator Meera kill switch. |
| `META_MEDIA_METRICS_ENABLED` | on | **Keep on** | Per-post data. The engagement roll-up above depends on it. |
| `CREATOR_COPILOT_ENABLED` | off | **Turn on now** (staging first) | Daily content idea plus caption sync and theme tagging. With it off, a connected creator sees "Usually ready within a day" forever. Theme tagging is plain Java; no caption text goes to AI. Cost per night: one Meta call per creator plus one small AI call per creator who gets a suggestion. |
| `MEERA_INTERACTION_LOG_RETENTION_ENABLED` | off | **Turn on now** | A 180-day purge of a brand-side log. It reduces privacy risk and costs nothing. |
| `TREND_INGEST_ENABLED` (phase-e only) | off | **After 2 decisions** | (1) Which workspace pays for the per-headline AI safety check (`TREND_INGEST_CLASSIFIER_WORKSPACE_ID`). Blank means nothing is stored. (2) API keys: NewsAPI, TMDb, YouTube. Check NewsAPI's plan terms; as far as I know its free plan is for development only. |
| `BRAND_SAFETY_SCORING_ENABLED` | off | **Your call**, with the cap | Brand-side scoring. Costs one AI call per creator per run (capped at 100/run). Get Rohan to confirm the monthly figure first. |
| `CREATOR_CONNECT_NUDGE_ENABLED` | off | **Your call** | Emails real creators who haven't connected Instagram. Good for Meera (more connected accounts means more real data), once Nisha approves the email. |
| `META_CREATOR_MARKETPLACE_ENABLED` | off | **Leave off** | Needs `instagram_creator_marketplace_discovery`, which Meta hasn't granted. With it on, the code quietly falls back and does nothing useful. |
| `MEERA_CREATOR_SEND_ENABLED` | off | **Must stay off** | The gate for Meera sending under the creator's name (B1). The route isn't built. It must stay off until B1 is reviewed; `CreatorSendGateTest` guards it. |

**These are not switches; they have to be built:**
- `draft_reply`, Meera writing replies (B0 Wave 5).
- Deleting a pasted brief (U-7, blocks going live).
- The cap raise (C-1).
- Everything in the findings above.

---

## Data & training roadmap

- **Now:**
  - The creator eval set (50 cases).
  - The engagement and reach roll-up.
  - Prove the live audience response.
  - Turn on `CREATOR_COPILOT_ENABLED` and the retention purge.
  - C-1 cap raise.
- **Next:**
  - Knowledge pack v1 (cached).
  - Guarded web search for brand and trend questions, with citations shown.
  - `get_trends` plus trend history.
  - The side-by-side model test.
- **Then:**
  - "Your history" memory line.
  - Brand track-record tool.
  - Learn from outcomes as they accumulate: accepted counters, sent-unedited drafts (`meera_drafts.edited`) and rate calibration.
- **Later:**
  - Retrieval (embeddings) only if the knowledge pack or per-creator history outgrows the prompt.
  - Fine-tuning: not recommended. Volume is too low, and chats are private. Revisit at about 10k consented, labelled examples.

## Verdict: SHIP WITH P1 FIXES — build the knowledge layer in the order above; do not flip all switches

- **P0 for this plan:** none in today's code.
- **Web search is conditional P0:** it must not ship without the untrusted wrapping, citations and the cap. Those three conditions are part of the design, not optional polish.
- **Routing:**
  - Vikram: backend roll-ups, tools, loop and SDK upgrade.
  - Ananya: citations UI.
  - Nisha and Priya: knowledge-pack facts.
  - Kabir: web-search untrusted handling, and the privacy-policy line.
  - Rohan: cost sign-off.
  - Priya: last call.
