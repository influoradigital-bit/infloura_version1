/**
 * F-0248 / F-0267 / F-0269 — unreachable-primary-action, brand-first path.
 * ----------------------------------------------------------------------------
 * `mapApiContractStatus` translates the real backend `ContractStatus`
 * (DRAFT/PENDING_SIGNATURES/ACTIVE/COMPLETED/TERMINATED/DISPUTED) into this
 * page's UI-only `Contract['status']` vocabulary. It can NEVER produce
 * 'pending_review' — that literal does not appear on the right-hand side of
 * any case in its switch.
 *
 * F-0248's first fix gated the Sign button on `status === 'pending_signature'`
 * only. That missed the common brand-first path: `Contract.builder()` defaults
 * a freshly-generated contract to DRAFT (influora-api Contract.java:232), and
 * it only advances to PENDING_SIGNATURES once *someone* signs
 * (`advanceIfFullySigned`, Contract.java:189-195). A brand opening a contract
 * it just generated saw 'draft' and had no Sign button at all — verified
 * against `ContractService#recordSignature`/`#doRecordSignature`
 * (ContractService.java:526-678) and `ContractController#sign`
 * (ContractController.java:78-113), neither of which has any status guard: a
 * DRAFT contract is fully signable server-side. F-0267 extends the gate to
 * cover DRAFT. F-0269 is the regression the first fix introduced in demo
 * mode: `mockContracts` contract-3 carried `status: 'pending_review'`, a
 * literal nothing gates on any more, making it unsignable in the fixtures —
 * fixed by giving it 'draft'.
 *
 * Four guarantees:
 *   A. Property test — every `selectedContract.status === '<literal>'` gate
 *      on a `<Button>` in this file (including multi-literal `||` gates)
 *      compares against a literal the mapper can actually produce. This is
 *      the general form: it would have caught F-0248 and catches the whole
 *      defect class, not just this one instance.
 *   B. Behavioral property test — for EVERY status `mapApiContractStatus` can
 *      emit, a brand who has not yet signed either reaches the Sign action
 *      (draft/pending_signature) or is shown an explicit, status-specific
 *      explanation of why not (signed/expired/disputed). A control gated on
 *      a literal no mapper output matches — or a status silently offering
 *      neither a path nor an explanation — fails this test.
 *   C. Explicit cases — a live DRAFT contract (brand-first path, the case
 *      F-0248's first fix missed) and a live PENDING_SIGNATURES contract
 *      (creator-first path) both render a clickable "Sign Contract" button
 *      when the brand hasn't signed, and neither does once the brand has.
 *   D. Demo-mode case — F-0269: `mockContracts` fixtures are signable.
 *
 * Run: npx vitest run src/components/brand/contracts/__tests__/contracts-sign-reachability.test.tsx
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

import { mapApiContractStatus } from '../contracts-and-deliverables';

// ---------------------------------------------------------------------------
// A. Property test — every status literal gated on is a value the mapper emits
// ---------------------------------------------------------------------------

/**
 * The complete real backend `ContractStatus` enum (src/lib/types.ts /
 * influora-api ContractStatus.java). `mapApiContractStatus`'s own switch is
 * exhaustive over exactly these plus `undefined`, with a `default: 'draft'`
 * catch-all for anything unrecognized.
 */
const REAL_BACKEND_STATUSES = [
  'DRAFT',
  'PENDING_SIGNATURES',
  'ACTIVE',
  'COMPLETED',
  'TERMINATED',
  'DISPUTED',
] as const;

/** Every UI-only status literal the mapper can actually hand back to the page. */
function mapperOutputSet(): Set<string> {
  const out = new Set<string>();
  for (const s of REAL_BACKEND_STATUSES) {
    const mapped = mapApiContractStatus(s);
    if (mapped) out.add(mapped);
  }
  // mapApiContractStatus(undefined) -> undefined, never a renderable literal — excluded.
  // The `default: 'draft'` branch adds no new literal beyond what DRAFT already contributes.
  return out;
}

const SOURCE_PATH = resolve(__dirname, '../contracts-and-deliverables.tsx');

/**
 * Pulls every `selectedContract.status === '<literal>'` comparison that
 * gates a `<Button>` out of the real component source — i.e. a status
 * literal an interactive *control* is reachable behind, which is exactly
 * what F-0248 broke (the Sign button). Source-scanning (not re-deriving the
 * JSX by hand) is what makes this a real regression test: it reads whatever
 * the file actually says, so a future control gated on a bad literal fails
 * this test without anyone updating it.
 *
 * Scoped to `<Button>` on purpose, not every conditional render: this file
 * also has status literals gating purely informational `<Badge>`/`<Card>`
 * blocks. Those don't strand a user the way a dead button does, so they're
 * out of this test's assertion — widening the match to any conditional
 * would make this test permanently red for a defect this ticket didn't ask
 * to be fixed, rather than pinning the one it did.
 *
 * F-0267: the Sign button's gate is now `(status === 'draft' ||
 * status === 'pending_signature') && !brandSigned && (` — a multi-literal
 * `||` group, not a single `&&`-chained comparison. A naive per-literal
 * regex anchored immediately before `&& (` would miss every literal except
 * the last in the group (silently dropping 'draft' from coverage — exactly
 * the kind of gap that let F-0267 through the first time). This version
 * instead isolates the whole condition expression inside a `{...&&(` JSX
 * block (never crossing a brace, so it can't bleed into an unrelated block)
 * and pulls every `selectedContract.status === '<literal>'` comparison out
 * of it, `||` or `&&` alike.
 */
function extractButtonGatedStatusLiterals(src: string): string[] {
  const blockRe =
    /\{([^{}]*?selectedContract\.status\s*===\s*'[^']+'[^{}]*?)&&\s*\(\s*\n\s*<(\w+)/g;
  const literalRe = /selectedContract\.status\s*===\s*'([^']+)'/g;
  const out = new Set<string>();
  let blockMatch: RegExpExecArray | null;
  while ((blockMatch = blockRe.exec(src)) !== null) {
    const [, condition, tag] = blockMatch;
    if (tag !== 'Button') continue;
    let literalMatch: RegExpExecArray | null;
    literalRe.lastIndex = 0;
    while ((literalMatch = literalRe.exec(condition)) !== null) {
      out.add(literalMatch[1]);
    }
  }
  return [...out];
}

describe('F-0248 — every status gate is reachable', () => {
  it('mapper output set is the 5 non-draft-collapsed UI statuses (sanity)', () => {
    expect(mapperOutputSet()).toEqual(
      new Set(['draft', 'pending_signature', 'signed', 'expired', 'disputed']),
    );
  });

  it('every status literal the component gates a Button control on is a value the mapper can emit', () => {
    const src = readFileSync(SOURCE_PATH, 'utf8');
    const gated = extractButtonGatedStatusLiterals(src);
    const reachable = mapperOutputSet();

    expect(gated.length, 'sanity: extraction found no button gates at all').toBeGreaterThan(0);
    // F-0267 sanity: confirms the OR-aware extraction above actually saw BOTH literals
    // in the Sign button's `(status === 'draft' || status === 'pending_signature')` gate,
    // not just the last one in the group -- the exact failure mode a naive regex has.
    expect(
      gated,
      'extraction did not see both literals in the draft/pending_signature OR-gate',
    ).toEqual(expect.arrayContaining(['draft', 'pending_signature']));

    const unreachable = gated.filter((literal) => !reachable.has(literal));
    expect(
      unreachable,
      `Status literal(s) gating a <Button> in contracts-and-deliverables.tsx that ` +
        `mapApiContractStatus can NEVER produce (dead button — see F-0248):\n${unreachable.join('\n')}`,
    ).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// B/C/D. Behavioral tests — Sign Contract reachability across live statuses
//        and demo-mode fixtures
// ---------------------------------------------------------------------------

const contractsList = vi.fn();
const contractsGet = vi.fn();
const dealsList = vi.fn();

// F-0269/D: `isApiLive()` is read at render time (inside the component body,
// not at module load), so a mutable flag read by the mock closure is enough
// to flip live/demo mode per-test without `vi.resetModules()`.
let liveModeOverride = true;

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => liveModeOverride,
    api: {
      ...actual.api,
      contracts: {
        ...actual.api.contracts,
        list: (...a: unknown[]) => contractsList(...a),
        get: (...a: unknown[]) => contractsGet(...a),
      },
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsList(...a) },
    },
  };
});

// Imported after the mock so the component picks up the mocked `@/lib/api`.
const { ContractsAndDeliverables } = await import('../contracts-and-deliverables');

function liveRecord(over: Record<string, unknown> = {}) {
  return {
    id: 'ctr-1',
    collaborationId: 'collab-1',
    status: 'PENDING_SIGNATURES',
    totalAmount: 50000,
    currency: 'INR',
    brandSignedAt: null,
    creatorSignedAt: null,
    milestones: [],
    expirationDate: '2026-12-31',
    createdAt: '2026-08-01',
    ...over,
  };
}

async function openContractTab(user: ReturnType<typeof userEvent.setup>) {
  const tab = await screen.findByRole('tab', { name: 'Contract' });
  await user.click(tab);
}

// Every test flips `liveModeOverride` explicitly at its own start, but reset
// after each test anyway so a forgotten flip in a future test can't leak
// demo-mode into a later live-mode test (or vice versa).
afterEach(() => {
  liveModeOverride = true;
});

describe('F-0248 — Sign Contract is reachable in live mode', () => {
  it('renders a clickable Sign Contract button for a contract awaiting the brand signature', async () => {
    contractsList.mockResolvedValue([liveRecord()]);
    contractsGet.mockResolvedValue(liveRecord());
    dealsList.mockResolvedValue([]);
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    await openContractTab(user);

    const signButton = await screen.findByRole('button', { name: /Sign Contract/i });
    expect(signButton).toBeEnabled();
  });

  // F-0267 — the case the first fix missed: a freshly-generated, unsigned contract
  // is DRAFT (Contract.builder() default, Contract.java:232), not PENDING_SIGNATURES
  // -- that only exists after a first signature (advanceIfFullySigned,
  // Contract.java:189-195). This is the normal brand-first path: the brand generates
  // a contract and opens it before anyone has signed. Against the pre-F-0267 gate
  // (`status === 'pending_signature' && !brandSigned`), a DRAFT contract renders no
  // Sign button at all -- this must fail against that code (see PROVE-IT output).
  it('renders a clickable Sign Contract button for a freshly-generated DRAFT contract (brand-first path)', async () => {
    contractsList.mockResolvedValue([liveRecord({ status: 'DRAFT' })]);
    contractsGet.mockResolvedValue(liveRecord({ status: 'DRAFT' }));
    dealsList.mockResolvedValue([]);
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    await openContractTab(user);

    const signButton = await screen.findByRole('button', { name: /Sign Contract/i });
    expect(signButton).toBeEnabled();
  });

  it('does NOT show the Sign button once the brand has already signed (awaiting creator instead)', async () => {
    contractsList.mockResolvedValue([liveRecord({ brandSignedAt: '2026-08-01T00:00:00Z' })]);
    contractsGet.mockResolvedValue(liveRecord({ brandSignedAt: '2026-08-01T00:00:00Z' }));
    dealsList.mockResolvedValue([]);
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    await openContractTab(user);

    // Both the header badge (line ~1021) and the Contract-tab card (line ~1318) render
    // this copy once the brand has signed and the creator hasn't — assert on the set,
    // not a single match, so this isn't tripped up by that (correct, unrelated) duplication.
    expect((await screen.findAllByText(/Awaiting Creator Signature/i)).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /Sign Contract/i })).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// B. Behavioral property test — every status mapApiContractStatus can emit
//    offers a brand who hasn't signed either a reachable sign path or an
//    explicit, status-specific explanation of why not.
// ---------------------------------------------------------------------------

describe('F-0267 — every mapped status is reachable or explained for an unsigned brand', () => {
  const cases: Array<{
    backendStatus: string;
    uiStatus: string;
    /** Only set for statuses where NOT signing is the correct behavior. */
    expectedExplanationLabel?: string;
  }> = [
    { backendStatus: 'DRAFT', uiStatus: 'draft' },
    { backendStatus: 'PENDING_SIGNATURES', uiStatus: 'pending_signature' },
    { backendStatus: 'ACTIVE', uiStatus: 'signed', expectedExplanationLabel: 'Active' },
    { backendStatus: 'TERMINATED', uiStatus: 'expired', expectedExplanationLabel: 'Expired' },
    { backendStatus: 'DISPUTED', uiStatus: 'disputed', expectedExplanationLabel: 'Disputed' },
  ];

  for (const { backendStatus, uiStatus, expectedExplanationLabel } of cases) {
    it(`${backendStatus} (-> '${uiStatus}') gives a brand who hasn't signed a reachable path or an explanation`, async () => {
      contractsList.mockResolvedValue([liveRecord({ status: backendStatus })]);
      contractsGet.mockResolvedValue(liveRecord({ status: backendStatus }));
      dealsList.mockResolvedValue([]);
      const user = userEvent.setup({ delay: null });

      render(
        <MemoryRouter>
          <ContractsAndDeliverables />
        </MemoryRouter>,
      );

      await openContractTab(user);

      const signButton = screen.queryByRole('button', { name: /Sign Contract/i });

      if (!expectedExplanationLabel) {
        // draft / pending_signature: these are exactly the states in which a
        // contract is genuinely awaiting the brand's signature and the backend
        // has no status guard rejecting either (verified against
        // ContractService#doRecordSignature) -- no fallback explanation is
        // acceptable here, only a real reachable Sign button counts.
        expect(signButton, `expected a reachable Sign button for ${backendStatus}`).not.toBeNull();
        expect(signButton).toBeEnabled();
      } else {
        // signed / expired / disputed: no Sign action, but the status badge must
        // carry the exact, status-specific label -- not just "some text exists",
        // which a genuinely dead button would also trivially satisfy via the
        // ever-present (but generic) status badge.
        expect(signButton, `expected NO Sign button for ${backendStatus}`).toBeNull();
        expect(
          (await screen.findAllByText(expectedExplanationLabel)).length,
          `expected the '${expectedExplanationLabel}' status badge to explain why ${backendStatus} isn't signable`,
        ).toBeGreaterThan(0);
      }
    });
  }
});

// ---------------------------------------------------------------------------
// D. Demo mode — F-0269: mockContracts fixtures are signable
// ---------------------------------------------------------------------------

describe('F-0269 — demo-mode mock fixtures are signable', () => {
  it('contract-3 (was status "pending_review", now "draft") renders a clickable Sign Contract button', async () => {
    liveModeOverride = false;
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter initialEntries={['/?contract=contract-3']}>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    await openContractTab(user);

    const signButton = await screen.findByRole('button', { name: /Sign Contract/i });
    expect(signButton).toBeEnabled();
  });
});
