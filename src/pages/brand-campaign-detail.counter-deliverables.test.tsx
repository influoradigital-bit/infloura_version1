/**
 * hirepath (2026-09-21) — the campaign page's Bids counter must ORDER something.
 *
 * WHY THIS FILE EXISTS
 * ----------------------------------------------------------------------------
 * This dialog had two inputs, an amount and a free-text message, and sent exactly those. The
 * common case on this tab is countering an APPLICATION, which arrives with a price and a pitch
 * but no structured order and no earlier proposal card — so the server had nothing to carry
 * forward either. The offer went out ordering nothing; if the creator accepted it, the contract
 * materialised zero submission slots and neither side had a control that could create one. The
 * only thing the brand ever saw was a 409 at accept telling them to "send a counter offer that
 * lists the deliverables" — from this dialog, which had no such field.
 *
 * The sibling unit test (`f0432-counter-usage-rights.test.ts`) pins the request BUILDER. This one
 * exists because a builder that is never reached is worth nothing: it drives the real dialog, so
 * a control that renders but is not wired to the payload fails here.
 *
 * Run: npx vitest run src/pages/brand-campaign-detail.counter-deliverables.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn(), useParams: () => ({ id: 'camp-1' }) };
});

const campaignsGet = vi.fn();
const dealsList = vi.fn();
const dealsCounter = vi.fn();
const messagesList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      campaigns: {
        ...actual.api.campaigns,
        get: (...a: unknown[]) => campaignsGet(...a),
        analytics: vi.fn().mockResolvedValue(null),
      },
      deals: {
        ...actual.api.deals,
        list: (...a: unknown[]) => dealsList(...a),
        counter: (...a: unknown[]) => dealsCounter(...a),
      },
      messages: { ...actual.api.messages, list: (...a: unknown[]) => messagesList(...a) },
    },
  };
});

import BrandCampaignDetailPage from './brand-campaign-detail';

const campaign = {
  id: 'camp-1',
  workspaceId: 'ws-1',
  title: 'Summer Launch',
  description: 'desc',
  objectives: ['Awareness'],
  status: 'ACTIVE' as const,
  budget: { min: 10000, max: 50000, currency: 'INR' },
  timeline: { startDate: new Date('2026-08-01'), endDate: new Date('2026-09-01') },
  platforms: ['INSTAGRAM' as const],
  contentTypes: ['REEL' as const],
  requirements: [],
  isPrivate: false,
  maxCollaborators: 5,
  createdBy: 'u1',
  createdAt: new Date('2026-08-01'),
  updatedAt: new Date('2026-08-01'),
};

/** An APPLICATION: priced, pitched, and carrying no structured order at all. */
const application = {
  id: 'deal-app-1',
  campaignId: 'camp-1',
  campaignName: 'Summer Launch',
  counterpartyId: 'cr-1',
  counterpartyName: 'Priya Sharma',
  status: 'APPLIED',
  dealValue: 25000,
  currency: 'INR',
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 0,
};

async function openCounterDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('tab', { name: /Bids/i }));
  await user.click(await screen.findByRole('button', { name: /^Counter$/i }));
  return screen.findByRole('dialog');
}

beforeEach(() => {
  vi.clearAllMocks();
  campaignsGet.mockResolvedValue(campaign);
  dealsList.mockResolvedValue([application]);
  messagesList.mockResolvedValue([]);
  dealsCounter.mockResolvedValue({ id: 'deal-app-1' });
});

describe('campaign detail — Bids counter carries the ordered deliverables', () => {
  it('sends the deliverables the brand chose, as enum wire names with qty', async () => {
    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    const dialog = await openCounterDialog(user);

    // The control that did not exist: a real type picker, not a label.
    await user.click(within(dialog).getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name: 'YouTube Video' }));

    const qty = within(dialog).getByLabelText('How many');
    await user.clear(qty);
    await user.type(qty, '2');

    await user.click(within(dialog).getByRole('button', { name: /Send counter offer/i }));

    await waitFor(() => expect(dealsCounter).toHaveBeenCalledTimes(1));
    const [dealId, payload] = dealsCounter.mock.calls[0] as [
      string,
      { amount: number; deliverables: Array<{ type: string; qty: number }> },
    ];
    expect(dealId).toBe('deal-app-1');
    // The whole point: the order travels with the price.
    expect(payload.deliverables).toEqual([{ type: 'YOUTUBE_VIDEO', qty: 2 }]);
    expect(payload.amount).toBe(25000);
  });

  it('refuses to send an offer that orders nothing, on the screen that can fix it', async () => {
    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    const dialog = await openCounterDialog(user);

    const qty = within(dialog).getByLabelText('How many');
    await user.clear(qty);
    await user.type(qty, '0');

    // Nothing ordered -> the submit control is inert, and nothing reaches the server. This used
    // to sail through and only fail much later, at accept, with an unactionable 409.
    expect(within(dialog).getByRole('button', { name: /Send counter offer/i })).toBeDisabled();
    expect(dealsCounter).not.toHaveBeenCalled();
  });

  it('seeds the dialog from the offer already on the table instead of a blank guess', async () => {
    const user = userEvent.setup({ delay: null });
    messagesList.mockResolvedValue([
      {
        id: 'm1',
        dealId: 'deal-app-1',
        kind: 'proposal',
        senderId: 'b1',
        senderType: 'brand',
        createdAt: '2026-09-01T00:00:00Z',
        readBy: [],
        metadata: { amount: 30000, deliverables: [{ type: 'INSTAGRAM_STORY', qty: 3 }] },
      },
    ]);

    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    const dialog = await openCounterDialog(user);

    await waitFor(() =>
      expect(within(dialog).getByRole('combobox')).toHaveTextContent('Instagram Story'),
    );
    expect(within(dialog).getByLabelText('How many')).toHaveValue(3);

    await user.click(within(dialog).getByRole('button', { name: /Send counter offer/i }));
    await waitFor(() => expect(dealsCounter).toHaveBeenCalledTimes(1));
    const payload = dealsCounter.mock.calls[0][1] as {
      deliverables: Array<{ type: string; qty: number }>;
    };
    // A price-only counter must re-send the same scope, not quietly drop or change it.
    expect(payload.deliverables).toEqual([{ type: 'INSTAGRAM_STORY', qty: 3 }]);
  });
});
