# Meera for Creators — Credit Model GTM Plan

**Author:** Tejas Mehta, CMO  
**Date:** 2026-09-05  
**For:** Swapnil Maruti, CEO  
**Context:** Phase B finance model, responding to "where does subscription fit and should new creators get 20 free credits?"

---

## 1. Where the Subscription Sits: Free Forever vs. Credits

### The "No Flat Fee" Position

Indian tier-2/3 creators (our target: 5k-50k followers, 5-15k INR monthly deal income) are price-sensitive and already conditioned by competitors like Snippet ($50/mo), Managr (EUR 29-199/mo), and Seneca ($49-99/mo) — all flat-fee, no-commission models. Our 15% platform fee is already our revenue line. A subscription **on top of** that commission would position us as "more expensive" and kill signup.

**Market positioning:** "Meera is what your 15% buys" — not an upsell, but the value that justifies the fee you already pay when you earn.

### What Stays Free Forever

These actions never draw credits, ever. They are the core value of the 15% fee and what keeps creators sticky even when they're between deals:

- **Money watch** (Phase C): payment timeline, payout status, TDS ledger, invoice tracking
- **Delivery proof** (Phase C): 24h/72h/7d snapshots, post metrics, keep-up monitoring
- **Health flags** (Phase D): account health rules, compliance alerts, follower/engagement warnings
- **Weekly note** (Phase D): money-first digest, what to post, what to fix
- **Consent & settings**: all preferences, opt-ins, rate card, channel management
- **WhatsApp notifications** (Phase C): deal updates, payment alerts, milestone reminders

**Why this split protects us:** A creator with zero deals this month still gets value (weekly note, health tracking). They stay on the platform, stay compliant, and are ready when the next deal arrives. Snippet and Seneca charge upfront whether you earn or not — we don't.

### What Draws Credits

AI-powered work that costs us real money per use:

| Action | Credit Cost | Use Case |
|--------|-------------|----------|
| Chat turn | 1 credit | "Should I accept this brief?" "How do I negotiate?" |
| Brief extraction | 3 credits | Paste a brand email → structured analysis + flags + quote |
| Voice turn | 2 credits | Hands-free chat (additive on top of the turn itself) |
| Discovery run | 40 credits | Find 5 brands in your niche who aren't on Influora yet (Phase E) |
| Pitch draft | ~1 credit | Draft an outreach email to a discovered brand (Phase E) |

**Why this split monetizes correctly:** Heavy users — creators running discovery every week, pasting 10 briefs a month, using voice constantly — are either (a) earning enough to afford it, or (b) using Meera as a prospecting tool beyond deal execution, which is a different value tier. Light users who paste 2 briefs a month and chat occasionally stay comfortably free.

---

## 2. The Signup Grant: Is 20 Credits Right?

### What 20 Credits Buys (Rohan's Costing)

Under Option C's blended rate (Rs 0.559/credit), 20 credits = Rs 11.18 absorbed cost. Here's what a creator can do with 20 credits:

- **Option A:** 20 chat turns (enough for 3-4 deal conversations)
- **Option B:** 6 brief extractions + 2 chat turns (paste 6 brand emails, chat twice)
- **Option C:** 10 chat turns + 3 brief extractions + 1 voice turn
- **Not enough for:** Even half a discovery run (needs 40 credits)

### What a First-Week Creator Needs to Reach "Aha"

The activation goal is: **paste one brief, see the price and flags, understand what Meera does.** That's 3 credits for extraction + maybe 2-3 credits for follow-up chat ("Why is this flagged?" "Can I counter-offer?"). Call it **6 credits minimum** to hit first value.

20 credits gives them **3 full brief analyses** with room for questions. That's enough to experience the value, but not enough to run discovery (which is a Phase E power-user feature anyway).

### Recommendation: 30-Credit One-Time Grant + 40 Monthly Free Allowance

**Structure:**
- **One-time signup grant:** 30 credits (never expires, use anytime)
- **Monthly free allowance:** 40 credits/month (Rohan's Option C baseline)
- **First secured deal bonus:** +20 credits when your first deal on Influora moves to "funded" status

**Why 30 + 40 beats a flat 20:**
- 30 one-time = **10 brief pastes** or **5 briefs + 15 chat turns** — enough to thoroughly test Meera across multiple real briefs without paying
- 40 monthly free = covers the **Light persona** (26 credits needed: 20 turns + 2 briefs) with headroom, keeps Typical creators (98 credits) buying top-ups at a sustainable rate
- First-deal bonus = **behavioral reward** tied to platform success, not just AI usage; a creator who closes their first deal gets 20 more credits to paste the next brief or chat about delivery

**What this abandons:** A pure "20 credits monthly free, no one-time grant" model. That's too stingy for onboarding — a creator pastes 6 briefs in week one (18 credits), burns through their whole month's allowance before they've even accepted a deal, and churns. The one-time grant **decouples** "try Meera thoroughly" from "use Meera sustainably."

---

## 3. Credit Packs and the Paid Tier

### Credit Packs (GST-Inclusive Display)

Rohan's Rs 249/100-credit pack is the baseline. Build a simple 3-tier pack structure:

| Pack | Credits | Price (incl. GST) | Cost/Credit | Margin |
|------|---------|-------------------|-------------|--------|
| Starter | 50 | Rs 149 | Rs 2.98 | 81.2% |
| Standard | 100 | Rs 249 | Rs 2.49 | 77.5% |
| Power | 300 | Rs 649 | Rs 2.16 | 74.1% |

**Display:** "Rs 249 (incl. GST)" — no checkout surprise, standard Indian B2C practice. The base price before GST is computed server-side for invoicing, never shown to the creator.

**One-time packs, not subscriptions.** Creators buy when they need, credits never expire. No recurring charge anxiety, no "cancel my plan" support load.

### The Manager Seat (Rs 1,999/month)

For agencies or high-volume creators managing multiple clients:

- **Price:** Rs 1,999/month (GST-inclusive)
- **Includes:** Up to 5 creator profiles under one manager login, shared credit pool (details TBD in Phase E spec section 9)
- **Margin:** 86% even if all 5 profiles run at Typical usage (Rohan's sheet)
- **When to show it:** Not at launch. Introduce 60 days post-launch after we see actual multi-profile demand in support tickets or creator interviews.

### How Credits Interact with the 15% Fee

**Rule:** Credits and commission are independent. Credits never reduce the platform fee. The platform fee never buys you credits.

- You pay **15% on every deal payout**, whether you used 0 credits or 300 credits that month.
- You pay for **credits** when you use AI features, whether you closed 0 deals or 10 deals that month.
- **Exception:** The 40 monthly free credits are absorbed cost (Rs 22.36/creator/month per Rohan's sheet), not deducted from the platform fee.

---

## 4. Earning Credits Without Paying: Simple and Abuse-Resistant

### Five Earning Paths

| Action | Credits Earned | Frequency Cap | Why It's Abuse-Resistant |
|--------|----------------|---------------|--------------------------|
| **Referral: brand joins through your pitch** | +100 credits | Once per brand | Phase E tracks which creator's pitch email a brand clicked; brand must complete signup + verify email; one brand = one credit grant, ever |
| **First secured deal** | +20 credits | Once, lifetime | Triggers when a deal moves to "funded" status; requires the funds to actually be secured; one-time only |
| **Profile completion** | +10 credits | Once, lifetime | All required fields filled: bio, rate card, portfolio with 3+ posts, Meta connected |
| **Meta Business Account connected** | +15 credits | Once, lifetime | Requires successful OAuth + page_read_engagement scope (Phase A baseline); one-time verification |
| **Weekly note streak** | +5 credits | Once per month | Open the weekly digest (Phase D) for 4 consecutive weeks; resets if you skip a week; caps at 5 credits/month |

**Total one-time earnable:** 145 credits (100 referral + 20 first deal + 10 profile + 15 Meta)  
**Total recurring earnable:** 5 credits/month (weekly streak)

**What we deliberately exclude:**
- ❌ "Share on social" — too gameable, creators will spam low-quality posts
- ❌ "Invite other creators" — Ponzi-scheme optics, and invitees who never activate are worthless
- ❌ "Leave a review" — creates review spam incentive
- ❌ "Daily login" — optimizes for vanity metric, not real usage

### Abuse Resistance

- **Referral** = hardest to game (requires a real brand signup, email verification, and attribution tracking from Phase E's pitch flow)
- **First deal** = requires actual money movement, can't fake it
- **Profile/Meta** = one-time verification, no repeat exploit
- **Weekly streak** = low payout (5 credits/month), high effort to game (would need to open 4 emails a month for a 2-rupee gain)

---

## 5. Messaging: Three Critical Copy Moments

### Pricing Page (for creators browsing pre-signup)

**Headline:** Meera is free. Top-ups are optional.

**Body:**
Every creator gets 40 free credits monthly — enough for brief reviews, deal advice, and daily questions. Pay only if you use discovery search or need extra help. Credits start at Rs 149 for 50, and there's no subscription. Your 15% platform fee already includes Meera; credits are just for power features.

**CTA:** Start free →

---

### Empty-Credit State (in-app, when balance hits zero)

**Tone:** Helpful, not pushy. No hype.

**Copy:**
You've used your free credits this month.

Top up to keep using Meera's AI features, or wait until [date] when your next 40 credits arrive. Money watch, delivery tracking, and your weekly note still work — those are always free.

**CTA 1:** Buy 100 credits — Rs 249 →  
**CTA 2:** See what's still free →

---

### Top-Up Nudge (when balance falls below 10 credits)

**Placement:** Small banner at top of chat, dismissible.

**Copy:**
You have [X] credits left. Top up now so Meera can keep helping.

**CTA:** Add credits →

**Suppress logic:** Once dismissed, don't show again until balance hits 5 credits. Never show more than once per session.

---

## 6. Rollout: What Ships When, What We Measure

### Phase B Launch (Week 1)

**Ships:**
- Credit ledger (backend table, debit/grant/refund on failed turns)
- 30-credit one-time signup grant (auto-applied at account creation for new creators; backfill for existing creators who haven't used Meera yet)
- 40 credits/month free allowance (replenishes on the 1st of each month)
- Credit pack purchase UI (3 packs: Rs 149/249/649)
- Brief extraction (3 credits), chat turns (1 credit), voice (2 credits) — all metered
- Backend copies the brand `AICreditService` shape into a creator-keyed `CreatorCreditService` (the brand table cannot hold a creator: see §8.2); costs are config, not the brand's flat `TURN_CREDIT_COST = 1`

**Does NOT ship yet:**
- Discovery runs (Phase E, gated on outreach domain + Perplexity API key)
- Pitch credits (Phase E)
- Manager seat (not before Day 60)
- Earning mechanisms (referral, streak, etc.) — all Phase E except profile completion, which is Phase B but low-priority

---

### Phase E Launch (Week 5-6)

**Ships:**
- Discovery runs (40 credits)
- Pitch drafts (1 credit)
- Referral credit grants (+100 credits when a brand you pitched joins)
- Profile completion bonus (+10 credits, one-time)
- Meta connect bonus (+15 credits, one-time)

**Does NOT ship yet:**
- Manager seat (wait for demand signal)
- Weekly streak bonus (Phase D dependency)

---

### First 30 Days: What We Measure

| Metric | What It Tells Us | Target (Month 1) | Red Flag |
|--------|------------------|------------------|----------|
| **Activation to first brief paste** | How quickly creators understand Meera's value | <24 hours for 60%+ of signups | >72 hours median |
| **Credit exhaustion rate** | % of creators who burn through all 70 credits (30 grant + 40 monthly) in month 1 | 15-25% (these are our power users) | <5% (we're over-allocating free credits) or >40% (we're under-allocating) |
| **Pack conversion rate** | % of creators who exhaust free credits AND buy a pack | 30%+ of exhausters | <10% (price too high or value unclear) |
| **Free-to-paid timing** | Days between signup and first pack purchase | 14-21 days (signals sustained usage, not impulse) | <7 days (grant too low) or >45 days (no urgency) |
| **Credits per brief paste** | How many credits creators spend per brief on average (extraction + follow-up chat) | 5-8 credits (3 for extraction + 2-5 turns of chat) | >15 credits (chat is becoming a support burden, not self-serve) |

**Kill criteria (Month 3):**
- Pack conversion <8% of credit exhausters → pricing is wrong or value is unclear, pivot to flat subscription
- >60% of creators never paste a brief → onboarding is broken, Meera's job-to-be-done is unclear
- Churn rate among paid creators >40%/month → credit model feels like nickel-and-diming, revert to commission-only

---

## 7. Open Decisions for Swapnil

Each decision ships with a stated default if no ruling arrives by Phase B Day 1.

| Decision | Options | Tejas Recommends | Default If No Ruling |
|----------|---------|------------------|----------------------|
| **D1: Manager seat timing** | (a) Ship with Phase E, (b) Wait 60 days post-Phase-E for demand signal | **(b) Wait 60 days** — no one is asking for it yet, and we don't want to split focus on a feature for <5% of users | Wait 60 days |
| **D2: Credit expiration** | (a) Credits never expire, (b) Expire after 12 months, (c) Expire after 6 months | **(a) Never expire** — Indian creators are inconsistent earners; a 6-month expiry feels predatory when someone's between deals | Never expire |
| **D3: Backfill grant for existing creators** | (a) All creators get 30-credit grant even if they signed up pre-Phase-B, (b) Only new signups get it | **(a) Backfill everyone** — existing creators are already skeptical of new features; giving them a grant signals "we value you" and re-activates dormant accounts | Backfill everyone who has used <10 credits total |
| **D4: Discovery run pricing** | (a) Keep at 40 credits (Rs 89 value at pack rate), (b) Subsidize to 20 credits to drive adoption, (c) Exclude from credit system entirely, make it a flat Rs 99/run add-on | **(a) Keep at 40 credits** — it's the highest-value feature in Phase E, and Rohan's costing (Rs 11.31 real cost per run) gives us 87% margin at 40 credits; subsidizing trains creators to expect freebies | Keep at 40 credits |
| **D5: Voice turn pricing visibility** | (a) Show "This turn cost 3 credits (1 base + 2 voice)" after every voice turn, (b) Just deduct silently and show running balance | **(a) Show the breakdown** — transparency builds trust, and creators need to learn that voice is more expensive; silent deductions feel like dark patterns | Show the breakdown |
| **D6: Credit gifting** | (a) Allow creators to gift credits to other creators (peer-to-peer), (b) Platform-only grants (no P2P) | **(b) Platform-only** — P2P gifting opens abuse vectors (credit laundering, fake-account farming) and adds billing complexity for near-zero user demand | Platform-only |
| **D7: Refund policy** | (a) Refund credits if Meera gives a wrong answer or fails mid-turn, (b) No refunds ever, (c) Refunds only for technical failures (timeout, error 500), not "wrong answer" | **(c) Technical failures only** — auto-refund on 500/timeout (already in the credit ledger design), but "wrong answer" is subjective and creates support hell | Refund on technical failure only |

---

## 8. What the Code Has Today vs. What We Build (Grounded, 2026-09-05)

<!-- 2026-09-05 revision: this section replaces the earlier draft, which described a ledger from first principles. It is now grounded in `facts/credits-billing.md` (every symbol opened at HEAD eac5e58). The build-level detail lives in `CREDITS-SPEC.md` in the Phase B folder; this section is the CEO-readable summary. -->

### 8.1 What exists and is reused as-is

| Piece | Where | How the creator ledger uses it |
|---|---|---|
| Brand AI-credit service: charge-at-send, refund only on provider failure, never refund after a persisted reply | `AICreditService` (`service/meera`), `MeeraSessionService.doSendTurn` / `doPersistAssistantWriteback` | Copied structurally into a new `CreatorCreditService`; the brand code is not edited |
| Idempotency ledger | `IdempotencyService.executeOnce` / `isCompleted` / `findCompletedResultDigest` | New scopes `meera.creator_charged` / `meera.creator_released`; no schema change |
| The creator branch in the turn path | `doSendTurn` L332 (`isCreatorTurn`), writeback L575, the server-minted turn id L324 | The charge is a one-line insertion on the branch that already exists |
| Python refund call | `chat.py` refund ladder → `POST /internal/meera/turns/release` | Already fires for creator turns; today it is a WARN no-op. One line in the controller makes it refund the creator ledger |
| Razorpay one-time orders + webhook routing by receipt prefix | `RazorpayClient.createOrder`, `RazorpayWebhookController.dispatchFundingEvent`, `WalletTopUpService.confirmCredited` (amount cross-check, server-derived key) | Pack purchase = new prefix `credits:` and one `startsWith` branch |
| Monthly reset job shape | `AICreditResetJob` (cron 1st 02:00 UTC, ShedLock, per-row try/catch) | Copied as `CreatorCreditResetJob` at 02:10 |
| Audit | `AuditLogService.recordMoneyEvent` / `recordAdminAction` | Pack credited + admin grant |
| Admin creator-agent controller | `AdminCreatorAgentController` (`PUT .../monthly-cap`) | Grant route added beside it |
| Frontend money gate, Razorpay checkout helper, error-code branching | `isMoneyActionBlocked`, `openRazorpayCheckout`, `MeeraCopilotChat` `ApiError.code` branches | New `'credit-pack'` operation; exhausted card; packs page |

### 8.2 What does not exist (verified) and is built

- **A creator balance table.** `brand_ai_credits` is keyed on `workspace_id` with an FK to `workspaces`; a creator has no workspace row, so it cannot hold a creator balance. New `creator_ai_credits` keyed on `users.id` with two buckets: `monthly_remaining` (resets to 40 on the 1st) and `purchased_balance` (packs, signup grant, admin grants; never expires).
- **A ledger with reasons.** Nothing today records *why* a balance moved. New `creator_credit_ledger` with `SIGNUP_GRANT | MONTHLY_RESET | PACK_PURCHASE | ADMIN_GRANT | TURN_DEBIT | TURN_REFUND | VOICE_DEBIT | VOICE_REFUND | BRIEF_DEBIT | BRIEF_REFUND` and a unique key that makes double-grants structurally impossible.
- **Weighted costs.** The brand ledger charges a flat 1 per turn. Creator costs are config: turn 1, voice 2 (charged at transcribe), brief 3 (charged in Phase B1's paste flow, refunded when the AI read falls back to the rule-based one).
- **Signup grant.** No signup hook exists and none is added: the 30 credits are granted the first time the creator starts a Meera session. Existing creators get it the same way, which is the "backfill everyone" default (D3) with zero backfill code.
- **A Spring-side gate before the model.** Today the only creator ceiling is Python's USD cap, enforced after the request has left Spring. The credit 402 fires in Spring before a stream token exists. The USD cap stays (raised to $2.00 per Phase B §14.4) as the cost fuse under the credit gate.
- **Read routes.** `GET /creator/meera/credits` (state `DISABLED | OK | LOW | EXHAUSTED`, `resets_on`), `GET /creator/meera/credits/ledger`; real values in the session-start and send-turn responses, which today hard-code `null` and `0`.
- **Packs.** Catalogue table seeded in a migration (the `plans` precedent; there is no yml pricing anywhere), `creator_credit_orders` mirroring `wallet_topups`, `GET /creator/credits/packs`, `POST /creator/credits/orders` with an `Idempotency-Key`, webhook branch, order poll route. Credits are not money: nothing is posted to `wallet_transactions`.
- **Kill switch and config.** `CREATOR_CREDITS_ENABLED` (default false) plus grant, allotment, three costs, daily cap, and low-balance threshold, all bound in `application.yml` (a named env var without a yml placeholder binds to nothing in this codebase).
- **Frontend.** Badge on the Co-pilot page, exhausted card with the two CTAs from §5, low-balance nudge with the suppression rules from §5, `/creator/credits` page, two pricing bullets behind a flag.

### 8.3 What the earlier draft got wrong (so nobody builds it)

- There is no `CreatorCoupon` entity and no referral table; `CouponCode` is a brand discount code. Earning paths (§4) need new ledger reasons only, no schema change, and are not v1.
- `payment_method = STRIPE` does not exist anywhere; Razorpay is the only gateway.
- There is no `meera_enabled` flag on creators; the feature flag is `MEERA_CREATOR_ENABLED` and the credit flag is separate.
- Costs cannot live only in yml under `influora.creator.credits` without a properties class; the spec defines `CreatorCreditProperties` under `influora.meera.creator-credits`.
- `ON DELETE CASCADE` is not used on money-adjacent tables here; the ledger keeps rows.
- The brand "150 credits after first funded campaign" advertised on the pricing page never fires in code (`applyEscrowFundedReset` has no caller). Not our bug to fix in this track, but do not copy the pattern.

### 8.4 Scope and cost of the build

Backend: 4 migrations, 4 entities, 4 repositories, 2 services, 1 properties class, 1 job, 2 events, 1 new controller, edits to 4 existing classes (`MeeraSessionService`, `MeeraInternalController`, `CreatorMeeraController`, `RazorpayWebhookController`, `AdminCreatorAgentController`), tests for each. Frontend: 1 page, 3 components, 2 API namespaces, tests. Python: no change. Everything ships dark behind two flags. Full step list: `CREDITS-SPEC.md` §12.

---

## Summary for Swapnil

**Where subscription sits:** Credits are optional top-ups for AI features (chat, briefs, discovery). Core platform value (money watch, delivery proof, health flags) stays free forever. Positioning = "Meera is what your 15% buys; credits are for power users."

**Signup grant:** Recommend **30 credits one-time + 40 credits monthly** (not 20). 30 one-time lets new creators thoroughly test Meera (10 brief pastes). 40 monthly keeps Light creators free and Typical creators buying top-ups at sustainable rates.

**Credit packs:** Rs 149/249/649 for 50/100/300 credits (GST-inclusive display), 74-81% margin. Manager seat (Rs 1,999/month) waits until Day 60 for demand signal.

**Earning without paying:** Five mechanisms (referral, first deal, profile, Meta, weekly streak), all abuse-resistant and tied to real platform actions.

**Rollout:** Phase B ships ledger + packs + metering. Phase E adds discovery (40 credits) + pitches (1 credit) + earning mechanisms. Measure activation speed, exhaustion rate, pack conversion, and free-to-paid timing. Kill at Month 3 if pack conversion <8% or paid-creator churn >40%.

**Open decisions:** Seven decisions flagged (manager timing, expiry, backfill, discovery pricing, voice visibility, gifting, refunds), each with a recommended default.

**Backend scope (grounded 2026-09-05):** Copy the brand `AICreditService` shape into a creator-keyed service; four new tables (balance, ledger, pack catalogue, orders), one job, one webhook branch, edits to five existing classes, no Python change. Ships dark behind `CREATOR_CREDITS_ENABLED`. Details: §8 and `CREDITS-SPEC.md`.

**Market positioning holds:** We are not Snippet (flat $50/mo, no commission). We are "commission + free Meera + optional top-ups for heavy use." That keeps us defensible against flat-fee competitors while monetizing the power-user tail.

---

**Questions for Swapnil before Phase B Day 1:**
1. Approve 30-credit grant + 40 monthly, or revert to 20 total?
2. Backfill existing creators, or new signups only?
3. Discovery at 40 credits (87% margin), 20 credits (subsidized), or flat Rs 99/run?
4. Manager seat at Phase E launch or wait 60 days?

— Tejas
