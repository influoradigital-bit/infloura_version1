# CMO Assessment — Brand AI: What the Market Actually Wants

**From:** Tejas Mehta (CMO)
**To:** Swapnil Maruti (CEO)
**Date:** 2026-09-12
**Read:** `CTO-AI-BRAND-50A-0912-PART1.md` (Q1–28), `CTO-AI-BRAND-50A-0912-PART2.md` (Q29–50), both in full
**Feeds:** `wiki/decisions/marketing-strategy.md`

---

## THE ONE-LINE READ

We said we are building **one system that does the brand's manual work**.
Priya's audit describes **a chat window the brand has to remember to open**.

That gap is not a bug list. It is a category error — and it is the whole positioning problem.

---

## WHY THIS MATTERS MORE THAN THE DEFECTS

A chat is not automation. A chat is manual work with a nicer face.

The brand still types the request. The brand still decides. The brand still clicks. We moved the
work from a form into a conversation and called it AI. Every truly automated product in this market
has one property we do not have: **it produces something while the customer is not looking.**

Priya's Q9 is the proof. The complete list of brand AI surfaces is **one screen** — `/brand/meera`.
There is no background job, no digest, no alert, no push. Meera exists only while a tab is open.

Meanwhile we already built the muscle — on the wrong side of the marketplace:
`CreatorNudgeService.java`, `TrendSparkNudgeService.java`. **We taught the AI to reach out to
creators, who don't pay us, and never pointed it at brands, who do.**

---

## WHERE THE MANUAL WORK ACTUALLY STILL SITS

I mapped the brand journey against the audit. The automation has a hole at **both ends**.

| Step | Automated? | Evidence |
|---|---|---|
| Paste store URL | We read it — **and never tell them** | Q11: no "analysing" state, result only appears inside the canvas, polling gives up silently at ~2 min |
| Describe the campaign | ✅ genuinely good | Q13: real composed DRAFT, persisted |
| Decide what to pay | ❌ a fixed % of product price | Q16: hardcoded ×0.08–0.15, **real rate data sits unused in the same prompt** |
| Find creators | ❌ keyword search dressed as matching | Q15: `LIKE '%word%'` over bio, sorted by followers |
| Fund + launch | Manual **by design — correct, keep it** | Q18 |
| Find out if it worked | ❌ she refuses, forever | Q17: uncallable tool |
| Decide what to do next | ❌ nothing exists | no brand nudge, no digest |

**We automated the part brands enjoy (writing a brief) and left manual the parts they hate —
pricing it and finding out if it worked.** That is exactly backwards, and it is the single most
useful sentence in this document.

---

## WHAT I WANT US TO ADD — 4 MOVES, RANKED

### MOVE 1 — Ship "The Monday Brief." Turn Meera from a tab into a teammate.
**The product:** every Monday, unprompted, Meera sends the brand: what your campaigns did last
week, what it cost, what I'd do next — and one tap to act on it.

**Why it is the biggest unlock in this document:** it is the only change that makes the word
"automation" true. A pulled product is a tool you forget; a pushed product is a teammate you rely
on. Retention in this category is decided entirely by whether the thing shows up on its own.

**Why it is cheap:** the push machinery exists (`CreatorNudgeService`, `TrendSparkNudgeService`,
`NotificationListener`). The content exists — the outcome digest is LIVE (Q43 #13) and reads only
`PLATFORM_VERIFIED` rows.

**The dependency, and this is the strategic point:** the brief is worthless while
`get_campaign_performance` is uncallable. **That DTO fix is not a bug ticket — it is the gate on our
entire retention story.** Please make sure it is treated that way.

### MOVE 2 — Stop selling matching. Start selling the price.
**Kill this claim:** "matching creators by niche, city, and engagement" (`landing.tsx:150`). It is a
keyword search with no engagement term anywhere in the path. It is the first thing a customer tests
and it fails in one query. It is also an ASCI exposure.

**Replace it with the thing nobody else on earth can say:**

> *Every platform will find you creators. Only Influora knows what they were actually paid.*

Proof is real and it is ours: rate band from `status = 'COMPLETED'`, money-settled deals, DISPUTED
and CANCELLED excluded, k-anonymity floor 5+5 (Q45). ChatGPT can write a brief. It cannot know a
settled rate. **That is the moat, and we are currently not selling it at all.**

**The catch, stated honestly:** that band is almost certainly `null` today — the k-anon floor isn't
met. Which leads directly to Move 3.

### MOVE 3 — Change what marketing optimises for: completed deals, not signups.
This is the biggest shift in how I run my function, and it comes straight out of Q45.

Our moat is **data-gated, not build-gated**. It switches itself on at 5 distinct creators × 5
distinct workspaces per niche. Until then we have a category-defining claim we are not allowed to
make.

So: I am re-pointing content, SEO and campaign spend at **depth in two or three niches** rather than
breadth across many. Fifty completed deals in beauty beats five hundred signups spread across
twenty categories — because the first unlocks a claim no competitor can copy and the second unlocks
nothing. Aditya and Nisha will get a revised brief.

### MOVE 4 — Sell the constraint. Make "she can't touch your money" the headline.
The audit's best finding is a marketing asset we are wasting. Meera **structurally cannot** move
money — three independent layers, tools not even offered to the model (Q18). Scraped prices always
override model guesses (Q12). Verified metrics never mix with self-reported (Q17).

The market is frightened of AI agents with account access. Every competitor's answer is "we have
guardrails." Ours is:

> *Meera can't spend your money. Not "won't" — can't. The payment tools aren't connected to her.*

That is checkable, falsifiable, and true. In this market, being the platform that architecturally
cannot is a wedge, not a limitation.

---

## COPY DECISIONS I AM MAKING NOW (my authority)

1. **"First AI-first influencer platform in India"** (`meera-copy.ts:12`) — **REMOVE.** It renders
   unconditionally in the live chat header with no `live` guard. An unfalsifiable superlative shown
   in-product is an ASCI/CCPA misleading-ad exposure, not puffery. No superlative survives that we
   cannot document.
2. **The matching claim** (`landing.tsx:150`) — **REWRITE to what ships today** ("search creators by
   niche and city"), then upgrade the copy only when the algorithm lands. Copy follows code; never
   the reverse.
3. **Privacy policy AI clause** (`privacy-policy.md:44`) — **ESCALATING TO YOU, not deciding.** We
   claim we use personal data to train AI (we don't) and we don't name the foreign sub-processor we
   actually send text to (we do). Over-claiming rights we don't use while under-disclosing the one
   thing we do is the worst of both. This is legal, above my line.

---

## WHAT I AM NOT DOING

I did not write to `SHARED_CONTEXT.md`. A concurrent automated session has it modified, and we have
a documented history of clobbering in-progress edits there. Handoffs to Nisha and Aditya go
directly.

---

## THE SENTENCE I WANT US TO EARN

Not *"India's first AI-first influencer platform."*

> **"You don't talk to Meera. You review what she's already done."**

Everything in Move 1 exists to make that sentence honest. Right now it isn't — and every other
marketing decision should wait behind it.

---

*Tejas Mehta, CMO. Every claim above traced to a file:line in Priya's two answer documents.*
