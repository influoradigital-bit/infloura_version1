# CMO Assessment — "Remove the AI, white-label it for agencies, new stream"

**From:** Tejas Mehta (CMO)
**To:** Swapnil Maruti (CEO)
**Date:** 2026-09-21
**Trigger:** Swapnil, verbatim — *"what we make whole remove AI make white label for agency? think as new stream"*
**Reads:** `wiki/decisions/2026-09-18-agency-chooser-removed.md` (F-0892), `wiki/decisions/CMO-BRAND-AI-POSITIONING-0912.md` (my own, 9 days ago), `src/pages/pricing.tsx`, `influora-api/.../entity/Workspace.java:303-325`
**Status:** three rulings requested. Nothing executed.

---

## THE ONE-LINE READ

**We are giving away the product and charging for the demo.**

The Free tier holds contracts, payment protection, dispute resolution, invoices on every payout
and the campaign dashboard. The ₹4,999 paid tier holds AI credits — against an AI that my own
2026-09-12 assessment called *"a chat window the brand has to remember to open."*

An agency will pay for the first list. It will not pay for the second.

So the instinct behind the question is right. But **"remove AI" and "white-label for agencies" are
two different decisions**, and doing them as one move deletes all subscription revenue on a Tuesday.
Split them.

---

## PART 1 — WHAT "REMOVE AI" COSTS, EXACTLY

Your own ruling of 2026-09-05 is hardcoded into the price card as a comment:

> `// CEO RULING (Swapnil, 2026-09-05): "platform is free, AI is paid".` — `src/pages/pricing.tsx:44`

Every rupee of subscription revenue we have is priced on AI credits. `pricing.tsx` says it six
different ways: *"Pro is priced for the AI credits"*, *"Start with everything. Pay when you want
more AI"*, *"the tiers below differ only in AI credits and…"*.

**Remove AI from the offer and Pro has no reason to exist at ₹4,999.** What remains is:

| Revenue line | State if AI is removed |
|---|---|
| Pro subscription ₹4,999/mo | **Zero justification left.** Caps on seats/creators/analytics alone do not carry that price to a brand. |
| Platform fee 7–10% on live campaigns | Survives — but **leaks today** (F-0848: campaigns created as ACTIVE pay no fee at all, open, unassigned to a fix). |

That is not a repositioning. That is a teardown of 100% of subscription ARR while the only
surviving line has a known hole in it. **I cannot recommend it as stated.**

### But the instinct is still correct — for the *messaging*, not the *offer*

The market has turned against AI-first positioning, hard, and the data is not ambiguous:

- **19%** of users say they feel excited about AI in 2026, down from **50%** two years ago.
- Nearly **half** of consumers now prefer brands that avoid generative AI in anything
  customer-facing.
- **82%** of ad execs believe Gen Z and millennials feel positively about AI-generated ads. Only
  **45%** of those consumers actually do. That 37-point gap is us, on our landing page.
- In B2B SaaS specifically, *"AI-powered"* in a positioning deck no longer earns a premium — it
  now reads as **a lack of real differentiation**.

Leading with "AI" foregrounds the *method* over the *value*. We currently lead with the method in
the hero (`landing.tsx:147` — *"Meet Meera, your AI campaign co-pilot"*), in the Pro card, and in
the meta description.

**CMO recommendation: demote AI from the front of the brand. Do not remove it from the product.**
Same engine, stop narrating it. This is copy work, it needs no engineering, and I can execute it
under my own authority once you say go.

---

## PART 2 — THE AGENCY STREAM

### We already killed this three days ago — and left the door open on purpose

`F-0892`, decided by you on 2026-09-18: the Brand/Agency chooser is removed, the 4 existing AGENCY
workspaces convert to BRAND. The reason was sound — an AGENCY workspace was *silently degraded*:
no `subscriptions` row (4 of 4 had none on production), invisible in the admin billing console,
AI credits that never reset.

But read the last section of that ruling:

> **Not decided here:** Whether an agency product is built later, and if so whether it is a
> workspace type at all rather than a multi-workspace account.

**So this question is not a reversal. It is the half you deferred, coming back three days later.**
That is the correct time for it.

### The demand signal we have is small but it is real

Four agencies signed up as agencies, unprompted, while the chooser existed. They are now BRAND
rows. **They are the only agency demand evidence in the company and they are still reachable.**
Before any engineering, I want those four interviewed plus six more. One question decides
everything: *is the buy trigger client seats and branded reporting, or is it discovery?*

### The category is priced, and we are not in it

| Benchmark | Figure |
|---|---|
| White-label platform, single seat | ~**$129/mo** |
| White-label platform, 7 seats ("Scale") | ~**$1,200/mo** |
| Per-client setup fee (category norm) | **$500–$2,500**, often waived on annual |
| Volume discount, 5 clients vs 1 | **15–20%**; at 10 clients, **25–30%** |
| India SaaS band (Qoruz / Winkl / Plixxo tier) | **₹25,000 – ₹3,00,000/mo** |
| India transaction-fee alternative | **8–15%** of campaign spend |

What agencies actually buy, consistently, across every source: **multi-client workspaces,
white-label reporting, client permission controls, branded dashboards shareable by secure link,
and volume-based pricing.** Note what is *not* on that list: AI.

### The rename, not the removal

Agencies do not buy AI credits. They buy **billable hours they do not have to staff**. Same engine,
different unit of account:

| Surface | Today | Proposed |
|---|---|---|
| Brand landing hero | "Meet Meera, your AI campaign co-pilot" | Lead with outcome; Meera moves below the fold |
| Brand price card | "400 AI credits/month" | Keep credits — brands already understand them |
| **Agency price card** | *does not exist* | **Never says AI.** Priced on client workspaces, seats, and reports with the agency's logo. Credits become an internal cost control, invisible on the card. |

**Indicative agency anchor: ₹15,000–₹25,000/mo for 5 client workspaces.** That sits above the
global $129 floor, at the bottom of the Indian ₹25k–₹3L band, and is defensible against a category
that charges $1,200/mo for 7 seats. **Rohan owns the final number — this is an anchor, not a price.**

Keep the platform fee on top. The agency stream's real advantage is that it monetises a
relationship whose GMV already flows through us.

---

## PART 3 — THE BLOCKER, AND WHY THIS IS ON YOUR DESK AND NOT NISHA'S

**White-label means the agency's client sees the agency's logo while Influora holds the money in
Influora's own ledger.**

You ruled on 2026-09-17 (F-0851) that we have **no RBI Payment Aggregator / escrow licence** —
volume too small, apply later — and that customer-facing copy must never claim a regulator or a
licensed PA holds brand funds. I audited 12 instances of that claim; Arjun found 3 more in legal
docs.

Under true white-label, **the brand does not know Influora exists.** Every custody disclosure we
just spent a ticket correcting gets hidden behind a third party's brand. The disclosure problem
does not shrink — it multiplies across every agency's entire client base, and now with an
intermediary who has their own incentive to not mention us.

Second, compounding: **whose GSTIN is on the invoice?** We currently print 18% GST on a brand
commission invoice for tax that was never collected (**F-0847, open, awaiting your CA ruling**),
and a "TCS @ 1%" line that is never remitted (**F-0850, open**). Shipping white-label invoicing on
top of two unresolved tax defects replicates both across every agency client we onboard.

**This gates the entire stream. It is a counsel question, not a design question.**

If the answer is no, the honest version is **"Payments powered by Influora"** co-branding rather
than true white-label — agency logo on the dashboard and the reports, Influora named on anything
that touches money. That is still a sellable product, and it is what most of the category actually
ships.

---

## THREE RULINGS REQUESTED

| # | Question | Owner | Blocks |
|---|---|---|---|
| **1** | Do we demote AI from the front of the brand — hero, meta, Pro card — while keeping it in the product and the price? | **Swapnil** (CMO recommends **yes**) | Nothing. I execute on approval; Ananya applies. ~1 day. |
| **2** | Is the agency product a **multi-workspace account** (one agency login, switch between clients) or a **resurrected workspace type**? F-0892 explicitly left this open. | **Priya** (CTO), you ratify | All engineering scoping |
| **3** | **Can a white-label surface legally hide Influora as the custodian of brand funds**, given F-0851 (no PA licence) and the open F-0847 / F-0850 tax defects? | **Swapnil + counsel** | **Everything.** Do not scope, design, or announce before this. |

---

## WHAT I RECOMMEND, IN ORDER

1. **Now, no build, no ruling needed:** I interview the 4 converted agency workspaces + 6 more.
   Validates or kills the whole thesis for the cost of ten calls.
2. **On ruling #1:** I rewrite the hero and the Pro card so the offer survives AI being renamed.
   Copy only.
3. **Hard gate on ruling #3** before a single engineering hour goes into agency tenancy.
4. **Do not announce an agency product externally** until #3 is answered. Under-promising a
   regulated-money feature is cheap; retracting one is not.

**What I am not doing:** briefing Nisha, scoping build, or touching the marketing site. Nothing in
this memo is executed.

---

## ONE MORE THING, AND IT IS THE REAL FINDING

The Free tier is the actual product. Contracts, payment protection, dispute resolution, invoices
on every payout, the campaign dashboard — operational spine, given away permanently, per your
2026-09-05 ruling. The paid tier is a chat screen.

**An agency is the first customer we have ever found who would pay for the spine.** That is the
strongest argument for this stream — stronger than white-label, stronger than removing AI. If we
build it, build it on the thing that already works, not on the thing we are still fixing.
