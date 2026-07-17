# INFLUORA — Feature Gap Analysis (Brand ↔ Influencer)

> ⚠️ **SUPERSEDED 2026-07-14 (Priya) — do not act on the Priority Matrix or "P0" calls below.**
> A1, A2, A5, B2, B6 are now shipped. A3/A4/B5 are in motion under `SUBSCRIPTION-BILLING-PLAN.md`
> (A3 ~15% built, not usable yet). A6/B4/B7 are partially shipped. A7/B1/B3 are still open but
> with new findings (A7 is a live legal-risk data-drop bug; B1 has an orphan frontend stub; B3 is
> blocked on a CEO ruling + a consent gap). **Current source of truth:**
> `wiki/tech/REMAINING-FEATURES-2026-07-13.md`. This file is kept as the historical record of the
> original 2026-07-07 audit — read it for context, act on the doc above.

> **Author:** Priya (CTO) + Tejas (CMO)  
> **For:** Swapnil (CEO) — business decisions required  
> **Date:** 2026-07-07  
> **Method:** Full-context audit of `influora-api` entities/controllers + all `wiki/tech` specs

---

## How to read this

Everything below is **NOT in the codebase and NOT in any spec** (verified — no entity, no controller, no service). Each gap is tagged:

- 🔴 **BLOCKER** — the marketplace/business model does not work without it
- 🟠 **COMPETITIVE** — competitors have it; we lose deals without it
- 🟡 **GROWTH** — drives acquisition/retention, not launch-blocking

Business-model decisions are flagged **→ SWAPNIL** because they're not mine to make.

---

## PART A — Priya (CTO): Structural Gaps

### A1. 🔴 Reviews & Ratings (two-sided trust)
**Status:** No `Review` / `Rating` entity exists. Zero implementation.

Every functioning marketplace (Upwork, Fiverr, Aspire, Grin) runs on two-sided reviews. Without it:
- Brands can't judge an unknown creator's reliability → they won't book.
- Creators can't avoid bad-paying brands → they churn.
- We have no trust signal to rank discovery on.

**Needs:** `Review` entity (rater, ratee, collaboration_id, stars 1-5, text, role), post-collaboration prompt, aggregate score on `CreatorProfile` and `BrandProfile`, moderation, and "can only review after a completed collaboration" gate (prevents fake reviews).

### A2. 🔴 Disputes & Refunds (escrow needs an exit)
**Status:** Only a `CollaborationStatus` enum value mentions dispute. No `Dispute` entity, no resolution workflow, no refund path.

We hold brand money in **escrow**. What happens when the creator ghosts, or the brand refuses to approve valid work? Right now: the money is stuck with no defined process. This is a legal and trust liability the moment real money flows.

**Needs:** `Dispute` entity (raised_by, collaboration, reason, evidence, status), resolution workflow (mediation → decision), refund path back through Razorpay, and an admin/ops resolution console. Kabir must review — this touches escrow release.

### A3. 🔴 Brand SaaS Billing / Subscription Engine
**Status:** No `Subscription`, `Plan`, or `Invoice` entity. **The entire revenue model has no engine.**

Rohan's pricing (Starter/Pro/Enterprise) is defined in strategy but **nothing charges the brand**. There is no recurring billing, no plan gating, no seat metering, no invoice generation. Today a brand could use everything for free forever.

**→ SWAPNIL decision:** confirm the pricing tiers and what each gates (tracked creators, API calls, exports, seats). Then Vikram builds: `Subscription` + `Plan` + `Invoice` entities, Razorpay subscription integration, plan-gate middleware, usage metering, dunning (failed-payment retries).

### A4. 🟠 Report Export (CSV / PDF)
**Status:** `export` appears in 0 files. Analytics render on screen only.

Agencies live and die by client reports. If a brand can't export a campaign ROI deck as PDF/CSV, they'll screenshot our dashboard into their own report — and we become invisible to their client. Export is where our brand shows up in the boardroom.

**Needs:** server-side PDF/CSV generation for campaign performance, creator comparison, and conversion reports; branded templates; scheduled email delivery.

### A5. 🟠 Real-time Messaging Maturity
**Status:** `AiConversation`/`AiMessage` exist (Meera chat) but there is **no brand↔creator direct message thread entity**. Spec `08_CREATOR_CHAT` describes it; backend doesn't have it yet.

Negotiation and briefing happen in DMs today (WhatsApp/email), off-platform — which means we lose the relationship and the data. We need first-class brand↔creator threads tied to a collaboration.

**Needs:** `MessageThread` + `Message` entities, attachments, read receipts, notifications, and off-platform-contact discouragement (keep the relationship on Influora).

### A6. 🟡 Notification Preferences & Digest
**Status:** `Notification` entity exists; no per-channel preference model, no digest batching.

We'll spam users into muting us. Need per-event channel prefs (email/push/SMS/in-app) and a daily/weekly digest option.

### A7. 🟡 Content Rights / Usage Licensing
**Status:** `Contract` exists but has no structured **content usage rights** (can the brand repost the creator's content? for how long? paid ads?).

This is a frequent real-world dispute source. Structure it in the contract now, cheaply, rather than litigate it later.

---

## PART B — Tejas (CMO): Growth & Market Gaps

> Priya asked me to look at this through a go-to-market lens. My job is asking "what makes brands sign up, stay, and tell others?" Here's what's missing to actually *sell* this.

### B1. 🟠 Referral / Invite Program (both sides)
No referral engine exists. In the creator economy, **creators recruit creators** and **agencies recruit brands** — it's the cheapest acquisition channel we have and we're leaving it on the table. Creator invites creator → both get a perk. Brand refers brand → credit. This is a `ReferralCode` + reward-ledger feature, and it compounds. **This is my #1 growth ask.**

### B2. 🟠 Public Creator Marketplace + SEO Landing Pages
Our creator profiles are behind login. Every competitor has **public, indexable** creator/category pages ("top fitness influencers in Mumbai") that pull organic search traffic. Aditya (SEO) and I need public profile pages with structured data (schema.org/Person), category hubs, and city pages. This is free top-of-funnel traffic we're currently getting zero of. Pairs with A1 (reviews = the content that makes those pages rank).

### B3. 🟠 Social Proof & Case Studies Surface
No place for testimonials, logos, or campaign success stories. B2B brands buy on proof. We need a `CaseStudy`/testimonial surface and "brands who found creators here" logo wall. Nisha (content) + Ishaan write them; we need the CMS hooks.

### B4. 🟡 Lifecycle Email / Re-engagement
`EmailOutbox` exists for transactional mail, but there's **no marketing lifecycle**: welcome series, "3 new campaigns match you" nudges, dormant-creator win-back, brand trial-expiry nudges. This is retention revenue. Needs a segmentation + campaign layer (or an ESP integration — Rohan to price).

### B5. 🟡 Campaign Templates & "Post a Campaign in 2 min"
Brands stall on a blank campaign form. Pre-built templates by goal (awareness / sales / UGC / affiliate) dramatically lift campaign-creation conversion. Cheap to build, big funnel win.

### B6. 🟡 Verified Badge / Trust Marks
A visible "Verified Creator" / "Verified Brand" badge (tied to KYC we already collect) is both a trust signal AND a marketing hook creators will show off. We collect the KYC — we're just not surfacing the badge.

### B7. 🟡 In-app Onboarding Nudges / Empty States that Sell
First-session activation is everything. Empty dashboards should *teach and convert*, not sit blank. Tejas + Ananya: design activation checklists ("Connect Instagram ✓ / Complete profile ✓ / Apply to first campaign").

---

## Priority Matrix

| # | Gap | Type | Owner | Effort | Priority |
|---|-----|------|-------|--------|----------|
| A1 | Reviews & Ratings | 🔴 Blocker | Vikram+Ananya | M | **P0** |
| A2 | Disputes & Refunds | 🔴 Blocker | Vikram+Kabir | L | **P0** |
| A3 | Brand SaaS Billing | 🔴 Blocker | Vikram | L | **P0 → Swapnil** |
| A4 | Report Export | 🟠 Comp | Vikram+Ananya | M | P1 |
| A5 | Brand↔Creator Messaging | 🟠 Comp | Vikram+Ananya | M | P1 |
| B1 | Referral Program | 🟠 Growth | Vikram+Tejas | M | P1 |
| B2 | Public SEO Pages | 🟠 Growth | Ananya+Aditya | M | P1 |
| A6 | Notification Prefs | 🟡 | Vikram | S | P2 |
| A7 | Content Rights in Contract | 🟡 | Vikram | S | P2 |
| B3 | Social Proof/Case Studies | 🟡 Growth | Nisha+Ananya | S | P2 |
| B4 | Lifecycle Email | 🟡 Growth | Tejas+Vikram | M | P2 |
| B5 | Campaign Templates | 🟡 Growth | Ananya | S | P2 |
| B6 | Verified Badges | 🟡 Growth | Ananya | S | P2 |
| B7 | Activation Empty States | 🟡 Growth | Tejas+Ananya | S | P2 |

*(Effort: S = <1wk, M = 1-2wk, L = 3+wk)*

---

## Priya's Recommendation

**Do the three P0 blockers before any more feature-width.** In order:

1. **Reviews (A1)** — unblocks trust and powers B2's SEO pages. Do first.
2. **Disputes/Refunds (A2)** — the escrow model is a liability without it. Non-negotiable before real money scales.
3. **Billing (A3)** — we cannot collect revenue without it. But it needs a Swapnil pricing decision first, so it can run in parallel once that's locked.

Everything in Part B is real growth upside, but **growth features amplify a working core — they don't substitute for one.** No point driving SEO traffic (B2) to a marketplace with no reviews (A1) and no way to pay us (A3).

---

## → Decisions needed from Swapnil

1. **Confirm SaaS pricing tiers** (Starter/Pro/Enterprise) and exactly what each gates → unblocks A3.
2. **Dispute resolution policy** — who is the final arbiter (ops team? automated rules?) and what's the refund policy → unblocks A2.
3. **Referral reward economics** (B1) — Rohan models cost, but you set whether we spend on it.
4. **Approve P0-first sequencing** above, or reprioritize.

Once you rule on 1–2, Vikram and I will write the detailed specs the same way we did the creator flow.
