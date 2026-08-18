/**
 * .proof-os/gates/F-0243.copy-agrees.spec.tsx
 *
 * Execution leg for gates/F-0243-verification-copy-agrees.sh.
 *
 * origin failure F-0243 (contradictory-gating-copy): on /brand/campaigns/new the banner said
 * verification was required to launch while the KYC prompt on the same screen called it
 * "optional — it won't block your campaign". A first-run brand could not tell whether they were
 * allowed to publish.
 *
 * Backend truth this measures against: CampaignValidator.validateStatusForWorkspace never blocks
 * a DRAFT and always blocks ACTIVE for an unverified workspace, and Workspace.applyKycDecision
 * does NOT auto-publish drafts on approval.
 *
 * WHY THIS FILE EXISTS, twice over.
 *
 *  1. The gate used to forbid one sentence: `grep -q "won.t block your campaign"`. That `.`
 *     stands in for an apostrophe, and in a BRE/ERE a bare `.` matches ONE BYTE. U+2019 (the
 *     curly apostrophe this codebase's copy actually uses everywhere) is three bytes, so the
 *     pattern silently only ever matched the ASCII form. Observed, back to back, on the same
 *     injected sentence differing only in that character: curly → exit 0 "VERDICT: aligned",
 *     ASCII → exit 1. Recorded in .proof-os/tasks/T-F0329-GATES/F-0243.inject.log.
 *
 *  2. Even with the apostrophe fixed, forbidding a sentence is the wrong assertion for a
 *     copy-AGREEMENT record. Any rewording of the contradiction escapes it, and any rewording
 *     of the honest copy false-reds it.
 *
 * WHAT IS ASSERTED INSTEAD. Both surfaces are RENDERED, their prose (controls removed) is
 * normalised — typographic apostrophes and dashes folded to ASCII, whitespace collapsed — and
 * each is classified for the stance it takes on one question: does verification stand between
 * this brand and publishing? The surfaces are then required to hold the SAME position, and that
 * position is required to be the backend's. Reword either side however you like; take opposite
 * positions and this fails.
 *
 * The classifier is run over a frozen table of known-bad and known-good copy first, so each run
 * proves it can both fail and pass before its verdict on the real components is believed.
 */
import * as React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const hook = vi.hoisted(() => ({
  value: {
    status: 'UNVERIFIED' as string | null,
    isLoading: false,
    isVerified: false,
    roleResolved: true,
    roleError: false,
    retryRole: () => {},
    canVerify: true,
  },
}));

vi.mock('@/hooks/brand/useWorkspaceVerification', () => ({
  useWorkspaceVerification: () => hook.value,
}));

// The KYC prompt fetches onboarding status when the API is live; in this suite it is not, and
// the fetch is not what is under test.
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, isApiLive: () => false };
});

import { WorkspaceVerificationBanner } from '@/components/brand/WorkspaceVerificationBanner';
import { BrandKycPrompt } from '@/components/brand/campaigns/brand-kyc-prompt';

/* ------------------------------------------------------------------------- *
 * the classifier
 * ------------------------------------------------------------------------- */

/**
 * Fold the typography. U+2019/U+2018/U+02BC all read as apostrophes to a human and none of them
 * are U+0027; em/en dashes likewise. Every pattern below is written in ASCII and every input is
 * normalised first, so a curly apostrophe can never again be the difference between a gate that
 * fires and a gate that does not.
 */
export function normalise(s: string): string {
  return s
    .replace(/[‘’ʼ՚′`´]/g, "'")
    .replace(/[–—−]/g, '-')
    .replace(/[   ]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** "verification stands between you and publishing" */
const BLOCKS_PUBLISH: RegExp[] = [
  /required to (publish|launch|go live)/i,
  /(needs?|need) to be verified to (publish|launch|go live)/i,
  /verified to (publish|launch|go live)/i,
  /before you can (publish|launch|go live)/i,
  /(need|have) to verify before/i,
  /verify (before|to) [^.]{0,40}(publish|launch|go live)/i,
  /(publishing|launching|going live) (needs|requires|is blocked)/i,
  /(needs?|requires?) a verified workspace/i,
  /verify (your )?(workspace|business) to (launch|publish|go live)/i,
  /(then|once .{0,30}) you can publish/i,
  /(resubmit|fix .{0,40}) to start publishing/i,
];

/** "verification does not stand between you and publishing" — the F-0243 claim */
const NOT_BLOCKING: RegExp[] = [
  /\boptional\b/i,
  /(won't|will not|doesn't|does not|never) blocks? (your|the|this|a |any )?(campaign|publish|launch|going live)/i,
  /not required (to|for) (publish|launch|creat|campaign|go live)/i,
  /no (verification|kyc) (is )?(needed|required)/i,
  /you can publish (without|before) (verif|being verified)/i,
];

/** "approval publishes your drafts by itself" — applyKycDecision does not do that */
const AUTO_PUBLISH: RegExp[] = [
  /go live the moment/i,
  /publish(es)? automatically/i,
  /automatically (publish|go live)/i,
  /publish as soon as it clears/i,
  /we'?ll publish (them|your drafts|it)/i,
];

export type Stance = { blocks: string[]; notBlocking: string[]; autoPublish: string[] };

function hits(text: string, pats: RegExp[]): string[] {
  const out: string[] = [];
  for (const p of pats) {
    const m = p.exec(text);
    if (m) out.push(m[0]);
  }
  return out;
}

export function stanceOf(rawText: string): Stance {
  const t = normalise(rawText);
  return {
    blocks: hits(t, BLOCKS_PUBLISH),
    notBlocking: hits(t, NOT_BLOCKING),
    autoPublish: hits(t, AUTO_PUBLISH),
  };
}

type Surface = { name: string; text: string };

/** The whole point of the record: two surfaces on one screen, one position between them. */
export function disagreements(surfaces: Surface[]): string[] {
  const problems: string[] = [];
  const saysBlocks = surfaces.filter((s) => stanceOf(s.text).blocks.length > 0);
  const saysNot = surfaces.filter((s) => stanceOf(s.text).notBlocking.length > 0);

  for (const s of surfaces) {
    const st = stanceOf(s.text);
    if (st.notBlocking.length) {
      problems.push(
        `${s.name} says verification does NOT gate publishing (${JSON.stringify(st.notBlocking)}) — ` +
          `the backend blocks ACTIVE for an unverified workspace, so this is false on its own ` +
          `terms, before any comparison`,
      );
    }
    if (st.autoPublish.length) {
      problems.push(
        `${s.name} says approval publishes drafts by itself (${JSON.stringify(st.autoPublish)}) — ` +
          `Workspace.applyKycDecision does not auto-publish`,
      );
    }
  }
  if (saysBlocks.length && saysNot.length) {
    problems.push(
      `the two surfaces contradict each other on the same screen: ` +
        `[${saysBlocks.map((s) => s.name).join(', ')}] say verification gates publishing while ` +
        `[${saysNot.map((s) => s.name).join(', ')}] say it does not — this is F-0243 itself`,
    );
  }
  if (!saysBlocks.length) {
    problems.push(
      `no surface on this screen states that verification gates publishing (${surfaces
        .map((s) => s.name)
        .join(' + ')}) — a first-run brand is left to find out by having a publish rejected`,
    );
  }
  return problems;
}

/* ------------------------------------------------------------------------- *
 * rendering
 * ------------------------------------------------------------------------- */

function proseOf(container: HTMLElement): string {
  const clone = container.cloneNode(true) as HTMLElement;
  clone.querySelectorAll('button, a, input, label').forEach((n) => n.remove());
  return clone.textContent ?? '';
}

function bannerProse(status: 'UNVERIFIED' | 'PENDING' | 'REJECTED'): string {
  hook.value = { ...hook.value, status, isVerified: false, isLoading: false };
  const { container } = render(
    <MemoryRouter initialEntries={['/brand/campaigns/new']}>
      <WorkspaceVerificationBanner />
    </MemoryRouter>,
  );
  return proseOf(container);
}

function kycProse(): string {
  const { container } = render(
    <MemoryRouter initialEntries={['/brand/campaigns/new']}>
      <BrandKycPrompt />
    </MemoryRouter>,
  );
  return proseOf(container);
}

/* ------------------------------------------------------------------------- *
 * frozen tables — the classifier's own falsification
 * ------------------------------------------------------------------------- */

const KNOWN_BAD: { why: string; surfaces: Surface[] }[] = [
  {
    why: 'F-0243 verbatim',
    surfaces: [
      { name: 'banner', text: 'Verify your workspace to launch campaigns. Start verification.' },
      {
        name: 'kyc prompt',
        text:
          "Verify your business - optional. Add your GSTIN & PAN to build creator trust. " +
          "It's optional - it won't block your campaign.",
      },
    ],
  },
  {
    why: 'F-0243 with a TYPOGRAPHIC apostrophe — the exact copy the old gate greened (observed)',
    surfaces: [
      { name: 'banner', text: 'Verify your workspace to launch campaigns.' },
      {
        name: 'kyc prompt',
        text:
          'Verify your business · required to publish. Add your GSTIN & PAN to build creator ' +
          'trust and speed up payouts. This is optional — it won’t block your campaign.',
      },
    ],
  },
  {
    why: 'the third position — "not required" phrased as reassurance',
    surfaces: [
      { name: 'banner', text: 'Verify your workspace to launch campaigns.' },
      { name: 'kyc prompt', text: 'Business verification is not required to create campaigns.' },
    ],
  },
  {
    why: 'a promise the backend does not keep — approval auto-publishes drafts',
    surfaces: [
      {
        name: 'banner',
        text: "Verification in review. Your drafts will go live the moment we approve you.",
      },
      { name: 'kyc prompt', text: 'Verify your business - required to publish.' },
    ],
  },
  {
    why: 'both surfaces silent — nothing false said, and nothing useful either',
    surfaces: [
      { name: 'banner', text: 'Verification in review.' },
      { name: 'kyc prompt', text: 'Add your GSTIN & PAN to build creator trust and speed up payouts.' },
    ],
  },
];

/**
 * Frozen known-good. The SECOND entry is deliberately a full rewrite of both surfaces: this
 * gate must survive someone rewording the copy, or it will be ripped out the first time content
 * touches it, and F-0243 will be unguarded again.
 */
const KNOWN_GOOD: { why: string; surfaces: Surface[] }[] = [
  {
    why: 'the shipped wording',
    surfaces: [
      {
        name: 'banner',
        text:
          'Verify your workspace to launch campaigns. You can build and save drafts now - ' +
          "you'll need to verify before you can publish them live.",
      },
      {
        name: 'kyc prompt',
        text:
          'Verify your business · required to publish. Add your GSTIN & PAN to build creator ' +
          'trust and speed up payouts. You can skip this and keep working on a draft - but ' +
          "you'll need to verify before you can publish a live campaign.",
      },
    ],
  },
  {
    why: 'a complete reword of both surfaces, same position',
    surfaces: [
      {
        name: 'banner',
        text: 'Get verified to launch. Drafts are fine unverified; publishing needs a verified workspace.',
      },
      {
        name: 'kyc prompt',
        text:
          'Add your GSTIN and PAN. Draft away in the meantime - but you must be verified ' +
          'before you can publish a campaign.',
      },
    ],
  },
];

/* ------------------------------------------------------------------------- *
 * the tests
 * ------------------------------------------------------------------------- */

describe('F-0243 — the two verification surfaces take the same position', () => {
  beforeEach(() => {
    window.localStorage.clear();
    hook.value = { ...hook.value, status: 'UNVERIFIED', isVerified: false, isLoading: false };
  });

  it('SELF-CHECK: the classifier rejects every frozen known-bad pairing', () => {
    const escaped = KNOWN_BAD.filter((kb) => disagreements(kb.surfaces).length === 0).map((kb) => kb.why);
    expect(
      escaped,
      'THIS TEST CANNOT FAIL: the copy classifier accepted pairings that reintroduce F-0243 —\n' +
        escaped.join('\n'),
    ).toEqual([]);
  });

  it('SELF-CHECK: the classifier accepts the frozen known-good pairings, reworded ones included', () => {
    for (const kg of KNOWN_GOOD) {
      expect(
        disagreements(kg.surfaces),
        `the classifier flags honest, agreeing copy (${kg.why}) — it is a false-red machine and ` +
          `will be deleted the first time someone rewrites this copy`,
      ).toEqual([]);
    }
  });

  it('the banner and the KYC prompt agree on the /brand/campaigns/new screen', () => {
    const surfaces: Surface[] = [
      { name: 'WorkspaceVerificationBanner[UNVERIFIED]', text: bannerProse('UNVERIFIED') },
      { name: 'BrandKycPrompt', text: kycProse() },
    ];
    expect(
      disagreements(surfaces),
      'rendered copy:\n' + surfaces.map((s) => `  ${s.name}: ${normalise(s.text)}`).join('\n'),
    ).toEqual([]);
  });

  it('holds for the PENDING and REJECTED banner states too', () => {
    for (const status of ['PENDING', 'REJECTED'] as const) {
      const surfaces: Surface[] = [
        { name: `WorkspaceVerificationBanner[${status}]`, text: bannerProse(status) },
        { name: 'BrandKycPrompt', text: kycProse() },
      ];
      expect(
        disagreements(surfaces),
        'rendered copy:\n' + surfaces.map((s) => `  ${s.name}: ${normalise(s.text)}`).join('\n'),
      ).toEqual([]);
    }
  });

  it('neither surface promises that approval publishes drafts by itself', () => {
    for (const status of ['UNVERIFIED', 'PENDING', 'REJECTED'] as const) {
      expect(stanceOf(bannerProse(status)).autoPublish, `banner[${status}]`).toEqual([]);
    }
    expect(stanceOf(kycProse()).autoPublish, 'kyc prompt').toEqual([]);
  });
});
