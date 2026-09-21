# CTO → CEO: Answers Q29–Q50 (Sections D, E, F)

**From:** Priya (CTO) · **To:** Swapnil Maruti (CEO) · **Date:** 2026-09-12
**Scope:** Q29–Q50 only. Q1–Q28 answered separately.
**Branch read:** `feat/meera-creator-phase-e` @ `04980b0` (+8 unpushed commits, dirty tree).
**Rule applied:** every claim below cites a file I opened. Where I could not open or could not
determine, the line says `UNVERIFIED` and why.

---

## EXECUTIVE SUMMARY (5 lines)

1. **A live-format Anthropic API key is committed to git** in `influora-ai/env.example` (tracked,
   108 chars, `sk-ant…`, no placeholder text), alongside a Sarvam key. Rotate today. Q50.
2. **The core sales claim is not what the code does.** "Suggests matching creators by niche, city
   and engagement" is a SQL `LIKE '%word%'` against display-name/username/bio/city, sorted by raw
   follower count, with **zero** engagement filtering (`CreatorProfileSpecifications.java:30-41`).
3. **There is no brand-side kill switch and no per-brand cost cap.** One brand can exhaust the
   whole company's $15/day AI ceiling; every other brand then gets "Didn't catch that — try again?"
   **and is still charged an AI credit**, because the refund path never runs on a gate block.
4. Safety engineering is genuinely good where it exists (SSRF guard, untrusted wrapping, replay
   guard, scope-excluded money tools) but the **invented-price kill-switch is not wired to the
   brand chat at all** — it only guards two creator-side routes.
5. **No human has ever completed a full brand journey with Meera on live.** Last live brand test
   (2026-07-23) was PARTIAL; every pass since then died on missing credentials.

---

# SECTION D — Trust, accuracy, safety

## Q29. Does untrusted.py stop pasted-page injection?

First-order: yes. Second-order: **partially, and that is the gap.**
`neutralize_angle_brackets` replaces every `<`/`>` with entities and `wrap_untrusted` adds
delimiters (`influora-ai/app/prompt/untrusted.py:44,57-58`). The scraped page body is wrapped
before it ever reaches Gemini (`routes/analyze_site.py:261,268`), and the brand's own typed
message is wrapped as `<untrusted_user_message>` (`prompt/assembler.py:735`).
**The gap:** what Gemini *extracts* from that page (product names, tone) is persisted to
`BrandProfile` and re-rendered into **Block B, a system block**, through `_safe()` only —
angle-bracket neutralization with **no delimiters and no "this is data" label**
(`prompt/assembler.py:408-415`, `_safe` at `:237-245`). A page whose product is literally named
*"IGNORE ALL PRIOR INSTRUCTIONS…"* lands in the highest-trust role of the prompt as plain text.
No end-to-end injection test exists for that path (`influora-ai/tests/prompt/` has none).
→ **For the brand:** a hostile competitor's page can't hijack the first read, but it can plant a
sentence that sits inside Meera's brain for every future turn of that brand's account.

## Q30. What stops SSRF on a pasted URL?

This one is genuinely solid. `guarded_fetch` is the only egress path for brand URLs
(`routes/analyze_site.py:190`); the two raw `httpx` clients left in the repo are Spring
(`clients/spring.py:83`) and Sarvam (`providers/sarvam.py:403,470`), both fixed internal hosts.
The guard: https-only (`config.py:298`), DNS resolved first and **the whole host rejected if ANY
resolved address is private** (`security/ssrf_guard.py:113-125`), first IP pinned and connected to
directly with Host header + SNI preserved so rebinding cannot swap it (`:171-182`), redirects
capped at 2 with every hop re-resolved and re-validated (`:232-241`), body cap enforced *during*
the stream not after (`:217-226`), and `169.254.169.254` blocked by name as well as by range
(`:33,41`). Fails closed on unparsable addresses (`:70-72`). 12 security test files cover it,
including IPv6 and pin gaps (`tests/security/test_ssrf_guard.py`, `test_f11_f13_pin_gaps.py`).
→ **For the brand:** we cannot be tricked into reading our own database through their store URL —
but note https-only means a plain `http://` store URL is silently refused.

## Q31. What is redacted, and does brand PII reach Anthropic?

**Bad news first: `redaction.py` is a LOGGING module, not an egress filter. Nothing redacts what
we send to Anthropic.** Its entire surface is a `logging.Formatter` and a `log_event` helper
(`security/redaction.py:200-254`); it scrubs PAN/phone/email/JWT/secret patterns out of *our own
stdout*, never out of a prompt.
What *does* protect the prompt is an allow-list, and it is good: `BrandContextAssembler` explicitly
excludes billingEmail/gstin/pan/KYC doc URLs/user email/phone
(`BrandContextAssembler.java:41-48`), and Python strips a second deny-list on arrival including
`pan`, `bank_account`, `upi`, `email`, `phone`, `wallet_balance`, `creator_pii`
(`prompt/assembler.py:70-101`).
**But the brand's own typed text goes verbatim**, and the browser replays the entire thread back
each turn (`MeeraChatPanel.tsx:457-465`). If a brand types a creator's phone number, it reaches
Anthropic's US servers. Our privacy policy is a **v0 draft, "NOT LEGALLY BINDING"**
(`src/content/legal/privacy-policy.md:3`) and names no AI sub-processor — only "Cloud and AI
infrastructure providers" (`:54`). No DPA or zero-retention configuration is present in the repo.
→ **For the brand:** structured brand data is well fenced; anything they *type* is not, and we
have not told them in writing which foreign company reads it.

## Q32. Brand-safety route — who calls it, is it visible?

**It is fully built and switched OFF.** The Python route exists and is service-token authed
(`influora-ai/app/routes/brand_safety.py:1-46`), the Java client exists
(`integration/ai/BrandSafetyAiClient.java:41`), the scorer exists
(`service/scoring/BrandSafetyScoreService.java`), and `ScoreCalculationJob:344` calls it.
The master switch defaults **false** (`config/BrandSafetyScoringProperties.java:41`, bound at
`application.yml:489` as `${BRAND_SAFETY_SCORING_ENABLED:false}`) and **`BRAND_SAFETY_SCORING_ENABLED`
is set in none of the three deploy manifests** (`deploy/utho/*.yml`, `deploy/hostinger/*.yml`) — so
unless ops set it in the VPS `.env` (which I cannot read from here), production runs the default
OFF and writes `brand_safety_score`/`garm_flags`/`content_sentiment` as NULL on every row.
The UI is already built and waiting: `src/components/analytics/BrandSafetyBadge.tsx` renders a
letter grade, and `src/hooks/analytics/useCreatorScores.ts:7` documents that these fields "come
back null from the real" backend. The classifier is deliberately pinned to Sonnet, not Haiku
(`influora-ai/app/config.py:161`) — i.e. it is the expensive kind, which is why it is off.
→ **For the brand:** we show a brand-safety badge that is permanently blank. It's one env var and
a budget decision away from being real.

## Q33. What stops Meera inventing creators, prices, results?

Three rails exist and **the most important one is not connected to the brand chat.**
✅ Works: analytics cannot be invented — `GetCampaignPerformanceExecutor` reads only
`SOURCE_PLATFORM_VERIFIED` metrics and returns `null` rather than a guess
(`GetCampaignPerformanceExecutor.java:42-44,143`), and the persona tells her to say "not enough
verified data" instead of estimating (`persona.py:167-173`). Creators can't be invented because
`show_creators` returns real DB rows.
❌ Broken: `has_invented_price()` — the "any price-shaped figure in the output is invented → reject"
kill-switch (`prompt/validators.py:59-120`) — is imported **only** by `routes/trendspark.py:151`
and `routes/creator_suggestion.py:193`. `routes/chat.py` does not import it at all (checked the
full import block, `chat.py:55-84`). The brand chat has no output price validator.
❌ Broken: the money-provenance rail depends on `price_source` (`persona.py:131-132`), but Block B
renders the catalog as `name (currency price)` with **`price_source` dropped**
(`assembler.py:408-415`). Meera therefore cannot tell a scraped price from a Gemini guess when
reading her own brand context.
→ **For the brand:** ask "what does my serum cost?" and Meera may state a number the AI invented
from the page, with no hedge, because the guard that catches that is wired to a different product.

## Q34. Is every AI interaction logged, and for how long?

Two tables, and **neither is a conversation archive by design.**
`meera_interaction_log` stores event type, tool name, campaign id, a redacted revision reason and
prompt version — **no message text** (`domain/entity/MeeraInteractionLog.java:26-55`, five event
types only: `MeeraInteractionEventType.java:19-27`). Its service never throws and silently drops
events on failure (`MeeraInteractionLogService.java:80-88`), and it is documented as write-only
with no read query.
Retention: `MEERA_INTERACTION_LOG_RETENTION_ENABLED` defaults **false** (`application.yml:533`) and
is **not set in any deploy manifest** → the purge job never runs and the table grows forever.
The actual transcript lives in `ai_messages.content` (`domain/entity/AiMessage.java:29-30`), written
for both roles by `MeeraSessionService.java:211-216,354-359,605-610`. **There is no retention job
for `ai_messages` at all** — I searched `job/` and found none. So: metadata purge is off, and
transcripts are kept indefinitely with no policy.
→ **For the brand:** we keep their conversations forever, we cannot currently delete them on
request, and the one table we *did* write a delete job for has it switched off.

## Q35. "Meera told me X and it was wrong" — evidence trail, how long to pull?

**There is no tool. It is a manual MySQL query by someone with VPS shell access.** I searched the
admin controllers and `service/admin/` for any read of `AiMessage`/`AiConversation` — zero hits.
No support UI, no API, no export.
Worse, the trail may not match what Meera actually saw. The prompt is assembled from the
`conversation` array the **browser** posts (`MeeraChatPanel.tsx:457-465` → `chat.py:548,556`), not
from `ai_messages`. The DB copy is written separately by Spring. If the client dropped, edited or
reordered a turn, the persisted record and the model's actual input diverge and **nothing
reconciles them**.
We do have correlating metadata: every AI log line carries `workspace_id` + `request_id` +
`prompt_version` (`security/redaction.py:204-212`) and the cost line carries the model and token
split — but log retention on the VPS is `docker logging` defaults only, so practically days.
Realistic time-to-answer today: hours, and the answer is "here is roughly what was stored."
→ **For the brand:** if a customer disputes what Meera said, we cannot hand them a signed
transcript. That is a support and a legal exposure, not a nice-to-have.

## Q36. Does the Meera prompt comply with the "escrow" ban, and is there a gate?

**Prompt: compliant. Gate: does not exist.**
I grepped the whole `influora-ai/app/` tree: the string "escrow" appears exactly once, as the
internal deny-list identifier `"escrow_internals"` in `prompt/assembler.py:80` — an identifier, not
user copy, which the 2026-09-02 ruling explicitly permits. `persona.py` (14,956 chars, read in
full) contains no occurrence.
There is **no vocabulary gate**. I listed all 200+ scripts in `.proof-os/gates/` and grepped them
for "escrow" and for the replacement vocabulary. The hits are all money-*behaviour* gates.
`F-0226-no-false-escrow-copy.sh` is the closest and it is a different control: it scans only
`src/components/creator/deal-room` for six specific false-success phrases and its own header says
it is "deliberately narrow so it fails on the DEFECT, not on any mention of escrow" (`:24-26`).
Meanwhile the FE still ships an `EscrowPill` with labels like `"₹X Secured"`
(`src/data/meera-copy.ts:29-36`) — those are the *approved* replacements, so this is fine, but it
is fine by luck, not by enforcement.
→ **For the brand:** the vocabulary is correct today; nothing stops the next engineer reverting it,
and we'd find out from a customer.

---

# SECTION E — Cost and limits

## Q37. What does one brand conversation cost us?

Measured, not estimated. I imported the real modules and counted.
Fixed prefix (Block A) = persona 14,956 chars ≈ **3,739 tokens** + 6 tool schemas 8,127 chars ≈
**2,031 tokens** = **~5,770 tokens**, marked `cache_control: ephemeral`
(`assembler.py:234,432,445`). Sonnet 4.5 at $3/$15 per MTok (`costs/pricing.py:105`), cache write
1.25× = $3.75, cache read 0.1× = $0.30 (`pricing.py:46-48,76-84`).
- First turn (cache write): 5,770 × $3.75/M ≈ **$0.022** + ~400 output × $15/M ≈ $0.006 → **~$0.029**
- Cached turn: 5,770 × $0.30/M ≈ $0.002 + ~1k fresh history × $3/M + output → **~$0.011**
- A tool turn costs ~2× (plan call + narrate call), up to 6 iterations (`config.py:308`).
A realistic 10-message conversation with 4 tool turns ≈ **$0.15–0.25 (₹13–21)**. Against Pro at
₹4,999 for 400 credits, a heavy Pro brand costs us ~$6/month. Margin is healthy.
→ **For the brand:** Meera is cheap per conversation; the pricing is not the risk. The ceiling is.

## Q38. Hard ceiling, and what a brand sees when it hits

**$15.00/day globally, company-wide, shared across every brand** — pinned literally in all three
deploy manifests (`deploy/utho/docker-compose.utho.yml:300`, `utho-shared:284`, `hostinger:282`).
Kill switch `AI_SPEND_KILL_SWITCH: "false"` sits beside it.
**There is no per-brand blocking cap in production.** `WORKSPACE_DAILY_HARD_CAP_USD` is opt-in and
`None` when unset (`config.py:443-445`); it appears in **no** deploy manifest. The $3/day
per-workspace figure is a WARNING-only soft cap that never blocks (`config.py:434-436`,
`chat.py:831-837`). The only per-user hard cap set in prod is `AI_CREATOR_MONTHLY_CAP_USD: "0.75"`
— creators only.
**What the brand sees mid-sentence:** `chat.py:448` returns HTTP 503 `AI_SPEND_CEILING_REACHED`.
The frontend has **no handler for that code** (I grepped all of `src/` — zero hits), so it falls to
the generic stream-error path and the brand reads **"Didn't catch that — try again?"**
(`MeeraChatPanel.tsx:638`).
**And they are charged anyway.** The credit is consumed at send by `AICreditService.tryConsumeForTurn`
before Python is called; the refund (`release_charge`) is defined *inside* `event_stream()`
(`chat.py:620-636`), a generator that never starts when the gate returns at `:448`.
→ **For the brand:** when our budget runs out, their AI silently breaks with a nonsense message and
they lose a credit for it. One noisy brand can do this to every other brand on the platform.

## Q39. Do brands consume credits? Live or spec?

**Live and enforced, and no brand pays money for them.** `AICreditService` charges at the send gate,
keyed on a server-minted `messageId`, with a guarded refund and an idempotency ledger
(`AICreditService.java:51-68`). Free tier = 100/month rising to 150 after the first funded campaign
(`:74-75`, `applyEscrowFundedReset:276-283`); Pro = 400/month, seeded in the DB
(`V55__seed_billing_plans.sql:33`, column documented at `V54__subscription_billing.sql:20`) and
synced by `SubscriptionService:684`. A 500-actions/day hard cap backstops the unlimited window
(`AICreditService.java:82`).
Two Kabir exploits are closed in this code and worth knowing about: the disconnect-farm (stream the
tokens, hang up before the write-back, never get charged) and the client-supplied turn-id replay
(`:34-53`).
**There is no way to buy more credits.** I searched for any top-up/purchase path for AI credits —
none exists; `/wallet/topup` is campaign money, unrelated. The meter is live in the UI
(`src/components/feature/meera/CreditMeter.tsx`, `hooks/useMeeraCredits.ts`).
→ **For the brand:** credits are a real limit they will hit, with no way to pay to lift it except
upgrading the whole plan. That is a revenue leak and a support ticket generator.

## Q40. Largest cost line — where is the fat?

Three answers, in order of size.
1. **Re-writing the cached prefix.** Block A is 5,770 tokens and the cache is `ephemeral` — a
   **5-minute TTL** (`assembler.py:234`, TTL documented `pricing.py:46-47`). A brand who thinks for
   six minutes between messages pays a fresh cache *write* at 1.25× ($0.022) instead of a read at
   0.1× ($0.002) — **a 10× cost swing on the same turn**, driven purely by how fast the human types.
   For a deliberative user (which is our whole use case) most turns are cache misses.
2. **Unbounded conversation replay.** The browser posts the entire thread every turn and
   **nothing caps it** — not `chat.py` (no length check on `body["conversation"]`), not
   `build_block_c_messages` (`assembler.py:713-758`, a plain `for turn in conversation`), and not
   the edge: the AI vhost in `deploy/utho/Caddyfile:47-56` sets no `request_body max_size` (the API
   vhost does, 1GB, `:36-38`). Input grows linearly forever.
3. **Uncapped product catalog.** `filteredProductCatalog` iterates every SKU
   (`BrandContextAssembler.java:189-209`) and Block B renders every one of them, every turn
   (`assembler.py:408-415`). A 400-SKU brand ships ~4k extra tokens per turn for no benefit.
Output is *not* the fat — it's capped at 1,536 tokens (`config.py:331`).
→ **For the brand:** our cost scales with their patience and their catalogue size, neither of which
we control or charge for.

## Q41. 100 brands use Meera hard tomorrow — what breaks first?

**The cost gate, on the same afternoon.** 100 brands × 20 turns/day × ~$0.015 ≈ **$30/day against a
$15/day ceiling** (`docker-compose.utho.yml:300`). Around the halfway point every remaining brand
starts getting "Didn't catch that — try again?" and losing a credit per attempt (Q38).
**Second: concurrency, not throughput.** Each turn reserves $0.02 × 6 iterations = $0.12
(`config.py:479-489`, `chat.py:427-431`). $15 ÷ $0.12 = **~125 concurrent turns** before the 126th
gets a 503 *at zero actual spend*. The comment at `config.py:483-487` documents this trade
deliberately.
**Third: MySQL.** Every turn round-trips `POST /internal/meera/context` with no caching
(`chat.py:172`, `clients/spring.py:296`), and that assembles `findRateBandCandidates` — a native
query filtering `JSON_CONTAINS(cp.categories, …)` over all COMPLETED collaborations
(`CollaborationRepository.java:63-75`). JSON_CONTAINS cannot use an index; this is a table scan per
turn.
**Not a limiter:** rate limiting. `influora.auth.rate-limit` covers the auth surface only
(`application.yml:169-174`, `AuthRateLimitFilter.java:138`). Nothing throttles `/meera/**`.
Anthropic throughput is **UNVERIFIED** — I cannot read our account tier from the repo.
→ **For the brand:** we can serve roughly 50 active brands a day before the product starts failing
for everyone, and the failure looks like a bug, not a limit.

## Q42. How many tokens of brand context per turn, and is any dead weight?

Block B is variable and **partly uncapped**. What is capped: past campaigns at 5
(`MeeraContextService.java:70,185`), template requirements at 3 each
(`BrandContextAssembler.java:242,249`), catalog *fields* at 4 —
`name/price/currency/price_source` (`:179-181`).
What is **not** capped: the number of catalog entries (`:189-209`, loops the whole list), the number
of campaign templates (`templateDigest:213-227`, no limit), and the conversation history (Q40).
Typical brand: ~200–500 tokens. A brand with a scraped 300-SKU store: ~3,000–4,000 tokens, every
turn, forever.
**Dead weight I can name:** `price_source` is faithfully assembled by Java and then **thrown away**
by the Python renderer (`assembler.py:408-415` emits name/currency/price only) — we pay to carry a
provenance field into the prompt and then don't print it, which is both waste *and* the Q33 safety
hole. `competitor_urls` is allow-listed (`:56`) but I found no renderer for it in `build_block_b` —
it is assembled and never used.
Block B is cached ephemeral (`:432`) so it is cheap *if* the brand replies within 5 minutes.
→ **For the brand:** a brand with a big catalogue silently costs us 5–10× a small one, and the one
field that would keep Meera honest about prices is the one we drop.

---

# SECTION F — What is real vs what we are selling

## Q43. What % of the brand AI is live and reachable today?

**Denominator = 18 distinct brand-facing AI capabilities.** Each verified individually:

| # | Capability | Status | Evidence |
|---|---|---|---|
| 1 | Meera brand chat, streaming text | **LIVE** | no feature flag exists in `MeeraController.java`; `/brand/meera` in sidebar `brand-layout.tsx:122`; `VITE_API_MODE=live` (`.env.production:11`) → `MeeraChatPanel.tsx:207` |
| 2 | Brand voice in/out (Sarvam) | **LIVE** | `routes/voice.py:190-192` BRAND branch; `VOICE_AI_BASE_URL` set in all 3 deploys |
| 3 | `show_creators` | **LIVE** (weak, see Q44) | `get_tool_schemas()` returns it |
| 4 | `calculate_budget` | **LIVE** | ditto |
| 5 | `create_campaign` (DRAFT) | **LIVE** | ditto |
| 6 | `get_campaign_performance` | **LIVE** | ditto |
| 7 | `present_options` | **LIVE** | ditto |
| 8 | `analyze_site` in-chat | **LIVE backend / DROPPED by UI** | offered by `schemas.py:448-452`, but `MeeraChatPanel.tsx:543` discards its `tool_result` |
| 9 | Onboarding store-URL analysis | **LIVE** | `AnalyzeSiteTriggerService.java:88`, called from `OnboardingService.saveBrandCompany`, no flag |
| 10 | AI credit metering | **LIVE** | `AICreditService.java`, `CreditMeter.tsx` |
| 11 | Template recommendation from context | **LIVE** | `BrandContextAssembler.java:213-227` |
| 12 | Past-campaign memory | **LIVE** | `MeeraContextService.java:185` |
| 13 | Outcome digest (verified outcomes) | **LIVE** | `assembler.py:355-368` |
| 14 | Niche rate band | **CODE LIVE, DATA-INERT** | k-anonymity floor of 5 distinct creators **and** 5 distinct workspaces (`BrandContextAssembler.java:263,418`) — at our volume this is almost certainly null |
| 15 | `request_payment` | **NOT OFFERED** | excluded at `schemas.py:449` |
| 16 | `confirm_launch` | **NOT OFFERED** | ditto |
| 17 | Creator brand-safety / GARM scoring | **FLAGGED OFF** | `BrandSafetyScoringProperties.java:41` default false, unset in every deploy manifest |
| 18 | Scripted demo chat + Living Canvas mock stages | **MOCK** | gated behind `!live`, correctly |

**13 of 18 = 72% live and reachable.** But two heavy caveats I will not let you quote without:
(a) #14 is live code over data that does not exist yet, and (b) **none of the 13 has a dated live
proof newer than 2026-07-23** (Q46). Code-reachable ≠ customer-proven.
→ **For the brand:** roughly three-quarters of what we built is switched on. What we cannot say is
that any of it has been watched working end to end by a person.

## Q44. Marketing claims the code does not back

Three. The first is the serious one.
**1. "Suggests matching creators by niche, city, and engagement"** (`src/pages/landing.tsx:150`).
The code: `ShowCreatorsExecutor.java:56-62` combines `nameSearch(niche)` + `singleCity(city)` and
sorts `Sort.Direction.DESC, "totalFollowers"`. `nameSearch` is
`LIKE '%<term>%'` across `displayName`, `username`, `bio`, `city`
(`CreatorProfileSpecifications.java:30-41`). **There is no engagement predicate and no engagement
sort anywhere in that path.** A skincare creator whose bio doesn't contain the word "skincare" is
invisible to Meera; a 500k-follower account with 0.2% engagement outranks a 20k account at 8%.
**2. "First AI-first influencer platform in India"** — `src/data/meera-copy.ts:12`, rendered
**unconditionally in the live chat header** (`MeeraChatPanel.tsx:731-733`, no `live` guard). An
unfalsifiable superlative in-product is an ASCI/CCPA misleading-ad exposure, not just puffery.
**3. Privacy policy vs practice** — `privacy-policy.md:44` claims we use personal data for
"training and evaluating AI features (Meera)". We do not train anything; we do send text to a
foreign sub-processor we never name (`:54`). We are over-claiming rights we don't use and
under-disclosing the one thing we do.
**Correctly honest, credit where due:** "400 AI credits/month" is real (`V55__seed_billing_plans.sql:33`);
"Meera proposes. You approve." is enforced by `schemas.py:449` excluding both money tools; the fake
"Scanning 300+ creators / Done — 38 found" thinking steps (`meera-copy.ts:46-51`) are correctly
gated to mock mode only (`MeeraChatPanel.tsx:782`).
→ **For the brand:** we sell a matching engine and ship a keyword search. That is the claim a
customer will test first and disprove in one query.

## Q45. The moat vs ChatGPT, in one sentence + proof

**Meera prices a campaign from what creators in that exact niche were actually paid on completed,
money-settled deals — a number that exists nowhere outside our database.**
Proof: `CollaborationRepository.findRateBandCandidates` (`:63-75`) pulls `agreed_rate` from
collaborations with `status = 'COMPLETED'` only, deliberately excluding DISPUTED and CANCELLED so a
contested rate never poses as a market signal (`:52-57`). `BrandContextAssembler:393-441` aggregates
min/median/max behind a k-anonymity floor of 5 distinct creators **and** 5 distinct workspaces
(`:263,418`), and `assembler.py:370-381` renders it as "from real completed collaborations across
the platform". Second moat leg: `get_campaign_performance` reads `SOURCE_PLATFORM_VERIFIED` rows
only (`GetCampaignPerformanceExecutor.java:42-44`) — ChatGPT can guess a reach number; it cannot
know one.
**The honest caveat:** that k-anon floor means the rate band is almost certainly `null` in
production today. The moat is architecturally real and commercially not yet switched on by data
volume.
→ **For the brand:** ChatGPT can write them a brief; only we can tell them what the brief should
cost — as soon as we have enough completed deals to say it without exposing anyone.

## Q46. Has a human completed a full brand journey with Meera on live?

**No.** Plainly no.
The last live brand test that reached the authenticated surface is 2026-07-23 and it is marked
**PARTIAL**: "Live text + voice TTS work; **cannot create campaigns** (write tools gated)", logged
as finding M-1 — "The core 'AI runs your campaigns end to end' promise is not wired to a write
action" (`wiki/reports/test-report-brand-side-live-2026-07-23.md:15,38`).
Every pass since then died before login. `wiki/build/verification-meera-live-2026-07-23.md:19-27`
records the repo's own seeded credentials returning 401 on the live box. The 2026-07-30 full E2E
(`wiki/reports/test-report-influora-test-live-e2e--build-a144f2f-2026-07-30.md`) closes with "RUN
BLOCKED: no test credentials for EITHER role — 0 of 42 IN VERIFY tickets could be verified" and
notes the blocker was already "three passes old".
We *do* have indirect proof Meera served real live turns — the blank-turn investigation is built on
captured live traffic from `200.141.1.6` (`wiki/ai-review/meera-blank-turn-ai-review.md:4,210`).
There is a one-turn live smoke harness (`.github/workflows/meera-live-smoke.yml`), but it is
`workflow_dispatch` only, it defaults to the **old test IP**, not `influora.in`, and its default
credentials are committed in plaintext (`:38-39`).
→ **For the brand:** we are selling a journey no human has ever walked on the live product. The
blocker has been "nobody made a test account" for six weeks.

## Q47. Every feature flag gating brand AI

| Flag | Defined | Default | Live value |
|---|---|---|---|
| *(none)* — brand Meera itself | — | — | **No flag exists.** `MeeraController.java` has no gate; brand Meera cannot be turned off without a deploy |
| `BRAND_SAFETY_SCORING_ENABLED` | `application.yml:489` / `BrandSafetyScoringProperties.java:41` | `false` | **false** — absent from all 3 deploy manifests |
| `AI_SPEND_KILL_SWITCH` | `config.py:430` | `false` | **`"false"`** — pinned `utho.yml:301`, `utho-shared:285`, `hostinger:283` |
| `AI_DAILY_SPEND_CEILING_USD` | `config.py:427` | `15.0` | **`"15.0"`** — pinned, same three files |
| `WORKSPACE_DAILY_HARD_CAP_USD` | `config.py:443` | `None` (no cap) | **unset everywhere** → no per-brand block |
| `AI_WORKSPACE_DAILY_SOFT_CAP_USD` | `config.py:434` | `3.0` | default; **warning only, never blocks** (`chat.py:831`) |
| `MEERA_CHAT_MAX_TOKENS` | `config.py:331` | `1536` | **`"1536"`** pinned (was the 384 blank-turn bug) |
| `MEERA_CREATOR_ENABLED` | `application.yml:202` | `true` | `"true"` — **creator side, not brand** |
| `CREATOR_COPILOT_ENABLED` | compose only | `false` | `false` — creator side |
| `VITE_API_MODE` | `api.ts:60` | `mock` | **`live`** (`.env.production:11`) — anything else and the whole product is a demo |

`MEERA_INTERACTION_LOG_RETENTION_ENABLED` (`application.yml:533`, default false, unset in deploys)
gates logging hygiene rather than a brand feature.
→ **For the brand:** there is no switch we can pull to stop Meera misbehaving short of the global
$15 kill switch, which takes down every AI feature for every customer at once.

## Q48. What is uncommitted, on a branch, or built-not-deployed?

**Everything.** We are on `feat/meera-creator-phase-e`, **42 commits ahead of `origin/main` and 0
behind** (`git rev-list --left-right --count origin/main...HEAD` → `0 42`), and **8 of those 42 are
not pushed to the feature branch's own remote** either. That includes brand-relevant work:
`c12ecde` brand-signup mobile number, `9707725` coupon workspace scoping, `f3a30c5` production build
host fix, `6363420` Pro break-even correction.
Uncommitted in the working tree: `application.yml` (adds the `influora.trend-ingest` block,
`+516..526`) together with **untracked** `influora-api/.../config/TrendIngestProperties.java` — the
class that block binds to. If `application.yml` were committed without that file the build breaks;
this is exactly the F-0324 untracked-file trap from our own error wiki.
Built-not-deployed: the entire brand-safety scoring chain (Q32), and the Meera-for-Creators Phase A
work this branch is named for.
→ **For the brand:** six weeks of fixes — including a brand signup fix and a production URL fix —
are sitting on one developer's laptop, not on the site customers use.

## Q49. One week, one engineer — what I'd fix

**Replace `nameSearch` in `ShowCreatorsExecutor` with a real category + engagement match.**
Day 1–2: swap `CreatorProfileSpecifications.nameSearch(niche)` (`ShowCreatorsExecutor.java:58`) for
a predicate over `CreatorProfile.categories` (the JSON column `findRateBandCandidates` already
queries with `JSON_CONTAINS`, `CollaborationRepository.java:73` — the data is there and indexed the
same way) with the free-text LIKE kept only as a fallback.
Day 3: change the sort from `totalFollowers DESC` (`:62`) to an engagement-weighted rank using the
`CreatorScore` rows we already compute, with `null` handled as "unknown, not zero" — the rule
commit `278c1b8` already established.
Day 4: widen the `show_creators` tool schema with a minimum-engagement parameter and teach the
persona to use it.
Day 5: provision the two live test accounts that have blocked every E2E pass since July, and run
`meera-live-smoke.yml` against `influora.in` rather than the dead IP.
**Why this one:** it is the single claim on the landing page, it is the first thing a customer
tests, and today it fails that test. Everything else on my list (the committed API key, the credit
burned on a gate block) is hours of work, not a week.
→ **For the brand:** "find me the right creators" starts returning the right creators, and we
finally have an account we can demo from.

## Q50. What you did not ask — what would embarrass us most

Four, ordered by how bad the headline is.
**1. A live-format Anthropic API key is committed to git.** `influora-ai/env.example` is **tracked**
(not gitignored, `git check-ignore` exits 1), and its `ANTHROPIC_API_KEY` line is **108 characters,
`sk-ant…`-prefixed, with no placeholder word in it**. `SARVAM_API_KEY` is 36 chars, `sk_…`, also no
placeholder. Both are in committed history (`8c7b18b`, `1792c37`), so deleting the line fixes
nothing — **they must be rotated at the vendors today**, and the repo's public/private status
checked. (I did not print or copy any value.) The same file pattern also puts a plaintext demo
brand password in `.github/workflows/meera-live-smoke.yml:38-39`.
**2. Two of the five Living Canvas stages are dead in production, and a code comment says the
opposite.** `MeeraChatPanel.tsx:84-89` maps stage `funding`→`request_payment` and `live`→
`confirm_launch` — both **excluded from the tool list** (`schemas.py:449`), so neither stage can ever
advance. Separately, `:543` drops any `tool_result` not in `MEERA_FUNCTION_CALLS` (`:108-114`),
which omits `analyze_site` — so when Meera really does read a brand's site mid-chat, the UI throws
the result away. The comment at `:96-99` asserting "`analyze_site` is NOT a real backend tool" is
**stale and wrong**; it *is* offered live (I imported `get_tool_schemas()` and it returns six tools
including `analyze_site`). The comment will cause the next engineer to mis-diagnose this.
**3. The credit-burn-on-failure loop.** Covered in Q38 and worth repeating because a customer finds
this by watching their meter: gate blocks → 503 → generic "Didn't catch that — try again?" →
brand retries → another credit gone. The refund exists (`chat.py:620-636`) but lives inside a
generator that never runs on that path.
**4. You did not ask who reviews Meera's output.** Nobody does. There is no sampling, no eval
harness on the brand chat, no red-team corpus, and `has_invented_price` — the one output validator
we wrote — is wired to the two creator routes and not to the brand chat (Q33). We will learn Meera
is wrong the same way our customers do.
**Stale intel I can clear:** the anyOf/oneOf/allOf tool-schema landmine is **closed** —
`schemas.py` contains none, and `tests/tools/test_tool_schema_anthropic_valid.py` runs in CI
(`.github/workflows/ai-tests.yml:51`). My earlier note that CI does not catch it is out of date.
→ **For the brand:** the one that ends up on a customer's screen is #2 and #3 — a co-pilot whose
visual half half-works and whose meter drains when it fails. The one that ends up in a news
article is #1.

---

*Priya, CTO. 22 of 22 answered from code I opened. Nothing above is inferred from a comment alone.*
