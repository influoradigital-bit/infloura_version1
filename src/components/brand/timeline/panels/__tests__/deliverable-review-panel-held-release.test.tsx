/**
 * [F-0471, partial-fix-narrows-defect] Approving is what pays the creator.
 *
 * F-0406 fixed the backend so a held release stops masquerading as success: `approve` returns
 * `ReviewResponse(status, paymentReleased, paymentHeldReason)`. F-0471 is the residual — the
 * frontend had FOUR approval surfaces and two of them threw that outcome away, so a brand
 * approving from those saw plain success while no money moved.
 *
 * This panel was one of the two. The repo-wide rule lives in `.proof-os/gates/_f0471_scan.py`
 * (every approval call site must branch on `paymentReleased`, which is what the finding's own
 * missed_by asked for); this file proves the branch actually REACHES THE USER on the surface that
 * was broken, rather than merely existing in the source.
 *
 * Run: npx vitest run src/components/brand/timeline/panels/__tests__/deliverable-review-panel-held-release.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DeliverableReviewPanel } from '../deliverable-review-panel';
import { toast } from '@/hooks/use-toast';
import type { TimelineEvent } from '@/lib/types';

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

const approveMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    deliverables: { ...actual.deliverables, approve: (...a: unknown[]) => approveMock(...a) },
  };
});

const EVENT: TimelineEvent = {
  id: 'evt-1',
  collaborationId: 'deal-1',
  timestamp: new Date('2026-09-01T10:00:00Z'),
  senderId: 'creator-1',
  senderType: 'creator',
  senderName: 'Priya Creates',
  tag: 'deliverable',
  content: 'Reel v1',
  status: 'delivered',
  metadata: { deliverableId: 'dlv-1' },
};

function renderPanel() {
  return render(
    <DeliverableReviewPanel
      open
      onOpenChange={() => {}}
      event={EVENT}
      currentUserType="brand"
    />,
  );
}

describe('DeliverableReviewPanel — F-0471 a held release must reach the brand', () => {
  beforeEach(() => {
    approveMock.mockReset();
    vi.mocked(toast).mockClear();
  });

  it('warns that payment was NOT released when the server holds it', async () => {
    const user = userEvent.setup();
    // The server did approve — it just did not pay, and it did not throw. That combination is the
    // entire defect: a `catch` block can never see this.
    approveMock.mockResolvedValue({
      status: 'APPROVED',
      paymentReleased: false,
      paymentHeldReason: 'MILESTONE_NOT_FUNDED',
    });

    renderPanel();
    await user.click(await screen.findByRole('button', { name: /approve/i }));

    await waitFor(() => expect(approveMock).toHaveBeenCalledWith('dlv-1'));
    expect(vi.mocked(toast)).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Approved — but payment was NOT released',
        variant: 'destructive',
      }),
    );
  });

  it('confirms the payment when the release really happened', async () => {
    const user = userEvent.setup();
    approveMock.mockResolvedValue({
      status: 'APPROVED',
      paymentReleased: true,
      paymentHeldReason: null,
    });

    renderPanel();
    await user.click(await screen.findByRole('button', { name: /approve/i }));

    await waitFor(() => expect(approveMock).toHaveBeenCalled());
    // The happy path must NOT inherit the warning — an always-destructive toast would pass the
    // test above while crying wolf on every successful payment.
    expect(vi.mocked(toast)).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Deliverable approved' }),
    );
    expect(vi.mocked(toast)).not.toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'destructive' }),
    );
  });
});
