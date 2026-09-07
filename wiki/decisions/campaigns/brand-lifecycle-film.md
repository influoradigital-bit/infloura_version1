# Campaign brief — campaign lifecycle films (brand + creator)

**Owner:** Tejas (CMO) · **Written:** 2026-09-06 · **Status:** built, awaiting Swapnil sign-off

## Why this exists

Every brand conversation stalls at the same question: *"Fine, I post a brief — then what?"*
We had a 94.5s film covering step one (`CampaignDemo`) and nothing covering the other seven
stages. This is that film.

## The asset set

| Film | Composition | Length | Use |
|---|---|---|---|
| Creation deep-dive | `CampaignDemo` | 94.5s | Onboarding, help centre, "how do I post a brief" |
| **Brand lifecycle** | **`CampaignLifecycle`** | **137s (2:17)** | **Homepage, sales deck, first sales call, /brand/how-it-works** |
| **Creator lifecycle** | **`CreatorLifecycle`** | **140s (2:20)** | **Creator acquisition, /creator/how-it-works, creator onboarding email** |

Render either with `npx remotion render <id> _export/<name>.mp4`.

## Positioning

**Core message:** *Brief to paid, in one place — with the money held safely in the middle.*

The competitive wedge is not discovery, it is **the middle of the deal**. Agencies and DMs can
find creators. What nobody does cleanly is contract → secure funds → review → release payment
→ prove results. That is stages 4 through 8, and it is where two-thirds of this film's runtime goes.

**Structural decision:** creation is compressed to a 14s montage. We already have a dedicated
film for it, and the whole point of this one is the part buyers have never seen.

## The stage spine

I did not invent marketing stage names. The video uses the product's own Deal Room phases from
`src/components/brand/deal-room/deal-room-step-progress.tsx:6`:

> **Negotiate → Contract → Secure funds → Deliver → Pay**

Two benefits: the video matches what a brand sees the day they sign up, and "Secure funds" is
already the product's word, so we stay clear of the banned "escrow" vocabulary for free.

## Scene list

| # | Stage | What it shows |
|---|---|---|
| 1 | Hook | The whole arc in one line |
| 2 | Create | Five-step brief, compressed, → live |
| 3 | Applications | 4 creators applied: name, their note, bucket status (see the 2026-09-07 correction below) |
| 4 | Negotiate | Read the pitch → Shortlist / Send counter offer / Accept quote |
| 5 | Contract | Terms + two-party signature progress → executed |
| 6 | Secure funds | ₹28,000 moves wallet → secured, held not paid |
| 7 | Deliver | Creator submits; status `SUBMITTED`, awaiting review |
| 8 | Approve and pay | Approve → ₹7,000 released, 1 of 4, ₹21,000 still secured |
| 9 | Results | Creator-reported reach *beside* measured sales |
| 10 | Outro | Full arc + CTA |

## Claims discipline — read before anyone edits copy

Three things in this flow are easy to overclaim. All three are handled deliberately:

1. **Analytics are not platform-measured.** `CampaignAnalytics.source` is hardcoded
   `"CREATOR_REPORTED"` (`src/lib/api.ts:1838`) and there is no impression tracking anywhere in
   the system. So stage 9 puts a **CREATOR-REPORTED** badge on reach/impressions/engagement and a
   separate **MEASURED** badge on tracking-link clicks, conversions and revenue, which genuinely
   are counted by us. Do not merge those two panels into one "analytics dashboard" — the honesty
   of that split *is* the trust message, and it is a differentiator, not an apology.
2. **Approval really does release money.** Verified: `BrandDeliverableService.approve()` calls
   `EscrowService.tryReleaseOnApproval` in the same transaction. The copy says the payment for
   *that deliverable*, not the whole budget, because the release is gated per-milestone.
3. **Publishing does not secure funds.** Securing happens at the deal stage, which is why it is
   stage 6 here and not part of stage 2. The earlier creation film had this wrong in draft and it
   was corrected.

## Open items for Swapnil

- The film shows the happy path. No dispute stage, though `CollaborationStatus.DISPUTED` exists.
  Deliberate — a 2-minute sales asset should not tour the unhappy path. Flagging it as a choice.
- Sample numbers (₹28,000 fee, ₹4,86,200 revenue) are illustrative and invented.
  If we want a real case study, that needs a real customer's permission.
- Both films are silent-safe but narrated; nothing depends on sound being on except the VO itself.
  Captions carry the stage names. Subtitles are not burned in — needed before paid social.


---

# Addendum — creator film + a correction (2026-09-07)

## Correction to the brand film, stage 2

The first cut of the applications scene showed a **quote, a timeline and a match
percentage on every application**. All three were wrong:

- `ApplyRequest` is `record ApplyRequest(@Size(max = 2000) String message)` and
  `Collaboration.apply()` sets no amount. **An application is a message and nothing else.**
  The rate is agreed later, on the proposal form.
- `matchScore` exists only on the mock fixtures. The live `dealToBidView` never sets it,
  and the badge is gated on `>= 90` even when present.
- Timeline is mock-only too.

The scene now shows name, handle, their note and the bucket status. The narration was
rewritten and the clip regenerated. **Do not re-add a price to the applications list** —
it is the single most tempting wrong detail in this whole flow, because the mock fixture
in `brand-campaign-detail.tsx` is full of them.

This is worth internalising beyond this one fix: that page seeds `React.useState(mockBids)`
and only swaps in live data after the fetch. Reading the fixture and assuming it is the
product is exactly how this got through.

## The creator film

Same deal, other chair: Ritika applying to Bloomveda's Diwali campaign, so the two films
can be watched back to back and agree with each other.

**Core message:** *Know when you get paid.* A brand's fear is "will I get the content".
A creator's is "will I get paid, and when". So the runtime concentrates on the two
moments that answer it: the money is secured **before** they start (stage 6), and approved
work becomes withdrawable (stage 8).

| # | Stage | What it shows |
|---|---|---|
| 1 | Find work | Open briefs with budget, platforms, deliverables up front |
| 2 | Apply | One note. "Free to apply — no quote needed yet" |
| 3 | Get picked | Applied → Shortlisted → In negotiation, visibly |
| 4 | Agree the rate | Proposal: typed deliverables, Amount (INR), deadline |
| 5 | Contract | Creator signs, brand signs |
| 6 | Money secured | ₹28,000 appears as **Secured** in the wallet before any shooting |
| 7 | Deliver | Submit; revisions are explicit |
| 8 | Get paid | Approved → **Available Balance** → withdraw → **Pending Payouts** |

**Wallet vocabulary is the product's own** — Available Balance / Secured / Pending Payouts
(`creator-wallet.tsx:738/:752/:764`), and the tooltip definitions there are used almost
verbatim because they are already the clearest statement of the money model we have.

### Two things the creator film is careful about

1. **Payout is not instant and the film does not say it is.** The escrow release lands in the
   creator's *Influora wallet*; the bank transfer is a separate RazorpayX payout that
   `PayoutService` only ever **QUEUES**, confirmed later by webhook. Hence "sits in Pending
   Payouts until it lands".
2. **Available Balance goes back down when a withdrawal is requested**, because Pending
   Payouts is money already deducted from it. The first cut showed both at once and
   double-counted ₹7,000.

## Open items for Swapnil (unchanged, plus one)

- Invented sample numbers; happy path only; no burned-in subtitles.
- **New:** the creator film shows a bank withdrawal to "HDFC ••4417". Creator payouts depend on
  RazorpayX plus creator bank/KYC records. Before this goes in front of creators, someone should
  confirm the live payout path actually works end to end — the film promises a withdrawal button
  that has never been proven on production.
