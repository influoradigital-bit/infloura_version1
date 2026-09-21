/**
 * Brand Wallet — Add Funds dialog must not state a payment fee it cannot back.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The Add Funds dialog used to render a payment-method picker whose rows read "Instant
 * transfer, no fees" (UPI) and "2% convenience fee" (Credit / Debit Card). Nothing in the
 * backend charges either, and the picker was a dead control: the chosen method never reached
 * api.wallet.topUp or openRazorpayCheckout — Razorpay Checkout picks the method itself. The
 * picker was removed; the brand chooses how to pay inside Razorpay's own window.
 *
 * The dialog has no data source for a fee (the top-up order carries amount/currency/status
 * only), so ANY percentage or "no fees"-style claim rendered in it is a hard-coded literal.
 * This test fails if one renders, and fails if a method picker comes back.
 *
 * Mounted in mock mode (isApiLive -> false), same as brand-wallet.dead-controls.test.tsx:
 * the mock branch never calls api.*, and isMoneyActionBlocked is exempt in mock mode, so
 * the Add Funds trigger is enabled.
 *
 * Run: npx vitest run src/pages/__tests__/brand-wallet.add-funds-fee-claims.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import BrandWalletPage from '../brand-wallet';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => false,
  };
});

// A percentage anywhere in the dialog (e.g. "2%", "1.5 %").
const PERCENT = /\d+(?:\.\d+)?\s*%/;
// Fee / no-fee claims in the forms this surface has used or is likely to regrow.
const FEE_CLAIM =
  /\b(?:no|zero|free\s+of|without)\s+(?:any\s+)?(?:extra\s+)?(?:fees?|charges?|surcharges?)\b|\bconvenience\s+fee|\bprocessing\s+fee|\bsurcharge|\bfee[-\s]?free\b/i;

function openAddFundsDialog(): HTMLElement {
  render(
    <MemoryRouter>
      <BrandWalletPage />
    </MemoryRouter>,
  );
  const trigger = screen.getByRole('button', { name: /^\s*add funds\s*$/i });
  expect(trigger).not.toBeDisabled();
  fireEvent.click(trigger);
  return screen.getByRole('dialog');
}

describe('BrandWalletPage — Add Funds dialog fee claims', () => {
  it('controls: the patterns match the strings this dialog used to ship, and not honest copy', () => {
    // Positive controls — the original literals must trip the patterns.
    expect(PERCENT.test('2% convenience fee')).toBe(true);
    expect(FEE_CLAIM.test('2% convenience fee')).toBe(true);
    expect(FEE_CLAIM.test('Instant transfer, no fees')).toBe(true);
    // Negative controls — the replacement copy must not.
    const honest =
      'You’ll choose how to pay — UPI, card or net banking — in the secure Razorpay window that opens next.';
    expect(PERCENT.test(honest)).toBe(false);
    expect(FEE_CLAIM.test(honest)).toBe(false);
  });

  it('renders no percentage and no fee / no-fee claim', () => {
    const dialog = openAddFundsDialog();
    const text = (dialog.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toMatch(/add funds to wallet/i); // the dialog really opened
    expect(text).not.toMatch(PERCENT);
    expect(text).not.toMatch(FEE_CLAIM);
  });

  it('has no payment-method picker (Razorpay Checkout chooses the method)', () => {
    const dialog = openAddFundsDialog();
    const buttons = within(dialog).queryAllByRole('button', {
      name: /\bupi\b|credit\s*\/?\s*debit\s+card|net\s*banking/i,
    });
    expect(buttons).toHaveLength(0);
    expect(within(dialog).getByText(/razorpay window/i)).toBeInTheDocument();
  });
});
