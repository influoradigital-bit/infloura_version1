# Influora final price sheet (India only), 2026-09-19

Chair: neutral. Voices: Swapnil (CEO, owner), Tejas (CMO), Rohan (CFO). Based on each person's final position after the challenge round.
Rule applied: **top-ups first.** Free allowances let people try the product, and top-up packs are what we sell. No plan includes as many credits as a Typical user burns in a month.
All money is in Rs. GST is 18% and is stated on every line. All margins and contributions are **ESTIMATES** built on the Phase B cost sheet (blended Rs0.559/credit, chat turn Rs0.648, brief read Rs0.294, voice Rs1.415, discovery search Rs11.31, pitch bundle Rs1.81) and a gateway cost of about 2% of the gross.
**Status: this is a spec.** Nothing on this sheet can go live until the section 5 items are built. Brand credit packs, the creator credit ledger, the Rs199 plan tool, the brand outreach pitch and the manager seat do not exist in code today.

> **CEO rulings, 2026-09-19 (Swapnil). These override the tables below wherever they differ.**
> 1. **Creator free credits require a connected account.** Until a creator connects Instagram or Facebook (Meta connect), Meera gives them 5 onboarding turns (lifetime), then asks them to connect. On first connect they get a **one-time +30 connect bonus**, plus **40 credits/month** from then on. The +30 replaces the "signup 30" and "Meta connect 15" earned lines.
> 2. **Deduction model: 1 credit per Meera message, with surcharges only where we have a real cost.** A Meera tool that only reads or writes our own database costs **0 extra** (show creators, budget, campaign draft, payment request, launch). Surcharges apply only to voice (+2), brief reads (3) and web discovery / pitch (not built). This matches the code today: `TURN_CREDIT_COST = 1` (`MeeraSessionService.java:84`), and tool calls inside a turn are not charged. The "brand creator-discovery search 10 credits" line is **removed**: Meera's creator search runs on our own database, and the same search is free outside Meera.
>
> **Online search that already exists (verified in code, 2026-09-19):**
> - **Brand live Instagram lookup:** `GET /creators/external/lookup` → `ExternalCreatorService.lookup` → Meta Business Discovery API (`instagramInsightsClient.businessDiscovery`). It looks up any public Instagram business or creator account live. It has **no AI cost, no credit charge and no Influora rate limit**; only Meta's API limits apply. Decision: keep it **free** (it has no AI cost and it drives sign-ups), but add a per-workspace rate limit before launch.
> - **Meta Creator Marketplace search:** `CreatorMarketplaceClient.search`, off by default (`META_CREATOR_MARKETPLACE_ENABLED:false`, needs the `instagram_creator_marketplace_discovery` scope). Free once it is enabled.
> - **Brand website analysis:** `analyze_site.py` fetches the brand's own URL (through an SSRF guard) and runs an AI extraction. It is triggered by Meera and is not credit-metered. Its cost is covered by the message.
> - **TrendSpark:** `TrendPullJob` pulls trend headlines from outside sources on a schedule. It is platform cost, not charged per user.
> - **No general web-search API** (Perplexity, Google, SERP) exists anywhere in the code. The creator "brand-discovery search" (~Rs11.31 per run, Perplexity-priced in Phase B) is the only paid web search, and it is **not built**.

---

## 1. Final price sheet

### 1a. Brands

| Line | Final | GST | Notes |
|---|---|---|---|
| Platform fee, Free | **10% of funded budget** | + 18% GST on the fee | Charged on top of the budget. No minimum fee. The rate is snapshotted per deal. |
| Platform fee, Pro | **5% of funded budget** | + 18% GST on the fee | 5% for Pro is not built yet (see section 5). |
| Pro monthly | **Rs4,999/mo** | + GST (Rs5,898.82 at checkout) | Includes 5 seats, the 5% fee and 200 credits a month (reset monthly, no rollover). Pro users get +20% credits on every pack. Pro pays for itself against Free at about Rs1L funded per month. |
| Pro annual | **Rs49,990/yr** | + GST | 2 months free. Credits are still granted at 200 per month. |
| Existing Pro | 400 credits/mo for **90 days**, then 200 | - | We give in-app notice before the step-down. The upgrade page never shows 400 again. |
| Free signup credits | **50, one time** | - | No monthly refill. Raise to 75 if median turns-to-funded exceeds 40 in the telemetry window. |
| Funded-campaign grant | **+50 per FUNDED campaign**, max 2 grants/mo | - | Never granted on create. The free ceiling is 150 credits a month, and only for a brand that is funding campaigns. |
| Existing Free brands | Keep 100/mo for **60 days**, then the new rule | - | This window is also used to collect turns-to-funded telemetry. |
| Credit cost per action | **Meera message 1** (any database tool in that reply is included: show creators, budget, campaign draft, payment, launch) · voice +2 on top of the message (voice message = 3) · brief read 3 · live Instagram lookup **free** · outreach pitch + 2 follow-ups 3 | - | Will be shown on every screen that spends credits. **Today only the 1-credit message is metered.** Voice, brief-read surcharges and the pitch are not built. Voice is the first surcharge to add, in `MeeraController` `/voice/speak`. |

**Brand top-up packs** (prices exclude GST, 18% added at checkout; paid credits burn last; validity is covered in section 2):

| Pack | Price ex-GST | Checkout incl. GST | Credits | Rs/credit (ex-GST) | With Pro +20% | First pack ever (+50%, capped at +50) | Margin blended / worst case* |
|---|---|---|---|---|---|---|---|
| Starter | Rs499 | Rs588.82 | 100 | Rs4.99 | 120 | 150 | 84.6% / 83.8% |
| **Growth** (Most popular, pre-selected) | Rs1,499 | Rs1,768.82 | 330 (+10%) | Rs4.54 | 396 | 380 | 83.4% / 82.5% |
| Scale | Rs2,999 | Rs3,538.82 | 750 (+25%) | Rs4.00 | 900 | 800 | 81.4% / 80.4% (with Pro bonus 78.2% / 77.0%) |

*Worst case assumes every credit is spent on voice messages (Rs2.063 per 3-credit voice message = Rs0.688/credit). Before the CEO ruling above it assumed paid discovery searches at Rs1.13/credit; brands have no paid search now. Blended assumes chat at Rs0.648. Margin is price ex-GST minus AI cost minus 2% gateway on the gross, divided by price ex-GST. The Scale pack stays at 60% of Pro's list price, so the largest pack never competes with Pro.

### 1b. Creators

| Line | Final | GST | Notes |
|---|---|---|---|
| Commission on paid deals | **10%** (was 15%) | **OPEN, needs a CA ruling:** inclusive ("you keep 90% before applicable taxes") or plus GST ("you keep 88.2%") | The rate is snapshotted per deal. In-flight 15% deals keep 15%. No 0% buckets. No subscription is needed for paid collabs. |
| Free Meera | **Only after Meta connect:** one-time +30 connect bonus + **40/mo** | - | Before connecting, a creator gets 5 onboarding Meera messages (lifetime), then a prompt to connect. Monthly credits reset each month. The +30 counts toward the earned-credit lifetime cap. (CEO ruling 2026-09-19.) |
| Free AI cost cap | **$1.25 (~Rs105)** for free credits (today $0.75) | - | Balances the creator paid for are **never** capped, because the cap is derived from credits. |
| Credit cost per action | **Meera message 1** (database tools in the reply included) · voice +2 on top of the message (voice message = 3) · brief read 3 · brand-discovery web search 20 · pitch + 2 follow-ups 3 | - | Search and pitch are **not built** (creator Meera has no tools today, creator_persona.py:86). List them only when they ship. The web search (~Rs11.31/run) is the one action with a real per-use cost, so it keeps a surcharge. |

**Creator top-up packs** (GST-inclusive; paid credits burn last):

| Pack | Price incl. GST | Net of GST | Credits | Rs/credit (incl. GST) | First pack ever (+50%, capped at +50) | Margin blended / worst case (all voice) |
|---|---|---|---|---|---|---|
| Tap-in | Rs99 | Rs83.90 | 40 | Rs2.48 | 60 | 71.0% / 64.7% |
| **Popular** (Most popular) | Rs249 | Rs211.02 | 120 | Rs2.08 | 170 | 65.9% / 58.4% |
| Power | Rs649 | Rs550.00 | 350 | Rs1.85 | 400 | 62.0% / 53.7% |

Reprice if the measured cost goes above Rs0.75 per credit. These packs replace Phase B's Rs149/50, Rs249/100 and Rs649/300. The Rs649 anchor is kept from Phase B.

| Line | Final | GST | Hard limits / conditions |
|---|---|---|---|
| **Rs199/mo Personalised PR plan** | Rs199/mo, monthly only | GST-inclusive (net Rs168.64) | **WAITLIST ONLY: "founding price, notify at launch".** We bill nothing and claim no features until two things are live: the PR-finder tool and server-side first-contact attribution. When it ships: **one 60-credit pool per month**, with no rollover and no separate quotas. Every action is charged from the pool, so full use is 3 searches, or 20 pitches, or 60 chat turns. That works out to Rs3.32 per credit, dearer than every pack. Subscribers get **+10% on every pack**. Commission is **5% only** where a Meera-sent pitch was the pair's first contact while the creator was subscribed. That means the brand's first reply is in the pitch thread and the deal is signed within **30 days**. The cut is capped at Rs3L per brand-creator pair or 12 months, whichever comes first, and every case is audit-logged. If attribution does not ship, the plan launches with **no** commission cut. Worst-case AI cost is about Rs41 plus about Rs4 gateway, a margin of about 73%. |
| **Manager seat** | Rs1,999/mo | + GST (Rs2,358.82) | Covers 5 creator profiles, with a pooled 250 credits a month (no rollover). Pooled packs get **+15%**. Each creator's commission is unchanged. **Built only if there is demand 60 days after launch.** Margin is about 88%. |
| **Earned credits** | Lifetime cap **85** | - | Meta connect bonus 30 (once, first connect; this replaces the old signup 30 and connect 15), first paid deal 20, profile complete 10, referral 25 (when the referred creator's profile is verified). Each grant is keyed in the ledger so it can never be granted twice. Earned credits are never cash. There are no on-time-delivery credits and no gifting between users. Cost is about Rs48 (85 × Rs0.559), once per creator. |

---

## 2. Top-up mechanics (the rules that make top-ups the natural path)

1. **Allowances are small, and packs are how you grow.** Brand Free is a one-time 50, and the only refill comes from FUNDED campaigns. Pro includes 200 credits (Typical brand ~86-100/mo, Heavy ~284/mo). The PR plan includes 60 and the manager seat 250. No plan gives credits more cheaply per rupee than the middle pack.
2. **Subscriptions multiply top-ups instead of replacing them.** Pro gets +20% on every pack, the PR plan +10% and the manager seat +15%. Paying subscribers become our best pack buyers.
3. **Bigger packs always give a better rate.** Brand packs step down Rs4.99 → 4.54 → 4.00 per credit, and creator packs Rs2.48 → 2.08 → 1.85. The middle pack is labelled Most popular and pre-selected, and every larger pack shows "save X%".
4. **First pack ever: +50% credits, capped at +50.** This happens once per account and is offered at the first low-balance moment. It costs us Rs13-32 in AI, which is cheap for converting someone into a buyer.
5. **Burn order: grant, then monthly plan credits, then earned, then paid.** Allowances run out first. The balance the user paid for visibly survives the month, so buying feels safe.
6. **Validity (majority 2-1; Tejas dissents, see section 3):** paid and earned credits stay valid for **12 months from the last purchase or the last credit spend**. Any activity renews the whole balance, so active users never lose credits. Before any balance lapses, the user gets an email reminder at 30 days and again at 7 days. Copy never says "never expire".
7. **Nudges help and never block.** Brand: a banner when the balance falls below 20% of its last refill. Creator: a banner at 5 credits. Both sides: a cost preview and an inline "Add 100 credits - Rs499 + GST" (brand) or "Add 40 credits - Rs99" (creator) before any action that would overdraw. At zero balance, the chat input becomes the pack picker. All banners are dismissible. The user sees ONE balance number and never the buckets.
8. **Offer packs while checkout is warm.** Brands see a pack offer right after funding a campaign, while the Razorpay session is open, with an outreach credit estimate. Creators see the Rs249 pack right after a payout is released.
9. **Monthly usage email** showing the balance and the projected run-out date.
10. **Auto-top-up is opt-in and OFF by default.** The user picks the pack, the threshold and a monthly cap. Each charge stays under Rs15,000, the RBI e-mandate limit before extra authentication is required. An email receipt is sent every time. A legal and compliance review is needed before this is built.
11. **Credits are a closed loop.** They are a separate product from wallet money (ledger reason PACK_PURCHASE, receipt prefix `credits:`). They are non-refundable, non-transferable and not cashable, and can be used only for Influora AI services. They are never mixed with wallet funds meant for creators.
12. **Credit costs per action are fixed and published.** We never lower the credits an action costs to make a pack look bigger.

---

## 3. How each number was decided

Each person's position here is their FINAL position after the challenge round.

| Line | Swapnil | Tejas | Rohan | Result | Basis |
|---|---|---|---|---|---|
| Brand fee Free 10% / Pro 5% | yes | yes | yes | **Consensus** | Settled direction. |
| Fee GST display | + GST | GST-inclusive | + GST | **2-1 plus GST** | B2B input tax credit norm, and matches how Pro is priced. |
| Rs299 minimum fee | no | not proposed | yes | **Rejected** | It breaks the clean 10% headline below Rs2,990 of budget (a Rs1,500 campaign would pay 20%). Funded grants cost at most Rs64.80/mo, which a 10% fee on a budget of about Rs650 already covers. **Dissent: Rohan.** |
| Pro Rs4,999 + GST, annual Rs49,990 | yes | yes | yes | **Consensus** | - |
| Pro 200 credits, existing Pro keep 400 for 90 days | yes | yes | yes | **Consensus** | At 400 included credits, not even a Heavy user (~284) would buy a pack. |
| Pro +20% on every pack | yes | yes | yes | **Consensus** | - |
| Free 50 one-time + 50/FUNDED, max 2/mo; existing Free keep 100 for 60 days | yes | yes | yes | **Consensus** | Rohan showed a monthly 50 refill would cover about 58% of Typical usage for free. |
| Brand ladder Rs499/100, 1,499/330, 2,999/750 (+ GST) | yes | no (Rs299/40, 999/160, 2,499/500) | yes | **2-1** | The entry pack covers one Typical brand month in one purchase. Worst-case margin stays at or above 69%. Tejas's Rs6.24-7.48/credit gives a Typical brand a monthly credit bill of about Rs860 or more. **Dissent: Tejas** wants the lower Rs299 entry price. |
| Brand pack GST | + GST | GST-inclusive checkout | + GST | **2-1 plus GST** | - |
| ~~Brand search 10 credits~~ | yes | none priced | yes (only with the funded-only refill) | **Overruled by CEO, 2026-09-19** | Meera's creator search reads our own database (no per-use cost), and the same search is free outside Meera. It is now covered by the 1-credit message. |
| First pack +50% | +50%, cap 50 | +20% | +50% | **2-1 +50%** | The cap bounds the cost of the brand Scale pack. **Dissent: Tejas** (+20%). |
| Validity | 12 months rolling from last purchase | never expire | 12 months rolling from last purchase **or use** | **2-1 rolling; Rohan's "or use" wording chosen** | It gives a date for booking unused credits while protecting active users. **Dissent: Tejas (his must-have).** He argues that a creator who is quiet for 13 months loses the balance, and that the unused-credit liability can be booked without expiry. **The evidence favours him in part:** the Phase B GTM plan (credits-gtm-plan.md, decision D2) recommended "never expire" because Indian creators earn unevenly. The chair keeps the majority position but adds the 30-day and 7-day reminders and escalates this to Swapnil (Q1). |
| Creator commission 10%, snapshot per deal | yes | yes | yes | **Consensus** | Whether GST is inclusive or added is open (Q2). |
| Creator free 30 + 40/mo | yes | yes | yes | **Consensus; amended by CEO 2026-09-19** | Phase B spec. The CEO made both grants conditional on Meta connect (5 onboarding messages before connecting). |
| Free creator AI cap | keep $0.75 | not stated | $1.25 | **$1.25, on evidence** | Month one can reach 40 + 30 + up to 55 more earned credits (earned credits are capped at 85 lifetime, and the connect bonus of 30 is part of that). That is up to 125 credits, about Rs86 at worst case, above the Rs63 cap. A creator would then be throttled while still showing a positive balance. **Dissent: Swapnil** (keep $0.75). |
| Creator search credits | 20 | 15 | 20 | **2-1 at 20** | At 20 a search uses half of a Rs99 pack, not the whole pack (the 30-credit version would have used all of it). It nets about 68%. **Dissent: Tejas** (15, so one tap doesn't eat most of a first pack). |
| Voice | +2 | voice turn 3 | +2 on top of the turn | **Consensus in substance** | A voice turn costs 3 credits. |
| Creator ladder Rs99/40, 249/120, 649/350 | no (Rs99/50, 249/140, 599/375) | Rs99/40, 249/120, **699**/350 | Rs99/40, 249/120, 649/350 | **2-1 on the lower two packs; Rs649 top pack on evidence** | Swapnil's ladder falls to 45-51% at the all-voice worst case, and Rohan will not sign a pack below about 50%. Rs649 is the Phase B anchor, and Tejas originally proposed Rs649 too. **Dissent: Swapnil** (more generous packs sell better under a top-ups-first rule). |
| Rs199 plan waitlist, no billing | yes | yes | yes | **Consensus** | creator_persona.py:86: no tools. |
| Rs199 plan as a single credit pool | yes | yes | yes | **Consensus** | Users see one number. |
| Rs199 pool size | 100 | 100 | 60 | **60, on evidence** | Swapnil set the test himself: the plan must be dearer per credit than the Rs249 pack. With the adopted ladder the Rs249 pack is Rs2.08/credit, and 100 credits for Rs199 is Rs1.99, which fails that test. 60 credits is Rs3.32/credit, which passes. **Dissent: Tejas** (100; he warns that 3 searches a month may churn early subscribers). **Swapnil's number was 100, but his own principle requires fewer credits.** Resize once there is telemetry. |
| Rs199 pack bonus | +15% | +10% | +10% | **2-1 +10%** | At +25% the Rs649 pack falls to about 43% at worst case. |
| 5% cut window | 30 days | 30 days | 60 days | **2-1 at 30 days** | A shorter window gives less room for gaming the attribution. **Dissent: Rohan** (60). |
| 5% cut cap Rs3L per pair / 12 months | yes | yes | not restated | **Adopted** | - |
| Manager seat Rs1,999 + GST, built after 60 days | yes | yes | yes | **Consensus** | - |
| Manager seat pooled credits | 250 | 500 | 250 | **2-1 at 250** | 500 would exceed what five Light-to-Typical creators need, so they would not buy packs. **Dissent: Tejas.** |
| Manager seat pack bonus | +15% | +25% | +20% | **No majority; +15% chosen** | It is the only option that keeps the Rs649 pack at about 47% worst case. +20% gives about 45% and +25% about 43%. The chair decided this, and it is not a consensus. Re-check before the seat is built. |
| Earned credits lifetime cap 100 (now 85: CEO folded signup + connect into one 30 connect bonus) | yes | not stated | yes | **2-0** | A monthly cap of 100 would have been a back-door refill: 5 deals a month would earn 2.5 times the monthly allowance. |
| Auto-top-up opt-in, OFF, under Rs15,000 | yes | yes | yes | **Consensus** | - |

---

## 4. Worked examples (ESTIMATES)

**Basis:** fee plus GST. Commission shown **GST-inclusive** (Rs10,000 includes Rs1,525 GST, so Influora keeps Rs8,475); this is the conservative case until the CA rules. Gateway is about 2% of the amount collected. Ops overhead is Rs500 per deal (chair's common basis). If the commission is ruled plus-GST, add about Rs1,525 per Rs1L. TDS is not modelled; it is withheld tax, not a cost to us.

**A. Rs1L deal from a Free brand, standard creator**
- The brand pays Rs1,00,000 + fee Rs10,000 + GST Rs1,800 = **Rs1,11,800**.
- The creator receives **Rs90,000**.
- Influora keeps fee Rs10,000 + commission Rs8,475 = Rs18,475. Costs are gateway about Rs2,236, payout about Rs50, AI about Rs200 and ops Rs500.
- **Contribution: about Rs15,500** (about 15.5% of budget). The three bases on the table give Rs14,150-16,900.
- If the deal also triggers one brand Growth pack (+Rs1,250) and one creator Rs249 pack (+Rs139): **about Rs16,900**.
- Today at 15% commission, the commission line alone would be about Rs4,237 higher. Pack revenue recovers about a third of the cut, so the plan needs about 1.3 times the completed deal value (GMV) at the 90-day review.
- The same deal from a Pro brand: fee Rs5,000, so about Rs10,500 on the deal, plus the Pro subscription (about Rs4,000 contribution a month).

**B. Rs25k nano deal from a Free brand**
- The brand pays Rs25,000 + fee Rs2,500 + GST Rs450 = **Rs27,950**.
- The creator receives **Rs22,500**.
- Influora keeps Rs2,500 + Rs2,119 = Rs4,619. Costs are gateway about Rs559, payout about Rs10, AI about Rs60 and ops Rs500.
- **Contribution: about Rs3,490** (about 14%).
- A Rs299 minimum fee (rejected) would have made no difference here.

**C. A Typical creator month** (AI cost about Rs56, about 100 credits; only features that are built today)
- Usage: 60 chat turns (60) + 8 brief reads (24) + 5 voice turns (15) = **99 credits**.
- Month 1 (after connecting Instagram): 30 connect bonus + 40 monthly = 70 free credits, so the creator is 29 short. At 5 credits left they see the nudge and buy their first Rs99 pack: 40 + 20 bonus = 60 credits, with 31 left over.
- Month 2: 40 free credits. They are 59 short, so they use the 31 left over and then buy Rs249 (120 credits), leaving 92.
- Month 3: 40 free, so they use 59 of the 92, leaving 33.
- Month 4: they buy again.
- Steady state is **about Rs249 every 2 months**, about Rs139 contribution per pack.
- If they close one Rs25k deal, they keep Rs22,500.
- Once the PR tool ships, adding 1 search (20 credits) and 3 pitches (9) raises the need to about 128 credits a month, which is roughly one Rs249 pack a month.

**D. A Free brand's first month**
- Signup: 50 credits. The brand explores with 30 Meera messages (30, including Meera showing creators), 4 voice messages (12), 2 brief reads (6) and 5 live Instagram lookups (free), which uses 48. With 2 credits left (under 20% of the last refill) the banner appears.
- The brand funds a Rs50k campaign: fee Rs5,000 + GST Rs900, and +50 credits (balance 52). It plans with 44 Meera messages and 2 voice messages, which uses 50.
- The warm-checkout pack offer, shown right after funding, converts: first Starter pack Rs499 + GST = Rs588.82, giving 100 + 50 bonus = **150 credits**.
- The brand funds a second campaign: +50, the last grant this month.
- Month total: fees Rs10,000 (if both campaigns are Rs50k) + pack Rs499 ex-GST. Free credits granted: 150.
- The outreach pitch spend is left out of this example because the brand outreach pitch is not built.

---

## 5. What must be built or fixed before each line goes live (in order)

**A. Code blockers. Nothing that shows a percentage is published until these are fixed.**
1. `influora-api/src/main/resources/application.yml:407` `platform-fee-percent: ${PLATFORM_FEE_PERCENT:15.00}` → set it to 10.00 and make sure prod env matches. This blocks the 10% creator commission.
2. `BrandPlatformFeeService.java:36` hard-codes `"Platform fee (10%) — charged only when your campaign goes live."` → derive the text from `brandFeeBps` so Pro can show 5%. `PlatformFeeService.java:94` also builds a "Platform fee (" label and must match.
3. Build the Pro 5% brand fee (a fee rate that depends on plan) and snapshot the rate per deal. Make one number identical across the pricing page, the Meera quote, the wallet debit and the invoice PDF GST line.
4. Get a CA ruling on whether commission is GST-inclusive or plus-GST before the invoice PDF or the "keep 90%" copy changes.

**B. Payments safety. Required before any pack is sold.**
5. A reconciliation job for pack orders. Wallet top-ups already have one: `WalletTopUpReconciliationJob` runs hourly (commit dead7b4) and credits paid orders through `WalletTopUpService.confirmCredited`. Credit packs are a separate product and need the same job wired for PACK_PURCHASE before any pack is sold, because a pack that is paid for but never credited becomes a chargeback. (Corrected 2026-09-19: an earlier draft said the webhook had no fallback.)

**C. Credits platform. Must ship in ONE release together with the smaller allowances.**
6. Brand credits (`AICreditService`, which today sets `DEFAULT_MONTHLY_ALLOTMENT = 100`, Pro 400 and a +50 funded bonus that stacks) → 50 one-time, +50 per FUNDED capped at 2 a month, Pro 200, with grandfathering (Free 60 days, Pro 90 days) and in-app notice.
7. Brand pack catalogue and checkout (+ GST, invoice prefix `credits:`, ledger reason PACK_PURCHASE, a separate product from `WalletTopUp`). Also the Pro +20% bonus, the first-pack +50% and the burn order.
8. Creator credit ledger `creator_ai_credits` (planned in the Phase B plan, not built). It needs a monthly bucket, a paid/earned bucket, the signup grant, the pack catalogue (GST-inclusive) and earned credits keyed so they can't be granted twice.
9. The AI cost cap: $1.25 for free credits and a cap derived from credits for paid balances, so a paid pack is never throttled.
10. Validity: 12 months from the last purchase or spend, with 30-day and 7-day reminders. The finance side reports outstanding credits monthly and books unused credits.
11. Credit surcharges enforced on the server: voice +2 first (`MeeraController` `/voice/speak` charges nothing today), then brief reads. The 1-credit message already exists. Also the creator connect gate (5 onboarding messages, then the 40/mo and +30 only after Meta connect) and a per-workspace rate limit on `GET /creators/external/lookup` (none today). All of this shown in the UI, plus the single-balance display, the nudges, the warm-checkout offers and the usage email.
12. A copy gate that blocks "escrow", any licence or regulator claim, and claims about unbuilt tools on all new pricing screens.

**D. Later lines**
13. The brand outreach pitch action, before the "pitch 3 credits" brand line is shown.
14. The creator PR-finder (search and pitch executors) and server-side first-contact attribution, then the Rs199 plan: waitlist until then, then billing, then the 5% cut (only if attribution ships).
15. Manager seat: 60 days after launch, only if there is demand.
16. Auto-top-up by e-mandate: after a legal and compliance review.

---

## 6. Open questions for Swapnil

1. **Validity:** keep the majority's 12-month rolling rule (from the last purchase or spend, with reminders), or take Tejas's never-expire? Never-expire is also what the Phase B plan recommended (D2). If you choose never-expire, finance books unused credits on an estimated basis (CA to confirm).
2. **Commission GST:** inclusive ("keep 90%") or plus-GST ("keep 88.2%")? This is a CA ruling, and it moves about Rs1,525 per Rs1L.
3. **Rs199 plan pool:** 60 credits (the chair's decision, which passes your own "dearer than the Rs249 pack" test) or 100 (Tejas, and your original number)?
4. **Brand entry pack:** Rs499/100 (majority) or Tejas's lower Rs299/40?
5. **Minimum fee:** confirm there is no Rs299 minimum per funded campaign (Rohan wants one).
6. **Free creator AI cap:** confirm $1.25 (up from $0.75).
7. **Manager seat pack bonus:** +15% (chair's decision) versus +20% (Rohan) or +25% (Tejas).
8. **GST on prepaid credits:** is it due when the pack is sold (the advance-receipt rule)? CA to confirm before pack invoices are built.
9. **The 1.3x deal-value target at the 90-day review:** accept it as the success bar for moving commission from 15% to 10%?
