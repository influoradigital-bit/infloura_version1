/**
 * The brand's deliverables list used to offer Approve / Request changes over nothing but a title
 * and a status. The submitted files, caption and notes were shown nowhere: the only component that
 * renders them (DeliverableViewer) was mounted by no page, so approval — the brand's sign-off on
 * the work — was blind.
 *
 * Run: npx vitest run src/components/brand/deal-room/__tests__/deal-deliverables-tab.view-submission.test.tsx
 */
import { describe, expect, it, vi } from 'vitest';
import fs from 'node:fs';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DealDeliverablesTab, type DealDeliverableItem } from '../deal-deliverables-tab';

const items: DealDeliverableItem[] = [
  { id: 'd_waiting', title: 'Instagram Reel #1', type: 'video', status: 'pending' },
  { id: 'd_review', title: 'Instagram Reel #2', type: 'video', status: 'pending_review' },
  { id: 'd_approved', title: 'Instagram Story #1', type: 'image', status: 'approved' },
  { id: 'd_revision', title: 'Instagram Story #2', type: 'image', status: 'revision' },
];

function card(title: string): HTMLElement {
  // The title's nearest card root.
  return screen.getByText(title).closest('[data-slot="card"]') as HTMLElement;
}

describe('DealDeliverablesTab — viewing the submission', () => {
  it('a deliverable awaiting review can be opened BEFORE it is approved', async () => {
    const onView = vi.fn();
    const onApprove = vi.fn();
    const user = userEvent.setup();
    render(
      <DealDeliverablesTab done={1} total={4} dealValue={40000} items={items} onView={onView} onApprove={onApprove} />,
    );

    await user.click(within(card('Instagram Reel #2')).getByRole('button', { name: 'Review submission' }));

    expect(onView).toHaveBeenCalledWith('d_review');
    expect(onApprove).not.toHaveBeenCalled();
  });

  it('already-reviewed work stays viewable; nothing to view before the creator has submitted', () => {
    render(<DealDeliverablesTab done={1} total={4} dealValue={40000} items={items} onView={vi.fn()} />);

    expect(within(card('Instagram Story #1')).getByRole('button', { name: 'View submission' })).toBeInTheDocument();
    expect(within(card('Instagram Story #2')).getByRole('button', { name: 'View submission' })).toBeInTheDocument();
    expect(within(card('Instagram Reel #1')).queryByRole('button', { name: /submission/i })).toBeNull();
  });

  it('renders no View control without onView — the creator deal room reuses this list', () => {
    render(<DealDeliverablesTab done={1} total={4} dealValue={40000} items={items} />);

    expect(screen.queryByRole('button', { name: /submission/i })).toBeNull();
  });

  it('no longer promises that approval releases the money', () => {
    render(<DealDeliverablesTab done={1} total={4} dealValue={40000} items={items} />);

    expect(screen.queryByText(/releases from secured funds as each deliverable is approved/i)).toBeNull();
    expect(screen.getByText(/from the Payments panel/i)).toBeInTheDocument();
  });

  it('the viewer is actually MOUNTED in the brand deal room — an unmounted component is the finding', () => {
    const page = fs.readFileSync('src/pages/brand-chat.tsx', 'utf8');
    expect(page).toContain("from '@/components/brand/deliverables/DeliverableViewer'");
    expect(page).toMatch(/<DeliverableViewer\s/);
    expect(page).toContain('onView={setViewingDeliverableId}');
  });
});
