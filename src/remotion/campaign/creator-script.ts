/**
 * Script for "How a campaign works, for creators" — the same lifecycle as the
 * brand film, told from the other side of the deal.
 *
 * Brief: Tejas (CMO), 2026-09-07.
 *
 * The creator's anxiety is different from the brand's. A brand worries "will I
 * get the content". A creator worries "will I get paid, and when". So this film
 * spends its money-stages on the two things that answer that:
 *   - the budget is secured BEFORE you start (stage 5)
 *   - approved work lands in Available Balance, which you withdraw (stage 8)
 *
 * Wallet vocabulary is the product's own, from `src/pages/creator-wallet.tsx`:
 * **Available Balance / Secured / Pending Payouts** — with the tooltip
 * definitions at :738, :752 and :764 used almost verbatim, because they are
 * already the clearest statement of the money model anywhere in the product.
 *
 * Application-status words are the real buckets from `src/lib/application-status.ts:51`:
 * Applied / Shortlisted / In negotiation / Active / Completed / Closed.
 */

export interface CreatorScene {
  id: string;
  kicker: string;
  caption: string;
  voice: string;
  seconds: number;
}

export const CREATOR_SCRIPT: CreatorScene[] = [
  {
    id: 'hook',
    kicker: 'Influora for creators',
    caption: 'How a campaign works',
    voice:
      'This is a brand campaign on Influora from a creator’s side: how you find it, how you get picked, and exactly when you get paid.',
    seconds: 8,
  },
  {
    id: 'browse',
    kicker: 'Stage 1 — Find work',
    caption: 'Briefs that fit you',
    voice:
      'Brands post open briefs. You see the budget range, the platforms, the deliverables and the deadline before you spend a minute on it.',
    seconds: 12,
  },
  {
    id: 'apply',
    kicker: 'Stage 2 — Apply',
    caption: 'Apply with a note',
    /**
     * ACCURACY: applying is a message and nothing else — `ApplyRequest` is
     * `record ApplyRequest(@Size(max = 2000) String message)` and
     * `Collaboration.apply()` sets no amount. Deliberately does NOT say "quote
     * your rate here"; that happens on the proposal form at stage 4.
     */
    voice:
      'Applying is one note: why this brand, why you. No quote yet, and it costs you nothing to put your name in.',
    seconds: 11,
  },
  {
    id: 'shortlist',
    kicker: 'Stage 3 — Get picked',
    caption: 'Applied, shortlisted, talking',
    /** ACCURACY: bucket labels are the literal `APPLICATION_BUCKETS` values. */
    voice:
      'Every application you send has a status you can actually see: Applied, Shortlisted, In negotiation. No wondering whether anyone read it.',
    seconds: 12,
  },
  {
    id: 'negotiate',
    kicker: 'Stage 4 — Agree the rate',
    caption: 'Your rate, your deliverables',
    /**
     * ACCURACY: the proposal form really does carry a typed deliverables list
     * (type + qty), an Amount (INR) field and a Deadline — see
     * `src/components/brand/deal-room/proposal-form.tsx:235, :319, :363`.
     */
    voice:
      'This is where money gets discussed. In the Deal Room you put up exactly what you will make, what it costs, and by when. The brand can accept it or counter it.',
    seconds: 14,
  },
  {
    id: 'contract',
    kicker: 'Stage 5 — Contract',
    caption: 'Both sides sign',
    voice:
      'Once you agree, the contract is generated from those terms. You sign, the brand signs, and what was agreed is written down instead of sitting in a chat.',
    seconds: 13,
  },
  {
    id: 'secured',
    kicker: 'Stage 6 — Money secured',
    caption: 'Paid in before you start',
    /**
     * ACCURACY: the "Secured" tooltip in `creator-wallet.tsx:752` reads
     * "Funds a brand has locked for a deal that's still in progress. Not
     * withdrawable yet — moves to Available Balance once you deliver and it's
     * approved." The line below is that sentence, said out loud.
     */
    voice:
      'Now the part that matters. Before you shoot anything, the brand puts the money in and it shows in your wallet as Secured. You cannot withdraw it yet, but you can see it is there.',
    seconds: 15,
  },
  {
    id: 'deliver',
    kicker: 'Stage 7 — Deliver',
    caption: 'Submit for review',
    voice:
      'You make the work and submit each deliverable. If the brand asks for a revision you will see exactly what they want changed.',
    seconds: 11,
  },
  {
    id: 'paid',
    kicker: 'Stage 8 — Get paid',
    caption: 'Approved becomes withdrawable',
    /**
     * ACCURACY: two distinct steps. `EscrowService.release` moves money into the
     * creator's Influora wallet (Available Balance); `PayoutService` is the
     * separate out-of-band RazorpayX push to a real bank/UPI account and is only
     * ever QUEUED here, becoming PROCESSED asynchronously by webhook. Hence
     * "on its way", never "instant".
     */
    voice:
      'When a deliverable is approved, that payment moves from Secured to Available Balance, and it is yours. Withdraw it to your bank or UPI, and it sits in Pending Payouts until it lands.',
    seconds: 16,
  },
  {
    id: 'outro',
    kicker: 'No chasing',
    caption: 'Know when you get paid',
    voice:
      'Find the brief, agree the rate, see the money secured, deliver, get paid. No chasing invoices. Join Influora as a creator today.',
    seconds: 11,
  },
];
