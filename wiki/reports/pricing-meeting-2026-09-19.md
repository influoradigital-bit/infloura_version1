# Influora Pricing Strategy Meeting: Minutes

**Date:** 2026-09-19
**Chair:** neutral chair (not a participant; wrote these minutes only)
**Format:** Round 1 independent analyses, then Round 2 cross-challenges, then this summary

| Participant | Role | Model |
|---|---|---|
| Swapnil | CEO | opus |
| Tejas | CMO | sonnet |
| Rohan | CFO | fable |

**Marking conventions:** "ESTIMATE" means the participant's own modelled figure. No measured GMV, campaign count, Pro subscriber count or creator AI usage data existed at the time of the meeting. Competitor figures for Influish are self-reported and unverified unless stated otherwise.

**Hard constraints (apply to every option):** user copy must never contain the word for a third-party holding account, and must never claim an RBI licence or say that a licensed payment aggregator holds funds. Deals already in progress may carry 15% in their contracts.

---

## 0. Baseline and proposal on the table

**Current pricing (verified from code, 2026-09-19)**
- Creator commission: 15%, deducted at payout release (`platform_fee_config.default_fee_bps=1500`, a single global value that admins can edit).
- Brand platform fee: charged once at go-live on the committed budget, with no refund if the brand under-spends. Free plan 10%, Pro plan 7%. Under CTO ruling P-3 the pricing page shows no fee percentage.
- Razorpay charges are absorbed by the platform.
- Pro plan: Rs 4,999/month, 400 Meera credits, 5 seats. Free plan: 100 credits (150 after the first funded campaign), 1 seat. Brands cannot buy top-up packs; a brand at 0 credits waits until the 1st of the month.
- AI cost: about Rs 0.56 per credit.
- Creator AI: free, no tools yet, $0.75/month cost cap per creator. Creator packs exist only in the spec.
- Worked example, Rs 1,00,000 budget on the Free plan: brand pays Rs 1,10,000, creator receives Rs 85,000, platform keeps Rs 25,000 (about 23%).

**Proposal from the prior discussion**
1. Creator commission 15% to 10%, and 0% when the creator brings the brand to Influora.
2. Brand fee: Free stays at 10%, Pro goes from 7% to 5%.
3. Publish the brand fee percentage (reverses P-3).
4. Pro price stays at Rs 4,999/month.
5. Add brand AI credit top-up packs.
6. Creator AI: about 40 free credits/month. Sell packs at Rs 149/249/649. Drop the Rs 899-1,499/month plan idea. Optional creator plan at about Rs 499-999 per year.

---

## 1. Round 1: independent analyses

### 1A. Swapnil (CEO, opus)

**Assessment.** He supports the direction but not its priorities.
- A 10% commission still loses the headline comparison against Influish's "0%".
- The bigger churn driver is the brand fee: it is charged on committed budget and never refunded.
- Pro at 5% and publishing the fee are both right.
- He would ship the proposal with three changes: charge the fee on money actually released, make the creator 0% time-boxed, and defer creator packs and plans until creator tools exist.
- "Price should stop being a reason to leave; we win on payment protection, Meera and attribution."

**Supporting arguments**
- The current take of about 23% is agency-level (15-25%) for a self-serve product. The proposal brings it to about 18%, or about Rs 17.8k net of Razorpay (ESTIMATE).
- Pro at 5% breaks even against Free at Rs 1L/month of spend, down from about Rs 1.67L today. That fits D2C budgets of Rs 50k-3L.
- A published fee is a trust wedge, because Influish does not disclose how it charges brands. Collabstr also publishes its fees.
- A 0% commission on creator-brought brands costs little, because the brand fee is still earned. It turns creators into a sales channel.
- Brand top-ups are high-margin and fix a dead end for brands at 0 credits.
- Dropping the monthly creator plan is right, because Influish sells a plan at Rs 499 per year.

**Risks**
- Take falls 20% on Free and 32% on Pro. Spend must grow about 25% (Free) or about 47% (Pro) to break even.
- 10% still loses the headline comparison.
- A non-refundable fee on committed budget becomes more visible once published, and is a likely source of complaints and chargebacks.
- In-flight deals at 15% could feel penalised.
- The referral 0% can be gamed.
- A small team is shipping several money features at once while live-payment defects remain open (no webhook reconciliation fallback; affiliate commission never credited).
- Copy compliance.

**Impact**
| | |
|---|---|
| Brands | Free: same price, plus transparency and (in his version) a fee only on money released. Pro: saves Rs 2,000 per Rs 1L. Top-ups end the wait for the monthly reset. |
| Creators | Rs 85k to Rs 90k per Rs 1L (+5.9%). 0% on brands they bring. Free AI at about Rs 22/creator/month (ESTIMATE). |
| Revenue | Per Rs 1L: Free Rs 25k to Rs 20k; Pro Rs 22k to Rs 15k. Needs at least 1.3x spend growth within 12 months. |

**Alternatives he considered:** (A) the proposal as written; (B) his preferred option, a launch-window 0% plus a fair fee base; (C) brand pays all (creator 0%, brand 15%/10%), to be A/B tested only; (D) subscription-led (creator 5%, Pro at 3%, a Scale plan at Rs 14,999 with 0% brand fee).

**Key assumptions:** real spend is small today, so the rate cut is a growth decision; Influish's traction numbers are unverified; brands choose on total cost plus trust; Rs 0.56/credit holds; Razorpay costs about 2%; lowering the rate on in-flight deals is contract-safe; the team can ship about 2 money features per quarter.

**Round 1 numbers**
- Creator: 10%. 0% on each creator's first deal until 31-Mar-2027. 0% for 12 months on creator-referred brands. In-flight deals released at 10%.
- Brand: Free 10%, Pro 5%. Fee settled on released amounts, with a pro-rata refund of the unspent share. Fee published.
- Pro: Rs 4,999, plus an annual plan at Rs 49,990.
- Brand top-ups: Rs 499 = 250 credits, Rs 1,499 = 1,000 credits.
- Creator AI: 40 free credits/month. No packs or plan until creator tools exist.

### 1B. Tejas (CMO, sonnet)

**Assessment.** The proposal improves the messaging but does not win the headline fight: 10% still loses to "0%". The referral 0% is the smartest part, because it is a distribution mechanic. Publishing the fee turns Influish's undisclosed brand model into our differentiator. Two things undercut the story: creator AI packs look like double-dipping, and the Rs 499-999/year plan sits right on top of Influish's Rs 499/year plan.

**Supporting arguments**
- The referral 0% turns creators into unpaid sales reps.
- "We tell you the number, they won't."
- Killing the monthly creator plan removes a reputational liability.
- 10% still beats global comparables (Collabstr 15% creator fee, Fiverr 20%).
- Top-ups remove a brand churn point.

**Risks**
- The headline comparison is lost on every comparison page.
- A published fee without a "what it funds" line invites sticker shock.
- Creator packs on top of commission create a "hidden toll" narrative.
- The annual plan copies Influish's price point.
- Referral attribution must be defensible.
- More copy means more surface for compliance mistakes.

**Impact**
| | |
|---|---|
| Brands | A trust signal in the middle of the funnel, weaker at the top. Top-ups remove an objection. |
| Creators | Sentiment improves if the 10% rate and the referral 0% lead the message. There is a risk if packs are framed as monetising creators. |
| Revenue | Take falls from about 23% to about 18% (deferred to Rohan). The upside only exists if the cut is publicised. |

**Alternatives he considered:** fee-free first brand campaign; volume-tiered brand fee; 0% on a creator's first N referred deals; "commission funds your AI" framing instead of selling packs.

**Key assumptions:** Influish's 0% dominates creator comparisons for 6-12 months; buyers read headline numbers, not fine print; referral attribution can be enforced (flagged as a product dependency); the P-3 reversal needs sign-off from Swapnil and Priya; packs framed as optional do not break "pay only if you earn".

**Round 1 numbers**
- Creator: 10%, with the referral 0% as the headline ("Bring a brand, keep 100%").
- Brand: **Free 8%**, Pro 5%, published, each with a "where it goes" line.
- Pro: Rs 4,999.
- Brand top-ups: yes.
- Creator AI: 40 free credits/month plus packs at Rs 149/249/649. No annual plan.

### 1C. Rohan (CFO, fable)

**Assessment.** The proposal is not unsafe, but it is unfunded.
- At constant volume, contribution per Free deal falls about 23% (Rs 18,390 to Rs 14,150 per Rs 1L).
- Portfolio contribution falls about 34% (Rs 9.55L to Rs 6.33L/month at an assumed Rs 50L GMV; ESTIMATE).
- GMV must rise about 55-60% to make up the difference, and there is no elasticity evidence either way.
- He will not sign off on an uncapped 0% for creator-brought brands.
- The brand-side changes are affordable.

**Supporting arguments**
- 10%/5% sits at the low end of the self-serve norm and well under agency fees.
- Pro at 5% pays for itself: the net subscription (Rs 4,236) covers the 2-point cut up to about Rs 2.1L/month of spend per brand.
- Moving take toward the brand fee improves cash timing.
- AI cost is not the binding constraint: Pro's 400 credits cost about Rs 259 (6% of the net subscription), and creator packs have 65-74% margin.
- Top-ups close a revenue leak. His Round 1 ladder was 50/Rs 999, 150/Rs 2,499, 400/Rs 5,999.
- The Rs 899-1,499/month creator plan cannot sell against Influish's Rs 499/year.

**Risks**
- A 0% brought deal on Pro contributes only Rs 1,559 (1.5%) per Rs 1L, and one dispute makes it negative.
- Referral gaming: if 50% of deals become "brought", contribution falls about 47% (ESTIMATE).
- Price elasticity is unproven.
- `default_fee_bps` is a global setting. Changing it would silently reprice every open deal.
- The code contradicts itself:
  - `BrandPlatformFeeService` hardcodes "Platform fee (10%)" for Pro.
  - `PLATFORM_FEE_PERCENT` defaults to 15 and Meera's `request_payment` quotes it.
  - `CommissionInvoice` prints fee + 18% GST while the wallet debits the fee only.
- Free creator AI costs about Rs 1.1-1.3L/month at 5,000 active creators (ESTIMATE). The $0.75 cap is too low: Typical usage is at 88.5% of it and Heavy at 253%.
- A Rs 499/year plan nets about Rs 35/month against about Rs 56/month of cost for a Typical creator.
- Below about Rs 5k, a 5% Pro fee does not cover the gateway cost.
- GST treatment of the creator commission has not been ruled on.

**Impact**
| | |
|---|---|
| Brands | Free: cash out unchanged. Pro: saves Rs 2,000 per Rs 1L, and break-even against Free drops to about Rs 1L/month. Trust risk if the invoice, Meera and the pricing page show three different numbers. |
| Creators | Standard deal: Rs 85k to Rs 90k. Brought deal: Rs 1L. Creator AI is free for about 90% of creators (Light 26 credits < 40). |
| Revenue | At Rs 50L GMV: contribution Rs 9.55L to Rs 6.33L (-34%). Break-even GMV rises from about Rs 5.6L to about Rs 8.8L/month (ESTIMATE). |

**Alternatives he considered:**
- An earned 10% tier (12% default, 10% after Rs 2L of payouts; brought deals at 5% capped).
- Shifting take to the brand side (Free 12%, Pro 8%).
- 5% only on annual Pro (monthly stays at 7%).
- Per-deal fee floors (Rs 299 per campaign, Rs 99 per payout).
- A metered creator AI tier with no annual plan.

**Key assumptions:**
- Razorpay costs 2.36% of the full funded amount.
- Both the fee and the commission are GST-inclusive (18/118 backed out).
- AI costs Rs 0.648/credit for brands and Rs 0.559 for creators, which depends on a cache hit rate of at least 70%.
- Portfolio model: Rs 50L GMV, 70/30 Free/Pro, 20% brought, 15 Pro subscriptions.
- Fixed burn about Rs 1L/month; overhead about Rs 200/deal.
- Creator usage personas are modelled, not observed.

**Round 1 numbers**
- Creator: 12%, dropping to 10% after Rs 2L of payouts. Brought deals at 5% for 12 months or the first Rs 5L.
- Brand: Free 10%, Pro 5%, GST-inclusive. Rs 299 minimum fee.
- Pro: Rs 4,999, plus an annual plan at Rs 49,990.
- Brand top-ups: 50/Rs 999, 150/Rs 2,499, 400/Rs 5,999.
- Creator AI: 40 free credits/month, voice at 2 credits, cap raised to $2, packs at Rs 149/249/649, no annual plan.
- Publishing only after the code inconsistencies are fixed.
- Contribution on a Rs 1L Free deal: Rs 15,848 (14.4%).

---

## 2. Round 2: challenges and responses

### 2A. Swapnil (CEO)

**Challenges he raised**
| Target | Their claim | His challenge |
|---|---|---|
| Tejas | Free fee 10% to 8% | Collabstr's own benchmark is 10%. Free is the largest segment, so this is the most expensive line (about Rs 70k/month at Rs 50L GMV; ESTIMATE). The concession brands actually feel is the fee on unspent budget, not the rate. |
| Tejas | Ship creator packs now, kill the annual plan | Inconsistent: the packs carry the same "double-dipping" risk. Creators should pay nothing but commission until creator tools exist. |
| Rohan | 12% default with an earned 10% tier | Every input is an ESTIMATE. At Rs 10L of real spend, 2 points is about Rs 17k/month. A three-clause message loses to "0%", and a tier adds a state machine on the payout path. |
| Rohan | Brought 0% on Pro is loss-making | Rs 1,559 is a positive contribution, and it comes with a free brand acquisition (typical CAC Rs 5-20k; ESTIMATE). Agrees the gaming risk is real. |
| Rohan | Upfront, non-refundable fee helps cash timing | Trust damage outweighs float. Keep upfront collection, and return the unspent share as wallet credit. |

**Strongest counterargument to him (self-stated):** his plan stacks four revenue cuts, and before traction almost every deal is a creator's first deal. It also adds a refund build while he himself warns about fragile payment code.

**His response:** accepted mostly. He caps the launch offer at the first Rs 50,000 earned (maximum Rs 5,000 per creator), adopts the Rs 299 floor so that no deal loses money, and splits the build into three phases.

**What he changed his mind on**
1. Top-up prices: moves from Rs 2/credit to Rohan's Round 1 ladder, priced at or above the Pro rate.
2. Publishing waits until the code is consistent.
3. Fee bps are recorded per deal; 15% deals move to 10% only by a written override.
4. First-deal 0% is capped at Rs 50k.
5. Referral 0% gets Rohan's limits.
6. Rs 299 minimum fee.
7. Fees are published as GST-inclusive.

**Unchanged:** creator 10%, Free 10%, no creator packs or plans until creator tools ship.

**Final numbers**
- Creator: 10%, GST-inclusive, recorded per deal at creation.
  - Launch offer: 0% on a creator's first Rs 50,000 earned, until 31-Mar-2027.
  - Referral: 0% for 12 months, capped at the first Rs 5L of spend with that brand. The brand must be new to the platform and must sign up first through the creator's link.
  - In-flight deals: 15% to 10% by a per-deal written override.
- Brand: Free 10%, Pro 5%, GST-inclusive, published with a worked example after the code fixes. Rs 299 minimum fee. Unspent fee share returned as wallet credit at campaign close (Phase 2, after a GST credit-note ruling).
- Pro: Rs 4,999, or Rs 49,990 per year.
- Brand top-ups: 50/Rs 999, 150/Rs 2,499, 400/Rs 5,999.
- Creator AI: 40 free credits/month + 30 at signup, voice at 2 credits, cap $2. No creator packs or plans until creator tools ship; packs come first when they do.
- Build phases: Phase 1 rates, snapshots and publishing; Phase 2 (60 days) wallet credit and top-ups; Phase 3 referral 0%.
- 90-day review on completed spend, Pro conversion, share of referred deals, and unspent budget.

### 2B. Tejas (CMO)

**Challenges he raised**
| Target | Their claim | His challenge |
|---|---|---|
| Swapnil | Two 0% mechanics (first deal plus 12-month referral) | Fails the simplicity test. "Which 0% applies to me?" becomes a support and consumer-protection risk. The first-deal 0% brings in no acquisition. Pick the referral offer only. |
| Swapnil | Ship the under-spend refund in the same wave | Too many builds plus a comms push at once. Ship it as a fast-follow with its own announcement. |
| Rohan | Earned tier (12% dropping to 10%) | It cannot be marketed: it reads as 12% on day one. It belongs in retention email, not acquisition copy. |
| Rohan | Shift take to brands (Free 12%) | Publishing a fee that just went up, under a "transparency" message, reads as "they only told us because it got worse". Rejects it regardless of margin. |

**Strongest counterargument to him (self-stated):** Rohan's contribution math. Tejas proposed Free at 8% for how the page reads, without costing it.

**His response:** accepted. Disclosure is itself the concession to brands.

**What he changed his mind on:** drops Free 8% (Free stays at 10%). Wants a single referral trigger. Now wants both the monthly and the annual creator plans killed.

**Final numbers**
- Creator: 10%. 0% on the ONE first deal with a brand the creator personally referred ("Bring a brand, keep the first payout 100%"). No 12-month window, no first-deal-ever offer. No creator monthly or annual plan.
- Creator AI: 40 free credits/month, and **sell the Rs 149/249/649 packs as-is**.
- Brand: Free 10%, Pro 5%, published, each with a "what it funds" line. Published after the invoice and config fixes, which he treats as a same-sprint fix rather than an indefinite hold.
- Pro: Rs 4,999.
- Brand top-ups: yes; pricing deferred to Rohan.
- Under-spend refund: fast-follow, not at launch.

### 2C. Rohan (CFO)

**Challenges he raised**
| Target | Their claim | His challenge |
|---|---|---|
| Swapnil | Brought 0% "costs almost nothing" | On Pro it leaves Rs 1,559 (or about Rs 1,937 on Swapnil's Razorpay figure). Uncapped, it becomes the default bucket. Needs a cap per brand pair. |
| Swapnil | Refund unspent fee in the same wave | Razorpay does not return MDR, so the gateway fee is paid twice. It needs credit notes against invoices that are already defective, and it adds a refund leg on an unreconciled inbound leg. Use wallet credit, after reconciliation exists. |
| Swapnil | Top-ups at Rs 2/credit | That is 1/6 of Pro's implied Rs 12.50/credit and removes the reason to upgrade. It is a pricing-architecture problem, not a margin problem. |
| Swapnil | "Cheap today, so the rate cut is about growth" | True for the rate. False for uncapped clauses: prices are sticky on the way down, and open-ended 0% cannot be narrowed later without backlash. |
| Tejas | Free 8% | Shrinks the Free-Pro gap to 3 points, so Pro break-even goes back to Rs 1.67L. Costs about Rs 58k/month (ESTIMATE). Brands benchmark against agencies, not against 8% vs 10%. |
| Tejas | "Commission funds AI" framing | Rules out charging for creator AI forever, although packs are the only high-margin creator line. Commission comes from a few cash earners while free AI is used by everyone. |
| Tejas | The creator headline is what we must win | Influish reviews describe mostly barter deals, and 0% of barter is Rs 0. Cash-earning creators care about payout reliability. A fee-free first brand campaign would cost about Rs 2L/month (ESTIMATE). |

**Strongest counterargument to him (self-stated):** he is protecting an invented Rs 50L GMV with five mechanics while the webhook is still unreconciled.

**His response:** conceded on the default rate. Kept the caps, because the size of a 0% bucket is set by the attribution rule, not by volume.

**What he changed his mind on**
1. Creator 12%/10% tier becomes 10% flat.
2. Brought deals go from 5% to 0%, capped.
3. Accepts a first-payout 0% offer, capped at Rs 25k.
4. Top-ups drop from Rs 15-20/credit to Rs 6-10/credit.
5. In-flight deals released at 10%, which requires per-deal snapshots.
6. Creator packs move to the next quarter, after metering.
7. Under-spend handled as wallet credit after reconciliation.

**Dropped:** the Rs 99 per-payout floor. **Kept:** the Rs 299 minimum fee, Pro annual, and the preconditions for publishing.

**Final numbers**
- Creator: 10%, GST-inclusive, bps snapshotted per deal. In-flight deals released at 10%.
  - Launch offer: 0% on the first Rs 25,000 of a creator's released payouts, until 31-Mar-2027.
  - Referral: 0% for 12 months or the first Rs 3L of GMV per brand pair, whichever comes first. Attribution requires the brand's first-ever signup through the creator's link, with no prior account under the same email domain or GSTIN.
- Brand: Free 10%, Pro 5%, GST-inclusive. Rs 299 minimum fee. Unspent share returned as wallet credit after webhook reconciliation.
- Publish only after four fixes: plan-aware `BrandPlatformFeeService` copy, `PLATFORM_FEE_PERCENT=15` removed, the `CommissionInvoice` GST line fixed, and a ruling on creator-commission GST.
- Pro: Rs 4,999, or Rs 49,990 per year at 5%.
- Brand top-ups: **100/Rs 999, 300/Rs 2,499, 1,000/Rs 5,999**, never expire.
- Creator AI: Phase 1 now: 40 free credits + 30 at signup, voice at 2 credits, cap $2. Phase 2 next quarter: packs at Rs 149/249/649. No monthly or annual plan.
- Revenue consequence (ESTIMATE):
  - Contribution about Rs 6.0L vs Rs 9.55L today (-37%).
  - Break-even GMV about Rs 9L/month.
  - Revenue-neutral only at +55-60% GMV.
- 90-day triggers:
  - GMV at least 1.3x the baseline.
  - 0% buckets above 25% of GMV raises an alarm.
  - Attribution disputes.
  - Creator usage against the cap.
  - If GMV misses, the response is volume work, not another price cut.

---

## 3. Points all three agree on (final positions)

1. Creator base commission goes from **15% to 10%**.
2. Brand fee on the **Free plan stays at 10%** (Tejas dropped 8% in Round 2).
3. **Pro fee goes from 7% to 5%**, and Pro stays at **Rs 4,999/month**.
4. **Publish the brand fee** (reverses P-3), but **only after** the fee copy, the `PLATFORM_FEE_PERCENT=15` setting and the invoice PDF GST line all agree.
5. **Some 0% for creator-referred brands** is worth doing as an acquisition mechanic. The scope is disputed (see Section 4).
6. **Add brand AI top-up packs.** The price ladder is disputed.
7. **No monthly creator plan** (Rs 899-1,499) and **no annual creator plan** (Rs 499-999) for now.
8. Creator AI stays **free at about 40 credits/month**.
9. Under-spend fairness is a real problem, and fixing it **does not ship in the launch wave**. It is sequenced later.
10. The pricing change is a growth bet that must be judged at a **90-day review** against real completed spend.
11. Copy constraints: no third-party-holding-account wording and no licence claims.

## 4. Points of disagreement

| Issue | Swapnil | Tejas | Rohan |
|---|---|---|---|
| Referral 0% scope | 12 months, cap Rs 5L per brand, new brand via creator link | **One first deal only** with the referred brand | 12 months or Rs 3L per pair, whichever comes first; plus email-domain/GSTIN check |
| Launch "first earnings" 0% offer | Yes: first **Rs 50,000** earned, to 31-Mar-2027 | **No**: two 0% mechanics confuse; this one brings no acquisition | Yes: first **Rs 25,000** of released payouts, to 31-Mar-2027 |
| Creator AI packs (Rs 149/249/649) | Not until creator AI tools ship | **Ship now** | Next quarter, after metering |
| Brand top-up ladder | 50/Rs 999, 150/Rs 2,499, 400/Rs 5,999 (Rs 15-20/credit; Rohan's Round 1 ladder) | Defers to Rohan | 100/Rs 999, 300/Rs 2,499, 1,000/Rs 5,999 (Rs 6-10/credit) |
| Gate for the unspent-fee return | Phase 2 (about 60 days), after a GST credit-note ruling; wallet credit | Fast-follow with its own announcement; mechanism not specified | Wallet credit, only after webhook reconciliation exists |
| Rs 299 minimum fee per campaign | Yes | Not addressed | Yes |
| Pro annual Rs 49,990 | Yes | Not addressed | Yes |
| Creator voice metering + $2 cap | Yes | Not addressed | Yes (ships first, as a cost control) |
| In-flight 15% deals | 10% by a written per-deal override | Not addressed | Release at 10%, requires per-deal bps snapshot |
| Weight of the creator headline number | Must not lose it; answered by the capped launch offer | The headline matters most; answered by the single referral hook | Matters less; Influish deals are mostly barter, so reliability matters more |

Note on top-ups: in Round 2 Swapnil adopted the ladder that Rohan himself then abandoned in favour of cheaper packs. The two proposals now sit on opposite sides of each other's Round 1 positions.

## 5. Important assumptions

- **No real baseline exists.** GMV, campaign count, Pro count, Free/Pro mix and creator AI usage are all modelled (Rohan's portfolio: Rs 50L/month GMV, 70/30 Free/Pro, 20% brought, 15 Pro subscriptions).
- **Elasticity:** the plan breaks even only if completed GMV rises about 55-60% (Rohan). Swapnil set a minimum of 1.3x for the 90-day review.
- **Razorpay cost:** 2% (Swapnil) or 2.36% of the funded amount including GST (Rohan). Not yet confirmed against settlement reports.
- **GST:** the brand fee and Pro plan are GST-inclusive (ruled 0917). **Creator commission GST treatment is not ruled.** It changes creator net pay by about 1.5-2.7 points.
- **AI cost:** Rs 0.56-0.648/credit. This depends on a cache hit rate of at least 70% and Haiku extraction; losing either raises cost by 40% up to 3x.
- **Competitor:** Influish's 0% pricing is verified on its page. Its traction (1.6M creators, 80K businesses) and its brand-side monetisation are not.
- **Contracts:** lowering the rate on in-flight deals is contract-safe (a legal assumption, not verified).
- **Ledger:** fee bps can be snapshotted per deal. Today `default_fee_bps` is global, so this is unverified.
- **Team capacity:** about 2 money features per quarter (Swapnil; ESTIMATE).

## 6. Pricing scenarios compared: Rs 1,00,000 budget on the Free plan

"Gross take" = brand fee + creator commission. "Contribution" is after GST back-out, Razorpay and about Rs 200 of overhead, using Rohan's cost basis (ESTIMATE). A dash means the participant did not compute it.

| Scenario | Brand fee (Free/Pro) | Creator commission | Brand pays | Creator gets (standard deal) | Platform gross take | Contribution |
|---|---|---|---|---|---|---|
| **Current** | 10% / 7% | 15% | Rs 1,10,000 | Rs 85,000 | Rs 25,000 (22.7%) | ~Rs 18,390 (16.7%) |
| **Proposal as tabled** | 10% / 5% | 10%; 0% on brought brand (uncapped) | Rs 1,10,000 | Rs 90,000 | Rs 20,000 (18.2%); brought: Rs 10,000 | ~Rs 14,150 (12.9%); brought: ~Rs 5,679 |
| Rohan Round 1 | 10% / 5% | 12% (drops to 10% after Rs 2L); brought 5% | Rs 1,10,000 | Rs 88,000 | Rs 22,000 (20.0%) | ~Rs 15,848 (14.4%) |
| Tejas Round 1 | **8%** / 5% | 10% | Rs 1,08,000 | Rs 90,000 | Rs 18,000 (16.7%) | ~Rs 12,506 (11.6%) |
| **Swapnil final** | 10% / 5%, Rs 299 floor, unspent returned as credit (Phase 2) | 10%; 0% on first Rs 50k earned; referral 0% for 12 months up to Rs 5L | Rs 1,10,000 (less any unspent-fee credit) | Rs 90,000 (up to Rs 95,000 inside the launch offer; Rs 1,00,000 referred) | Rs 20,000; up to Rs 5,000 less inside the launch offer; Rs 10,000 referred | ~Rs 14.2k standard; ~Rs 5.7k referred (his figures) |
| **Tejas final** | 10% / 5% | 10%; 0% on the single first referred deal only | Rs 1,10,000 | Rs 90,000 (Rs 1,00,000 on that one deal) | Rs 20,000; Rs 10,000 on the one referred deal | ~Rs 14,150 standard (not computed by Tejas) |
| **Rohan final** | 10% / 5%, Rs 299 floor, unspent returned as credit (after reconciliation) | 10%; 0% on first Rs 25k of payouts; referral 0% for 12 months or Rs 3L | Rs 1,10,000 (less any unspent-fee credit) | Rs 90,000 (up to Rs 92,500 inside the launch offer; Rs 1,00,000 referred) | Rs 20,000; up to Rs 2,500 less inside the launch offer; Rs 10,000 referred | ~Rs 14,150 (12.9%) |

Other structures raised but not adopted by anyone as a final position:
- Brand pays all: creator 0%, brand 15%/10% (Swapnil, A/B test only).
- Subscription-led with a Scale plan at Rs 14,999 and 0% brand fee (Swapnil).
- Fee-free first brand campaign (Tejas; Rohan costed it at about Rs 2L/month).
- Volume-tiered brand fee (Tejas).
- Shift take to brands, Free 12% / Pro 8% (Rohan; Tejas rejected it).
- 5% only on annual Pro (Rohan).

**Portfolio view (Rohan, ESTIMATE, Rs 50L GMV):**
- Contribution: Rs 9.55L/month today, Rs 6.33L under the proposal, about Rs 6.0L under his final plan (-37%).
- Break-even GMV: about Rs 5.6L today, about Rs 9L under his final plan.

## 7. Business risks

1. **Revenue falls before volume arrives.** Contribution drops about 34-37% at constant GMV. There is no elasticity evidence.
2. **0% buckets become the default.** Referral and launch offers without strict attribution and caps absorb GMV. Rohan's alarm threshold is 25% of GMV.
3. **Published number vs what the brand actually sees.** Pricing page, Meera quote (15%), `BrandPlatformFeeService` ("10%" on Pro) and the invoice PDF (fee + 18%) disagree. This risks chargebacks and GST audits.
4. **Global fee config.** Changing `default_fee_bps` silently reprices every open deal, and can conflict with contracts signed at 15%.
5. **Complaints about a non-refundable fee on committed budget** once the fee is visible.
6. **Payment-path fragility.** The webhook has no reconciliation fallback, a Rs 1,000 credit is still stranded, and affiliate commission has never been credited. Refund or credit legs added on top multiply the failure modes.
7. **Creator AI cost.** It grows with no matching revenue (about Rs 1.1-1.3L/month at 5,000 active creators; ESTIMATE). The current $0.75 cap is already exceeded by Typical and Heavy usage.
8. **Headline loss vs Influish's "0%"** persists under every final plan.
9. **Copy compliance.** More public pricing copy means more chances of prohibited wording or licence implications.
10. **Too many builds at once.** A small team shipping several money features in parallel.

## 8. Questions still needing validation

1. What is the real completed GMV, campaign count, Free/Pro mix and Pro subscriber count today? That is the day-0 baseline for the 1.3x test.
2. What does Razorpay actually cost as a share of funded amount (UPI vs cards), from settlement reports?
3. Is the creator commission GST-inclusive or charged on top? This needs a CA ruling.
4. Can fee bps be snapshotted per deal, and do in-flight contracts permit release at 10%? This needs a legal check.
5. How much committed budget goes unspent at campaign close? Swapnil guesses 5-15%.
6. How do referrals get attributed: which rule survives disputes (link + new email domain + new GSTIN)?
7. How do real creators use Meera: Light/Typical/Heavy mix, voice share, cache hit rate?
8. Which creator headline converts best: "keep 90%", "first Rs 25k/50k free", or "bring a brand, keep 100%"?
9. What drives brand choice: rate vs fee on released money vs published transparency?
10. Influish: what are its real traction figures and brand-side monetisation, and what share of its deals are cash vs barter?
11. Which top-up ladder sells without cannibalising Pro?
12. Who formally lifts P-3? Swapnil and Priya (Tejas's assumption).

## 9. Next steps: testing the pricing in the real market

Sample sizes are ESTIMATES. As a reference point, detecting a 5% to 8% conversion change at 80% power and 5% significance needs about 1,050 visitors per arm. Where traffic is lower, qualitative tests are used instead.

| # | Step | Method | Sample / duration | Success metric | Owner |
|---|---|---|---|---|---|
| 0 | **Preconditions (blocking)** | Fix `BrandPlatformFeeService` copy, remove `PLATFORM_FEE_PERCENT=15` from config and Meera, fix the `CommissionInvoice` GST line, add per-deal bps snapshot; get CA ruling on commission GST and legal check on in-flight deals | 1 sprint | One fee number is identical on the pricing page, Meera, the wallet debit and the PDF | Priya (eng), Rohan (GST/legal) |
| 1 | **Baseline** | Pull completed GMV, campaigns, Free/Pro split, unspent budget share, Razorpay settlement cost | Before any change | Day-0 figures recorded in `finance/` | Rohan |
| 2 | **Creator headline test** | Landing-page A/B/C: "Keep 90%" vs "First Rs 25k at 0%" vs "Bring a brand, keep 100%" | About 1,050 visitors per arm, or 4 weeks if traffic is lower | Creator signup rate and signup-to-first-deal rate | Tejas |
| 3 | **Brand fee interviews + pricing-page test** | 15-20 D2C brand interviews (Rs 50k-3L budgets) comparing: published 10%/5%, fee on released money, current terms. Then a live published page | 4 weeks | Stated preference; demo-to-funded-campaign rate; complaints about the fee | Tejas + Swapnil |
| 4 | **Pro conversion at 5%** | Launch Pro 5% (and the annual plan if approved) to all brands | 90 days | Free-to-Pro conversion; share of Pro brands spending at least Rs 1L/month | Rohan |
| 5 | **Referral pilot** | Invite-only pilot with 20-30 creators, using the attribution rule chosen by Swapnil | 90 days | Referred brands that fund a campaign; attribution disputes (target 0); share of GMV in 0% buckets (alarm above 25%) | Swapnil |
| 6 | **Brand top-up ladder test** | Fake-door, then live, packs shown to brands at 0 credits. Test Swapnil's vs Rohan's ladder | All brands that hit 0 credits over 60 days | Pack purchase rate; Free-to-Pro conversion does not fall | Rohan |
| 7 | **Creator AI metering** | Ship 40 free credits, voice at 2 credits, $2 cap; collect usage telemetry | 30-60 days | Light/Typical/Heavy distribution; cost per active creator within budget; cache hit rate at least 70% | Rohan + Priya |
| 8 | **Competitor check** | Verify Influish claims (app-store reviews, brand calls, a creator posing as a user) | 2 weeks | Cash vs barter share; how brands are charged | Tejas |
| 9 | **90-day review** | Compare with day-0 | Day 90 | Completed GMV at least 1.3x baseline. If missed: volume work, not further price cuts | Swapnil (chair), Rohan |

**Decision required from Swapnil before step 2:**
- Choose the 0% structure: a single referral trigger (Tejas) or a capped launch offer plus capped referral (Swapnil/Rohan), and set the caps (Rs 50k vs Rs 25k; Rs 5L vs Rs 3L; 12 months vs one deal).
- Settle the timing of creator packs.
- Settle the top-up ladder.
- Confirm with Priya the P-3 reversal once step 0 is done.

---

## Round 3 - CEO counter-proposal (Swapnil, 2026-09-19)

The real CEO (Swapnil, the human owner) rejected all three final positions above and tabled his own counter. Tejas (CMO) and Rohan (CFO) each responded point by point, then cross-checked each other. Sections 0-9 above are unchanged; this section is appended.

### R3.1 The counter-proposal

**Verbatim (lightly cleaned):**
> "10% brand free, 5% pro. Top up after 50 credit expire - if they create campaign they can [get] 50 back.
> Creator free to use for campaign AI for 40 credit per month, but for finding personalized PR Rs 199 per month with credit limit, also the 10% of commission become 5%, + top-up credit."

**Interpreted (chair's reading, accepted by both respondents):**
- **Brands:** Free plan fee 10%, Pro fee 5%, Pro stays Rs 4,999/month. Free AI allowance 50 credits (today: 100/month, 150 after the first funded campaign; Pro 400; 1 turn = 1 credit; about Rs 0.56-0.648/credit). When the 50 is used up the brand buys a top-up pack. Creating a campaign grants 50 credits back.
- **Creators:** base commission 10% (from 15%). Free Meera for campaign work at 40 credits/month. New paid creator plan at Rs 199/month that unlocks "finding personalised PR" (Meera finds brands and writes personalised pitches/outreach), has a credit limit, and cuts that creator's commission from 10% to 5%. Creators can buy top-up credits.
- **What it drops:** both Round 2 0% mechanics (the first-Rs 25k/50k launch offer and the 12-month referral 0%) are absent. It also reverses the Round 2 consensus of "no creator plan for now" (Section 3, point 7).

**Ambiguities, and how respondents resolved them (none confirmed by the CEO yet):**

| # | Ambiguity | Tejas | Rohan |
|---|---|---|---|
| a | +50 on campaign CREATED or FUNDED/live? | FUNDED only | FUNDED only, max 2 grants/month |
| b | Is the 50 one-time or monthly? | Not resolved | Monthly (keeps the existing 1st-of-month reset) |
| c | Credit limit on the Rs 199 plan | Not sized; must clearly beat the free 40 | 120/month, PR turns at 2 credits, cap $1.50 |
| d | Is Rs 199 GST-inclusive? | Asked | Assumed inclusive (net Rs 168.64), per the 0917 ruling |
| e | 5% on ALL deals or only PR-sourced? | PR-sourced only | PR-sourced only (first contact = Meera-sent pitch) |

**Code facts both respondents relied on (verified by them):** creator Meera has no tools today (`influora-ai/app/prompt/creator_persona.py:86` "You have NO tools in this phase"; `influora-ai/app/providers/claude.py:150` "a CREATOR turn carries NO tools"). No brand-discovery or pitch executor exists in `service/meera/tool` (all brand-side) or `service/creatorcopilot` (nudges, trend screening, Meta OAuth). There is no creator plan (`PlanCode` = FREE, PRO) and no credit top-up product (`WalletTopUp` is wallet money). The loyalty-bonus hook exists: `AICreditService.java:86` `LOYALTY_BONUS = 50`. So every creator-side item in the counter, and the brand top-up, is a build, not a switch.

### R3.2 Tejas (CMO) - verdicts

| CEO point | Verdict | Why (short) | Fix |
|---|---|---|---|
| Brand fee Free 10% / Pro 5%, Pro Rs 4,999 | Agree | Same as the Round 2 consensus | None |
| Free brand AI 100 to 50, then top-up | Agree with changes | Top-up kills the "wait for the 1st" dead end; halving the allowance is a new cut with no usage baseline and risks a paywall in the first session | Ship top-ups now; A/B test 50 vs 100 on new signups only |
| +50 when a campaign is created | Disagree | Farmable: create drafts, mint credits, never fund | Grant only on FUNDED/live |
| Creator free AI 40 credits/month | Agree | Round 2 consensus | None |
| Rs 199/month "finding personalised PR" plan | Disagree | Tool does not exist; selling it is a refund and trust risk; Rs 2,388/year is 4.8x Influish's Rs 499/year | Fake-door/beta first; price against Rs 499/year if launched |
| Rs 199 plan cuts commission 10% to 5% | Disagree | Never costed in Rounds 1-2; if blanket, a creator saves Rs 5,000 on one Rs 1L deal for a Rs 2,388/year plan | Scope 5% to PR-sourced deals only; Rohan must cost it |

**Numbers (ESTIMATE):** 100 to 50 credits saves about Rs 28-32 per Free brand per month. Rs 199/month = Rs 2,388/year gross per subscriber. If 5% applies to all deals, the plan pays for itself only below about Rs 47,760/year of that creator's GMV (Rohan later showed the real test is per subscribed month, about Rs 2,400 of payouts; see R3.4).

- **Biggest risk:** selling Rs 199/month for a feature with no code behind it, with a commission cut in the same bundle. Either creators find nothing behind the paywall (refunds, trust damage while competing with Influish), or the 5% applies broadly and every serious creator subscribes just to halve commission.
- **Best thing:** top-up on expiry for brands. It fixes the one dead end everyone already agreed was broken.
- **Amended version (final, after cross-check):** Brand fee 10% / 5% (unchanged). Keep Free AI at 100/month; A/B test 50 on new signups. Ship brand top-ups now (100/Rs 999, 300/Rs 2,499, 1,000/Rs 5,999). +50 only on FUNDED, max 2 grants/month. Creator commission 10%, free AI 40/month. Hold the Rs 199 plan and any linked commission cut until the tool exists; fake-door first; price against Rs 499/year; any discount scoped to Meera-attributed PR-sourced deals, with Rohan and Priya sign-off.

### R3.3 Rohan (CFO) - verdicts

Cost basis unchanged from Round 1 so figures compare: Razorpay 2.36% of the funded amount; fee and commission GST-inclusive (18/118 backed out); Rs 200 overhead/deal; AI Rs 0.648/credit (brand), Rs 0.559/credit (creator). All ESTIMATE.

| CEO point | Verdict | Why (short) | Fix |
|---|---|---|---|
| Brand fee Free 10% / Pro 5%, Pro Rs 4,999 | Agree | Round 2 consensus; ~Rs 14,150 contribution per Rs 1L Free deal with a 10% creator | Publish only after the 4 code fixes (incl. `PLATFORM_FEE_PERCENT=15` at `application.yml:407`) |
| Free brand AI 50, then top-up | Agree with changes | Saves only Rs 32/brand/month; the value is conversion. No top-up product exists, so cutting first recreates the dead end at half the allowance | Monthly 50; ship the ladder in the SAME release (25/Rs 299, 100/Rs 999, 300/Rs 2,499, 1,000/Rs 5,999; never expire); raise to 75 if median turns-to-funded exceeds ~40 |
| +50 when a campaign is created | Agree with changes | On CREATE: Rs 32.40 per draft with Rs 0 revenue (20 drafts = Rs 648). On FUNDED: covered 8x by the Rs 253 net minimum fee | FUNDED only, once per campaign, max 2/month (Free ceiling 150 = today's) |
| Creator commission 10% | Agree | Costs ~Rs 4,240 per Rs 1L deal (Rs 18,390 to Rs 14,150) | Per-deal bps snapshot before the change |
| Creator free AI 40/month | Agree | ~Rs 22/creator/month (Light user) | +30 at signup, voice 2 credits, cap $1 |
| Rs 199 PR plan with credit limit | Agree with changes | Sound as a product, but on day one it buys only the commission cut | Launch only with the PR-finder tool; 120 credits, PR turns 2 credits, cap $1.50; annual Rs 1,999 |
| Rs 199 plan cuts commission to 5% | Disagree | As a blanket rate: a 5-point cut for Rs 169 net; break-even ~Rs 2,400 of payouts per subscribed month; release-month arbitrage | 5% only where the pair's first contact was a Meera-sent pitch while subscribed; snapshot at deal creation; cap Rs 3L per pair or 12 months |
| Creator top-up credits | Agree with changes | High margin, but reads as a toll to free creators with no tools | Rs 149/249/649 for Rs 199 subscribers at launch; free tier after 60 days of metering |
| No 0% buckets | Agree | Biggest financial improvement over Round 2: a 0% referred deal was Rs 5,679 (Free) / Rs 1,559 (Pro) per Rs 1L | Keep them out; use "Meera finds the brand, you keep 95%" if a headline is needed |

**Key numbers (ESTIMATE):**
- Rs 1L Free deal: Free creator Rs 14,150; Rs 199 creator at 5% Rs 9,916 (+~Rs 102 net subscription = Rs 10,018, -29%). On a Pro brand: Rs 10,034 vs Rs 5,796 (+102 = Rs 5,898, -41%).
- Rs 199 plan: net Rs 168.64 after GST; 120 credits fully used cost Rs 67; ~Rs 102-169/month left per subscriber before commission effects.
- Blanket 5%: gives up Rs 42.37 per Rs 1,000 of payouts; break-even ~Rs 2,400 of payouts per subscribed month (Rs 3,980 if credits unused). One Rs 50k release saves the creator Rs 2,500 for Rs 199 (12.6x).
- Brand +50 grant: Rs 32.40 each; the 2/month cap bounds exposure at Rs 65/brand/month.
- Portfolio (Rs 50L GMV, 70/30 Free/Pro, 15 Pro subs): today ~Rs 9.55L/month; Round 2 finals ~Rs 6.0L; CEO counter as written (blanket 5%, ~400 subscribers) ~Rs 5.4-5.9L; Rohan amended (5% on ~20% PR-sourced GMV, ~200 subscribers, no 0%) ~Rs 6.8L. Break-even GMV: ~Rs 5.6L today, ~Rs 9L Round 2, ~Rs 9.5-10L CEO as written, ~Rs 7.5-8L amended.

- **Biggest risk:** the Rs 199 plan as a universal 5% switch, sold before the PR tool exists. Every creator with a live deal rationally subscribes, especially in release months; contribution per Rs 1L Free deal falls from Rs 14,150 to ~Rs 10,018, and the portfolio drops below the Round 2 finals. On day one the plan's only feature is the discount.
- **Best thing:** no 0% bucket. The creator concession is paid for, tied to an action attributable server-side, and is 5% rather than 0%. Properly scoped it beats the Round 2 referral 0% by Rs 4,237 per Rs 1L on every deal.
- **Amended version (final, after cross-check):** Brands 10% / 5%, GST-inclusive, Rs 299 minimum, Pro Rs 4,999/month or Rs 49,990/year. Top-up ladder ships first. Free AI 50/month for new signups only (existing brands stay at 100 until 30 days of turns-to-funded data), +50 per FUNDED campaign, max 2/month; Pro 400 + the same grants. Creators 10%, snapshotted per deal; free AI 40/month + 30 at signup, cap $1; no 0% offers. Rs 199/month (annual Rs 1,999) only with the PR-finder release: 120 credits, PR turns 2 credits, cap $1.50; 5% only on Meera-pitch-sourced deals, cap Rs 3L per pair or 12 months. Sequence: Phase 1 rates + snapshots + fee copy; Phase 2 (60 days) brand 50/+50/top-ups; Phase 3 PR tool + Rs 199 plan + 5% scope. The 90-day review adds an alarm if PR-sourced GMV exceeds 30%.

### R3.4 Cross-check

**Tejas on Rohan:** the scoped-5% fix is not "ready to ship". Server-side first-contact attribution is a second unbuilt feature stacked on the unbuilt PR tool, and the ~20% PR-sourced GMV share behind the Rs 6.8L figure has nothing under it (an ESTIMATE on an ESTIMATE). Mis-attributed pitches would create commission disputes, a worse trust problem than an unbuilt feature. "You keep 95%" copy is not safe until Priya scopes attribution and it is proven in production.
**Tejas concedes:** +50 on FUNDED with Rohan's cost math; blanket 5% is a release-month arbitrage and must go; GST-inclusive and per-deal snapshots are non-negotiable.

**Rohan on Tejas:** anchoring the Rs 199 plan to Influish's Rs 499/year makes every active subscriber a loss: Rs 499/year nets Rs 35/month against Rs 67/month of credits at full use (about -Rs 32/month before any commission). Tejas's Rs 47,760/year break-even is about 12x too generous because the plan is monthly and commission is taken at release. The 5% cut was costed (Rs 4,237 per Rs 1L). The +50-on-create objection needed a number: Rs 32.40 per draft.
**Rohan concedes:** the PR tool does not exist (re-verified), so no launch before it ships, and a fake-door first is right; +50 on CREATE is farmable; 5% must be PR-sourced only; test 50 credits on new signups rather than cutting everyone.

**Unresolved between them:** whether the Rs 199 plan should exist at all this year (Tejas: hold it; Rohan: yes, with the tool), its price anchor (Rs 499/year vs Rs 199/month or Rs 1,999/year), and whether a scoped 5% can be costed before attribution is built.

### R3.5 Where the team agrees and disagrees with the CEO

**Agrees with the CEO (both respondents):**
- Brand fee Free 10% / Pro 5%, Pro Rs 4,999/month.
- Creator commission 10%; free creator AI at 40 credits/month.
- Brand top-up packs when credits run out (both want them shipped first, not after a cut).
- A campaign-linked +50 credit grant (as a concept).
- Dropping the 0% buckets (Rohan explicitly; Tejas did not object).

**Disagrees with the CEO:**
- **+50 on CREATE:** both say FUNDED/live only, max 2/month. Unanimous.
- **Blanket 5% for Rs 199 subscribers:** both say PR-sourced deals only. Unanimous. This is the one line that makes the counter financially worse than the Round 2 finals.
- **Launching Rs 199 before the PR tool exists:** both say no. Tejas says hold it this year; Rohan says launch with the tool.
- **Cutting all Free brands to 50 credits:** both now say new signups only; existing brands stay at 100 until turns-to-funded data exists.

### R3.6 Scenario table: Rs 1,00,000 budget on the Free brand plan

Rohan's cost basis (Section 6). "Standard" = a deal not sourced by the PR tool. Contribution excludes the subscription except where shown in brackets (+~Rs 102/month net per Rs 199 subscriber at full credit use). Where a respondent did not compute a figure the chair derived it from Rohan's basis; those are marked (chair). All ESTIMATE.

| Scenario | Brand pays | (i) Free creator: creator gets / platform take / contribution | (ii) Rs 199 creator, standard deal | (ii) Rs 199 creator, PR-sourced deal | Brand AI (Free) |
|---|---|---|---|---|---|
| Current (reference) | Rs 1,10,000 | Rs 85,000 / Rs 25,000 / ~Rs 18,390 | n/a (no plan) | n/a | 100/month; 150 after first funded |
| **CEO counter as written** (5% on all subscriber deals) | Rs 1,10,000 | Rs 90,000 / Rs 20,000 / ~Rs 14,150 | Rs 95,000 / Rs 15,000 / ~Rs 9,916 (~Rs 10,018 with sub) | Same as standard: Rs 95,000 / Rs 15,000 / ~Rs 9,916 | 50, top-up, +50 per campaign created (uncapped) |
| **Tejas amended** (no Rs 199 plan yet) | Rs 1,10,000 | Rs 90,000 / Rs 20,000 / ~Rs 14,150 | Plan not offered; deal at 10%: ~Rs 14,150 | Not offered; if later launched and scoped: ~Rs 9,916 (chair) | 100 (50 A/B on new signups), top-up, +50 per FUNDED, max 2/month |
| **Rohan amended** (5% on PR-sourced only) | Rs 1,10,000 | Rs 90,000 / Rs 20,000 / ~Rs 14,150 | Rs 90,000 / Rs 20,000 / ~Rs 14,150 (~Rs 14,252 with sub) | Rs 95,000 / Rs 15,000 / ~Rs 9,916 (~Rs 10,018 with sub) | 50 for new signups (100 existing), top-up, +50 per FUNDED, max 2/month |

Same deal on a **Pro** brand (5% fee, brand pays Rs 1,05,000): 10% creator ~Rs 10,034; 5% creator ~Rs 5,796 (~Rs 5,898 with sub). Round 2 referral 0% for comparison: ~Rs 5,679 (Free), ~Rs 1,559 (Pro).

Portfolio (Rohan, Rs 50L GMV): CEO as written ~Rs 5.4-5.9L/month; Tejas amended ~Rs 6.0L or slightly above (chair: roughly the Round 2 base without the 0% buckets; not computed by Tejas); Rohan amended ~Rs 6.8L; today ~Rs 9.55L.

**Chair's read:** almost all of the gap between the three versions comes from point (e). On a deal the PR tool did not source, the CEO's version loses ~Rs 4,234 per Rs 1L (about -30%) against either amendment. On a PR-sourced deal, the CEO's version and Rohan's are the same.

### R3.7 Questions the CEO must answer

1. **5% scope (decides everything):** all of a Rs 199 subscriber's deals, or only deals Meera's PR tool sourced? Both respondents refuse sign-off on "all deals".
2. **+50 trigger:** campaign CREATED (draft) or FUNDED/go-live? Is it capped per month?
3. **The 50 brand credits:** monthly allowance or a one-time start? Does it apply to existing Free brands or to new signups only?
4. **Rs 199 credit limit:** how many credits? (Rohan proposes 120, PR turns at 2 credits.)
5. **GST:** is Rs 199 inclusive (net Rs 168.64) or Rs 199 + 18% = ~Rs 235?
6. **Timing:** will you hold the Rs 199 plan until the PR-finder tool ships? Today creator Meera has no tools.
7. **Tejas vs Rohan:** hold the Rs 199 plan this year (Tejas), or launch it with the tool at Rs 199/month or Rs 1,999/year (Rohan)?
8. **0% buckets:** is dropping the launch offer and referral 0% intentional, or should they sit alongside the Rs 199 plan? (Both respondents call stacking them the worst case.)
9. **Pro credits:** does Pro keep 400, and does Pro also get the +50 per funded campaign?
10. **Annual creator plan:** is Rs 1,999/year acceptable to discourage subscribing only in release months?
11. **Attribution:** will you fund a server-side "first contact was a Meera pitch" build (Priya to scope) before any 5% ships?
12. **Still open from Round 2:** is creator commission GST-inclusive or charged on top; and the day-0 baseline (real GMV, campaigns, Free/Pro mix, Pro count).
