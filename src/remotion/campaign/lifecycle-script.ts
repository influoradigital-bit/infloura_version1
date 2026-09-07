/**
 * Script for "How an Influora campaign works, end to end" — the full brand
 * lifecycle, from creating the campaign to reading the numbers.
 *
 * Brief: Tejas (CMO), 2026-09-06. This is the companion to `CampaignDemo`,
 * which is the deep-dive on step one only. Here creation is compressed to a
 * montage so the runtime goes to the stages nobody has seen on video.
 *
 * The spine is the product's own vocabulary, not invented marketing stages —
 * the Deal Room's five phases at
 * `src/components/brand/deal-room/deal-room-step-progress.tsx:6`:
 *   Negotiate → Contract → Secure funds → Deliver → Pay
 *
 * Accuracy rules applied throughout (see ACCURACY notes per scene):
 *  - "escrow" is banned in brand-facing copy; the product says "Secure funds".
 *  - Campaign analytics are CREATOR-REPORTED, never platform-measured, and the
 *    video says so out loud.
 */

export interface LifecycleScene {
  id: string;
  kicker: string;
  caption: string;
  voice: string;
  seconds: number;
}

export const LIFECYCLE_SCRIPT: LifecycleScene[] = [
  {
    id: 'hook',
    kicker: 'Influora for brands',
    caption: 'How a campaign works',
    voice:
      'This is a full Influora campaign, end to end: from writing the brief to paying the creator and reading the numbers.',
    seconds: 7,
  },
  {
    id: 'create',
    kicker: 'Stage 1 — Create',
    caption: 'Brief in five steps',
    voice:
      'It starts with the brief. Five steps: Basics, Content, Budget, Requirements, and Review. Then you publish, and the campaign is live.',
    seconds: 12,
  },
  {
    id: 'bids',
    kicker: 'Stage 2 — Applications',
    caption: 'Creators apply to you',
    /**
     * ACCURACY [corrected 2026-09-07]: an application carries a MESSAGE ONLY.
     * `ApplyRequest` is `record ApplyRequest(String message)` and
     * `Collaboration.apply()` sets no amount — so there is no quote, no timeline
     * and no deliverable breakdown at this stage. `matchScore` is mock-only:
     * `dealToBidView` never sets it and the badge only renders at >= 90.
     * The rate is agreed later, on the proposal form (stage 3).
     */
    voice:
      'Creators find your brief and apply, with a note on why they are a fit. You read the pitches and shortlist the ones you want to talk to.',
    seconds: 11,
  },
  {
    id: 'accept',
    kicker: 'Stage 3 — Negotiate',
    caption: 'Shortlist, counter, accept',
    /**
     * ACCURACY: all three controls are real and wired — `api.deals.accept`,
     * `api.deals.reject` and `api.deals.counter` in
     * `src/pages/brand-campaign-detail.tsx:758, :781, :807`.
     */
    voice:
      'Open an application to read their pitch. You can shortlist it, send a counter offer, or accept the quote as it stands.',
    seconds: 12,
  },
  {
    id: 'contract',
    kicker: 'Stage 4 — Contract',
    caption: 'Both sides sign',
    /** ACCURACY: two-party e-sign is real (Contract entity + signature progress UI). */
    voice:
      'Accepting generates the contract. The scope, the fee and the milestones are written into it, and both sides sign before any work starts.',
    seconds: 12,
  },
  {
    id: 'securefunds',
    kicker: 'Stage 5 — Secure funds',
    caption: 'Money in before work starts',
    voice:
      'Now you secure the funds. The money leaves your wallet and is held against the campaign, so the creator knows the budget is real and you know it is not paid out yet.',
    seconds: 14,
  },
  {
    id: 'deliver',
    kicker: 'Stage 6 — Deliver',
    caption: 'Creator submits the work',
    voice:
      'The creator does the work and submits each deliverable for review. You see it before it goes anywhere near their audience.',
    seconds: 11,
  },
  {
    id: 'approve',
    kicker: 'Stage 7 — Approve and pay',
    caption: 'Approve releases the payment',
    /**
     * ACCURACY: verified in `BrandDeliverableService.approve()` — it calls
     * `EscrowService.tryReleaseOnApproval` in the same transaction ("[B3] fix").
     * The release is gated on that milestone's release_condition, so the copy
     * says the payment for that deliverable, not the whole budget.
     */
    voice:
      'Approve it, and the payment for that deliverable is released to the creator. Ask for a revision instead, and nothing is paid until you are happy.',
    seconds: 13,
  },
  {
    id: 'analytics',
    kicker: 'Stage 8 — Results',
    caption: 'Reported reach, measured sales',
    /**
     * ACCURACY — the most important line in this video. `CampaignAnalytics.source`
     * is always "CREATOR_REPORTED" (`src/lib/api.ts:1838`), and there is no
     * impression tracking anywhere in the system. Tracking links and coupons are
     * separately real and genuinely measured (clicks, conversions, revenue).
     * The script keeps those two apart on purpose.
     */
    voice:
      'Once it is posted, the creator reports the reach and engagement from their own account. For sales you do not have to take anyone’s word: tracking links and coupon codes measure the clicks, the conversions and the revenue themselves.',
    seconds: 17,
  },
  {
    id: 'outro',
    kicker: 'Start to finish',
    caption: 'Brief to paid, in one place',
    voice:
      'Brief, applications, contract, secured funds, delivery, payment and results. One campaign, one place. Start yours on Influora today.',
    seconds: 11,
  },
];
