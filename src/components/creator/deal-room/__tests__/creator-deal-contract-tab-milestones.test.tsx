/**
 * F-0640 (unrendered-agreed-terms): the creator's deal-room contract tab had no
 * `milestones` prop and never rendered the payment schedule — a creator could
 * countersign a contract whose milestones they could never actually see.
 *
 * This spec proves:
 *  1. A contract with milestones renders each one's real amount and description.
 *  2. A contract with an empty milestone list renders an honest empty state
 *     rather than implying a schedule exists (no fabricated placeholder rows).
 *
 * Run: npx vitest run src/components/creator/deal-room/__tests__/creator-deal-contract-tab-milestones.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CreatorDealContractTab } from '../creator-deal-contract-tab';
import type { ContractMilestone } from '@/lib/api';

const milestones: ContractMilestone[] = [
  { id: 'm-1', sequenceNo: 1, description: 'Draft content delivered', amount: 15000, dueDate: '2026-09-20' },
  { id: 'm-2', sequenceNo: 2, description: 'Final content approved', amount: 10000 },
];

describe('CreatorDealContractTab — F-0640 payment milestones', () => {
  it('renders each milestone\'s real amount and description', () => {
    render(
      <CreatorDealContractTab
        contractId="CTR-1"
        brandName="Acme Co"
        campaignName="Summer Launch"
        amount={25000}
        contractAmount={25000}
        milestones={milestones}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    expect(screen.getByText('Draft content delivered')).toBeInTheDocument();
    expect(screen.getByText('₹15,000')).toBeInTheDocument();
    expect(screen.getByText('Final content approved')).toBeInTheDocument();
    expect(screen.getByText('₹10,000')).toBeInTheDocument();
  });

  it('renders a due date only when the milestone actually carries one', () => {
    render(
      <CreatorDealContractTab
        contractId="CTR-1"
        brandName="Acme Co"
        campaignName="Summer Launch"
        amount={25000}
        contractAmount={25000}
        milestones={milestones}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    // m-1 has a dueDate — shown (en-IN Intl format: "20 Sept 2026").
    expect(screen.getByText(/due 20 sept 2026/i)).toBeInTheDocument();
    // m-2 has no dueDate — no fabricated placeholder date for it.
    expect(screen.queryByText(/final content approved/i)?.closest('li')?.textContent).not.toMatch(/due/i);
  });

  it('renders an honest empty state for an empty milestone list, not a fabricated schedule', () => {
    render(
      <CreatorDealContractTab
        contractId="CTR-1"
        brandName="Acme Co"
        campaignName="Summer Launch"
        amount={25000}
        contractAmount={25000}
        milestones={[]}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    expect(screen.getByText(/no payment milestones are on file for this contract yet/i)).toBeInTheDocument();
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('treats an unpassed milestones prop the same as empty — no crash, no fabricated schedule', () => {
    render(
      <CreatorDealContractTab
        contractId="CTR-1"
        brandName="Acme Co"
        campaignName="Summer Launch"
        amount={25000}
        contractAmount={25000}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    expect(screen.getByText(/no payment milestones are on file for this contract yet/i)).toBeInTheDocument();
  });
});
