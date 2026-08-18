/**
 * .proof-os/gates/F-0240.reaches-payload.spec.tsx
 *
 * Execution leg for gates/F-0240-campaign-type-reaches-payload.sh.
 *
 * origin failure F-0240 (silent-type-discard): the picker captured `selectedType` but never
 * handed it to CampaignForm, so "Direct Deal" silently created a STANDARD/OPEN campaign,
 * immutably.
 *
 * WHY THIS FILE EXISTS. The gate used to prove the fix by grepping for the literal
 * `campaignType: selectedType` in the page. That literal survives the most plausible wrong
 * fix there is — hoisting it into a local and then handing CampaignForm something else:
 *
 *     const pickedTypeValues = selectedType ? { campaignType: selectedType } : undefined;
 *     <CampaignForm initialValues={templateInitialValues ?? undefined} />
 *
 * Observed: the pre-repair gate exited 0 on exactly that tree
 * (.proof-os/tasks/T-F0329-GATES/F-0240.inject.log). A literal being present is a snapshot;
 * this file asserts the PROPERTY — click Direct Deal, and the type the brand picked is what
 * CampaignForm is actually handed.
 *
 * CampaignForm itself is mocked to a props recorder on purpose: the real one is a validated
 * multi-step wizard, and driving it to submit would make this test measure the wizard rather
 * than the handoff that broke. The two links after this one (initialValues merged into
 * formData, formData.campaignType placed in the create payload) are asserted structurally by
 * the gate's own assertion table, which is self-tested against a frozen known-bad.
 *
 * This file lives under .proof-os/gates/ rather than src/ because proof-os gate work owns
 * .proof-os/** only. It is run by the gate via node_modules/.bin/vitest with an explicit
 * config, never by a bare `vitest run` of the whole suite.
 */
import * as React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const rec = vi.hoisted(() => ({ props: [] as Record<string, unknown>[] }));

vi.mock('@/components/brand/campaigns/campaign-form', () => ({
  CampaignForm: (props: Record<string, unknown>) => {
    rec.props.push(props);
    return <div data-testid="campaign-form-stub" />;
  },
}));

// The KYC prompt on this page fetches onboarding status; it is not what is under test here.
vi.mock('@/components/brand/campaigns/brand-kyc-prompt', () => ({
  BrandKycPrompt: () => null,
}));

import BrandNewCampaignPage from '@/pages/brand-new-campaign';

type Handoff = { initialValues?: { campaignType?: string } };

async function pickFrom(Page: React.ComponentType, name: RegExp): Promise<Handoff> {
  rec.props.length = 0;
  render(
    <MemoryRouter initialEntries={['/brand/campaigns/new']}>
      <Page />
    </MemoryRouter>,
  );
  await userEvent.click(await screen.findByRole('button', { name }));
  await screen.findByTestId('campaign-form-stub');
  return rec.props[rec.props.length - 1] as Handoff;
}

const pick = (name: RegExp) => pickFrom(BrandNewCampaignPage, name);

/**
 * The F-0240 defect, frozen. A picker that stores the chosen type and then renders the form
 * without it — the shape the original record describes, and the shape the "detached literal"
 * wrong fix collapses back to. The last test below runs the SAME assertion over this and
 * requires it to fail. A test that cannot fail is worse than no test.
 */
function KnownBadPage() {
  const [selected, setSelected] = React.useState<string | null>(null);
  const pickedTypeValues = selected ? { campaignType: selected } : undefined;
  if (selected) return <StubForm />;
  return (
    <div>
      <button type="button" onClick={() => setSelected('DIRECT')}>
        Direct Deal
      </button>
      <span hidden>{JSON.stringify(pickedTypeValues)}</span>
    </div>
  );
}

function StubForm() {
  rec.props.push({});
  return <div data-testid="campaign-form-stub" />;
}

describe('F-0240 — the picked campaign type reaches the form', () => {
  it('hands DIRECT to CampaignForm when the brand picks Direct Deal', async () => {
    const props = await pick(/Direct Deal/i);
    expect(props, 'CampaignForm was never rendered after picking a type').toBeTruthy();
    expect(
      props.initialValues?.campaignType,
      'the brand picked Direct Deal and CampaignForm was handed ' +
        JSON.stringify(props.initialValues) +
        ' — the type is discarded between the picker and the form (F-0240)',
    ).toBe('DIRECT');
  });

  it('hands OPEN to CampaignForm when the brand picks Open Campaign', async () => {
    const props = await pick(/Open Campaign/i);
    expect(
      props.initialValues?.campaignType,
      'the brand picked Open Campaign and CampaignForm was handed ' +
        JSON.stringify(props.initialValues),
    ).toBe('OPEN');
  });

  it('SELF-CHECK: the same assertion rejects the frozen F-0240 defect', async () => {
    const props = await pickFrom(KnownBadPage, /Direct Deal/i);
    // Deliberately inverted: if `.toBe('DIRECT')` would PASS here, this whole file is blind
    // and its two greens above mean nothing.
    expect(
      props.initialValues?.campaignType,
      'THIS TEST CANNOT FAIL: the known-bad picker — which never hands the type to the form — ' +
        'satisfied the same assertion the two tests above rely on.',
    ).not.toBe('DIRECT');
  });
});
