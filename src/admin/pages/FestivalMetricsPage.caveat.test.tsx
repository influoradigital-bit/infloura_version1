/**
 * INFLUORA ADMIN PANEL — Festival metrics measurement caveat
 * Reference: T-FESTIVALBOX-0905, [Kabir M-1 / M-2]
 *
 * Focus: the caveat the BACKEND sends with every copy-metrics response is actually rendered.
 *
 * Why this is worth a test of its own. copy_count comes from an unauthenticated public endpoint:
 * a browser reports a tap and the server increments. It is not deduplicated per person and it is
 * capped per origin rather than authenticated, so it can be moved by anyone willing to use enough
 * addresses. The risk that matters is not technical — it is a figure like "2,431 copies" reaching
 * a sponsorship deck or an invoice as though it were audited, because nothing between the table
 * and the slide ever said otherwise.
 *
 * `measurementCaveat` is the server's sentence carried to the UI so that cannot happen quietly.
 * A caveat that is sent but never rendered is exactly equivalent to no caveat at all, and no
 * type-checker or lint rule can see the difference — only this can.
 *
 * Run: npx vitest run src/admin/pages/FestivalMetricsPage.caveat.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render as rtlRender, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import FestivalMetricsPage from './FestivalMetricsPage';

const toastFn = vi.fn();
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastFn }) }));

const getCopyMetricsMock = vi.fn();

vi.mock('../services/api-contracts', async () => {
  const actual = await vi.importActual<typeof import('../services/api-contracts')>(
    '../services/api-contracts',
  );
  return {
    ...actual,
    festivalMetricsApi: {
      getCopyMetrics: (...args: unknown[]) => getCopyMetricsMock(...args),
    },
  };
});

function render(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return rtlRender(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

// The exact string the backend sends (AdminFestivalMetricsDtos.CouponCopyMetricsResponse
// .MEASUREMENT_CAVEAT). Asserted through the API rather than hardcoded in the component, so the
// backend stays the single owner of the wording.
const SERVER_CAVEAT =
  'Copy taps self-reported by the public page, not verified unique shoppers:' +
  ' not deduplicated per person, and capped per origin rather than' +
  ' authenticated. Treat as an intent trend, not an audited count.';

describe('FestivalMetricsPage — measurement caveat (M-1/M-2)', () => {
  beforeEach(() => {
    getCopyMetricsMock.mockReset();
    getCopyMetricsMock.mockResolvedValue({
      success: true,
      data: {
        edition: 'MUMBAI_FESTIVE_2026',
        sponsorTotals: [{ sponsorSlug: 'acme-corp', totalCopies: 2431 }],
        dailySeries: [{ sponsorSlug: 'acme-corp', day: '2026-09-06', copyCount: 2431 }],
        measurementCaveat: SERVER_CAVEAT,
      },
    });
  });

  async function loadMetrics() {
    const user = userEvent.setup();
    render(<FestivalMetricsPage />);
    await user.type(screen.getByLabelText(/festival edition/i), 'MUMBAI_FESTIVE_2026');
    await user.click(screen.getByRole('button', { name: /load metrics/i }));
    await waitFor(() => expect(getCopyMetricsMock).toHaveBeenCalled());
  }

  it("renders the server's caveat verbatim once metrics load", async () => {
    await loadMetrics();

    await waitFor(() =>
      expect(screen.getByText(SERVER_CAVEAT, { exact: false })).toBeInTheDocument(),
    );
  });

  it('shows the caveat in the same view as the number it qualifies', async () => {
    await loadMetrics();

    // Both on screen together. A caveat on another tab, behind a tooltip, or below a fold the
    // reader never reaches is the same as no caveat — the count and its qualification have to be
    // readable in one glance.
    // getAllByText: 2431 legitimately appears twice — once in the per-sponsor totals table and
    // once in that sponsor's daily series row.
    await waitFor(() => expect(screen.getAllByText('2431').length).toBeGreaterThan(0));
    expect(screen.getByText(SERVER_CAVEAT, { exact: false })).toBeInTheDocument();
  });

  it('labels it as not verified, not merely "not a sale"', async () => {
    await loadMetrics();

    // The page already carried a "demand signal, not a sale" note before M-1/M-2. That is a
    // different claim: it says the number is not revenue. This asserts the ADDITIONAL one — that
    // the number is not trustworthy as a count — so a future edit cannot delete the new framing on
    // the grounds that the old note "already covers it".
    expect(screen.getByText(/not a verified count/i)).toBeInTheDocument();
  });
});
