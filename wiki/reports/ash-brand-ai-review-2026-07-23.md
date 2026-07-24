# Ash — Brand-Side AI Code Review (Influora)

**Reviewer:** Ash (AI/ML expert & AI code reviewer)
**Date:** 2026-07-23
**Method:** Code-level review only. Deployed app at `http://200.141.1.6` used as reference behaviour; **zero AI credits spent**. Every answer is backed by an exact file + line/section in this repo.
**Scope:** three brand AI surfaces — (1) Meera chat, (2) Campaign-creation AI, (3) Analytics AI. Plus the traced production defect **M-1** (Meera refuses to create campaigns).

---

## Architecture in one paragraph

Two services own the brand AI. **`influora-ai`** (Python/FastAPI) is the *reasoner*: it holds the Claude/Gemini/Sarvam keys, assembles prompts, runs the function-calling loop, and streams SSE. **`influora-api`** (Java/Spring) is the *money core*: it mints scoped per-turn credentials, executes every tool that changes state, and re-derives/re-authorizes all amounts. Meera **proposes**, Spring **disposes**, the human **commits money**. The two are joined by a dual-credential mesh: an `X-Meera-Service-Token` (service identity) + a per-turn **on-behalf JWT** (which human/workspace). **M-1 lives entirely in that on-behalf JWT's `scope` claim.**

---

## Part A — 15 questions, answered with citations

### Surface 1 — Meera chat (brand "AI cofounder")

**Q1. What model/provider is Meera's brain, and how is it invoked?**
Anthropic Claude, model `claude-sonnet-4-5-20250929`, pinned in `influora-ai/app/config.py:68` (`CLAUDE_MODEL`, env-overridable). The client is `anthropic.AsyncAnthropic` (`influora-ai/app/providers/claude.py:106`), and every chat turn streams via `self._client.messages.stream(model=CLAUDE_MODEL, …)` at `claude.py:136-142`. Sonnet (not Opus/Haiku) is a deliberate quality-vs-cost choice for the conversational brain; the cheap phrasing routes use Haiku (`config.py:86`).

**Q2. How is the prompt constructed?**
Three-block layout for Anthropic prompt caching (`influora-ai/app/prompt/assembler.py:1-26`, `assemble_prompt` at `assembler.py:415`):
- **Block A** — stable persona + tool names, `cache_control: ephemeral` (`assembler.py:115-126`); persona text is `influora-ai/app/prompt/persona.py:17-138`.
- **Block B** — per-brand cached context (name, niches, tone, catalog, templates, past campaigns, outcome digest, credit state), built in `build_block_b` (`assembler.py:267-311`), server-sourced from Spring `POST /internal/meera/context` (`chat.py:82-150`), never from the browser body.
- **Block C** — volatile conversation history (`build_block_c_messages`, `assembler.py:362-397`).
All brand-authored text is angle-bracket-neutralized (`_safe`, `assembler.py:129-137`) and user turns wrapped in `<untrusted_user_message>` (`assembler.py:375-377`) for prompt-injection isolation.

**Q3. How is tool/function-calling wired?**
Six Spring-contract tools + two local tools, single source of truth in `influora-ai/app/tools/schemas.py:38-85` (`show_creators, calculate_budget, create_campaign, request_payment, confirm_launch, get_campaign_performance`; local: `analyze_site`, `present_options`). The loop is `run_tool_loop` (`influora-ai/app/tools/loop.py:105-378`): Claude emits `tool_use` → known-tool check (`loop.py:193`) → local tools run in-process (`loop.py:213-314`) → all others forward to `TOOL_TO_SPRING_PATH[tool]` (`schemas.py:78-85`, forward at `loop.py:316-337`). Money/state tools carry an idempotency key = `tool_use.id + workspace_id` (`loop.py:317-319`, `schemas.py:87-89`). **Python never treats any AI-supplied amount as authoritative** (`loop.py:321-327`).

**Q4. How does streaming work?**
`influora-ai/app/routes/chat.py` returns `StreamingResponse` (`chat.py:491`) with an SSE event protocol documented at `chat.py:7-16` and emitted via `sse_event` (`chat.py:78`): `token`, `thinking`, `tool_start`, `tool_result` (payload carried on `event.tool_result_data`, `chat.py:340-347`), `done`, `error`. A `: ping` heartbeat every ~15s keeps proxies warm (`chat.py:320`, `370-372`). On client disconnect, in-flight provider calls are cancelled so no tokens are wasted (`chat.py:311`, `claude.py:145-147`).

**Q5. What is Meera's context/memory model?**
The Python service is **stateless** — it holds no history between calls (`assembler.py:362-366`). Spring sends the full conversation each turn; Block B is re-fetched per turn and degrades to empty (never 500s) if Spring is down or the brand is still analyzing (`chat.py:104-124`). Cache key is `(prompt_version, audience, workspace_id, session_id)` (`assembler.py:400-412`) — never global — so a brand turn can never collide with a future creator turn.

**Q6. What are Meera chat's failure modes and cost/credit accounting?**
Provider resilience: a circuit breaker opens on sustained failure and refuses to burn credits into a known-bad provider (`claude.py:39-59`, `134`). Spend gating runs before any provider call (`chat.py:209-216`). Credit is charged at **send** time (in Spring), and this route only decides persist-vs-refund: refund (`release_turn_credit`) fires **only** on a genuine provider failure with nothing usable delivered — never on a client disconnect (`chat.py:425-489`). Cost is estimated from token usage via `estimate_cost_usd` (`chat.py:394-397`, `influora-ai/app/costs/pricing.py:194`), with a $3/workspace/day soft cap and a global $15/day ceiling + kill switch (`config.py:328-346`).

### Surface 2 — Campaign-creation AI

**Q7. How does the AI actually build a campaign?**
Via the `create_campaign` tool. Schema at `schemas.py:127-164` (required: `product_name, campaign_type, creator_count`; optional `template_id, creator_ids`). Meera is told to use it in persona `persona.py:118-122`. The loop forwards it to Spring `POST /internal/meera/create_campaign` (`schemas.py:81`, `loop.py:316-337`). Spring's `CreateCampaignExecutor.doExecute` (`influora-api/.../service/meera/tool/CreateCampaignExecutor.java:144-262`) creates a `CampaignIntent` + a `DRAFT` `Campaign` — idempotent via `IdempotencyService.executeOnce` (`CreateCampaignExecutor.java:103-121`).

**Q8. Who is authoritative over money/amounts in a created campaign?**
Not the AI. The tool is D-tier "draft only"; **no budget field is writable by the AI** — `budgetMin`/`budgetMax` are left null on creation (`CreateCampaignExecutor.java:186-212`, class javadoc `:44-59`). Spring re-derives amounts and a human funds escrow in a separate C-tier step. `request_payment`'s `display_amount_hint` is chat-copy only and discarded by Spring (`schemas.py:166-183`, `loop.py:321-327`).

**Q9. How do campaign templates feed the AI?**
`template_digest` is rendered into Block B (`assembler.py:140-171`) so Meera can recommend a template by name and pass its `template_id`. When set, Spring derives `campaign_type` from the template row (may be `STANDARD`), ignores any AI-supplied type, and copies requirements/hashtags/audience/guidelines — but **never budget** (`CreateCampaignExecutor.java:159-210`); visibility is enforced by `CampaignTemplateService.requireVisible` (`CreateCampaignExecutor.java:165`).

**Q10. [M-1] Why does Meera refuse to create a campaign in production?**
**Root cause: the on-behalf JWT scope gate.** The per-turn on-behalf token is minted with a hardcoded scope of **only the two read tools**:
```
OnBehalfTokenService.SCOPE_READ_ONLY = "show_creators calculate_budget"   // OnBehalfTokenService.java:59
.claim("scope", SCOPE_READ_ONLY)                                          // OnBehalfTokenService.java:95
```
But every tool route in `MeeraInternalController` now enforces that the token's `scope` lists the exact tool being called. `create_campaign` requires scope `create_campaign`:
```java
onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
        onBehalfJwt, workspaceId, MeeraToolName.create_campaign.name());   // MeeraInternalController.java:174-177
```
`requireScope` splits the scope string and rejects because `create_campaign` isn't in `"show_creators calculate_budget"` → **403 `ON_BEHALF_SCOPE_INSUFFICIENT`** (`OnBehalfAuthResolver.java:162-173`). Python's loop catches the `SpringCallError`, yields a `tool_result` with status `error` (`loop.py:338-354`), Claude reads the failed tool result and **narrates a refusal to the brand** ("I can't create the campaign from this session — build it in the dashboard"). The refusal is model-generated, not a canned string (grep confirms the phrasing exists only in wiki reports/tests, never in code).
The tool is fully defined, offered, forwarded, and executable end-to-end — **the single blocker is the minted scope string.** The design doc itself flags this as unfinished: `OnBehalfTokenService.java:51-58` says "Extend this … when a write tool (e.g. create_campaign) is enabled."

### Surface 3 — Analytics AI

**Q11. What analytics AI exists on the brand side?**
The `get_campaign_performance` tool (R-tier, added Phase 2) — schema `schemas.py:199-211` returns verified spend, reach, engagement, ROI, response rate, attributed revenue for one owned campaign; executed by `GetCampaignPerformanceExecutor` behind IDOR checks (`MeeraInternalController.java:226-236`). Meera is instructed to call it for any "how did it do / what was the ROI" question and to quote **only** verified figures, never estimates (`persona.py:128-134`). A second analytics-adjacent surface is GARM brand-safety scoring (see Q13). The moat's cross-tenant **outcome digest** (real niche rate bands from completed collaborations) is injected into Block B (`assembler.py:201-264`).

**Q12. Is the analytics tool actually live?**
**No — it is dead for the same reason as M-1.** `get_campaign_performance` also calls `resolveForWorkspaceRequiringScope(…, "get_campaign_performance")` (`MeeraInternalController.java:230-232`), and that tool is **not** in `SCOPE_READ_ONLY` either. So any brand asking Meera for campaign performance gets a 403 → the same model-narrated refusal. **Four of the six Spring tools** (`create_campaign`, `get_campaign_performance`, `request_payment`, `confirm_launch`) are structurally 403'd today; only `show_creators` and `calculate_budget` work.

**Q13. How does the brand-safety analysis AI work, and what does it cost?**
`POST /internal/brand-safety` uses Claude with a **forced single tool** (`analyze_creator_content`, `schemas.py:344-429`) via `complete_with_forced_tool` (`claude.py:264-331`, `tool_choice`) to return structured GARM classification + sentiment JSON instead of prose. Model is `BRAND_SAFETY_MODEL`, defaulting to **Sonnet** (`config.py:105-115`) — deliberately, but flagged as the biggest avoidable cost once fan-out scales; the Haiku flip is gated on a GARM golden-set A/B.

### Cross-cutting — voice + product understanding

**Q14. How does voice (TTS/STT) work, what models, what cost?**
Cascade: **Sarvam STT → Gemini grammar cleanup → Sarvam TTS**. `POST /voice/transcribe` = Sarvam STT (`voice.py:191-201`) then Gemini cleanup (`voice.py:212-213`, `GEMINI_MODEL`); `POST /voice/speak` = Sarvam TTS (`voice.py:291-292`). Every stage fails **silently to text** (`voice.py:203-210`, `294-298`). The browser proxies through Spring's `MeeraController.speak`/`transcribe` (`MeeraController.java:237-322`) with a service token; response contract is locked so only auth is ever non-200. TTS text is truncated to 500 chars for cost (`voice.py:34`, `110-143`). Cost: STT flat $0.006/call, TTS char-scaled at ₹30/10k chars (`pricing.py:61-111`).

**Q15. How does Meera understand a product from a URL (analyze_site), and how safe/cheap is it?**
`analyze_site` is a **local Python tool** (not forwarded to Spring, `schemas.py:47-58`): SSRF-guarded fetch → HTML sanitized (`analyze_site.py:77-89`) → structured JSON-LD/OpenGraph facts extracted from raw HTML *before* sanitization (`analyze_site.py:203-207`) → **Gemini** (`gemini-2.5-flash`, `config.py:67`) classifies niche/tone/catalog (`analyze_site.py:242-243`, prompt `gemini.py:40-72`). Scraped prices are re-asserted over model output so a hallucinated price can never win (`merge_known_products`, `analyze_site.py:106-138`). A recent fix makes Gemini classify the **site owner's** business, not showcased example brands (`gemini.py:46-55`). Gemini rates: $0.30/$2.50 per MTok (`pricing.py:51`).

---

## Part B — Prioritized improvement plan

### P0 — Ship-blockers (product promise is currently false)
1. **Fix M-1 / analytics death — widen the minted on-behalf scope.** One-line-class change in `OnBehalfTokenService` (spec in Part C). Without it, "AI runs your campaigns end to end" is untrue: Meera can find creators and quote a budget, then cannot create, cannot report performance, cannot request payment, cannot launch. This is the highest-leverage fix in the entire brand AI surface.
2. **Add a regression eval + contract test** asserting each of the six tools is reachable given a freshly minted token. The gap that produced M-1 is exactly the class of bug an eval catches: the Python side, the schema, the executor, and the controller are all correct in isolation; only the *credential* is wrong, so every unit test passes while the live flow is dead. Wire a live-token round-trip test in `OnBehalfAuthResolverTest` / a Python `test_chat_money_path` extension.

### P1 — Cost & correctness
3. **Move brand-safety off Sonnet to Haiku** once the GARM golden-set A/B passes (`config.py:105-115`). GARM labeling is bounded, schema-locked classification — a textbook Haiku job. Biggest avoidable recurring cost as fan-out scales.
4. **Confirm `create_campaign` idempotency-key header is always sent.** The controller hard-requires `Idempotency-Key` (`MeeraInternalController.java:171`); Python sets it only for `IDEMPOTENT_REQUIRED_TOOLS` (`loop.py:317-319`) — which does include `create_campaign`, so this is correct today, but the coupling is implicit. Add an assertion so a future schema edit can't silently 400 every create.

### P2 — Smarter outputs
5. **Let analytics answers cite the outcome digest.** Block B already carries verified niche rate bands and campaign outcomes (`assembler.py:201-264`); the persona should explicitly tell Meera to ground budget/ROI claims in that real cross-tenant data rather than only `calculate_budget`'s heuristic. This is the moat — surface it.
6. **Tighten `analyze_site` for JS/SPA storefronts.** The path is intentionally browserless (`analyze_site.py:45-57`); structured-extraction recovery is good but client-rendered stores still come back thin. Track the `analyze_site_structured_only_recovery` signal (`analyze_site.py:225-230`) to decide whether the gated render sidecar is worth it.
7. **Raise `meera_chat_max_tokens` observability.** 384 output tokens (`config.py:242-244`) is a good spoken-length backstop, but pair it with a `done` reason so a truncated tool-planning turn is visible rather than silently short.

---

## Part C — Concrete fix spec for M-1 (implementable by a backend dev)

**File:** `influora-api/src/main/java/com/influora/service/meera/OnBehalfTokenService.java`

**Change 1 — add a full-tool scope constant** (next to `SCOPE_READ_ONLY` at line 59):
```java
/**
 * Full brand-turn tool scope. create_campaign (D) and get_campaign_performance (R) move
 * no money; request_payment/confirm_launch (C) are ADDITIONALLY gated on OWNER/ADMIN at the
 * resolver (resolveForWorkspaceRequiringElevatedRoleAndScope), so listing them here cannot
 * bypass the human-confirm money rail — scope only unblocks the tools the human is allowed
 * to drive; role/tier still protects money. Keep in sync with MeeraToolName.
 */
public static final String SCOPE_ALL_TOOLS =
        "show_creators calculate_budget create_campaign get_campaign_performance "
        + "request_payment confirm_launch";
```

**Change 2 — mint the full scope** (line 95, inside `mint(...)`):
```java
// was: .claim("scope", SCOPE_READ_ONLY)
.claim("scope", SCOPE_ALL_TOOLS)
```

**Why this is safe:**
- The scope claim only authorizes *which tool routes the token may reach*. It does **not** move money. `request_payment`/`confirm_launch` remain protected by the independent OWNER/ADMIN check in `resolveForWorkspaceRequiringElevatedRoleAndScope` (`OnBehalfAuthResolver.java:148-153`) — both checks must pass, and this change touches only the scope half.
- `ToolCallValidator` tier gate (`ToolCallValidator.java:38-45`) is unchanged; no tool maps to FORBIDDEN.
- The AI still cannot set a budget (`CreateCampaignExecutor.java:186-212`); create remains draft-only.
- Token TTL stays 120s single-turn (`OnBehalfTokenService.java:43`).

**If a more conservative rollout is wanted:** mint scope by role — `create_campaign get_campaign_performance` for everyone, appending `request_payment confirm_launch` only when `userType`/workspace role is elevated. `userType` is already passed into `mint(...)` (`MeeraSessionService.java:255`), so the signature needs no change; the role lookup would need threading in. The flat `SCOPE_ALL_TOOLS` above is sufficient and correct because the resolver already re-checks role for money tools.

**Tests to update:** `OnBehalfAuthResolverTest.java` (add a create_campaign/get_campaign_performance accept case), and add a live-token round-trip so all six tools are proven reachable. Update any test asserting the exact `SCOPE_READ_ONLY` string is what gets minted.

**Verification after fix:** ask Meera to create a campaign → expect a `tool_start`/`tool_result{status:"ok"}` for `create_campaign` (`chat.py:330-349`) and a `DRAFT` campaign row, not a refusal.
