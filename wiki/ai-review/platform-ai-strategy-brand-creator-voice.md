# AI Review (Deep): Influora Platform AI Strategy — Brand + Creator + Voice

Reviewer: Ash · 2026-07-20 · Follow-up to `campaign-templates-knowledge-ai-review.md` (the P1 fixes there are Phase 1 of this roadmap).

---

## 1. The business, in one paragraph

Influora is an **escrow-backed influencer-marketing marketplace for India**. Brands fund campaigns into escrow; micro-creators (1K–100K followers, Instagram-verified via Meta OAuth) deliver content; money releases only on approved/verified deliverables. Revenue: **10% (7% Pro) brand publish fee** on go-live, **15% creator commission** at escrow release, **₹4,999/mo Pro subscription**, Snapsby catalog-video sales, affiliate commissions. The trust moat is escrow + Meta-verified metrics; the growth engine is the Hype Campaign (100+ creators, 72-hr reel blitz). Everything is INR, Razorpay, GST/TDS-aware.

**Why this matters for AI:** the platform's unique asset is **verified outcome data nobody else has** — escrow-verified spend, PLATFORM_VERIFIED reach/engagement per deliverable, accepted-vs-rejected deal rates, GARM safety scores, conversion/coupon revenue. An AI grounded on that data is a moat; an AI grounded on nothing (today's state) is a chat skin.

## 2. AI today — the honest map

| Journey step | Brand side | Creator side |
|---|---|---|
| Onboarding | analyze_site (Gemini) seeds brand profile | **none** |
| Discovery/matching | show_creators (read tool); nightly ScoreCalculationJob is heuristic, not LLM | **none** |
| Campaign creation | Meera create_campaign (draft-tier), calculate_budget | **none** (creators browse/apply manually) |
| Deals/negotiation | **none** | **none** |
| Contracts | none (correct — deterministic) | none |
| Deliverables | none | **none** (verification = Meta insights matching, not AI) |
| Analytics/ROI | **none** (raw numbers only) | **none** |
| Money/escrow/payouts | none — structurally forbidden (correct, keep forever) | none (correct) |
| Disputes/reviews | none | none |
| Content safety | GARM brand-safety scoring — **OFF by default**, scores surface only to brands/admin | invisible to creators |
| Trend intelligence | TrendSpark nudge (phrasing-only AI) | **none** |
| Voice | Meera composer STT (Sarvam) + Gemini cleanup + TTS (500-char cap) | **none** |

Three structural facts:
1. **The entire creator side has zero AI.** Grep-verified: no Meera, no assistant, no suggestions in any `/creator/*` route. Half the marketplace is dark.
2. **The brand AI runs on an empty knowledge layer** (previous review: Block B never populated, templates invisible).
3. **The money rails are correctly AI-free** — "Meera proposes, Spring disposes, the human commits money." Every recommendation below preserves this.

## 3. P0 DESIGN RULE for any two-sided AI: the information barrier

The moment AI exists on both sides, Influora is a broker running advisors for **both counterparties of the same negotiation**. This needs a Chinese wall, designed in from day one:

- The brand's Meera context must NEVER contain a creator's private data (floor rate, other deals, wallet, negotiation history with other brands).
- A creator copilot's context must NEVER contain the brand's private data (budgetMax, other creators' agreed rates in the same campaign, escrow balances).
- Implement as a **per-audience field allow-list at the Spring context endpoint** (the same `/internal/meera/context` proposed in the prior review, with an `audience: BRAND|CREATOR` parameter) — extend `_FORBIDDEN_BRAND_FIELDS` in `assembler.py` into two audience-scoped allow-lists. Cross-party facts may only enter a prompt in **aggregate, market-level form** ("creators in this niche typically close at ₹X–Y"), never per-counterparty.
- Deal-advice tools on both sides are **R-tier advisory only**: the AI drafts a counter-offer, the human taps send. The AI never accepts, rejects, or counters autonomously (mirrors `CANNOT_ACCEPT_OWN_OFFER` discipline in DealService).

This is the one thing that, done wrong, becomes a trust/legal incident. Tag Kabir on the allow-list review.

## 4. Making the AI smarter — the Platform Brain

The principle: **stop making the LLM smarter in isolation; make its grounding data richer.** Same Claude, 10× more useful. Four knowledge tiers, all already in the database:

**Tier 1 — Identity (per-workspace, cached Block B):** brand profile, product catalog, tone, campaign templates (SYSTEM + CUSTOM), past-campaign summary. *Status: built but disconnected — Phase 1 fix from prior review.*

**Tier 2 — Outcomes (the moat):** per-campaign verified results from `DeliverableMetric` (PLATFORM_VERIFIED reach/engagement), escrow release history, `AffiliateEarning` conversion revenue, `UtmCampaign.revenueAttributed`. Feed a 3-line digest into Meera's context: "Your last REVIEW campaign: 8 creators, ₹42k, 310k verified reach, ₹1.2L attributed revenue." Now `calculate_budget` and template recommendations are grounded on *this brand's actual ROI*, not heuristics.

**Tier 3 — Market intelligence (aggregate, barrier-safe):** `RateEstimationService` estimates, `CreatorScore` distributions, niche-level accepted-rate bands from closed deals, TrendSpark trend feed. Powers both sides: brands get "fair rate for a 40k-follower Pune food creator is ₹X"; creators get "your quoted rate is 20% under market for your engagement tier."

**Tier 4 — Interaction memory (flywheel):** log present_options taps vs recommended flag, revision-request reasons, deal accept/reject/counter deltas, funded-vs-abandoned drafts. This is the eval set and future few-shot corpus. Start logging NOW — it costs one table and pays forever.

## 5. Brand-side upgrades (beyond Phase 1 templates fix)

- **B1 — Explainable matching (P1, R-tier):** `show_creators` returns rows; Meera should say *why*: "picked her for 6.2% engagement and a 94 safety score." Data already in `CreatorScore` — include score fields in the tool result payload. One executor change + persona line.
- **B2 — Analytics copilot (P1):** brands see raw metric tables today. Add a read-only `get_campaign_performance` tool (Spring aggregates from DeliverableMetric/UtmCampaign/AffiliateEarning) so Meera can answer "how did my campaign do, what should I change?" This is the retention feature — it closes the loop from spend → insight → next campaign.
- **B3 — Turn on brand-safety scoring (P1, ops not code):** `BrandSafetyScoringProperties.isEnabled()` is false → `brandSafetyScore` is NULL everywhere → the discovery ranking and BrandSafetyBadge run blind. The pipeline is built and fail-closed; enable it capped, backfill top-searched creators first.
- **B4 — Deal-room drafting assist (P2, behind the info barrier):** "draft a polite counter at ₹8k citing timeline" — text drafting only, human sends, no autonomy.

## 6. Creator-side AI — the biggest untapped surface

Creators are free-tier, mobile-first, Hinglish-speaking, and currently get **nothing**. Priorities by marketplace impact (faster deal close + fewer revisions = faster escrow release = faster GMV cycle = more commission revenue):

- **C1 — Pre-submit compliance check (P1, highest ROI, no chat UI needed):** before a creator submits a deliverable, run caption/content against (a) campaign `requirements` + `brand_guidelines` (from the campaign/template row) and (b) a GARM-lite pass — reusing the existing `analyze_creator_content` forced-tool pattern in `brand_safety.py`. Return: "missing #ad disclosure", "coupon code not visible", "brand bans competitor mentions." Every caught issue kills a revision round-trip (`REVISION_REQUESTED` cycle), which today costs days of escrow latency. Cheap model (Haiku-class), ~₹0.10/check, fail-open (never block submission).
- **C2 — Brief-to-shot-list helper (P1):** when a deal hits CONTRACTED, generate a one-tap content brief from campaign type + requirements + brand tone: hook ideas, shot list, caption draft with required hashtags/disclosure pre-inserted. Grounded entirely on data the creator is already party to — no barrier risk.
- **C3 — Rate & profile advisor (P2):** surface `RateEstimationService` + market bands to the creator: "your engagement supports ₹6–8k/reel; your profile is missing rates, which drops you from 60% of searches." Data exists, currently shown only to brands — showing creators their own market position is free retention.
- **C4 — Creator copilot chat (P2, after C1–C3 prove value):** Meera-pattern sibling (shared infra: same three-block assembler, same credit service pattern, same tool-tier discipline) with read tools only: my_deals, my_metrics, market_rate, campaign_requirements. Persona: practical senior-creator mentor, Hinglish-comfortable. Strictly no money tools.
- **Cost control for a free tier:** creator AI must be capped (daily action cap like `BrandAiCredit`'s 500/day) and run on the cheapest adequate model; C1/C2 are single-shot calls, not conversations, so cost is naturally bounded. Later: premium creator tier upsell.

## 7. Voice — from feature to interface

Current state (`voice.py`, `sarvam.py`): request/response STT (Sarvam saarika) → Gemini transcript cleanup (edit-first, never auto-send) → separate TTS call per reply, 500-char cap, graceful truncation, silent text fallback at every stage, spend-gated. Solid plumbing. Gaps, in priority order:

- **V1 — Language parity (P1, tiny):** `/voice/transcribe` returns `lang_detected` but `/voice/speak` defaults `lang="en-IN"` and the persona has no language-matching rail. A Hinglish speaker gets an English voice back. Fix: thread `lang_detected` through the frontend to `speak`, add one persona line ("reply in the language the user speaks — Hinglish in, Hinglish out"). For the creator side this is not optional — it's the difference between usable and ignored.
- **V2 — Speakable normalizer (P1, from prior review):** normalize before TTS: "₹15,000–₹75,000" → "fifteen to seventy-five thousand rupees", strip `#`, spell out "UGC". Becomes urgent the moment template/budget data starts flowing (Phase 1).
- **V3 — Sentence-streamed TTS (P2, biggest perceived-latency win):** today the browser waits for the FULL reply, then one TTS round-trip, then playback — worst-case seconds of dead air. Chat already streams sentence-by-sentence over SSE; fire TTS per completed sentence and queue audio chunks client-side. First audio lands ~3× sooner. No new providers — an orchestration change in the voice-output hook + allowing multi-input Sarvam calls.
- **V4 — TTS phrase cache (P2, cost):** Meera's openers/confirmations repeat across users. Hash(text+lang+speaker) → cached audio (R2 or memory). Eliminates the most frequent TTS calls entirely; also revisit the 500-char cap per-message-type (confirmations short, plans allowed longer).
- **V5 — Barge-in (P3):** stop playback when the user starts talking (client-side: mic energy while audio playing → pause + start capture). Pure frontend; makes hands-free mode feel conversational instead of walkie-talkie.
- **V6 — Voice identity (P3):** pin one Sarvam speaker as Meera's voice everywhere (the browser-fallback voice bug already showed users notice inconsistency); creator copilot gets a distinct voice.

## 8. Roadmap (sequenced, each phase shippable alone)

| Phase | Items | Effort | Why first |
|---|---|---|---|
| **1 — Knowledge foundation** | Server-side context endpoint + template digest + template_id on create_campaign (prior review P1s) + V1 lang parity + V2 normalizer | ~1 sprint | Everything else grounds on this; kills the client-supplied-context security smell |
| **2 — Outcome grounding + safety on** | Tier-2 outcome digest, B1 explainable matching, B2 analytics tool, B3 enable GARM scoring, start Tier-4 logging | ~1–2 sprints | Turns Meera from planner into operator with a track record; logging must start early |
| **3 — Creator AI v1** | C1 compliance pre-check, C2 brief helper, audience-scoped allow-lists (info barrier, Kabir review) | ~2 sprints | Highest marketplace ROI per token; no chat UI to build |
| **4 — Conversational + voice depth** | C4 creator copilot, C3 rate advisor, V3 streamed TTS, V4 cache, B4 deal drafting | ~3+ sprints | Needs Phases 1–3's data and barriers in place |

## 9. Eval & guardrail requirements (non-negotiable before Phase 3)

- Golden sets per surface: 15 product→template/budget cases (brand), 20 caption→compliance-verdict cases (creator C1 — include #ad missing, competitor mention, clean cases), 10 Hinglish STT→intent cases (voice). Run on every PROMPT_VERSION bump.
- C1 verdicts are advisory to the creator — never auto-reject, never surface to the brand (barrier).
- All new tools R-tier unless a human-confirm flow exists; money stays structurally absent from every new schema.
- Every new context field goes through the audience allow-list; no per-counterparty data crosses sides, ever.

## Verdict

Current AI = one brand-side assistant running on an empty knowledge layer, plus two narrow phrasing/classification services, with half the marketplace (creators) at zero. The database already holds a moat-grade knowledge base — verified outcomes, market rates, safety scores — that no prompt currently sees. Priority is not a bigger model or more tools; it is **wiring the knowledge the platform already owns into the AI (both sides, behind an information barrier) and making voice first-class for the Hinglish mobile user**. Phases 1–2 are low-risk and compounding; Phase 3 opens the creator half of the marketplace.
