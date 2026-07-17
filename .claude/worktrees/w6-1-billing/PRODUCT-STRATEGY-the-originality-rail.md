# Influora — Product Strategy: Find the Hero Product

**Date:** 10 July 2026
**Role:** Product development review
**Grounded in:** `docs/BUSINESS-IDEA-THAT-STANDS.md`, `MASTER-BUSINESS-PLAN.md` (Tara's six-agent code audit), `docs/PROJECT-STATUS.md`, plus external research on Instagram's 2026 originality enforcement and the competitive set.

---

## The finding, in one sentence

> **The video product you've been designing for two days is not a separate business. It is the missing compliance rail inside your own Hype Campaign — and without it, Hype will get your creators' accounts throttled by Instagram.**

Build it as *that*, not as an OpusClip competitor. And build the cheap version, not the expensive one.

---

## 1. Diagnosis: what Influora actually is

From the code, not the pitch deck:

- Three-sided platform: **Brand / Creator / Admin**. Escrow, contracts, affiliate tracking.
- **161 REST endpoints, 54 entities, 44 migrations, ~100 Java tests.** Backend is **88% built**.
- Frontend still boots on `getMock*()`. **End-to-end live: ~42%.**
- Tara's audit already told you the answer to "what should we build": *"The single highest-leverage engineering action is not building more features. It is connecting the ones already built."*
- **Meera ships a hardcoded script, not AI.** (See §7. This is a problem.)

The business thesis that survived debate: escrow-backed campaign OS for Indian brands + micro-creators. **Hype Campaign is the flagship growth feature.** Fee model: brand-side ₹20K setup + 15% on the escrow pool, min pool ₹150K, creator pays zero.

---

## 2. The problem nobody in your docs has costed

Your own debate doc identifies the risk and hand-waves the fix:

> *"Instagram demotes duplicate reels… **v2 fix: derivative amplification, not replication.** Creator picks a format lane with original hook, caption, face/voice."*

Here is what that risk actually is, quantified, as of 2026:

| Instagram enforcement (2026) | Consequence |
|---|---|
| Post shares **≥70% visual similarity** with existing content | Flagged as repost → **reduced distribution** |
| Account posts **≥10 reposts in a 30-day window** | **Excluded from recommendations entirely** — no Explore, no Reels feed, no suggested posts |
| April 30, 2026 update | Originality enforcement extended from Reels to **all formats** |
| Observed effect | Aggregator accounts: **60–80% reach drop.** Original creators: **40–60% reach lift.** |

Now read your Hype spec again. You are pushing **one source reel + one audio + one hashtag to 150–300 micro-creators inside 72 hours** and asking them to produce derivative content.

**If the cohort's pairwise visual similarity lands above 70%, Influora doesn't just deliver a weak campaign. Influora burns its own creators' accounts.**

And a micro-creator whose account gets excluded from recommendations does not come back to the platform that did it to them. Your creator-acquisition funnel becomes a creator-destruction funnel. Your moat — "accumulated per-creator performance data" — is worthless if the creators are throttled.

**This is an existential product risk, and "the creator picks a format lane" is not a control. It's a hope.**

---

## 3. The hero product

> **The Originality Rail: Influora is the only campaign platform that can guarantee, contractually, that every reel in a cohort ships algorithmically original.**

Three rails. Two you've built. One is missing.

| Rail | Status | Who else has it |
|---|---|---|
| **Money** — escrow, contracts, TDS, instant payout | 88% built | Collabstr, Ainfluencer, GRIN (escrow only, no TDS) |
| **Compliance** — #ad/paid-partnership detection gates escrow release | Specced (M3) | Nobody, in India |
| **Originality** — cohort-level visual diversity, enforced pre-payout | ❌ **Missing** | **Nobody, anywhere** |

### Why this is the wedge

Every competitor — Collabstr (200K creators), Ainfluencer, GRIN, Heepsy, Afluencer — does **discovery + escrow**. That's a solved, commoditised, crowded space. You will not win it.

**Nobody does production.** And crucially: nobody *can* do cohort-level originality, because the objective function only exists if you own a cohort.

- OpusClip / Submagic optimise **one video in isolation.** Make this video good.
- Influora must optimise **150 videos against each other.** Make these 150 videos *maximally different from one another* while carrying the same brand message.

That is an inverted objective. It requires the campaign brief, the creator roster, each creator's prior content style, and the submitted deliverables — **all four of which only Influora has in one place.** A consumer B-roll tool structurally cannot do this. It has no cohort.

### The contractual promise

You already have escrow and deliverable-review rails. So you can sell something no one else can:

> *"Every reel in your Hype campaign clears Instagram's originality threshold — verified before payout — or that reel's escrow isn't released."*

Brands buy outcomes. That is an outcome, it is measurable, and it plugs straight into the escrow release condition you've already built.

---

## 4. Build the cheap version. The expensive version doesn't survive the math.

This is where the last two days of work goes wrong. Run the numbers against **your own fee model**, not against a generic SaaS.

Hype campaign at the minimum pool floor: 150 reels × ₹1,000 = **₹150,000 pool.**
Influora revenue: ₹20,000 + 15% = **₹42,500 (~$500).**

| Option | Cost per reel | Total COGS | % of your revenue | Margin |
|---|---|---|---|---|
| **A. Generate full AI B-roll per reel** (the 2-day plan) | $2.14 | **$321** | **64%** | 36% |
| A, optimised hard | $1.00 | $150 | 30% | 70% |
| **B. Claude differentiates the *brief*; creator shoots** | **$0.011** | **$1.65** | **0.33%** | **99.7%** |
| C. B + pHash cohort originality check | +~$0 | ~$1.65 | 0.33% | 99.7% |

**Option A eats 64% of campaign revenue.** Hype's whole economic logic is a thin platform fee across many cheap reels. It cannot absorb a $2 generation cost per reel. The video-generation product, built as designed, *kills the campaign it was meant to serve.*

**Option B costs $1.65 for the entire 150-creator campaign.**

The pattern is now three-for-three across these reports: **the intelligence is nearly free; the pixels are the entire bill.** So stop buying pixels.

### What Option B actually is

Claude reads the campaign brief + each creator's profile and prior content, then generates **150 different creative briefs** — not 150 videos:

- assigns a distinct **format lane** (reaction / duet / POV / "I tried it" / regional-language retelling / stitch / unboxing)
- writes a distinct **hook line** in that creator's register and language
- writes a distinct **shot list** (3–5 shots, things they can film on a phone)
- writes a distinct **caption angle**
- enforces cohort-level diversity: no two creators in the same city get the same lane; hook embeddings must stay apart

**The creator shoots it.** Their face, their voice, their room. That is *by definition* original content — which is exactly what Instagram is now rewarding with 40–60% more reach.

You were trying to solve an originality problem with a generation tool. The generation tool *creates* the similarity problem. **Human creators, differentiated by AI, are the answer.**

### And the enforcement half — this is the defensible part

Before escrow release, run a **perceptual hash (pHash) + embedding check** across the cohort:

- pairwise similarity across 150 reels = 11,175 comparisons, O(1) each — **compute cost ≈ zero**
- any reel **≥70% similar** to another in the cohort → **escrow release blocked**, creator gets one re-shoot with a new lane
- fingerprint against the source reel too
- log every score → this is the **per-creator originality dataset** that becomes the real moat

Nobody can copy that with a WhatsApp broadcast list. It compounds.

### The optional paid tier

For the 2–3 genuinely *conceptual* shots a brief needs — "AI beating the coder," a visual no phone camera can capture — generate one hero shot via Higgsfield MCP. **$1.03 cost.** Sell as a per-reel add-on at **$4**, or bundle into a premium campaign tier. Optional. Never the default.

---

## 5. Sequencing — and the honest bad news

**Do not build any of this yet.**

Your code audit says **42% end-to-end live**, backend 88% built, frontend on mocks, 4 ship-blocking gaps, no pricing/terms/about routes. Your own debate doc gates Hype behind *"3 concierge campaigns and ~500 verified creators"* and caps it at *"≤100 reels for the first 3 runs."*

The Originality Rail is the right hero product. It is **M4 work.** Building it now, against a mocked frontend, is the most seductive mistake available to you — it's the fun problem, and it is not the problem.

| Stage | What | Gate to pass |
|---|---|---|
| **Now** | Connect frontend to the 161 endpoints that already exist. Kill `getMock*()`. | E2E live ≥ 80% |
| **M1** | Creator auth + Instagram OAuth + real Deal Room backend | — |
| **M2** | Escrow + Razorpay Route + contracts | Real money moves |
| **M3** | Deliverable review + AI pre-screen. **Ship pHash originality scoring here — read-only, no enforcement.** Collect data on 3 concierge campaigns. | ≥ 200 scored reels |
| **M3.5** | Turn on Claude brief-differentiation for one concierge campaign. Compare reach vs. an undifferentiated control cohort. | Differentiated cohort's median reach ≥ 1.3× control |
| **M4** | Hype Campaign with the Originality Rail enforced at escrow release. ≤100 reels. | Median per-reel reach ≥ 75% of creator's 30-day baseline |

**M3.5 is the whole company's riskiest assumption.** Run it before you build anything else.

---

## 6. The four riskiest assumptions, and the cheapest test for each

Product development is not building. It's finding the assumption that kills you and testing it for the least money.

| # | Assumption | If false | Cheapest test | Cost |
|---|---|---|---|---|
| **1** | Claude-differentiated briefs actually produce cohort reels below the 70% similarity threshold | Hype is a liability engine. **Kill Hype.** | 10 creators, 1 brief, 2 arms (differentiated vs. not). pHash the 10 reels. | **~$5 + 10 creator fees** |
| **2** | Differentiated reels get *more reach*, not just less penalty | The rail is compliance theatre, not a selling point | Same 10 reels: compare each creator's reach vs. their own 30-day baseline | **$0** |
| **3** | Micro-creators will *follow* a specific shot list in 72 hours | Briefs are ignored; you're back to duplicates | Send 20 briefs. Measure adherence rate. | **~$1 + 20 fees** |
| **4** | Brands will pay a premium for an originality guarantee | It's a cost centre, not a product | Put the guarantee in the concierge campaign #1 pitch. See if it closes. | **$0** |

Total to de-risk the entire hero product: **under $10 of API spend and ~30 creator fees.** Do this before you spend $174 on Higgsfield Ultra.

Note assumption 2 carefully: 70% is a *penalty* threshold. Clearing it avoids demotion. It does not automatically buy reach. Those are different claims and your pitch must not conflate them.

---

## 7. Two things to kill or fix now

**1. Meera.** The code audit says the AI co-founder "ships a hardcoded script, not AI," and your only CI workflow is a Lighthouse check on `/brand/meera`. You are polishing the performance of a mannequin. Either wire it to the FastAPI service (which is 80% built and has three real providers) or rename it until you do. Shipping a scripted bot as an "AI co-founder" to Indian D2C brands is a trust problem, and trust is the entire product.

**2. The standalone video-editor idea.** Kill it as a separate product. OpusClip and Submagic own that market — Submagic claims 4M+ users at $12–20/month, and you'd carry $2–10 COGS per video. You cannot win there and you don't need to. Every useful thing in that idea survives, reborn as the Originality Rail, where you have an unfair advantage instead of a structural disadvantage.

---

## 8. The one-liner

> **Influora funds the reel, differentiates the reel, and proves the reel is original — before the money moves.**
>
> Competitors do discovery and escrow. Influora is the only platform that stands behind whether the content will actually reach anyone.

Money. Originality. Compliance. Three rails, one contract, zero competitors holding all three.

---

## Assumptions, gaps, and where I could be wrong

- **The 70% similarity figure and the "10 reposts / 30 days" exclusion rule come from third-party creator-marketing blogs, not Meta's official documentation.** They are consistent across several independent sources, but I could not verify them against a primary Meta source. **Before you build enforcement on a hard 70% cutoff, verify it directly.** If the real threshold is softer or fuzzier, the *product* still holds — the *contractual guarantee* needs rewording.
- Reach figures (60–80% aggregator drop, 40–60% original lift) are reported, not independently measured. Treat as directional.
- INR/USD taken at ~₹85. Fee model figures come from `BUSINESS-IDEA-THAT-STANDS.md`, not from code.
- pHash is a weak signal on video. Real cohort similarity likely needs frame-embedding cosine distance, not just perceptual hashing. Budget engineering time; the *cost* stays near zero either way.
- Claude token estimates for brief generation assume ~1,500 in / 800 out per creator at promotional Sonnet 5 pricing ($2/$10 per 1M), which **expires 31 Aug 2026**.
- I have not read `MASTER-BUSINESS-PLAN.md` in full (19KB) or the 4 named ship-blocking gaps. Those may reorder §5.

---

## Sources

- Internal: `docs/BUSINESS-IDEA-THAT-STANDS.md`, `MASTER-BUSINESS-PLAN.md`, `docs/PROJECT-STATUS.md`, `README.md`
- [Meta's New Original Content Rules (2026) — ALM Corp](https://almcorp.com/blog/meta-original-content-rules-2026-facebook-instagram-creators/)
- [Instagram Original Content Rule 2026 — Full Creator Guide](https://gotmenow.com/2026/05/12/instagram-original-content-rule-2026/)
- [Instagram penalizes non-original content — foro3d](https://foro3d.com/en/2026/mayo/instagram-castiga-el-contenido-no-original-adios-a-los-reposteadores.html)
- [Instagram Reels Algorithm 2026 — SocialPilot](https://www.socialpilot.co/blog/instagram-reels-algorithm)
- [How the Instagram Algorithm Works 2026 — Sprout Social](https://sproutsocial.com/insights/instagram-algorithm/)
- [Collabstr](https://collabstr.com/)
- [Top 15 Micro Influencer Marketing Platforms 2026 — Influencer Marketing Hub](https://influencermarketinghub.com/micro-influencer-platform/)
- [Influencer Marketing Pricing India 2026 — upGrowth](https://upgrowth.in/influencer-marketing-pricing-india-2026/)
- [Higgsfield MCP](https://higgsfield.ai/mcp)
- [Claude Sonnet 5 pricing — Eden AI](https://www.edenai.co/post/claude-sonnet-5-pricing-benchmarks-api-access)
