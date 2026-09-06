/**
 * F-0432 — dropped-field-at-call-site, `brand-campaign-detail.tsx` + `creator-chat.tsx` halves.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The finding as filed reads: "of the four screens that counter, only brand chat maps a real
 * [usageRights] value; the others send nothing." `brand-chat.tsx:1602` is confirmed correct
 * (`usageRights: data.usageRightsDuration.replace(/-/g, ' ')`, sourced from `ProposalForm`'s
 * duration picker). `deal-room-dashboard.tsx:479` is a different agent's file this round
 * (F-0440) and is untouched here.
 *
 * For the two sites this file covers, the honest finding is narrower than "dropped": neither
 * screen's counter dialog collects a usage-rights value at all.
 *   - `brand-campaign-detail.tsx`'s Counter dialog has exactly two inputs — amount and a
 *     free-text message. Neither `CampaignBid` nor `DetailCampaignView` carries a usage-rights
 *     field either, so there is nothing on this page to map.
 *   - `creator-chat.tsx`'s `CounterProposalForm` has an amount, a deadline, a free-text
 *     "message", and a free-text "Any Changes to Terms?" field whose own placeholder ("max 2
 *     revisions, 30-day exclusive usage, etc.") shows it is a general catch-all, not a
 *     usage-rights value specifically.
 * Synthesizing a `usageRights` string from either dialog's free text would not restore a dropped
 * field — it would fabricate one, silently overwriting the deal's actual usage-rights term with
 * prose that was never meant to mean that (TECH-STACK.md rule 7 — never fabricate). Both call
 * sites now build their `POST /deals/:id/counter` body through an exported pure function
 * (`buildCounterOfferBody`) precisely so this omission is documented and pinned by a test,
 * instead of being silently unexplained — which is what let this get re-filed as a plain
 * "dropped field" bug in the first place.
 *
 * FALSIFICATION
 * -------------
 * Every test below fails for the right reason against the pre-fix shape of its call site: temporarily
 * changing either `buildCounterOfferBody` to smuggle its free-text field into `usageRights` (the
 * exact wrong "fix" the doc comments at both call sites warn against) turns every `not.toHaveProperty
 * ('usageRights')` assertion here red. See the task report for the quoted failure.
 *
 * Run: npx vitest run src/pages/f0432-counter-usage-rights.test.ts
 */

import { describe, it, expect } from 'vitest';
import { buildCounterOfferBody as buildBrandCampaignCounterBody } from './brand-campaign-detail';
import { buildCounterOfferBody as buildCreatorCounterBody } from './creator-chat';
import type { CounterProposalFormData } from '@/components/creator/deal-room/counter-proposal-form';

describe('brand-campaign-detail.tsx — buildCounterOfferBody (F-0432)', () => {
  it('never sends usageRights — this dialog collects no usage-rights input', () => {
    const body = buildBrandCampaignCounterBody(45000, 'Can we do 3 reels instead of 2?');
    expect(body).not.toHaveProperty('usageRights');
    expect(body).toEqual({ amount: 45000, message: 'Can we do 3 reels instead of 2?' });
  });

  it('does not repurpose the free-text message as usageRights even when it reads like a rights ask', () => {
    // The exact trap the fabrication would fall into: this message is ABOUT usage, but it is
    // still the brand's counter-offer note, not a usage-rights duration/term.
    const body = buildBrandCampaignCounterBody(30000, 'Can we get exclusive usage rights for 12 months?');
    expect(body).not.toHaveProperty('usageRights');
    expect(body.message).toBe('Can we get exclusive usage rights for 12 months?');
  });

  it('omits message entirely when blank, rather than sending an empty string', () => {
    const body = buildBrandCampaignCounterBody(45000, '   ');
    expect(body).toEqual({ amount: 45000, message: undefined });
  });
});

describe('creator-chat.tsx — buildCounterOfferBody (F-0432)', () => {
  const baseData: CounterProposalFormData = {
    proposedAmount: 60000,
    deadline: '2026-10-01',
    terms: 'max 2 revisions, 30-day exclusive usage',
    message: 'Happy to take this on at this rate.',
  };

  it('never sends usageRights, even when `terms` reads like a usage-rights ask', () => {
    const body = buildCreatorCounterBody(baseData);
    expect(body).not.toHaveProperty('usageRights');
  });

  it('sends deadline as a real CounterRequest field, not folded into prose', () => {
    const body = buildCreatorCounterBody(baseData);
    expect(body.deadline).toBe('2026-10-01');
  });

  it('folds terms into the message instead of usageRights, prefixed so it reads as a quoted term', () => {
    const body = buildCreatorCounterBody(baseData);
    expect(body.message).toBe(
      'Happy to take this on at this rate.\n\nTerms: max 2 revisions, 30-day exclusive usage',
    );
  });

  it('omits deadline/message rather than sending empty strings when the form leaves them blank', () => {
    const body = buildCreatorCounterBody({
      proposedAmount: 60000,
      deadline: '',
      terms: '',
      message: '',
    });
    expect(body).toEqual({ amount: 60000, message: undefined, deadline: undefined });
    expect(body).not.toHaveProperty('usageRights');
  });
});
