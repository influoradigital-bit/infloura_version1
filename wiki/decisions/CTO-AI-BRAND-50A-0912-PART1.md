# CTO → CEO: Answers to Q1–Q28 (Sections A, B, C)

**From:** Priya (CTO)
**To:** Swapnil Maruti (CEO)
**Date:** 2026-09-12
**Branch inspected:** `feat/meera-creator-phase-e` (working tree; `main` is 2 weeks behind at `8f1153d`)
**Scope:** Q1–Q28 only. Q29–Q50 are answered separately.

---

## EXECUTIVE SUMMARY — the three things you need to know

1. **The analytics tool is dead, not slow.** `get_campaign_performance` requires a `campaign_id` copied from an `[id=...]` marker in Meera's context. Spring's context DTOs (`MeeraContextDtos.java:52-55`, `:76-83`) carry **no campaign id field at all**, so that marker is never rendered. Meera is instructed to refuse rather than guess — so she tells every brand "I can't pull verified numbers for that yet," every time, forever. The old scope 403 was fixed; this replaced it.
2. **`ToolCallValidator` — the thing we call our tool choke point — blocks nothing at runtime.** All six call sites pass a hardcoded compile-time enum (`MeeraInternalController.java:198-307`), so the name-whitelist can never throw and the tier gate can never fire. Real tool-name rejection happens in Python, not here.
3. **Meera structurally cannot touch money, and that part is genuinely solid.** The two commit-tier tools are not offered to the model, not in the token scope, and additionally role-gated — three independent layers. Everything else on the brand side is live, routed, linked, and reachable by a logged-in brand today; the failures are quality failures, not vapour.

**Verification:** 26 of 28 answered from code I opened. 2 marked UNVERIFIED (live deployment state, real-world analyze_site failure rate) — both stated as such, with the exact check that would close them.

---

# SECTION A — What the AI actually is

## Q1. Which model, where configured, who changes it

Provider is Anthropic via the official SDK — `influora-ai/app/providers/claude.py:116` (`anthropic.AsyncAnthropic`). Model id comes from one constant: `influora-ai/app/config.py:68` — `CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-sonnet-4-5-20250929")`.
**Live value is the code default**: `deploy/utho/docker-compose.utho.yml:283-321` sets `ANTHROPIC_API_KEY`, `MEERA_CHAT_MAX_TOKENS` and others but does **not** set `CLAUDE_MODEL`. So production runs `claude-sonnet-4-5-20250929`.
Anyone with shell on the Utho box can change the model by adding `CLAUDE_MODEL` to the compose env and restarting the container — **no code deploy, no review, no audit trail**. There is no allow-list of permitted model ids anywhere.
Other models exist for other jobs: `TRENDSPARK_MODEL` (Haiku, `config.py:132`), `BRAND_SAFETY_MODEL` (`:161`, defaults to Sonnet). Prompt version stamped on every turn: `config.py:69` — `meera-2026.08.10.1`.
→ **For the brand:** brand chat runs on Claude Sonnet 4.5; the model can be swapped by one ops person in minutes, which is both our fastest cost lever and an uncontrolled one.

## Q2. Which provider serves a brand turn; fallback chain

**Claude only.** `chat.py:82,131` instantiates `ClaudeProvider`; `loop.py:195` is the sole call into `stream_turn`. No brand chat turn ever touches Gemini or Sarvam.
`gemini.py` is imported by exactly two files: `analyze_site.py:37` (classifying a scraped store page, `GEMINI_MODEL = "gemini-2.5-flash"`, `config.py:67`) and `voice.py:61`. `sarvam.py` is imported only by `voice.py:62` (Indian-language TTS/STT).
**There is no fallback chain.** `claude.py:39-59` is a circuit breaker: 5 consecutive failures opens it (`config.py:208-209`), and an open circuit raises `CircuitOpenError`, which `chat.py:774-784` turns into an SSE `error` event with code `provider_timeout`. Nothing retries into a second provider. By design — the comment at `claude.py:29-30` says surfacing a degraded error beats silently retrying into a known-bad provider.
"Has it ever fired" — `UNVERIFIED`: the breaker state is per-process in memory and emits no metric; only the app log would show it.
→ **For the brand:** if Anthropic is down, Meera is down. There is no second brain.

## Q3. One brand message, end to end

1. `src/components/feature/meera/MeeraChatPanel.tsx` composer → `meeraApi.sendTurn` (`src/lib/meera-api.ts:536`) → `POST /meera/sessions/{id}/messages`.
2. `influora-api/.../web/MeeraController.java:117` → `MeeraSessionService.sendTurn` → `doSendTurn`: charges 1 AI credit **at send** (`creditService.tryConsumeForTurn`), saves the USER `ai_messages` row, then mints **two** tokens — a stream token (`StreamTokenService.mint`) and a per-turn on-behalf JWT (`OnBehalfTokenService.mint`, `:82`). Returns immediately; no Python call from Java.
3. Browser opens its own SSE connection **direct to Python** — `src/hooks/useMeeraStream.ts` POSTs to `${VITE_MEERA_STREAM_URL}/chat` (built as `https://ai.influora.in`, `.github/workflows/publish-images.yml:251`).
4. `influora-ai/app/routes/chat.py:337` verifies the stream token, derives audience from a signed claim (`:396`), then calls back into Spring `POST /internal/meera/context` (`_fetch_brand_context`, `:164`) for Block B.
5. `app/prompt/assembler.py:809` builds Block A (persona + tools) / Block B (brand facts) / Block C (replayed history) → `app/tools/loop.py:144` → `app/providers/claude.py:130` → Anthropic.
6. Text deltas: `claude.py:224` → `loop.py:218` → `chat.py:719` (`sse_event("token")`) → `MeeraChatPanel.tsx:526` appends. On completion `chat.py:896` posts the final text back to Spring.
→ **For the brand:** two hops and two independent credentials per message — the browser talks to the AI service directly, so Java never blocks on the model.

## Q4. Why a separate Python service

It is deliberate and documented: `influora-ai/app/main.py:3` — "Stateless per request: no DB, no session store, no local disk writes." Three things it buys us: (a) the browser can stream SSE straight from it without holding a Spring thread open for the length of a model turn; (b) the Anthropic/Gemini/Sarvam SDKs and their async streaming are first-class in Python and painful in Java; (c) it can be restarted, scaled or rolled back without touching the money path — no schema, no migrations.
What it costs: a second credential surface (the on-behalf JWT + service token + HMAC signature, `application.yml:246-253`), a schema-drift seam between Python dicts and Java DTOs that CI only partly covers — **and that seam is exactly where our worst defect lives** (Q17).
When it is down: see Q5.
→ **For the brand:** the split is the right call architecturally; the bill is that two teams' shapes have to agree, and today they don't.

## Q5. What a brand sees when `influora-ai` is unreachable — **and the credit is not refunded**

**Bad news first: the credit is charged before Python is ever contacted, and the refund path runs only inside Python.** `MeeraSessionService.doSendTurn` charges at send; the refund is `POST /internal/meera/turns/release`, called from exactly one place — `influora-ai/app/clients/spring.py:344` via `chat.py:945`/`:952`. If Python is unreachable, nothing calls it. The brand pays for a turn that never happened.
The UI does not hang. `useMeeraStream.ts:271-280` catches the failed `fetch` and fails with `CONNECTION_ERROR`; `:350-358` catches a socket that closes without a `done` frame (`STREAM_INCOMPLETE`). The panel then does **not** re-POST (that would double-charge — `MeeraChatPanel.tsx:621-623`); it calls `getMessagesAfter` to see if the server finished anyway (`:624-631`), and failing that shows "Didn't catch that — try again?" (`:632-643`).
No spinner-forever: there is a 30s heartbeat watchdog (`useMeeraStream.ts:60,140-150`) — but `MeeraChatPanel` passes no `onHeartbeatTimeout` handler, so a stall only produces a `console.warn` (`:146`).
→ **For the brand:** they get an honest error in a few seconds, but they are silently billed a credit for it.

## Q6. Persona size, cost per turn, and whether caching is really on

`app/prompt/persona.py:17-232`. Measured, not estimated (I imported and counted): **14,956 characters ≈ 3,700 input tokens**. The six tool schemas add **8,127 characters ≈ 2,000 tokens**. Block A is therefore **~5,800 tokens on every single turn**. Authorship is not recorded in the file; it carries fix markers from Ash, Kabir and prior CTO reviews — treat it as team-owned, `PROMPT_VERSION`-stamped.
Caching **is** wired: `cache_control: {"type": "ephemeral"}` on Block A (`assembler.py:234`) and Block B (`:432`).
**But Block B's cache breakpoint misses on every turn.** `build_block_b` renders `remaining={credits_remaining}` (`assembler.py:425-429`), and that number decrements on every send (`MeeraSessionService.doSendTurn` → `tryConsumeForTurn`). A changed byte = a new cache entry, written at 1.25× input price instead of read at 0.1×.
**Can I prove caching works?** Not from our own telemetry. `claude.py:282-287` receives `cache_read_input_tokens`, and `pricing.py:280-296` prices it — but the `ai_spend` log event (`chat.py:808-829`) emits cost, `stop_reason` and `output_tokens` and **never emits the cache fields**. So we bill correctly and cannot measure the hit rate.
→ **For the brand:** we pay for ~5,800 tokens of instructions per message; the big block is cached, the brand-facts block is being paid for at full price every time because a credit counter sits inside it.

## Q7. Streaming behaviour and mid-stream drops

Real token streaming, not a spinner-then-block. `claude.py:221-224` yields each `text_delta`; `loop.py:218` normalises it; `chat.py:717-719` writes `event: token`; `MeeraChatPanel.tsx:526` does `assistantText += event.text` and updates the bubble in place (bubble created lazily on the first token, `:533`).
Stream owner on the wire is `app/routes/chat.py` (`StreamingResponse`, `:954-962`, with `X-Accel-Buffering: no`). Client owner is `src/hooks/useMeeraStream.ts` — a `fetch` + `ReadableStream` reader (`:311-337`), deliberately not `EventSource` because the Python edge is POST-only (`:6-18`).
On a drop: `:338-348` → `CONNECTION_ERROR`; a clean close with no terminal frame → `STREAM_INCOMPLETE` (`:350-358`). **No reconnect, by design** (`:27-28`) — the stream token is single-use, so a retry would 401. Recovery is one `getMessagesAfter` fetch. Note the contrast: our deal-chat stream *does* reconnect with backoff (`src/lib/api.ts:2668-2680`); Meera's does not.
Server-side, a disconnect is handled properly: `chat.py:654-682` drains the provider's partial usage so we still record what Anthropic billed us.
→ **For the brand:** replies appear word by word; if the connection dies mid-sentence they get the finished reply back on one retry fetch, or an honest error — never a frozen screen.

## Q8. Does Meera remember a brand between sessions

Yes, three different ways — and one of them is unbounded.
(1) **The conversation never ends.** `MeeraSessionService.java:128-131` resumes the workspace's most recent `ACTIVE` conversation. I grepped every write of `ConversationStatus` in `src/main`: the only value ever written is `ACTIVE` (`AiConversation.java:139`, `MeeraSessionService.java:141`,`:207`). Nothing archives or closes a thread, ever.
(2) **History has no cap.** `MeeraSessionService.java:672-683` — with no `after` cursor, `listMessages` returns the **entire** message history with no LIMIT. That is what the browser reloads when a brand reopens the page.
(3) **Durable brand facts** are re-fetched fresh every turn into Block B (`chat.py:164`): display name, niches, tone dial, brand colour, product catalog, campaign templates, past-campaign summary, outcome digest, credit state (`assembler.py:388-432`).
Important nuance: the model's memory of the *chat* is only what the **browser** replays in the request body (`chat.py:556`), and `assembler.py:670-757` deliberately treats all of it as untrusted data, not as verified platform facts.
→ **For the brand:** Meera knows their brand and their history — but a heavy user's thread grows without limit and will eventually get slow and expensive.

---

# SECTION B — Where the AI touches the brand

## Q9. Every brand screen where AI runs — complete list

| Route | Component | Guard | What AI does |
|---|---|---|---|
| `/brand/meera` | `src/pages/brand-meera.tsx` → `MeeraWorkspace` (`src/App.tsx:356-363`) | Yes, `BrandLayoutWrapper` | The only brand chat surface. Chat panel + 6-stage Living Canvas |
| `/brand/onboarding` | `src/components/brand/onboarding/onboarding-steps.tsx:1107-1114` | Yes | Website field; triggers server-side `analyze_site` (no AI UI here) |
| `/brand/help` | `src/pages/brand-help.tsx:66` | Yes | "Ask Meera" deep-link into `/brand/meera` with a pre-seeded query |
| `/brand/wallet`, deal room | `FundEscrowButton` (`src/pages/brand-wallet.tsx:83`, `deal-payments-tab.tsx:9`) | Yes | The human money step Meera redirects to — not itself AI |
| `/meera-for-creators` | `src/pages/meera-for-creators.tsx` (`src/App.tsx:734`) | **No guard — public** | Marketing page, creator-facing |

That is the complete set. The landing page uses `MeeraOrb` (`src/pages/landing.tsx:40`) as decoration only — no model call.
→ **For the brand:** one real AI screen, reached from the sidebar, plus an invisible site-analysis step at signup.

## Q10. Is `brand-meera.tsx` real, and is it linked

**It is real, routed, guarded and linked — no caveat.** Route `/brand/meera` at `src/App.tsx:356-363`, wrapped in `BrandLayoutWrapper` → `ProtectedRoute` (redirects to `/brand/login` when unauthenticated, `:183`, and to `/brand/onboarding` when onboarding is incomplete, `:187-189`).
Nav link: `src/components/brand/brand-layout.tsx:122` — `{ label: 'Meera', href: '/brand/meera', icon: Sparkles }` — the **second item in the Main sidebar group**, between Home and Campaigns. Also in the ⌘K command bar (`src/components/brand/command-bar.tsx:80`) and three notification deep-links (`src/hooks/useNotifications.ts:94,103,112`).
A logged-in brand with completed onboarding can reach it today, in one click.
One orphan worth knowing: `src/components/feature/meera/CreditMeter.tsx` has **zero importers**, and `src/hooks/useMeeraCredits.ts` is never invoked by any component — so the credit meter we built never renders. The live paywall uses separate local state in `MeeraChatPanel.tsx:225,707,793-794` and does work.
→ **For the brand:** Meera is a first-class, prominently placed product surface, not a demo.

## Q11. Store URL at signup — what actually happens

The field is `Website (optional)` — `src/components/brand/onboarding/onboarding-steps.tsx:1107-1114`, a plain `<input type="url">` with no validation beyond the browser's. It is submitted with the rest of the company step: `src/pages/brand-onboarding.tsx:104` → `src/lib/api.ts:1493` → `POST /onboarding/brand/company`.
**There is no "analyzing…" state in onboarding and no result shown there.** Analysis runs server-side and its output only ever surfaces inside the Meera canvas: `src/components/feature/meera/StageSnapshot.tsx:110-113` (the "Reading your site…" state) and `:197-219` (the result — site avatar, URL, niche tags, extracted product grid), gated on `analysisStatus === 'READY'`.
Polling gives up silently: `src/hooks/useBrandProfile.ts:29-30` polls every 4s, **max 30 polls (~2 minutes)**, then stops (`:50-52`).
It does populate the profile: the catalog/niche/tone land on `BrandProfile` and are read back into every Meera turn (`assembler.py:408-415`).
→ **For the brand:** we take their URL, read their store, and use it — but they are never told we did it, and if it takes over two minutes they see nothing at all.

## Q12. What `analyze_site` really extracts

Exact output shape — `app/routes/analyze_site.py:302-311`:
`{success: true, data: {source_url, niche_tags, tone_dial, brand_color, product_catalog: [{name, price, currency, price_source}]}}`, or `{success: false, error: {code, message}, degraded: "paste_a_link"}`.
Two sources, and the honest one wins: structured facts (schema.org JSON-LD / OpenGraph / microdata) are parsed from the raw HTML **before** sanitisation (`:223-224`), then Gemini 2.5 Flash fills gaps (`:268`), then `merge_known_products` (`:107-139`) re-asserts scraped prices over anything the model said and stamps everything else `price_source: "inferred"`. A model can never pass off a guess as a scraped price.
**The known weakness is explicit in the code** (`:46-58`): the fetch is a plain `httpx` GET — **no JavaScript rendering**, despite `playwright` sitting in `requirements.txt`. Client-rendered storefronts come back near-empty and return `empty_page` (`:244-249`) unless they emit JSON-LD server-side.
**How often it returns garbage: `UNVERIFIED`.** The failure modes are individually logged (`analyze_site_ssrf_blocked`, `analyze_site_decode_failed`, `empty_page`, `classify_failed`) but nothing aggregates them into a rate. Closing this needs one log query on the live box.
→ **For the brand:** on a normal Shopify/WooCommerce store we read real products and real prices; on a heavily JS-rendered site we may read nothing and ask them to type it in — and we cannot currently tell you how often that is.

## Q13. AI campaign creation — real row or throwaway draft

**Real, persisted rows.** `CreateCampaignExecutor.java:274-286` writes a `campaign_intents` row; `:379` writes the `campaigns` row via `campaignRepository.save`. It survives closing the tab and appears in the brand's campaign list.
It is properly guarded: role check first (`:150-152`, OWNER/ADMIN/MANAGER only — the on-behalf scope proves *which tool*, not *which member*), then idempotency via `IdempotencyService.executeOnce` (`:160-164`) so a double-submit cannot create two drafts, with a replay path (`:154-157`).
Content is genuinely composed, not an empty shell: title, description, objectives, platforms, content types, hashtags and target audience all come from the conversation (`:320-349`), with server-side allow-lists on platforms/content types (`:89-93`) so the model cannot invent an enum value. Target audience is stored as `interests` only — never a fabricated age or gender (`:339-348`).
→ **For the brand:** they end up with a real, reviewable draft campaign in their dashboard, not a chat transcript.

## Q14. Is an AI-created campaign ever live without a human click

No. The guarantee is a hardcoded literal with no input path to it: `CreateCampaignExecutor.java:300` — `.status(CampaignStatus.DRAFT)`. There is no branch, no parameter, no template field that can change it.
Money is structurally unwritable on this path: `budgetMin`/`budgetMax` are never set (`:288-291`), and for HYPE campaigns `perReelRate`, `slotCap` and `liveUntil` are explicitly passed as `null` with the comment "HUMAN ONLY, never AI-set" (`:370-374`). Dates are never accepted at all — the tool schema has no date field (`schemas.py:147-289`).
The only code that flips a campaign to ACTIVE is `ConfirmLaunchExecutor`, and it refuses unless it reads a real `EscrowStatus.FUNDED` hold from the database (`:256-275`) — nothing the AI asserts is consulted. That tool is also not offered to the model at all (Q18).
The persona reinforces it in words: `persona.py:104-109` — "NEVER say a draft is 'live', 'up', or 'running'."
→ **For the brand:** nothing Meera does can spend their money or publish to creators. A human sets the budget and clicks launch.

## Q15. Creator discovery — what actually runs

**A LIKE query, not a match algorithm — and it searches the wrong fields.** `ShowCreatorsExecutor.java:56-63` builds a spec from `CreatorProfileSpecifications.nameSearch(niche)` + `singleCity(city)`. And `nameSearch` (`CreatorProfileSpecifications.java:30-41`) is `LIKE '%<niche>%'` against **displayName, username, bio and city** — it does **not** touch `categoriesJson`, even though the result object returns categories (`ShowCreatorsExecutor.java:73`).
So "find me skincare creators" only returns creators who literally typed "skincare" in their handle or bio. A creator tagged `Beauty` with a bio that says "I make glow content" is invisible.
Ranking is `Sort.by(DESC, "totalFollowers")` (`:62`) — pure follower count, zero relevance weighting. Hard cap 10 results (`:37`).
The javadoc claims "verified pool" (`:19-20`) but **there is no `isVerified` or discoverability filter in the spec** — unverified and half-complete profiles can be returned. `isVerified` is only reported as a field (`:76`).
There is no unit test for this executor at all.
→ **For the brand:** "find me creators" returns a keyword search over bios sorted by follower count. It looks like matching and is not — this is the gap between what we sell and what runs.

## Q16. Budget — the formula

Hardcoded multipliers. `CalculateBudgetExecutor.java:158-169`: `awareness` ×0.08, `launch` ×0.12, `conversion` ×0.15, `review` ×0.06, anything else ×0.10 (`:30`). Per-creator rate = `product_price × multiplier`; pool = rate × creator count, defaulting to **5 creators** (`:31`, `:53`). Currency always `"INR"` (`:110`).
**No market data of any kind is consulted.** The one genuinely defensible thing it does is provenance: `price_source` is re-derived from our own persisted `BrandProfile.productCatalogJson` (`:122-155`) and is never read from the model's input — Kabir demonstrated the model self-certifying a guessed price as "scraped", and that field was removed from the schema entirely (`schemas.py:116-121`). When provenance is unknown it fails safe to `"inferred"` and the rationale string tells Meera to say "based on an estimated price" (`:91-96`).
The irony: we **do** have real market data — `niche_rate_band` (min/median/max from real completed collaborations, k-anonymity floor n≥5, `BrandContextAssembler.java:262-263`, rendered at `assembler.py:370-381`). `calculate_budget` never reads it.
→ **For the brand:** the budget we quote is a percentage of their product price, not a market rate — and we already own the market rates, we just don't feed them to the tool that quotes numbers.

## Q17. Analytics — the worst finding in this document

Your memory is **half right, and the reality is worse.**
The tool itself is clean: `GetCampaignPerformanceExecutor.java:128` filters `DeliverableMetric` to `SOURCE_PLATFORM_VERIFIED` only, `:131` sums spend strictly from `EscrowStatus.RELEASED` holds, `:184` always labels the result `PLATFORM_VERIFIED`. Self-reported numbers are **omitted, not relabelled**. Only `DeliverableVerificationService.java:297` (a platform API fetch) can write a verified row; a creator self-report can never overwrite one (`DeliverableMetric.java:166-171`).
**But the tool can never be called.** Both the persona (`persona.py:174-176`) and the tool description (`schemas.py:337-340`) instruct: the `campaign_id` **must** be copied verbatim from an `[id=...]` marker, and if none is listed Meera must say the figures are unavailable and never invent an id. `assembler.py:309-310` and `:351,360-361` render that marker **only if the context payload carries a campaign id**. It never does: `MeeraContextDtos.PastCampaignEntry` (`:52-55`) is `{type, creator_count, funded}` and `CampaignOutcomeEntry` (`:76-83`) is `{type, creator_count, spend_inr, funded, verified_reach, reach_source, attributed_revenue_inr}`. **Neither has an id field.**
The F-18 fix was applied on the Python rendering side and the Java DTO was never widened. CI cannot catch it: the Python↔Java drift check covers top-level field names only, and `assembler.py:334` says so in as many words.
→ **For the brand:** ask Meera "how did my Diwali campaign do?" and she will say she can't pull verified numbers — for every campaign, forever. She never presents self-reported numbers as verified; she presents nothing at all.

## Q18. Payments — can the AI move money

**No, and there are three independent layers stopping it.**
1. **The tools are not offered to the model.** `schemas.py:448-452` filters commit-tier tools out of `get_tool_schemas()`. I imported and ran it: the model sees exactly six tools — `show_creators, calculate_budget, create_campaign, get_campaign_performance, analyze_site, present_options`. `request_payment` and `confirm_launch` are absent, so there is nothing for Meera to propose.
2. **The token scope excludes them.** `OnBehalfTokenService.java:68-69` — `SCOPE_DEFAULT` is four tools, money tools deliberately omitted pending security sign-off (`:63-66`); enforced at `OnBehalfAuthResolver.java:178-189`.
3. **Role gate.** Both money routes additionally require OWNER/ADMIN (`MeeraInternalController.java:256,280` → `OnBehalfAuthResolver.java:132-137`).
Even if one were reached, `RequestPaymentExecutor` returns `PENDING_CONFIRM` and never touches a wallet (`:23-34`, `:172`); `ConfirmLaunchExecutor` verifies a real FUNDED hold from the DB (`:256-275`).
The human step is outside chat entirely: the brand opens the wallet and clicks Secure Campaign Funds (`FundEscrowButton`, `src/pages/brand-wallet.tsx:83`). `persona.py:155-166` instructs Meera to redirect there and never claim she started a payment.
→ **For the brand:** Meera physically cannot spend their money, and we can say so with a straight face. This is our strongest safety claim.

---

# SECTION C — What the AI can actually DO

## Q19. The complete brand tool list

| Tool | What a brand gets |
|---|---|
| `analyze_site` | Paste a store link and Meera reads the real products and prices off the page instead of guessing (local, `analyze_site.py:142`) |
| `show_creators` | A list of up to 10 creators matching a keyword + city, sorted by followers (`ShowCreatorsExecutor.java:48`) — see Q15 for how weak this is |
| `calculate_budget` | A suggested pool total and per-creator rate from the product price (`CalculateBudgetExecutor.java:44`) — advisory, never charged |
| `create_campaign` | A real, composed DRAFT campaign in their dashboard (`CreateCampaignExecutor.java:132`) |
| `get_campaign_performance` | Verified spend/reach/ROI for one of their campaigns (`GetCampaignPerformanceExecutor.java:102`) — **currently uncallable, Q17** |
| `present_options` | Tappable choice cards instead of a wall of text (`loop.py:354-393`) |

Not offered to the model: `request_payment`, `confirm_launch` (Q18). Not part of chat at all: `analyze_creator_content`, used only by the batch brand-safety route (`schemas.py:469-486`).
→ **For the brand:** six things Meera can do; five of them work.

## Q20. READ vs WRITE per tool

| Tool | Tier | Mutates our DB? |
|---|---|---|
| `show_creators` | R (`schemas.py:69`) | Only an audit row (`ShowCreatorsExecutor.java:79`) |
| `calculate_budget` | R (`:70`) | Only an audit row (`CalculateBudgetExecutor.java:99`) |
| `get_campaign_performance` | R (`:74`) | Only an audit row (`GetCampaignPerformanceExecutor.java:161`) |
| `create_campaign` | **D — WRITE** (`:71`) | **Yes:** `campaign_intents`, `campaigns`, `meera_tool_calls`, `idempotency_keys`, audit, interaction log (`CreateCampaignExecutor.java:274,379,391`) |
| `analyze_site` | local | **Yes, indirectly:** writes back onto `BrandProfile` via `POST /internal/meera/analyze_site_result` (`loop.py:421-438`) |
| `present_options` | local, display | **Yes, one telemetry row** in `meera_interaction_log` (`loop.py:369-378`) |
| `request_payment` / `confirm_launch` | C | Unreachable today; `confirm_launch` *would* write Collaboration invite rows and flip a campaign ACTIVE |

Only one tool the model can reach writes business data: `create_campaign`, and it can only write a DRAFT with no money fields.
→ **For the brand:** exactly one AI action changes anything in their account, and it produces a draft they must approve.

## Q21. What is enabled in production right now

**There is no feature flag gating brand-side Meera. At all.** I grepped the entire repo for `MEERA_BRAND*` / `brand-enabled` / `MEERA_ENABLED` across `.yml`, `.java`, `.ts`, `.tsx`, `.py` — zero hits. `influora.meera.creator-enabled` (`application.yml:202`, default `true`) gates only **creator** surfaces, not brand.
The only live levers on brand AI, from the prod compose (`deploy/utho/docker-compose.utho.yml:300-301`):
- `AI_SPEND_KILL_SWITCH: "false"` — **live value, read from the deployed compose file.** Flip to `true` and every brand turn 503s (`chat.py:441-449`).
- `AI_DAILY_SPEND_CEILING_USD: "15.0"` — platform-wide daily ceiling.
- Per-workspace AI credits (`AICreditService`), charged 1 per turn.
Two tools are compiled in but structurally unreachable (`request_payment`, `confirm_launch`) — not by a flag, by the scope constant at `OnBehalfTokenService.java:68` and the schema filter at `schemas.py:449`.
The frontend has no Meera flag either; `VITE_API_MODE` defaults to `mock` (`src/lib/api.ts:60-61`) but `vite.config.ts:39` **fails the production build** unless it is `live`, and CI passes `live` (`.github/workflows/publish-images.yml:224`). So prod is genuinely live.
→ **For the brand:** brand AI is fully on, with no per-tenant or per-feature off switch — our only emergency brake is a global kill switch that takes Meera down for everyone.

## Q22. What `ToolCallValidator` actually blocks — nothing

**Lead with it: as wired, this class rejects nothing at runtime. Every branch is dead code.**
It is invoked only through `MeeraInternalController.requireTool` (`:424-435`), and all six call sites pass a **compile-time enum constant**: `:198` `MeeraToolName.show_creators`, `:210` `calculate_budget`, `:224` `create_campaign`, `:258` `request_payment`, `:280` `confirm_launch`, `:307` `get_campaign_performance`.
So `validateAndResolve(expected.name(), ...)` (`ToolCallValidator.java:75`) is always `MeeraToolName.valueOf(MeeraToolName.show_creators.name())` — it cannot throw. The `resolved != expected` mismatch check (`MeeraInternalController.java:431`) is impossible. And the FORBIDDEN-tier branch (`ToolCallValidator.java:91`) is unreachable by the class's own admission (`:17-21`: "there is structurally no MeeraToolName entry that maps to FORBIDDEN").
A concrete bad call, and where it *actually* dies: Claude emits `update_payment_method`. It is rejected in **Python** at `loop.py:327-341` (`is_known_tool`), logged, and returned to the model as an error — it never leaves the process. If something bypassed Python, there is simply no `/internal/meera/update_payment_method` route, so it 404s at Spring's router.
Also worth stating: this validator checks tool **names** only. It never validates tool **arguments** — that is each executor's job.
→ **For the brand:** the protection is real, but it lives one layer up from where our docs say it does. The named safety component is decorative.

## Q23. The on-behalf scope bug — fixed in code, on `main`, deployment unverified

Fixed in code and merged. `OnBehalfTokenService.java:68-69` — `SCOPE_DEFAULT = "show_creators calculate_budget create_campaign get_campaign_performance"`, with the comment at `:57-62` naming exactly the bug you remember ("fix M-1, 2026-07-23 — Meera could not create campaigns … the Analytics AI answered 'how did my campaign do?' with the same refusal"). Commit `706b60f` (2026-07-23); `git branch --contains` confirms it is on **`main`**.
**Deployed: `UNVERIFIED`.** `deploy/utho/docker-compose.utho.yml:280` pins `image: ghcr.io/…/influora-ai:latest` and the API image likewise — a mutable tag. Nothing in the repo records which digest the box is running. The check that closes this: SSH to the Utho box and run `docker inspect --format '{{.Image}}' <api-container>`, then match the digest to a GHCR build.
**But the fix does not restore analytics.** Even with the scope correct, `get_campaign_performance` is uncallable for the DTO reason in Q17. We traded a silent 403 for a polite refusal.
→ **For the brand:** campaign creation works. Analytics still does not, for a different and worse reason than the one we fixed.

## Q24. `OnBehalfTokenService` blast radius

**TTL 120 seconds** (`:43`), ES256-signed with the Spring JWKS keypair — deliberately *not* the public access-token secret, so compromising one credential family cannot forge the other (`:20-27`). Audience `meera-onbehalf` (`:46`), distinct from the stream token's. A full public access token presented here is rejected structurally on algorithm mismatch, not by a denylist (`:119-125`).
Scope: four tools (`:105` → `SCOPE_DEFAULT`), enforced per-route at `OnBehalfAuthResolver.java:178-189`. Workspace is pinned by claim and must match the request body (`:89-97`).
**What stops replay: nothing.** The class javadoc says it outright (`:36-38`) — a random `jti` is minted (`:95`) but "the consumed-`jti` replay store itself is a separate fix … **NOT implemented here**". A `turnId` claim is written (`:104`) and **no resolver reads it**. So within its 120-second window a leaked token can be replayed any number of times, against any of its four tools, from anywhere.
Practical blast radius of a stolen token: 120 seconds of unlimited draft-campaign creation and creator/budget reads for one workspace. No money, no cross-tenant reach.
→ **For the brand:** a two-minute credential that cannot touch money — but if one leaks we have no way to revoke or even detect its reuse.

## Q25. Cross-brand isolation and the test that proves it

Boundary is the on-behalf JWT's `workspaceId` claim, re-proven on every single tool call: `OnBehalfAuthResolver.java:89-97` rejects with 403 `ON_BEHALF_WORKSPACE_MISMATCH` unless the token's workspace equals the request body's. Executors then scope their own queries: `GetCampaignPerformanceExecutor.java:112-118` resolves through `findByIdAndWorkspaceId` in **one** call, so "no such campaign" and "another brand's campaign" produce byte-identical 404s — no IDOR oracle.
Block B is server-sourced, never client-supplied: `chat.py:466-471` states that any `brand` key in the request body is ignored entirely. The prompt cache key includes audience + workspace + session (`assembler.py:794-806`).
Tests that prove it: `OnBehalfAuthResolverTest.java:314` `testRejectsWorkspaceMismatch`, plus `:209` (rejects a full access token), `:251` (wrong audience), `:266` (bad signature). `MeeraInternalControllerContextTest` covers the context route.
**Gap, stated plainly:** there is **no unit test at all** for `ShowCreatorsExecutor` or `GetCampaignPerformanceExecutor` — the two most data-exposing read tools. Creator profiles are intentionally a shared pool, not per-brand data (`ShowCreatorsExecutor.java:28-32`).
→ **For the brand:** their data is walled off per workspace and the wall is tested at the gate — but the two tools that read the most data have no tests of their own.

## Q26. `present_options` — real cards, fully wired

Real, tappable, and the tap does something. `src/components/feature/meera/ToolResultRenderer.tsx:289` exports `OptionsCards`; `:297-300` renders a genuine keyboard-focusable `<button type="button">` per option with a live `onClick`; `:303-305` gives the recommended option an accent border and `:310-314` a "Recommended" pill.
The click handler round-trips: `MeeraChatPanel.tsx:767-773` fires telemetry (`meeraApi.logOptionTapped`) and then calls `handleSend(opt.label)` — i.e. tapping a card **sends that choice as the brand's next chat turn**. Not a no-op.
Server side it is a local display tool: `loop.py:354-393` echoes the options straight back for the browser and hands Claude a minimal ack, plus one `OPTIONS_PRESENTED` interaction-log row. The persona forbids listing options in prose and mandates this tool instead (`persona.py:36-44`, `:120-123`).
One gate: tool-result cards only render when `live` is true (`MeeraChatPanel.tsx:757`). In a production build that is guaranteed (`vite.config.ts:39` fails the build otherwise), so this is a local-dev caveat only.
→ **For the brand:** when Meera offers a choice, they tap a card and the conversation moves — this is one of the polished parts of the product.

## Q27. Campaign templates — where the list comes from

Two sources, read live per turn: `MeeraContextService.java:176-177` loads **all SYSTEM templates plus this workspace's CUSTOM templates**, and `BrandContextAssembler.java:216-230` renders one line each (name, type, budget band, first 3 requirements) into Block B.
The SYSTEM set is **four rows seeded by a Flyway migration** — `V20260714150000__campaign_templates.sql:38-115`: *Brand Awareness* (HYPE, ₹10k–50k), *Sales & Conversions* (DIRECT, ₹15k–75k), *UGC Content Pack* (STANDARD, ₹5k–20k), *Affiliate / Revenue Share* (REVIEW, ₹0–30k). So: static content, live delivery. CUSTOM rows are genuinely live per workspace.
**Defect found while verifying this.** When Meera passes a `template_id`, `CreateCampaignExecutor.java:309-314` copies only `requirements`, `hashtags`, `target_audience` and `brand_guidelines` — it does **not** copy `platforms`, `content_types` or `objectives`, and the `else` branch that would have applied the AI-composed equivalents is skipped (`:315-349`). The persona explicitly tells the model not to supply them itself when a template is set (`persona.py:135-138`).
Net: recommending a template by name produces a **thinner** draft than building from scratch — no platforms, no content types, no objectives.
→ **For the brand:** the templates are real and Meera names them correctly — but taking her template recommendation currently gives them a less complete campaign than ignoring it.

## Q28. The three worst things Meera will confidently get wrong

1. **"How did my campaign perform?"** — she will refuse, every time, for every campaign, and sound reasonable doing it. Root cause in Q17: the context payload carries no campaign id (`MeeraContextDtos.java:52-55`, `:76-83`), so the `[id=...]` marker her instructions require (`persona.py:174-176`) is never rendered. A brand will reasonably conclude we have no analytics.
2. **"Find me skincare creators."** — she presents a follower-sorted bio keyword search as a curated match (`CreatorProfileSpecifications.java:30-41`), with no category matching and no verified-profile filter (`ShowCreatorsExecutor.java:56-63`). The list *looks* authoritative. A brand who knows the space will spot creators we missed and conclude our pool is thin — when it is our query that is thin.
3. **"Use the Brand Awareness template."** — she recommends it by name, passes the `template_id`, and the resulting draft silently loses platforms, content types and objectives (`CreateCampaignExecutor.java:309-314`). She then tells them to open it and publish. They open a half-empty form.
Honourable mention: every budget she quotes sounds market-derived and is a fixed percentage of product price (`CalculateBudgetExecutor.java:158-169`) — while our real rate-band data sits unused in the same prompt.
What she will **not** get wrong: prices (scraped facts override the model, `analyze_site.py:107-139`), money movement (Q18), draft-vs-live status (`CreateCampaignExecutor.java:300`), or another brand's data (Q25).
→ **For the brand:** Meera's failures are all failures of *quality presented confidently* — she is never dangerous, but in three named situations she is wrong in a way an experienced marketer will notice before we do.

---

*Q29–Q50 (Sections D, E, F) are answered in a companion document.*
