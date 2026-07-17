# Influora — The Business Idea That Stands
> Output of a founder-vs-industry-expert debate (3 rounds), both sides with full access to the repo, docs, and built UI/UX. Date: 2026-07-04.

## One-line
**Influora is an escrow-backed Campaign Operating System for Indian brands and micro-creators — and the Hype Campaign is its flagship growth feature, not the company.**

"Escrow-with-instant-payout is the flagship *company*; Hype is the flagship *feature*."

---

## The Core Business (STANDS)
Brand–creator deals in India happen in DMs, WhatsApp, and Excel. Payments are trust-based; 90% of micro-creators (1K–100K followers) never get paid professionally. Influora fixes this:

- **Brands:** Create campaigns (Open with bidding / Direct Deals), negotiate in a chat-first Deal Room with proposal cards, e-signed contracts, deliverable review (2-revision cap), escrow wallet — funds lock at signing, release on approval.
- **Creators:** Inbox of real, funded opportunities. Transparent net earnings shown upfront. Guaranteed escrow payout. Ratings that compound into a career.
- **Trust rails as moat:** OAuth-verified Instagram accounts, PAN-KYC'd creators, enforced #ad disclosure as an escrow-release condition, TDS-clean payouts (194C/194R computed at payout, quarterly filing). Nobody copies accumulated per-creator performance/fraud data with a WhatsApp broadcast list.

## Revised Fee Model (STANDS WITH CONDITIONS)
The original double-sided 10%+10% (~20% effective extraction) **falls** — killed in debate.

- **Brand-side only: ₹20K flat campaign setup + 15% on the escrow pool.**
- **Creator-side platform fee: ZERO.** Creator sees ₹1,000 gross → ₹900 net (TDS only, line-itemed).
- Conditions: minimum pool floor ₹150K (or minimum blended fee ₹40K); brand retainer layer (₹40–50K/quarter) by month 6; concierge labor kept under 30% of campaign revenue.
- Unit check (300 reels × ₹1,000): ₹65K revenue, ~₹30–35K contribution per campaign.

## The Hype Campaign v2 (STANDS WITH CONDITIONS)
**What it is:** When a big creator drops a reel or a brand launches a product film, the trend window is 48–72 hours. A Hype Campaign pushes a brief (source reel, audio/hashtag, do's/don'ts, flat ₹500–2,000 per reel) to hundreds of matched micro-creators' inboxes. One-tap accept — no negotiation. They create derivative reels in bulk; brand pre-funds the pool in escrow; each approval triggers an instant micro-payout. 100–500 transactions in 72 hours.

**How it survived the debate (v1 → v2):**

| Risk raised by expert | v2 fix |
|---|---|
| Instagram demotes duplicate reels / coordinated-inauthentic-behavior flags | **Derivative amplification, not replication**: creator picks a format lane (reaction, duet/remix, POV, "I tried it", regional-language retelling) with original hook, caption, face/voice. Shared elements = audio + one hashtag only — mimics organic trend formation. Staggered randomized posting cohorts, density caps. |
| 300 reels ≈ 15 human review hours in 72 hrs — fails | AI pre-screen (audio match, brief checklist, disclosure detection, repost-fingerprint fraud check); humans review only flagged ~10–15% (≈3–4 hrs); **48-hr auto-approve SLA or escrow auto-releases**. |
| Bot/repost farms swarm no-negotiation offers | Eligibility gated: Instagram OAuth-verified + PAN-KYC + tier based on prior approved deliverables. |
| ASCI #ad disclosure liability | Approval BLOCKED unless #ad/#collab + paid-partnership tag detected in post metadata. No disclosure, no payout. |
| Unproven against IG's originality classifier | **Gate at ≤100 reels** for first 3 runs; scale to 300 only when median per-reel reach ≥75% of each creator's 30-day baseline with no cohort decay. |

**Why it's the growth engine:** each Hype campaign is a creator-acquisition funnel — a micro-creator's first ₹1,000 from a brand converts them into a KYC'd, verified, rated platform account. Brands come for the viral spike, stay for the OS.

## GTM / Cold Start (STANDS)
Phase 1 = **managed marketplace**: 3–5 concierge campaigns, 50–150 hand-recruited creators via city communities, manual RazorpayX payouts. Campaign #1 pitch: *"Fixed price, fixed pool — guaranteed N approved reels or shortfall refunded from escrow."* Brands buy outcomes, not networks.

## Build Sequence (STANDS)
Honest to current repo state (brand auth, onboarding, campaigns CRUD, discovery live; rest is frontend + mocks):

1. **M1** — Creator auth + Instagram OAuth verification + real Deal Room backend
2. **M2** — Escrow (`escrow_holds`/wallet migrations) + Razorpay Route + contracts
3. **M3** — Deliverable review pipeline + AI pre-screen
4. **M4** — Hype Campaign type — only after 3 concierge campaigns and ~500 verified creators

## Biggest Remaining Risk
**Platform dependency.** Instagram controls the OAuth scopes, paid-partnership metadata, and originality enforcement that both verification and Hype depend on. One API/policy shift invalidates them overnight. Mitigation: build escrow + compliance rails platform-agnostic from M1 — that's the part nobody can take away.

## Debate Scorecard
| Element | Verdict |
|---|---|
| Core OS (escrow, contracts, Deal Room, payouts) | ✅ STANDS |
| Fee model (brand-side ₹20K + 15%) | ⚠️ STANDS WITH CONDITIONS |
| Hype Campaign v2 | ⚠️ STANDS WITH CONDITIONS (gated ≤100 reels until telemetry) |
| GTM / concierge cold start | ✅ STANDS |
| Build sequence M1→M4 | ✅ STANDS |
| Double-sided 20% fee (original) | ❌ FALLS |
| "Algorithm hijack" via identical bulk reels (original) | ❌ FALLS — replaced by derivative amplification |
