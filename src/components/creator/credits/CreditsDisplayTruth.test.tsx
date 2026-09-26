/**
 * The credits number a creator sees must be the credits they can actually spend (2026-09-24).
 *
 * 1. Pending grants count. `GET /creator/credits` keeps the welcome 40 (before the first message)
 *    and this month's 15 in `pending`, outside `total`; the next charge adds them before it checks
 *    the balance. Reading `total` alone showed a brand-new creator "0 credits", an out-of-credits
 *    banner and blocked quick actions while 40 free credits were waiting.
 * 2. Every mounted balance agrees. The Co-pilot page mounts several `useCreatorCredits` at once;
 *    a change in one must reach the others.
 * 3. The Wallet page shows the "Meera credits" card once.
 *
 * Run: npx vitest run src/components/creator/credits/CreditsDisplayTruth.test.tsx
 */
import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CreatorCreditBalance } from '@/lib/api';
import { spendableCredits, spendableFreeCredits } from '@/lib/creator-credits-balance';
import { actionBlockedReason } from '@/lib/creator-quick-actions';
import { CreditBalancePill } from './CreditBalancePill';
import { ZeroCreditsBanner } from './ZeroCreditsBanner';

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn() }));
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    api: { ...actual.api, creatorCredits: { ...actual.api.creatorCredits, get: getMock, listOrders: vi.fn().mockResolvedValue([]) } },
  };
});

// A brand-new creator: no grant written yet, the welcome 40 waiting for the first message.
const NEW_CREATOR: CreatorCreditBalance = {
  enabled: true,
  total: 0,
  free: 0,
  paid: 0,
  dailyUsed: 0,
  dailyCap: 30,
  pending: { welcome: 40, monthly: 0 },
  costs: { turn: 1, voiceTurn: 2, brief: 3, script: 3, profileReview: 3 },
};

describe('pending grants count as spendable', () => {
  it('adds pending welcome and monthly credits to total and to free', () => {
    const b = { ...NEW_CREATOR, total: 5, free: 2, paid: 3, pending: { welcome: 40, monthly: 15 } };
    expect(spendableCredits(b)).toBe(60);
    expect(spendableFreeCredits(b)).toBe(57);
    expect(spendableCredits(null)).toBe(0);
    expect(spendableCredits({ enabled: true, total: 7 })).toBe(7);
  });

  it('a new creator sees "40 credits", not an empty red pill', () => {
    render(<CreditBalancePill balance={NEW_CREATOR} language="en" />);
    const pill = screen.getByTestId('credit-balance-pill');
    expect(pill).toHaveAttribute('data-state', 'normal');
    expect(pill.textContent).toContain('40');
  });

  it('a new creator is not shown the out-of-credits banner', () => {
    const { container } = render(<ZeroCreditsBanner balance={NEW_CREATOR} language="en" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('a new creator can use "Write a script" (3 credits)', () => {
    expect(actionBlockedReason('SCRIPT', NEW_CREATOR, 'en')).toBeNull();
  });

  it('with nothing pending and nothing left, zero is still zero', () => {
    const empty = { ...NEW_CREATOR, pending: { welcome: 0, monthly: 0 } };
    render(<CreditBalancePill balance={empty} language="en" />);
    expect(screen.getByTestId('credit-balance-pill')).toHaveAttribute('data-state', 'zero');
    expect(actionBlockedReason('SCRIPT', empty, 'en')).not.toBeNull();
  });
});

describe('every mounted balance agrees', () => {
  beforeEach(() => {
    getMock.mockReset();
  });

  it('a charge in one component updates the others, and clears pending (no double count)', async () => {
    const { useCreatorCredits } = await import('@/hooks/useCreatorCredits');
    getMock.mockResolvedValue(NEW_CREATOR);
    const chat = renderHook(() => useCreatorCredits());
    const briefCard = renderHook(() => useCreatorCredits());
    await waitFor(() => expect(briefCard.result.current.balance).not.toBeNull());
    expect(spendableCredits(briefCard.result.current.balance)).toBe(40);

    // First message: the server grants the 40, charges 1, reports 39 remaining.
    act(() => chat.result.current.applyCreditsRemaining(39));

    expect(chat.result.current.balance?.total).toBe(39);
    expect(spendableCredits(chat.result.current.balance)).toBe(39);
    expect(briefCard.result.current.balance?.total).toBe(39);
    expect(spendableCredits(briefCard.result.current.balance)).toBe(39);
    chat.unmount();
    briefCard.unmount();
  });

  it('a refresh after buying credits reaches every mounted component', async () => {
    const { useCreatorCredits } = await import('@/hooks/useCreatorCredits');
    getMock.mockResolvedValue({ ...NEW_CREATOR, total: 0, pending: { welcome: 0, monthly: 0 } });
    const heroChip = renderHook(() => useCreatorCredits());
    const briefCard = renderHook(() => useCreatorCredits());
    await waitFor(() => expect(briefCard.result.current.balance).not.toBeNull());
    expect(spendableCredits(briefCard.result.current.balance)).toBe(0);

    getMock.mockResolvedValue({ ...NEW_CREATOR, total: 60, paid: 60, pending: { welcome: 0, monthly: 0 } });
    await act(async () => {
      await heroChip.result.current.refresh();
    });

    expect(spendableCredits(briefCard.result.current.balance)).toBe(60);
    heroChip.unmount();
    briefCard.unmount();
  });
});

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ user: { displayName: 'Priya Sharma', firstName: 'Priya', role: 'creator' }, logout: vi.fn() }),
}));
vi.mock('@/components/creator/credits/CreatorCreditsWalletCard', () => ({
  CreatorCreditsWalletCard: () => <div data-testid="wallet-credits-card-stub" />,
}));

describe('Wallet page', () => {
  it('shows the "Meera credits" card exactly once', async () => {
    const { default: CreatorWalletPage } = await import('@/pages/creator-wallet');
    render(
      <MemoryRouter initialEntries={['/creator/wallet']}>
        <CreatorWalletPage />
      </MemoryRouter>,
    );
    expect(screen.getAllByTestId('wallet-credits-card-stub')).toHaveLength(1);
  });
});
