# 20 — ROHAN (CFO): COST REVIEW OF REMAINING-WORK PACKETS vs. API PRICING

> **Owner:** Rohan Sharma (CFO) · **For:** Swapnil (CEO) sign-off · **Date:** 2026-07-05
> **Scope:** Cross-checked `16-VIKRAM-REMAINING-TASKS.md`, `17-KABIR-REMAINING-TASKS.md`, `18-ANANYA-REMAINING-TASKS.md`, `19-AI-ARCHITECT-REVIEW.md` against the cost model in `../PRD-MEERA-AI-COFOUNDER.md` §6–§7 (our last pricing conversation, dated 2026-07-04, originally stress-tested with Arjun + me — verdict was GREEN).
> **Method:** Verified current, real-world API pricing for every provider named in the plan (web search, sourced below) rather than trusting the PRD's static numbers, since 2 months have passed and this market moves fast.

---

## 0. HEADLINE VERDICT

🟡 **YELLOW — one build-blocking correction, no budget blow-up.** The PRD's chosen strategy (Claude Sonnet + prompt caching for chat, Gemini Flash for scrape) is still the right call and the ₹23/brand-campaign-month steady-state number **holds**. But one named model in the spec is **already dead**, and I found four cost lines the remaining-work packets introduce that aren't in my tracked budget yet. None of this blocks Vikram from starting — it blocks him from starting *correctly*.

| # | Finding | Severity | Costs money? | Action owner |
|---|---|---|---|---|
| 1 | `Gemini 2.0 Flash` (named in `04-AI-SERVICE-SPEC.md` §6, inherited into `16`) is deprecated, shut down 2026-06-01 | 🔴 Build-blocking | No — drop-in price match | Vikram, before Domain D build |
| 2 | Claude pricing: intro rate active now, steps up 2026-09-01 | 🟢 Informational | Cost rises ~50% in Sept, already budgeted for | none — awareness only |
| 3 | Voice-reply (TTS) credit weighting has thin/negative margin on long replies | 🟡 Watch | Small, per-voice-turn | Vikram (cap reply length) |
| 4 | 4 new infra cost lines from docs 16/17 not yet in my subscription tracking | 🟡 Needs budget entry | Yes, all small | Rohan tracks, Swapnil approves |
| 5 | Doc 19's "cap unlimited-while-live credits" recommendation | 🟢 Endorse | Removes an uncapped liability | Vikram implements, I set the number |

---

## 1. FINDING 1 — Gemini 2.0 Flash is dead; the fix is free

`04-AI-SERVICE-SPEC.md` §6 and the original PRD §5 both pin the website-analyzer and creator-deliverable-prescreen model to **Gemini 2.0 Flash**. As of today, Gemini 2.0 Flash is deprecated and was shut down 2026-06-01 — over a month before this review. Doc 16 inherits `app/providers/gemini.py` from that spec without re-pinning the model string, so if Vikram builds Domain D against the literal spec text, it calls a dead endpoint on day one.

**The good news:** the replacement is a exact price match. **Gemini 2.5 Flash-Lite** is priced at **$0.10 / $0.40 per million tokens (input/output)** — identical to what the PRD assumed for "2.0 Flash" — and is GA-stable with a *better* output ceiling (65K vs 8K tokens). This is a one-line model-string swap in `app/config.py`, not a cost or architecture change.

**Action:** Vikram pins `app/config.py`'s Gemini model to `gemini-2.5-flash-lite` (or whatever the current GA identifier is at build time — confirm against `ai.google.dev/gemini-api/docs/pricing` the week Domain D build starts, don't trust this doc's exact string six months from now). No line-item cost change to the PRD's "All Gemini Flash: ₹1/campaign-month" figure.

**Process gap this exposes:** nobody owns a "re-verify provider model names/pricing before build" checkpoint. I'm adding this as a standing item to my daily/pre-build checklist (see §6).

---

## 2. FINDING 2 — Claude Sonnet pricing: PRD math checks out, timing note only

Current Anthropic pricing (verified today):

| Tier | Input | Output | Window |
|---|---|---|---|
| Introductory (now) | $2 / M tokens | $10 / M tokens | through 2026-08-31 |
| Standard | $3 / M tokens | $15 / M tokens | from 2026-09-01 |
| Prompt caching | cached input at 0.1× (90% off) | — | both tiers |

I recomputed the PRD's per-brand/campaign-month chat cost (112,000 input + 10,000 output tokens, per PRD §6 table) at both rates:

| Rate | Input cost (65% cached) | Output cost | Total (USD) | Total (₹, @85) |
|---|---|---|---|---|
| Intro ($2/$10, **active now**) | $0.0784 | $0.10 | $0.178 | **₹15.16** |
| Standard ($3/$15, **from Sep 1**) | $0.118 | $0.15 | $0.268 | **₹22.75 ≈ PRD's ₹23** |

**Verdict:** the PRD's headline ₹23/brand-month figure is exactly what standard pricing produces — it was modeled correctly, just at the *post-September* rate. Real cost right now is actually **~34% cheaper** than budgeted (₹15 vs ₹23) because of the introductory pricing window. This is not a problem — it's margin. The only thing worth flagging: **don't let anyone re-forecast Q3 unit economics off the current cheap rate** — cost genuinely rises ~50% on 2026-09-01 back to what's already in the plan. No budget action needed; I'll just make sure the September forecast uses $3/$15, not today's $2/$10.

---

## 3. FINDING 3 — Voice reply (TTS) credit weighting: thin margin on long replies

Current Sarvam pricing (verified today): **STT ₹30/hour (≈₹0.50/min)**, **TTS ₹15–30 per 10,000 characters** (range depends on contracted tier — confirm which tier Priya/Vikram provision).

Using the PRD's own peg (100 credits ≈ ₹22 worst case → **₹0.22/credit**):

| Voice action | Credits (04-AI-SERVICE-SPEC §5) | Budgeted ₹ | Real cost (measured) | Margin |
|---|---|---|---|---|
| Voice input (STT + cleanup, ≤30s clip) | 3 | ₹0.66 | ₹0.25–0.30 | 🟢 healthy, ~55% headroom |
| Voice reply (TTS only) | 4 | ₹0.88 | ₹0.34–0.90 for a 150–300 char reply | 🟡 thin at short replies, **negative at the high end / high-rate tier** |

If a spoken reply runs long (300 chars) and the contracted TTS tier is the ₹30/10k rate, TTS alone (₹0.90) already meets or exceeds the 4-credit allowance — before counting the underlying Claude text-generation cost that produced the reply in the first place, which is additive, not included in that 4 credits.

**Recommendation (aligns with `19-AI-ARCHITECT-REVIEW.md`'s "voice-out should be opt-in" point):** cap the text sent to TTS at **~200 characters** — truncate/summarize for speech, always show the full reply in the chat panel. This is good UX (nobody wants Meera reciting a paragraph) *and* closes the margin gap. No credit-weight change needed if this cap is enforced; if Vikram/Ananya want longer spoken replies, bump voice-reply to 5 credits instead.

**Action owner:** Vikram (`app/routes/voice.py` — enforce max-chars-to-TTS), confirm which Sarvam tier is contracted.

---

## 4. FINDING 4 — New cost lines the remaining-work docs introduce (not yet in my tracking)

None of these are large, and none require pausing work, but they're real recurring/variable costs my current subscription list (Claude Max, Cursor Pro, Canva Pro, self-hosted n8n/Ollama/Postiz) doesn't cover. I'm opening budget lines for all four; only #4 needs Swapnil's explicit sign-off since it's a new paid tier, not just usage of something already approved.

| # | Cost line | Introduced by | Type | Needs Swapnil approval? |
|---|---|---|---|---|
| 1 | `influora-ai/` Python service container hosting (Domain D) | `16` §1 | New recurring infra (compute) | Already implied by the architecture ruling Swapnil already approved — I'll track it, not re-ask |
| 2 | Distributed rate-limit store (`RateLimitService`, Domain E — "not in-memory") | `16`/`17` Domain E | New recurring infra (Redis or equivalent) | No — small, standard DevOps line, Meera provisions |
| 3 | Live MySQL dev/staging datasource | `16` §6 | New recurring infra | No — same reasoning |
| 4 | MSG91 **email send volume** (Domain B, 39 files, 22 event types × brand count) | `16` Domain B | Variable, scales with usage | **Not a new vendor** — MSG91 is already locked/approved for OTP (`docs/MSG91-EMAIL-OTP.md`) using the same Email API v5. This is volume growth on an existing line, not a new subscription. I'm adding it to my per-unit tracking so it doesn't silently balloon once notifications ship, but no new approval is required. |

**Net effect on my monthly fixed-cost table:** two new small recurring infra lines (#1 Python container, #2 Redis/rate-limit store) get added once Meera/DevOps provisions them — I'll get exact numbers from Meera at that point and fold them into the next `cost-log.json` update. Directionally these are tens of dollars/month, not a material risk to the ₹133 (or whatever the current monthly ceiling is) budget.

---

## 5. FINDING 5 — Endorsing doc 19's uncapped-credit flag, with a number

`19-AI-ARCHITECT-REVIEW.md` correctly flags that PRD §7's "unlimited while live" credit tier has no ceiling — a brand that goes live and then hammers Meera with chat for the full campaign window has genuinely uncapped AI spend. The PRD's own exposure table only bounds the *free-tier* cost (`₹22 × free-brand count`); it never bounds the *live* tier.

**My recommendation:** cap "unlimited while live" at **500 actions/day** per brand (roughly 30x a normal day's usage — generous enough that no real brand hits it, but it kills a runaway/abuse scenario). At worst-case token cost per action (~₹0.5–1 for a heavy chat turn with tool calls), that's a **≤₹500/day hard ceiling per live brand**, which becomes a real number I can alert on instead of an open-ended "trust the credit-reset logic" assumption.

**Action:** Vikram adds a daily counter alongside `unlimited_until` in `brand_ai_credits` (small addition to the V14 credit-service logic, not a schema rework); Kabir should treat this as a rate-limit control worth a red-team test (can a scripted client blow past 500/day before the counter catches it?).

---

## 6. UPDATED UNIT ECONOMICS (replaces PRD §6 table with current, sourced numbers)

| Line | PRD assumed | Verified today | Status |
|---|---|---|---|
| Claude Sonnet, cached, per brand-month | ₹23 | ₹15 now / ₹23 from Sep 1 | ✅ matches at steady state, currently better |
| Gemini Flash, website analysis | ₹1 (implied) | ₹0.44 (2.5 Flash-Lite, drop-in) | ✅ model swap required, cost unchanged |
| Voice input (3 credits) | ₹0.66 budget | ₹0.25–0.30 real | ✅ healthy margin |
| Voice reply (4 credits) | ₹0.88 budget | ₹0.34–0.90 real (TTS only, pre text-gen) | 🟡 cap reply length (§3) |
| Free-tier ceiling | ₹22 × free-brand count | unchanged | ✅ still holds |
| Live-tier ceiling | **none (gap)** | proposed ₹500/day/brand hard cap | 🟡 new control, see §5 |

**Bottom line: AI unit economics are still GREEN.** Nothing here changes the PRD's core claim that AI cost is not the risk (concierge labor and payout fees still dwarf it). The corrections above are precision fixes, not a re-scope.

---

## 7. ACTION ITEMS

| # | Action | Owner | Blocking? |
|---|---|---|---|
| 1 | Re-pin Gemini model string away from `2.0-flash` before any Domain D code calls it | Vikram | Yes — build-blocking for Domain D |
| 2 | Cap TTS spoken-reply length (~200 chars); confirm contracted Sarvam TTS tier | Vikram | No, but ship alongside voice (Phase 5) |
| 3 | Add a daily hard cap (proposed 500 actions/day) to the "unlimited while live" credit tier | Vikram | No — should land with `AICreditService` work, before live-money launch |
| 4 | Kabir red-teams the new daily cap for bypass | Kabir | Gate before live-money launch, same as other credit/rate controls |
| 5 | Add a standing "re-verify provider model IDs + pricing" checklist item before any provider-facing build starts | Rohan (process) | No |
| 6 | Fold Python-container + Redis hosting costs into `cost-log.json` once Meera provisions them | Rohan | No |
| 7 | Track MSG91 email volume against existing OTP baseline once Domain B ships | Rohan | No |

**Nothing above requires slowing down Vikram, Kabir, or Ananya's remaining-work packets (`16`/`17`/`18`).** Item 1 should be folded into `16`'s Domain D task list as a one-line correction before that section is built from.

---

## SIGN-OFF

**Rohan's recommendation to Swapnil:** APPROVE the remaining-work plan as costed, with the Gemini model-string correction applied and the live-tier daily cap (§5) added to Vikram's `AICreditService` scope. No budget increase required. Escalation level: **GREEN** (well under 70% of any threshold — this review is precision-tuning, not a budget alarm).

Awaiting Swapnil's sign-off below.

---

**Sources checked (2026-07-05):**
- Anthropic Claude pricing — platform.claude.com/docs/en/about-claude/pricing
- Gemini API pricing / 2.0 Flash deprecation — ai.google.dev/gemini-api/docs/pricing; aifounders.cz "Gemini 2.0 Flash Is Deprecated" (2026); tokencost.app migration guide (2026)
- Gemini 2.5 Flash-Lite pricing — pricepertoken.com/pricing-page/model/google-gemini-2.5-flash-lite
- Sarvam AI pricing — sarvam.ai/api-pricing; docs.sarvam.ai/api-reference-docs/pricing
