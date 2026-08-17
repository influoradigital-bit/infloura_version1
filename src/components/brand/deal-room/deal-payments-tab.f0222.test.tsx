/**
 * Deal-room Payments panel — milestone-scoped escrow funding (F-0222).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * `EscrowService.initiateFund` binds `collaborationId` onto the new hold ONLY inside the
 * `milestoneId != null` branch. Campaign-level funding with no milestoneId is a deliberate,
 * documented path (a pre-contract pool), and it leaves `collaboration_id` NULL — so
 * `notifyEscrowFunded` returns early, `onEscrowFunded` never runs, and the collaboration
 * never advances CONTRACTED -> IN_PROGRESS. The creator's Submit Deliverable control is gated
 * on IN_PROGRESS, so the deal freezes with the brand's money already debited.
 *
 * Before the fix, `/brand/wallet`'s campaign-pool card was the ONLY reachable funding control
 * in the product, and the deal room's step-3 "Fund escrow" opened a panel with no control at
 * all. Every real fund therefore took the unbound path.
 *
 * This gate holds the shape that fixes it: when the brand deal room has a campaignId and a
 * fully-signed contract, the panel MUST offer a fund control carrying a real server
 * `milestoneId` — and the creator mount, which passes none of those props, must never render
 * a brand-only money action.
 *
 * `FundEscrowButton` is mocked to a prop probe on purpose: what this gate proves is which
 * milestoneId reaches the button, not Razorpay behaviour (that is F-0049's gate,
 * fund_escrow_onfunded.sh).
 *
 * Run: npx vitest run src/components/brand/deal-room/deal-payments-tab.f0222.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import type { ContractMilestone } from '@/lib/api';
import { DealPaymentsTab } from './deal-payments-tab';

const fundProps = vi.fn();

vi.mock('@/components/feature/meera/FundEscrowButton', () => ({
  FundEscrowButton: (props: Record<string, unknown>) => {
    fundProps(props);
    return <button data-testid="fund-escrow">fund</button>;
  },
}));

function renderPanel(overrides: Partial<React.ComponentProps<typeof DealPaymentsTab>> = {}) {
  return render(
    <MemoryRouter>
      <DealPaymentsTab
        dealValue={7000}
        contractStatus="active"
        deliverablesDone={0}
        deliverablesTotal={1}
        {...overrides}
      />
    </MemoryRouter>,
  );
}

const MS = (over: Partial<ContractMilestone> = {}): ContractMilestone => ({
  id: 'ms_1',
  sequenceNo: 1,
  description: 'Reel delivery',
  amount: 7000,
  status: 'PENDING',
  ...over,
});

beforeEach(() => {
  fundProps.mockClear();
});

describe('F-0222 — the brand deal room funds a MILESTONE, never a bare campaign', () => {
  it('passes a real server milestoneId to the fund control', () => {
    renderPanel({ campaignId: 'camp_1', milestones: [MS()], escrowFunded: false });

    expect(screen.getByTestId('fund-escrow')).toBeTruthy();
    const props = fundProps.mock.calls[0][0];
    // The whole defect in one assertion: a fund call without this is the unbound-hold path.
    expect(props.milestoneId).toBe('ms_1');
    expect(props.campaignId).toBe('camp_1');
  });

  it('targets the first PENDING milestone, skipping ones already funded', () => {
    renderPanel({
      campaignId: 'camp_1',
      escrowFunded: true,
      milestones: [
        MS({ id: 'ms_1', sequenceNo: 1, status: 'FUNDED' }),
        MS({ id: 'ms_2', sequenceNo: 2, status: 'PENDING', amount: 3000 }),
      ],
    });

    expect(fundProps.mock.calls[0][0].milestoneId).toBe('ms_2');
  });

  it('offers nothing to fund once every milestone has moved past PENDING', () => {
    renderPanel({
      campaignId: 'camp_1',
      escrowFunded: true,
      milestones: [MS({ status: 'RELEASED' })],
    });

    expect(screen.queryByTestId('fund-escrow')).toBeNull();
  });

  it('never renders a fund control on the creator mount, which passes no campaignId', () => {
    // Exactly how creator-chat.tsx mounts this component.
    renderPanel({ campaignId: undefined, milestones: undefined, escrowFunded: undefined });

    expect(screen.queryByTestId('fund-escrow')).toBeNull();
    expect(fundProps).not.toHaveBeenCalled();
  });

  it('withholds the control until BOTH parties have signed', () => {
    renderPanel({
      campaignId: 'camp_1',
      contractStatus: 'brand_signed',
      milestones: [MS()],
      escrowFunded: false,
    });

    expect(screen.queryByTestId('fund-escrow')).toBeNull();
  });

  it('does not skip a milestone that carries no status yet', () => {
    renderPanel({ campaignId: 'camp_1', milestones: [MS({ status: undefined })] });

    expect(fundProps.mock.calls[0][0].milestoneId).toBe('ms_1');
  });

  it('refuses to fund a milestone the server never persisted', () => {
    renderPanel({ campaignId: 'camp_1', milestones: [MS({ id: undefined })] });

    expect(screen.queryByTestId('fund-escrow')).toBeNull();
  });
});

describe('F-0222 — the panel states escrow honestly', () => {
  it('does not claim escrow is active on a signed but unfunded deal', () => {
    renderPanel({ campaignId: 'camp_1', escrowFunded: false, milestones: [MS()] });

    expect(screen.queryByText('Escrow active')).toBeNull();
    expect(screen.getByText('Contract signed — escrow not funded yet')).toBeTruthy();
  });

  it('reports escrow active only when the deal really is funded', () => {
    renderPanel({
      campaignId: 'camp_1',
      escrowFunded: true,
      milestones: [MS({ status: 'FUNDED' })],
    });

    expect(screen.getByText('Escrow active')).toBeTruthy();
  });

  it('keeps the creator mount on its prior signature-derived behaviour', () => {
    // escrowFunded omitted -> falls back to contractStatus, unchanged by this fix.
    renderPanel({ escrowFunded: undefined, contractStatus: 'active' });

    expect(screen.getByText('Escrow active')).toBeTruthy();
  });
});
