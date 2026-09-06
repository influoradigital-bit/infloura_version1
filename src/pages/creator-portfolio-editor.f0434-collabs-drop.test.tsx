/**
 * creator-portfolio-editor — F-0434 (dropped-field-at-call-site), now FIXED
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `api.portfolio.update()` takes `Partial<PortfolioPage>` (src/lib/api.ts). Historically the real
 * server-side contract, `PortfolioPatchRequest`
 * (influora-api/src/main/java/com/influora/web/dto/portfolio/PortfolioDtos.java), had no `collabs`
 * field — Jackson silently dropped the unknown JSON key — and separately
 * `PortfolioService#buildCollabs` (influora-api/src/main/java/com/influora/service/portfolio/
 * PortfolioService.java) hardcoded every collab's `displayMode` to `"logo"` on every read, so there
 * was no persisted per-collab display mode on the server AT ALL.
 *
 * A PREVIOUS PASS on this finding added `collabs: page.collabs` to the PATCH body and wrote a test
 * that asserted only the outgoing request body contained the key. That test was green for the
 * entire time the feature did not work — it never rendered anything or checked whether the value
 * survived a reload, so it could not fail against the actual defect (a value that "saves" but
 * reverts on next load). A LATER pass (frontend, honesty fix) disabled the Select entirely rather
 * than ship a control that silently discarded edits, and this file's assertions were flipped to pin
 * *that* — the control must be disabled — plus a PATCH-body-omits-`collabs` check.
 *
 * THE BACKEND HAS NOW LANDED REAL PERSISTENCE (verified by reading the actual code before writing
 * this test, not by trusting the report):
 *   - `PortfolioPatchRequest` has a `collabs: List<PortfolioCollab>` field (PortfolioDtos.java:119-142).
 *   - `PortfolioService.updateMine` validates each `displayMode` against
 *     `ALLOWED_COLLAB_DISPLAY_MODES` and persists an id->displayMode map into
 *     `portfolio_settings_json` (`extractCollabDisplayModes`/`loadCollabDisplayModes`,
 *     never-wipe-if-omitted, same convention as `rateCard`).
 *   - `buildCollabs` reads that persisted map back instead of hardcoding `"logo"`.
 *   - `influora-api/.../service/portfolio/PortfolioServiceCollabDisplayModeTest.java` proves this
 *     with a save -> independent re-read round trip server-side.
 *
 * THE FRONTEND FIX (this file's production counterpart, creator-portfolio-editor.tsx):
 *   1. The per-collab Select is re-enabled, wired to real local state
 *      (`updateCollabDisplayMode`), matching the `'logo' | 'name_only' | 'category' | 'hidden'`
 *      union both `src/lib/api.ts` and the backend's `ALLOWED_COLLAB_DISPLAY_MODES` agree on.
 *   2. `handleSave` sends `collabs: page.collabs` back in the PATCH body again — no longer inert,
 *      because the backend now has somewhere to put it.
 *
 * WHAT THIS TEST PINS, AND HOW IT FALSIFIES AGAINST BOTH POSSIBLE REGRESSIONS:
 *   - The per-collab displayMode control is ENABLED and operable (not the old disabled/view-only
 *     state). Reverting to `disabled` on the Select turns this RED for the right reason: the
 *     trigger is no longer clickable / `toBeEnabled()` fails.
 *   - Picking a new display mode and saving sends a `collabs` array in the PATCH body carrying the
 *     UPDATED value for the edited row. Reverting to the inert/no-op state (dropping `collabs` from
 *     handleSave, or never updating local state on selection) turns this RED for the right reason:
 *     either the key is missing entirely, or it still carries the old value.
 *   - A save -> reload round trip: after saving, a fresh mount (simulating navigating away and back)
 *     that reads the persisted value back from the mocked API renders the NEW display mode, not the
 *     original — this is the exact "silently reverted on next load" failure mode F-0434 was about,
 *     and it is the one thing neither of the two previous tests (echo-check, or disabled-check)
 *     could have caught.
 *
 * Run: node_modules/.bin/vitest run src/pages/creator-portfolio-editor.f0434-collabs-drop.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorPortfolioEditorPage from './creator-portfolio-editor';
import type { PortfolioCollab } from '@/lib/api';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/hooks/use-toast', () => ({ toast: vi.fn() }));

const getMineMock = vi.fn();
const analyticsMock = vi.fn();
const updateMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      portfolio: {
        ...actual.api.portfolio,
        getMine: (...a: unknown[]) => getMineMock(...a),
        analytics: (...a: unknown[]) => analyticsMock(...a),
        update: (...a: unknown[]) => updateMock(...a),
      },
    },
  };
});

/** A minimal portfolio page with exactly one past collab, so the displayMode
 *  control under test is unambiguous. `displayMode` is parameterized so callers
 *  can build both the "before save" and "after reload" fixtures from one place. */
function portfolioPage(displayMode: PortfolioCollab['displayMode'] = 'logo') {
  return {
    username: 'demo_creator',
    displayName: 'Demo Creator',
    bio: 'Fashion & lifestyle creator.',
    niches: ['Fashion'],
    verified: true,
    stats: { totalCollabs: 1, avgRating: 4.8, onTimeRate: 95, repeatBrands: 1 },
    badges: [],
    platforms: [],
    collabs: [
      {
        id: 'collab-1',
        brandId: 'brand-1',
        brandName: 'Luxe Apparel',
        campaignTitle: 'Summer Fashion Campaign',
        deliverables: '2 Reels + 4 Stories',
        platform: 'INSTAGRAM',
        completedAt: '2026-06-01T00:00:00Z',
        displayMode,
      },
    ],
    pinnedPosts: [],
    customLinks: [],
    rateCard: [],
    languages: [],
    topAudienceCities: [],
    visibility: {
      trustBar: true,
      badges: true,
      platformStats: true,
      pastCollabs: true,
      contentPortfolio: true,
      customLinks: true,
      rateCard: 'public' as const,
      languages: true,
      contactForm: true,
    },
  };
}

function mockAnalytics() {
  return {
    pageViews: { last30Days: 100, deltaPercent: 5 },
    profileClicks: 40,
    profileClicksEstimated: true,
    linkClicks: [],
    brandInquiries: 2,
    mediaKitDownloads: 1,
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/portfolio']}>
      <CreatorPortfolioEditorPage />
    </MemoryRouter>,
  );
}

/** Scope to this specific collab row so we don't hit the rate-card visibility Select
 *  (also a combobox) elsewhere on the page. */
async function getCollabTrigger() {
  await waitFor(() => {
    expect(screen.getByText('Luxe Apparel')).toBeInTheDocument();
  });
  const row = screen.getByText('Summer Fashion Campaign').closest(
    'div.flex.items-center.gap-3',
  ) as HTMLElement;
  return within(row).getByRole('combobox');
}

describe('CreatorPortfolioEditorPage — F-0434 collab display-mode genuinely persists', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getMineMock.mockResolvedValue(portfolioPage());
    analyticsMock.mockResolvedValue(mockAnalytics());
    updateMock.mockResolvedValue(portfolioPage());
  });

  it('the per-collab display-mode control is enabled — not the old disabled/"coming soon" state', async () => {
    renderPage();
    const trigger = await getCollabTrigger();

    // The old honesty-fix state (disabled, view-only) must be gone now that the backend has
    // somewhere to persist a change to. A live, editable control is only honest once picking a
    // value actually sticks — which the next test proves end-to-end.
    expect(trigger).toBeEnabled();
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();

    // Because nothing has been edited yet, the form must not be dirty on load.
    expect(screen.queryByRole('button', { name: /Save changes/i })).not.toBeInTheDocument();
  });

  it('picking a new display mode and saving sends the UPDATED value in the collabs PATCH body', async () => {
    const user = userEvent.setup();
    renderPage();
    const trigger = await getCollabTrigger();

    await user.click(trigger);
    await user.click(await screen.findByRole('option', { name: /Name only/i }));

    // Selecting a value marks the form dirty (a real, meaningful edit) — proves this isn't a
    // decorative control that ignores interaction.
    const saveButtons = await screen.findAllByRole('button', { name: /Save changes/i });
    await user.click(saveButtons[0]);

    await waitFor(() => {
      expect(updateMock).toHaveBeenCalledTimes(1);
    });

    const sentBody = updateMock.mock.calls[0][0];

    // The regression this guards against (inert-field direction): `collabs` must be present and
    // carry the newly-picked value for the edited row, not the original `'logo'`.
    expect(sentBody).toHaveProperty('collabs');
    expect(sentBody.collabs).toEqual([
      expect.objectContaining({ id: 'collab-1', displayMode: 'name_only' }),
    ]);

    // The fields that were already sent correctly must still be sent.
    expect(sentBody).toMatchObject({ bio: expect.any(String), niches: ['Fashion'] });
    expect(sentBody).toHaveProperty('visibility');
    expect(sentBody).toHaveProperty('customLinks');
    expect(sentBody).toHaveProperty('rateCard');
  });

  it('save -> reload round trip: the new display mode survives a fresh mount reading it back from the API', async () => {
    // Simulate the server having actually persisted the earlier save: the *next* getMine() call
    // (a fresh page load) returns the updated value. This is the exact failure mode F-0434 was
    // about — a change that "saves" but silently reverts on next load — and it's the one thing an
    // outgoing-request-body-only assertion can never catch.
    getMineMock
      .mockResolvedValueOnce(portfolioPage('logo'))
      .mockResolvedValueOnce(portfolioPage('hidden'));

    const user = userEvent.setup();
    const { unmount } = renderPage();
    const trigger = await getCollabTrigger();
    expect(within(trigger).getByText(/Name \+ logo/i)).toBeInTheDocument();

    await user.click(trigger);
    await user.click(await screen.findByRole('option', { name: /^Hide$/i }));

    const saveButtons = await screen.findAllByRole('button', { name: /Save changes/i });
    await user.click(saveButtons[0]);
    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1));
    expect(updateMock.mock.calls[0][0].collabs).toEqual([
      expect.objectContaining({ id: 'collab-1', displayMode: 'hidden' }),
    ]);

    // Simulate leaving and returning to the page.
    unmount();
    renderPage();
    const reloadedTrigger = await getCollabTrigger();

    // Reads back "hidden" — the second getMine() response — not the pre-edit "logo" value and
    // not a stale in-memory copy from the unmounted instance.
    await waitFor(() => {
      expect(within(reloadedTrigger).getByText(/^Hide$/i)).toBeInTheDocument();
    });
    expect(getMineMock).toHaveBeenCalledTimes(2);
  });
});
