/**
 * Brand Messages — `?creator=<CreatorProfile id>` URL matching (CR: Message button dead-ends).
 *
 * brand-creator-profile.tsx's Message button navigates to
 * `/brand/messages?creator=${creator.id}` where `creator.id` is the creator's CreatorProfile id
 * (from GET /creators/profile/:usernameOrId — CreatorPublicProfile.id). This page used to match
 * that param against `deal.counterpartyId`, which is the creator's User id — a different id — so
 * a brand with a real, message-bearing deal for that creator always landed on the "no
 * conversation yet" empty state.
 *
 * Fix: DealResponse now also carries `counterpartyProfileId` (the CreatorProfile id). The
 * Conversation view-model carries it through as `creatorProfileId`, and URL-matching compares
 * against that field instead of `creator.id` (which stays the User id everywhere else it's read,
 * e.g. DealMessage.readBy membership).
 *
 * Run: npx vitest run src/pages/brand-messages.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { Deal, DealMessage } from '@/lib/api';
import BrandMessagesPage from './brand-messages';

const dealsList = vi.fn();
const messagesList = vi.fn();
const messagesSend = vi.fn();
const messagesMarkRead = vi.fn();
const messagesStream = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    deals: { list: (...a: unknown[]) => dealsList(...a) },
    messages: {
      list: (...a: unknown[]) => messagesList(...a),
      send: (...a: unknown[]) => messagesSend(...a),
      markRead: (...a: unknown[]) => messagesMarkRead(...a),
      stream: (...a: unknown[]) => messagesStream(...a),
    },
  };
});

/** The creator's User id — DealResponse.counterpartyId. Deliberately different from the
 *  CreatorProfile id below, mirroring production where the two ids never collide. */
const CREATOR_USER_ID = 'user_789';
/** The creator's CreatorProfile id — DealResponse.counterpartyProfileId. This is what
 *  brand-creator-profile.tsx's Message button puts in the `?creator=` URL param. */
const CREATOR_PROFILE_ID = 'profile_123';

const REAL_DEAL: Deal = {
  id: 'deal_1',
  campaignId: 'camp_1',
  campaignName: 'Summer Launch',
  counterpartyId: CREATOR_USER_ID,
  counterpartyProfileId: CREATOR_PROFILE_ID,
  counterpartyName: 'Aarti Menon',
  counterpartyHandle: '@aarti',
  counterpartyAvatar: '',
  status: 'IN_PROGRESS',
  dealValue: 40000,
  currency: 'INR',
  lastMessage: 'Looking forward to it',
  lastMessageAt: new Date('2026-07-20T10:00:00Z').toISOString(),
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 2,
  escrowFunded: false,
};

const DEAL_MESSAGES: DealMessage[] = [
  {
    id: 'm_1',
    dealId: 'deal_1',
    kind: 'text',
    senderId: CREATOR_USER_ID,
    senderType: 'creator',
    content: 'Looking forward to it',
    createdAt: new Date('2026-07-20T10:00:00Z').toISOString(),
    readBy: [],
  },
];

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <BrandMessagesPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  messagesMarkRead.mockResolvedValue({ ok: true });
  messagesStream.mockReturnValue({ close: () => {} });
});

describe('BrandMessagesPage — ?creator= URL matching against CreatorProfile id', () => {
  it('opens the EXISTING conversation when arriving via a CreatorProfile id that has a real deal', async () => {
    dealsList.mockResolvedValue([REAL_DEAL]);
    messagesList.mockResolvedValue(DEAL_MESSAGES);

    renderAt(`/brand/messages?creator=${CREATOR_PROFILE_ID}`);

    // The deal's thread opened — header shows the counterparty name, not the empty state.
    expect(await screen.findByRole('heading', { name: 'Aarti Menon' })).toBeInTheDocument();
    expect(screen.queryByText('No conversation yet')).not.toBeInTheDocument();
    expect(screen.queryByText('Select a conversation')).not.toBeInTheDocument();

    // Confirms the fix specifically: matching succeeded on counterpartyProfileId, NOT
    // counterpartyId (the User id) — if the old buggy comparison were still in place this
    // would never have matched, since CREATOR_PROFILE_ID !== CREATOR_USER_ID here.
    expect(messagesList).toHaveBeenCalledWith('brand', 'deal_1');
  });

  it('still shows the honest "no conversation yet" empty state for a genuine no-deal creator (no false positive)', async () => {
    dealsList.mockResolvedValue([REAL_DEAL]); // only a deal for CREATOR_PROFILE_ID exists
    messagesList.mockResolvedValue([]);

    renderAt('/brand/messages?creator=profile_does_not_exist');

    expect(await screen.findByText('No conversation yet')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Aarti Menon' })).not.toBeInTheDocument();
    expect(messagesList).not.toHaveBeenCalled();
  });

  it('does NOT match on the User id (pre-fix regression guard)', async () => {
    dealsList.mockResolvedValue([REAL_DEAL]);
    messagesList.mockResolvedValue(DEAL_MESSAGES);

    // Arriving with the User id instead of the CreatorProfile id must NOT match — proves the
    // comparison is genuinely keyed on counterpartyProfileId, not counterpartyId.
    renderAt(`/brand/messages?creator=${CREATOR_USER_ID}`);

    expect(await screen.findByText('No conversation yet')).toBeInTheDocument();
  });
});
