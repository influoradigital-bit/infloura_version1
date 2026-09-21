/**
 * Creator Wallet page — Kv3b (Kavya)
 * Covers G-Kv3-A3: platform fee label after mock fetch (L-31-3).
 *
 * Run: npx vitest run src/pages/creator-wallet.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorWalletPage from './creator-wallet';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { displayName: 'Priya Sharma', firstName: 'Priya', role: 'creator' },
    logout: vi.fn(),
  }),
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/wallet']}>
      <CreatorWalletPage />
    </MemoryRouter>,
  );
}

describe('CreatorWalletPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the wallet header and offers payout details, not a withdraw CTA', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Wallet' })).toBeInTheDocument();
    expect(screen.getByText(/Track your earnings and payouts/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Payout details/i })).toBeInTheDocument();
  });

  it('offers no withdraw control at all, and says who pays instead', async () => {
    renderPage();

    // paytrigger — self-serve withdrawal does not exist (owner's ruling, 2026-09-21). A
    // DISABLED Withdraw button would still pass a "does it move money?" check while promising a
    // feature nobody is building, so the assertion is that no such control is rendered in any
    // state, enabled or not.
    expect(screen.queryByRole('button', { name: /withdraw/i })).toBeNull();

    expect(await screen.findByRole('heading', { name: 'How you get paid' })).toBeInTheDocument();
    const panel = (await screen.findByRole('heading', { name: 'How you get paid' })).closest('div');
    expect(panel).toHaveTextContent(/bank transfer \(NEFT\/IMPS\)/i);
    expect(panel).toHaveTextContent(/within 2 working days/i);
    // The trigger is the live link, not the approval.
    expect(panel).toHaveTextContent(/does not pay you, the live link does/i);
  });

  it('shows platform fee percent after mock fetch (G-Kv3-A3)', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/Platform fee:\s*15%/i)).toBeInTheDocument();
    });
    expect(
      screen.getByText(/Deducted when campaign earnings are released from secured funds/i),
    ).toBeInTheDocument();
  });

  it('renders payouts / history / tax tabs in demo mode', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: 'Payouts' })).toBeInTheDocument();
    });
    expect(screen.getByRole('tab', { name: 'History' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Tax Docs' })).toBeInTheDocument();
  });

  // F-0281 — the three headline money figures (Available Balance / In Escrow / Pending
  // Payouts) had no distinguishing copy: no tooltip, no helper text, same size/weight.
  // Each now carries a keyboard-reachable info trigger with a plain-language definition.
  // Focus (not just hover/click) opens Radix Tooltip content, so these assert on `focus`
  // directly — the same path a keyboard-only user relies on.
  describe('F-0281: wallet figure definitions', () => {
    it('Available Balance defines itself as already-released money Influora sends on', async () => {
      renderPage();

      const trigger = await screen.findByRole('button', { name: 'What is Available Balance?' });
      fireEvent.focus(trigger);

      // Radix TooltipContent renders the visible bubble AND a visually-hidden
      // `role="tooltip"` mirror of the same text for screen readers — two real DOM nodes
      // by design, hence findAllByText rather than findByText.
      const matches = await screen.findAllByText(
        /Released to you\. Influora transfers it to the account in your payout details/i,
      );
      expect(matches.length).toBeGreaterThanOrEqual(1);
    });

    it('Secured is distinguished from Available Balance as brand-locked, not-yet-released funds', async () => {
      renderPage();

      const trigger = await screen.findByRole('button', { name: 'What is Secured?' });
      fireEvent.focus(trigger);

      const [definition] = await screen.findAllByText(
        /Funds a brand has locked for a deal that's still in progress/i,
      );
      expect(definition).toBeInTheDocument();
      // The whole point of F-0281: this must NOT read like the Available Balance figure —
      // it must say the money is not the creator's yet.
      expect(definition.textContent).toMatch(/not yours yet/i);
    });

    it('Pending Payouts explains itself as a transfer already in flight to the bank — distinct from Secured', async () => {
      renderPage();

      const trigger = await screen.findByRole('button', { name: 'What is Pending Payouts?' });
      fireEvent.focus(trigger);

      const [definition] = await screen.findAllByText(
        /A transfer Influora has already started to your bank/i,
      );
      expect(definition).toBeInTheDocument();
      // F-0281/F-0336 — this field previously held the SAME funded-milestone figure as
      // "In Escrow" under a misleading label. The fix (WalletService#getSummaryForUser)
      // now derives it from real in-flight Payout rows instead, so the two definitions
      // must actually differ, not just be worded differently.
      expect(definition.textContent).not.toMatch(/locked for a deal/i);
    });
  });
});
