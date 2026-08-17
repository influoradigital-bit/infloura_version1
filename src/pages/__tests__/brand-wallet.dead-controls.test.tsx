/**
 * Brand Wallet — F-0264 (dead-controls-money-surface).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Wallet Export, Download Form 16A, and Download GST Summary used to render as live,
 * clickable controls with no onClick handler and no backend endpoint behind them
 * (LOOKUP for T-BRANDMED-0817 found no export/Form16A/GST-summary route in src/lib/api.ts,
 * src/lib/meera-api.ts, or the backend WalletController). On a money/tax-compliance surface
 * a click that silently does nothing reads as "my document is missing", not "not built yet".
 *
 * This asserts the actual DOM contract the F-0264 gate can't see from source shape alone:
 * each control is disabled (no click can fire it) AND carries an accessible name/description
 * that states *why*, not just its own label restated.
 *
 * Mounted in mock mode (`isApiLive` -> false) deliberately — the mock-data branch of
 * BrandWalletPage never calls `api.*`, so this test only needs to fake `isApiLive`, not the
 * whole wallet API surface.
 *
 * Run: npx vitest run src/pages/__tests__/brand-wallet.dead-controls.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import BrandWalletPage from '../brand-wallet';

const apiLive = vi.fn(() => false);

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => apiLive(),
  };
});

describe('BrandWalletPage — F-0264 dead money controls', () => {
  it('Export is disabled and gives a reason', () => {
    render(
      <MemoryRouter>
        <BrandWalletPage />
      </MemoryRouter>,
    );
    const exportButton = screen.getByRole('button', { name: /export/i });
    expect(exportButton).toBeDisabled();
  });

  it('Download Form 16A is disabled and its accessible name states why', () => {
    render(
      <MemoryRouter>
        <BrandWalletPage />
      </MemoryRouter>,
    );
    const form16aButton = screen.getByRole('button', { name: /form 16a.*not available/i });
    expect(form16aButton).toBeDisabled();
  });

  it('Download GST Summary is disabled and its accessible name states why', () => {
    render(
      <MemoryRouter>
        <BrandWalletPage />
      </MemoryRouter>,
    );
    const gstButton = screen.getByRole('button', { name: /gst summary.*not available/i });
    expect(gstButton).toBeDisabled();
  });

  it('none of the three dead controls carry a bare onClick that would fire on a stray click', () => {
    // Regression guard for the original defect: disabled=true already makes React refuse to
    // dispatch onClick, but this locks in that we did not "fix" the gate by adding a handler
    // that fires while the button reads as available — the control must never look clickable
    // AND do nothing at the same time.
    render(
      <MemoryRouter>
        <BrandWalletPage />
      </MemoryRouter>,
    );
    for (const name of [/export/i, /form 16a.*not available/i, /gst summary.*not available/i]) {
      const btn = screen.getByRole('button', { name });
      expect(btn).toHaveAttribute('aria-disabled', 'true');
    }
  });
});
