/**
 * .proof-os/gates/F-0437.payments-empty-state.spec.tsx
 *
 * Execution leg for gates/F-0437-payments-empty-state.sh.
 *
 * origin failure F-0437 (fabricated-data-in-ui), opened by priya 2026-09-02:
 *   "When the server sends no milestones the payments tab replaces the empty list with a
 *    fabricated schedule derived from the deal value and maps over it unconditionally, so a
 *    brand sees an invented payment plan; the sibling contract tab has a real empty state."
 *
 * The code (src/components/brand/deal-room/deal-payments-tab.tsx):
 *
 *     const realMilestones = milestones ?? [];
 *     const hasRealMilestones = realMilestones.length > 0;
 *     const perDeliverable =
 *       deliverablesTotal > 0 ? Math.round(dealValue / deliverablesTotal) : dealValue;
 *     const rows = hasRealMilestones
 *       ? realMilestones.map(...)                       // real server rows
 *       : [ { label: 'Funds secured', amount: dealValue, ... },
 *           ...Array.from({ length: deliverablesTotal }, (_, i) => ({
 *              label: `Deliverable ${i + 1} payout`, amount: perDeliverable, ... })) ];
 *     ...
 *     {rows.map((m) => ( ... {formatINR(m.amount)} ... ))}
 *
 * `milestones={[]}` — the server answering "this contract has no payment milestones" — is
 * collapsed into the same branch as `milestones={undefined}`, and the brand is shown a full
 * payment plan whose line amounts are `dealValue / deliverablesTotal`. Those rupee figures were
 * invented in the browser. The sibling `deal-contract-tab.tsx` gets this right:
 *     {contractRecord.milestones.length === 0
 *        ? <p>No milestones on this contract.</p> : ...}
 *
 * WHAT IS ASSERTED, and why it is shaped this way.
 *
 * 1 · NO INVENTED MONEY. The panel is rendered with `milestones={[]}` and a deal value /
 *     deliverable count chosen so the client-side division produces figures the server could
 *     not have sent: 90,000 over 7 deliverables is 12,857 a piece, and its multiples. Every
 *     rupee figure in the rendered output is extracted and checked against that forbidden set.
 *     This is deliberately NOT a search for the strings "Deliverable N payout" or
 *     "Funds secured": relabelling the fabricated rows would satisfy a wording check while the
 *     brand still reads an invented plan. The arithmetic is the defect, so the arithmetic is
 *     what is looked for.
 *
 * 2 · AN ACTUAL EMPTY STATE. Per the ledger's `missed_by`, absence of fabrication is not enough
 *     — a panel that renders zero rows and says nothing leaves the brand staring at a blank
 *     area with no idea whether the schedule is missing or still loading. The prose must say,
 *     in a single clause, that there are no milestones. That is a claim audit (negation + a
 *     milestone/schedule noun in one fragment), not a frozen sentence, so a reword stays green.
 *
 * SELF-FALSIFICATION (F-0273). Three checks run FIRST and are the reason to believe the two
 * above. Each proves one instrument can fail:
 *   · the rupee extractor finds 12,857 in a frozen string that contains it, and does not
 *     hallucinate it in one that does not;
 *   · the empty-state audit REJECTS the panel's current disclaimer copy and two other
 *     frozen near-misses, and ACCEPTS two frozen good ones;
 *   · the whole render+extract path is exercised against a panel given two REAL milestones,
 *     and their server amounts are found — so "no forbidden figure" can never be a pass earned
 *     by a blank or crashed render.
 * If any self-check fails the suite prints THIS TEST CANNOT FAIL and the shell gate refuses to
 * report a verdict about the component at all.
 *
 * Run: node_modules/.bin/vitest run --config .proof-os/gates/vitest.gates.config.ts \
 *          .proof-os/gates/F-0437.payments-empty-state.spec.tsx
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import type { ContractMilestone } from '@/lib/api';
import { DealPaymentsTab } from '@/components/brand/deal-room/deal-payments-tab';

const toastFn = vi.fn();
const releasePayout = vi.fn();

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastFn }) }));
vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: { ...actual.api, payments: { releasePayout: (...a: unknown[]) => releasePayout(...a) } },
  };
});
// The fund control is not the subject here (that is F-0222's gate). Stubbed so this spec does
// not drag Razorpay/env code into the render.
vi.mock('@/components/feature/meera/FundEscrowButton', () => ({
  FundEscrowButton: () => <button data-testid="fund-escrow">fund</button>,
}));

// ---------------------------------------------------------------------------
// The numbers. 90,000 / 7 is not a round figure, so every fabricated line amount
// and every subtotal built out of it is a value the server demonstrably never sent.
// ---------------------------------------------------------------------------
const DEAL_VALUE = 90_000;
const DELIVERABLES_TOTAL = 7;
const PER_DELIVERABLE = Math.round(DEAL_VALUE / DELIVERABLES_TOTAL); // 12857

/**
 * Figures that can only exist if the panel divided the deal value by the deliverable count:
 * the per-line amount, the "Released" subtotal for k completed deliverables, and the
 * "Secured" remainder (dealValue - releasedTotal) the panel computes from them.
 */
function fabricatedFigures(): Set<number> {
  const out = new Set<number>();
  for (let k = 1; k <= DELIVERABLES_TOTAL; k += 1) {
    // Only rupee-scale values. `dealValue - perDeliverable * 7` happens to be 1 here, and a
    // bare "1" appears in perfectly honest text ("Milestone 1", "Step 1") — forbidding it would
    // make this a false-red machine rather than a fabrication detector.
    for (const v of [PER_DELIVERABLE * k, DEAL_VALUE - PER_DELIVERABLE * k]) {
      if (v >= 1000) out.add(v);
    }
  }
  // Honest values must never be in the forbidden set: the deal value was given to the panel.
  out.delete(DEAL_VALUE);
  return out;
}

/** Every rupee-ish figure in a blob of rendered text, comma-separators removed. */
export function rupeeFigures(text: string): number[] {
  const out: number[] = [];
  for (const tok of text.match(/\d[\d,]*/g) ?? []) {
    const n = Number(tok.replace(/,/g, ''));
    if (Number.isFinite(n)) out.push(n);
  }
  return out;
}

// ---------------------------------------------------------------------------
// The empty-state claim audit. Meaning, not wording: some single clause has to
// both negate and be about milestones/the payment schedule.
//
// The noun list is narrow ON PURPOSE. The panel's own header copy already says
// "Funds not secured yet" — a broader noun ("funds", "payment") would accept that
// sentence as an empty state and this gate would green the untouched component.
// ---------------------------------------------------------------------------
const MILESTONE_NOUN =
  /\b(milestones?|payment schedule|payment plan|scheduled payments?|payment breakdown)\b/i;
const NEGATION = /\b(no|not|none|never|nothing|without)\b|n[’'`]t\b/i;

function fragments(text: string): string[] {
  return text
    .split(/[.!?;\n—–]+/)
    .map((s) => s.replace(/\s+/g, ' ').trim())
    .filter(Boolean);
}

/** The clauses that say, outright, that there is no milestone schedule to show. */
export function emptyStateClaims(text: string): string[] {
  return fragments(text).filter((f) => MILESTONE_NOUN.test(f) && NEGATION.test(f));
}

/**
 * What the brand READS, with clause boundaries preserved.
 *
 * A flat `container.textContent` is not usable here: it welds adjacent elements together, so the
 * section heading and a following paragraph come back as "Payment milestonesNo payment
 * milestones on this contract." — and `\bno\b` cannot match inside "milestonesNo". That would
 * fail a genuinely fixed component (measured: it did). Block elements therefore contribute a
 * line break around their text and inline elements a space, so an empty-state sentence stays one
 * clause while two separate paragraphs stay two.
 *
 * Buttons and links are removed: they are offers of an action, not statements about state.
 */
const BLOCK = new Set([
  'P', 'DIV', 'SECTION', 'HEADER', 'FOOTER', 'MAIN', 'ARTICLE', 'ASIDE', 'UL', 'OL', 'LI',
  'TABLE', 'TR', 'TD', 'TH', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BR', 'HR', 'FIGCAPTION',
]);

function proseOf(container: HTMLElement): string {
  const walk = (node: Node): string => {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? '';
    if (node.nodeType !== Node.ELEMENT_NODE) return '';
    const el = node as HTMLElement;
    const tag = el.tagName.toUpperCase();
    if (tag === 'BUTTON' || tag === 'A' || tag === 'SCRIPT' || tag === 'STYLE') return '';
    const inner = Array.from(el.childNodes).map(walk).join(' ');
    return BLOCK.has(tag) ? `\n${inner}\n` : inner;
  };
  return walk(container);
}

type Props = React.ComponentProps<typeof DealPaymentsTab>;

function renderPanel(overrides: Partial<Props> = {}) {
  return render(
    <MemoryRouter>
      <DealPaymentsTab
        dealValue={DEAL_VALUE}
        contractStatus="active"
        deliverablesDone={0}
        deliverablesTotal={DELIVERABLES_TOTAL}
        campaignId="camp_f0437"
        {...overrides}
      />
    </MemoryRouter>,
  );
}

/**
 * The brand deal room in live mode, with the server's answer being "no milestones".
 * brand-chat.tsx passes `milestones={liveApiMode ? liveContract?.milestones : undefined}`;
 * `[]` is a contract that exists and carries none.
 */
const EMPTY_CASES: { why: string; props: Partial<Props> }[] = [
  {
    why: 'signed contract, server returned zero milestones, nothing funded',
    props: { milestones: [], escrowFunded: false, deliverablesDone: 0 },
  },
  {
    why: 'signed contract, server returned zero milestones, escrow funded, 3 deliverables done',
    props: { milestones: [], escrowFunded: true, deliverablesDone: 3 },
  },
];

const MS = (over: Partial<ContractMilestone> = {}): ContractMilestone => ({
  id: 'ms_1',
  sequenceNo: 1,
  description: 'Reel delivery',
  amount: 4000,
  status: 'PENDING',
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
});

describe('F-0437 — SELF-CHECKS (an instrument that cannot fail proves nothing)', () => {
  it('the rupee extractor finds a fabricated figure that is present, and only when present', () => {
    const withIt = 'Deliverable 1 payout ₹12,857 pending — Secured ₹90,000';
    const withoutIt = 'No payment milestones yet — the deal is worth ₹90,000';
    expect(
      rupeeFigures(withIt),
      'THIS TEST CANNOT FAIL: the rupee extractor cannot see ₹12,857 in text that contains it, ' +
        'so "no fabricated figure rendered" would be meaningless',
    ).toContain(PER_DELIVERABLE);
    expect(
      rupeeFigures(withoutIt),
      'THIS TEST CANNOT FAIL: the rupee extractor invents ₹12,857 in text that does not ' +
        'contain it — it is a false-red machine',
    ).not.toContain(PER_DELIVERABLE);
  });

  it('the empty-state audit rejects the panel’s current copy and other frozen near-misses', () => {
    const KNOWN_BAD: { why: string; copy: string }[] = [
      {
        why: 'the disclaimer the panel prints TODAY under the fabricated rows — it names ' +
          'milestones but denies nothing, and sits beneath a full invented schedule',
        copy:
          'Estimated from the deal value — real milestones appear once the contract is generated.',
      },
      {
        why: 'the panel’s own header copy — a negation, but about escrow, not the schedule',
        copy: 'Funds not secured yet. Secure the funds after both parties sign the contract.',
      },
      {
        why: 'silence — zero rows and no explanation is indistinguishable from still loading',
        copy: 'Payment milestones',
      },
      { why: 'nothing rendered at all', copy: '' },
    ];
    const escaped = KNOWN_BAD.filter((kb) => emptyStateClaims(kb.copy).length > 0).map(
      (kb) => `${kb.why} :: ${kb.copy}`,
    );
    expect(
      escaped,
      'THIS TEST CANNOT FAIL: the empty-state audit accepts copy that is not an empty state —\n' +
        escaped.join('\n'),
    ).toEqual([]);
  });

  it('the empty-state audit accepts frozen good copy (it is not a false-red machine)', () => {
    const KNOWN_GOOD = [
      'No milestones on this contract.',
      'This contract has no payment milestones yet — one appears once the schedule is agreed.',
      'The payment schedule isn’t available for this deal.',
    ];
    const rejected = KNOWN_GOOD.filter((c) => emptyStateClaims(c).length === 0);
    expect(
      rejected,
      'THIS TEST CANNOT FAIL: the empty-state audit rejects honest empty-state copy —\n' +
        rejected.join('\n'),
    ).toEqual([]);
  });

  it('the render+extract path really reads the panel (real milestones are seen)', () => {
    const { container } = renderPanel({
      milestones: [MS(), MS({ id: 'ms_2', sequenceNo: 2, description: 'Story set', amount: 5000 })],
      escrowFunded: true,
    });
    const figures = rupeeFigures(container.textContent ?? '');
    expect(
      figures,
      'THIS TEST CANNOT FAIL: the panel rendered but its server milestone amounts were not ' +
        'found in the output, so a clean "no fabricated figure" result could just be a blank read',
    ).toEqual(expect.arrayContaining([4000, 5000]));
    // ...and the audit must NOT claim an empty state when there are real rows.
    expect(
      emptyStateClaims(proseOf(container)),
      'THIS TEST CANNOT FAIL: the audit reports an empty state on a panel that is showing two ' +
        'real milestones — it is not measuring emptiness',
    ).toEqual([]);
  });
});

describe('F-0437 — no server milestones means no invented payment plan', () => {
  it('renders no rupee figure that was derived from dealValue / deliverablesTotal', () => {
    const forbidden = fabricatedFigures();
    for (const c of EMPTY_CASES) {
      const { container, unmount } = renderPanel(c.props);
      const seen = rupeeFigures(container.textContent ?? '').filter((n) => forbidden.has(n));
      unmount();
      expect(
        Array.from(new Set(seen)),
        `[${c.why}] the payments tab shows the brand rupee figures the server never sent. ` +
          `With a ₹${DEAL_VALUE.toLocaleString('en-IN')} deal over ${DELIVERABLES_TOTAL} ` +
          `deliverables and an EMPTY milestone list, these came out of a client-side division ` +
          `(dealValue / deliverablesTotal = ₹${PER_DELIVERABLE.toLocaleString('en-IN')}) and are ` +
          `presented as a payment plan (F-0437).`,
      ).toEqual([]);
    }
  });

  it('says outright that there are no milestones, rather than showing nothing', () => {
    for (const c of EMPTY_CASES) {
      const { container, unmount } = renderPanel(c.props);
      const claims = emptyStateClaims(proseOf(container));
      unmount();
      expect(
        claims.length,
        `[${c.why}] the payments tab never tells the brand that this contract has no payment ` +
          `milestones. The sibling deal-contract-tab.tsx renders "No milestones on this ` +
          `contract." for exactly this state; this panel must have a real empty state too, not ` +
          `a blank area and not a derived schedule (F-0437).`,
      ).toBeGreaterThan(0);
    }
  });
});
