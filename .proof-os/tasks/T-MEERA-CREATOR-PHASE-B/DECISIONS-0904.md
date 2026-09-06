# Phase B — four rulings for Swapnil (2026-09-04)

Each ships with a default if there is no answer by B0 day 1. Full mechanisms in `SPEC.md` §14; this page is the one-minute version.

---

## 1. Should brands see that a message was drafted or sent by Meera?

**Recommendation: yes. Default ships as "shown".**

What the brand sees: one muted line under the bubble, "Drafted with Meera, approved by {creator}" or "Sent by Meera for {creator}". Nothing else changes; the creator's floor, preferences and internal ids stay hidden.

Why:
- AI that talks to people must say so under the EU AI Act (Article 50, applicable from August 2026). Indian advisories from MeitY point the same way. Building the hidden version now means rebuilding it later.
- The first brand that finds out on its own will say so in public. That cost lands on Influora, not on the creator.
- Brands that know a creator has a manager negotiate with the manager. That is the position we want creators in.

Cost of flipping later: one property, `influora.meera.brand-facing-stamp=false`.

---

## 2. What does a creator get for bringing a brand onto the platform?

**Recommendation: record it now, decide the reward after B0's numbers. Default ships as "recorded, unpaid".**

What ships: `campaigns.introduced_by_creator_profile_id`, written at every secure-link redeem. Read by nothing until you rule. Without it, no early creator can ever be credited.

Suggested ruling when you make it: platform fee **10% instead of 15%** on deals with that brand for the brand's first 90 days, and the creator sees a "you brought this brand" badge. On a ₹5,000 deal that is ₹250 given up to make the creator ask the brand at all.

What this is not: a cash referral. Nothing moves money.

---

## 3. Raise the creator AI cap from $0.75 to $2.00 a month?

**Recommendation: yes. Default ships at $2.00.**

Rohan's credit sheet: a Typical creator already sits at 88.5% of the $0.75 cap and a Heavy creator at 253%. At $2.00 they sit at 33% and 95%. The people who use Meera most are the first to hit the wall, and the wall today ends the conversation.

Also shipping regardless of the cap: when the cap is hit, paste-a-brief, deal risks, and approving drafts keep working (they cost nothing); brief extraction gets its own small cap of **$0.25 a month** (about 70 extractions) so chat cannot starve it.

Cost: exposure, not spend. Caps are ceilings. Per the sheet, AI cost stays under 21% of one platform fee even for a Heavy creator closing one deal a month.

---

## 4. Build the whole of Phase B, or build the reading half first and measure?

**Recommendation: split. Default ships as B0 (Paste and Read), then a gate, then B1 (Secure and Send).**

| | B0 | B1 |
|---|---|---|
| Creator gets | paste a brief → summary, price, risk flags, a drafted reply to tap | secure links, Meera sending routine replies alone, media kit, campaign matching |
| Sends anything without a tap | never | yes, six routine intents at level 1 |
| Engineer-days | ≈ 10-12 | ≈ 12-14 |

The gate (14 days live or 100 briefs, whichever first):

1. At least 40 creators pasted a brief. If not, the problem is getting people in, not features.
2. The risk flags are right at least 80% of the time on a hand-checked sample of 50, and the two flags that would most annoy a creator if wrong — hidden-disclosure and off-platform-payment — are wrong less than 10% of the time.
3. At least half the drafts get sent (edited or not), and at least a quarter go out with no edit at all — the second number is what says the writing is good, not merely usable.
4. Quoted price vs the brand's stated budget, per tier, shown to you. No pass mark; it decides whether the price constants get recalibrated before links freeze a number.
5. Zero floor leaks.

Precondition: **Phase A must be deployed** before B0 can be measured. That is blocked on a live smoke test that needs Docker or the VPS. It is now the oldest item on the critical path.

Why split: the flags and the prices are guesses until real briefs hit them. B1 automates actions on top of those guesses. Finding out they are wrong after B1 is built costs the whole of B1.

---

*Derived from `SPEC.md` §14; reviewed against source in §14.6 (Priya, 2026-09-05). Where this page and §14 differ, §14 wins.*

---

**If you say nothing:** brands see the Meera label, the referral column ships unpaid, the cap is $2.00, B0 builds first. Say the word to change any of the four.
