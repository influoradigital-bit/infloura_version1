import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { FestivalEdition } from '@/content/festival-editions';

/**
 * [Kabir H-6] The defect this pins: withdrawing consent did not stop the tracking.
 *
 * `decline`/`reset` cleared React state and localStorage, and the effect's non-accepted branch
 * only reset a ref. The injected `fbevents.js` <script> stayed in the document, `window.fbq`
 * stayed callable with every sponsor pixel still initialised, and `ensureMetaPixelBase`'s
 * `if (window.fbq) return` guard meant it could never be torn down for the life of the page. A
 * visitor who accepted and then declined was shown a UI that said their choice was recorded while
 * the tag kept running.
 *
 * WHY THIS FILE MOCKS THE CONTENT MODULE: no real sponsor has a `metaPixelId` (see
 * `src/content/festival-editions.ts`, whose header forbids inventing one — a killed session once
 * left a fake `Temp Fixture Brand` with a dead coupon on the real public page). The pixel path is
 * therefore unreachable with real content. A module mock keeps the fixture inside the test, where
 * it cannot reach a public page.
 */

// jsdom has no IntersectionObserver; framer-motion's viewport features need one. Same stub as
// creator-campaign-detail-status-label.test.tsx and friends. Only reachable here because the mock
// below gives the edition a sponsor — the real edition has none, so the sponsor sections (and
// their whileInView animations) never mount against real content.
class MockIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return [];
  }
}
// @ts-expect-error — jsdom has no IntersectionObserver global.
global.IntersectionObserver = MockIntersectionObserver;

const PIXEL_ID = '1234567890';

const EDITION: FestivalEdition = {
  slug: 'mumbai-2026',
  editionKey: 'MUMBAI_FESTIVE_2026',
  name: 'Test Edition',
  city: 'Mumbai',
  dateLabel: 'Festive 2026',
  status: 'LIVE',
  heroLine: 'Test hero',
  standfirst: 'Test standfirst',
  sponsors: [
    {
      slug: 'fixture-brand',
      brand: 'Fixture Brand',
      product: 'Fixture Product',
      blurb: 'A fixture, visible only inside this test file.',
      tier: 'TITLE',
      coupon: 'FIXTURE10',
      couponValue: '10% off',
      shopUrl: 'https://example.test/shop',
      metaPixelId: PIXEL_ID,
    },
  ],
  creators: [],
};

vi.mock('@/content/festival-editions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/content/festival-editions')>();
  return {
    ...actual,
    getEdition: (slug: string | undefined) => (slug === 'mumbai-2026' ? EDITION : null),
  };
});

const { default: FestivalBoxEditionPage, consentStorageKeyFor } = await import(
  '@/pages/festival-box-edition'
);

const STORAGE_KEY = consentStorageKeyFor('mumbai-2026');

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/festival-box/mumbai-2026']}>
      <Routes>
        <Route path="/festival-box/:edition" element={<FestivalBoxEditionPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function pixelScripts(): Element[] {
  return Array.from(document.querySelectorAll('script[src*="connect.facebook.net"]'));
}

describe('festival box sponsor pixel — consent withdrawal (H-6)', () => {
  let reload: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    window.localStorage.clear();
    delete window.fbq;
    delete window._fbq;
    pixelScripts().forEach((node) => node.remove());

    // jsdom's location.reload is not implemented and throws if called. Replacing it also lets the
    // test assert the reload actually happened, which is the load-bearing half of the teardown.
    reload = vi.fn();
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, reload },
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does not load the pixel before the visitor answers', () => {
    renderPage();

    expect(pixelScripts()).toHaveLength(0);
    expect(window.fbq).toBeUndefined();
  });

  it('loads and initialises the pixel on accept', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /accept/i }));

    await waitFor(() => expect(pixelScripts()).toHaveLength(1));
    expect(window.fbq).toBeDefined();
    // 'init' with this sponsor's id, then a PageView — queued, since fbevents.js never loads here.
    expect(window.fbq?.queue).toEqual(
      expect.arrayContaining([['init', PIXEL_ID], ['track', 'PageView']]),
    );
  });

  it('withdrawing consent removes the script, clears fbq, AND reloads the page', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /accept/i }));
    await waitFor(() => expect(pixelScripts()).toHaveLength(1));

    // "Manage sponsor tracking preferences" — the only withdrawal route a visitor has.
    await user.click(screen.getByRole('button', { name: /manage sponsor tracking preferences/i }));

    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(pixelScripts()).toHaveLength(0);
    expect(window.fbq).toBeUndefined();
    expect(window._fbq).toBeUndefined();
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('declining without ever accepting does NOT reload — no pixel ran, nothing to tear down', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /decline/i }));

    await waitFor(() =>
      expect(window.localStorage.getItem(STORAGE_KEY)).toContain('declined'),
    );
    // A reload here would be a gratuitous page refresh on every "no" — and, since the effect
    // re-runs after a reload, a potential loop.
    expect(reload).not.toHaveBeenCalled();
    expect(pixelScripts()).toHaveLength(0);
  });
});
