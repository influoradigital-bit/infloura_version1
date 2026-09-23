import { afterEach, describe, expect, it, vi } from 'vitest';

import type { CreatorDealsPageRow } from '@/lib/creator-deal-mappers';

const { listForDealsMock, listUnsignedMock } = vi.hoisted(() => ({
  listForDealsMock: vi.fn(),
  listUnsignedMock: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  api: {
    creatorDeliverables: { listForDeals: (...args: unknown[]) => listForDealsMock(...args) },
    contracts: { listUnsigned: (...args: unknown[]) => listUnsignedMock(...args) },
  },
}));

import {
  computeDealAttentionCounts,
  countSubmittableDeliverables,
  fetchAwaitingSignatureCount,
  isActiveDeal,
  loadDeliverablePendingCount,
  totalAttentionCount,
} from './creator-needs-attention';

function row(overrides: Partial<CreatorDealsPageRow> = {}): CreatorDealsPageRow {
  return {
    id: 'd1',
    brandId: 'b1',
    brandName: 'Brand',
    brandVerified: false,
    campaignTitle: 'Campaign',
    status: 'new',
    budget: 0,
    deliverables: [],
    deadline: '',
    unreadCount: 0,
    deliverablesDone: 0,
    deliverablesTotal: 0,
    escrowFunded: false,
    ...overrides,
  };
}

describe('isActiveDeal', () => {
  it.each(['contracted', 'in_progress', 'review'] as const)('treats %s as active', (status) => {
    expect(isActiveDeal(row({ status }))).toBe(true);
  });

  it.each(['new', 'negotiating', 'completed', 'disputed'] as const)('treats %s as NOT active', (status) => {
    expect(isActiveDeal(row({ status }))).toBe(false);
  });
});

describe('countSubmittableDeliverables', () => {
  it('counts only PENDING/DRAFT/REVISION_REQUESTED items that are not completed', () => {
    const items = [
      { id: '1', status: 'PENDING', completed: false },
      { id: '2', status: 'DRAFT', completed: false },
      { id: '3', status: 'REVISION_REQUESTED', completed: false },
      { id: '4', status: 'PENDING', completed: true },
      { id: '5', status: 'APPROVED', completed: false },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ] as any[];
    expect(countSubmittableDeliverables(items)).toBe(3);
  });
});

describe('loadDeliverablePendingCount', () => {
  afterEach(() => vi.clearAllMocks());

  it('short-circuits to 0 with no dealIds — never calls the API', async () => {
    expect(await loadDeliverablePendingCount([])).toBe(0);
    expect(listForDealsMock).not.toHaveBeenCalled();
  });

  it('sums submittable deliverables across the batched response', async () => {
    listForDealsMock.mockResolvedValue({
      d1: [{ id: 'x', status: 'PENDING', completed: false }],
      d2: [{ id: 'y', status: 'DRAFT', completed: false }, { id: 'z', status: 'APPROVED', completed: false }],
    });
    expect(await loadDeliverablePendingCount(['d1', 'd2'])).toBe(2);
    expect(listForDealsMock).toHaveBeenCalledWith(['d1', 'd2']);
  });

  it('fails soft to 0 per deal when the batched call rejects', async () => {
    listForDealsMock.mockRejectedValue(new Error('down'));
    expect(await loadDeliverablePendingCount(['d1'])).toBe(0);
  });
});

describe('computeDealAttentionCounts', () => {
  afterEach(() => vi.clearAllMocks());

  it('sums unreadCount over EVERY deal, not just active ones', async () => {
    listForDealsMock.mockResolvedValue({});
    const deals = [
      row({ id: 'd1', status: 'new', unreadCount: 2 }),
      row({ id: 'd2', status: 'contracted', unreadCount: 3 }),
    ];
    const counts = await computeDealAttentionCounts(deals);
    expect(counts.unreadMessages).toBe(5);
  });

  it('only fetches deliverables for ACTIVE deal ids', async () => {
    listForDealsMock.mockResolvedValue({ d2: [{ id: 'x', status: 'PENDING', completed: false }] });
    const deals = [
      row({ id: 'd1', status: 'new', unreadCount: 0 }),
      row({ id: 'd2', status: 'contracted', unreadCount: 0 }),
    ];
    const counts = await computeDealAttentionCounts(deals);
    expect(listForDealsMock).toHaveBeenCalledWith(['d2']);
    expect(counts.submittableDeliverables).toBe(1);
  });
});

describe('fetchAwaitingSignatureCount', () => {
  it('returns the length of GET /contracts/unsigned', async () => {
    listUnsignedMock.mockResolvedValue([{ id: 'c1' }, { id: 'c2' }]);
    expect(await fetchAwaitingSignatureCount()).toBe(2);
  });
});

describe('totalAttentionCount', () => {
  it('sums unread + submittable deliverables + awaiting signature', () => {
    expect(totalAttentionCount({ unreadMessages: 2, submittableDeliverables: 1 }, 3)).toBe(6);
  });
});
