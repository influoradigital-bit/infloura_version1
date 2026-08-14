/**
 * brand-creator-profile — per-platform follower provenance (CR-119).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `PlatformStat.verified` was structurally dead: the only backend writes were a hardcoded
 * `false`, so a genuinely Meta-OAuth-verified Instagram follower count was stored — and rendered
 * — identically to a creator-typed YouTube or Twitter number. The only signal on this page was a
 * small check icon that consequently never appeared in live mode.
 *
 * The flag is now driven by the snapshot's real data source, and this page states BOTH
 * provenance states in words rather than relying on badge-presence. These tests pin the wording,
 * because "brands can tell a verified follower count from a self-reported one" is the entire
 * user-facing point of the ticket — and a silent regression here is invisible to every backend
 * test.
 *
 * YouTube and Twitter/X have no OAuth or data-fetch integration anywhere in this codebase (the
 * still-open half of CR-119), so they must never read as verified in ANY mode, mock included.
 *
 * Run: npx vitest run src/pages/brand-creator-profile-provenance.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }));

class MockIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
// @ts-expect-error — jsdom has no IntersectionObserver global.
global.IntersectionObserver = MockIntersectionObserver;

vi.mock('@/components/brand/brand-layout', () => ({
  BrandLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

/** Renders the page in MOCK mode (isApiLive() false — no brand_token in localStorage). */
async function renderMockMode() {
  vi.resetModules();
  const { default: BrandCreatorProfilePage } = await import('./brand-creator-profile');
  return render(
    <MemoryRouter initialEntries={['/brand/creators/creator_1']}>
      <Routes>
        <Route path="/brand/creators/:id" element={<BrandCreatorProfilePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** The platform card for a given platform name, so assertions are scoped to that row. */
function platformCard(name: string): HTMLElement {
  const label = screen.getByText(name);
  const card = label.closest('div.rounded-lg');
  if (!card) throw new Error(`no platform card found for ${name}`);
  return card as HTMLElement;
}

describe('brand-creator-profile — follower provenance (CR-119)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  it('labels every platform explicitly — never leaves provenance to badge-presence alone', async () => {
    await renderMockMode();

    // Whatever each platform's state is, the page must SAY which it is. Before CR-119 the only
    // signal was an icon that never rendered, so every row was silently ambiguous.
    for (const name of ['Instagram', 'YouTube', 'Twitter']) {
      const card = platformCard(name);
      const said =
        within(card).queryByText('Followers verified') ?? within(card).queryByText('Self-reported');
      expect(said, `${name} must state its provenance in words`).not.toBeNull();
    }
  });

  it('YouTube is NOT presented as verified — no YouTube integration exists in this codebase', async () => {
    await renderMockMode();

    const card = platformCard('YouTube');
    expect(within(card).queryByText('Followers verified')).toBeNull();
    expect(within(card).getByText('Self-reported')).toBeInTheDocument();
  });

  it('Twitter is NOT presented as verified — no Twitter/X integration exists in this codebase', async () => {
    await renderMockMode();

    const card = platformCard('Twitter');
    expect(within(card).queryByText('Followers verified')).toBeNull();
    expect(within(card).getByText('Self-reported')).toBeInTheDocument();
  });

  it('Instagram — the one platform with a real OAuth integration — can read as Verified', async () => {
    await renderMockMode();

    const card = platformCard('Instagram');
    expect(within(card).getByText('Followers verified')).toBeInTheDocument();
    expect(within(card).queryByText('Self-reported')).toBeNull();
  });
});
