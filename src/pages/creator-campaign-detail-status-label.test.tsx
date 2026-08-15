/**
 * CreatorCampaignDetailPage — applied-status label never falls back to the raw enum
 * (CR-55 / F-0105).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * This page kept its own copy of the application-status label map, drifted from the
 * canonical `src/lib/application-status.ts` (missing COMPLETED, CANCELLED, IN_PROGRESS,
 * REVIEW_PENDING, REVISION_REQUESTED, DISPUTED). Its fallback printed the RAW backend enum
 * string, so a cancelled application literally rendered "CANCELLED" on this page — the exact
 * thing the platform-wide "never show Rejected/Cancelled" rule exists to prevent (a
 * withdrawn/declined application is CANCELLED and must render as "Closed"). Fixed by using
 * the canonical `getApplicationStatusLabel` instead of a local map.
 *
 * Run: npx vitest run src/pages/creator-campaign-detail-status-label.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import CreatorCampaignDetailPage from './creator-campaign-detail';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

// The page wraps sections in FadeUp (framer-motion's `whileInView`), which needs
// IntersectionObserver — not present in jsdom. Not under test here, so it's stubbed rather
// than mocking FadeUp itself (keeping the actual rendered content, including our target text,
// unmodified).
class MockIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
// @ts-expect-error — jsdom has no IntersectionObserver global.
global.IntersectionObserver = MockIntersectionObserver;

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const campaignGet = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      creatorCampaigns: {
        get: (...a: unknown[]) => campaignGet(...a),
        apply: vi.fn(),
      },
    },
  };
});

const BASE_CAMPAIGN = {
  id: 'camp_1',
  title: 'Diwali Skincare Reels',
  description: 'Promote our new serum line',
  platforms: ['INSTAGRAM'],
  requirements: [],
  applicationDeadline: null,
  startDate: null,
  endDate: null,
  maxCollaborators: null,
  createdAt: '2026-07-01T00:00:00Z',
  objectives: [],
  contentTypes: [],
  hashtags: [],
  brandGuidelines: null,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/campaigns/camp_1']}>
      <Routes>
        <Route path="/creator/campaigns/:id" element={<CreatorCampaignDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CreatorCampaignDetailPage — applied-status label (CR-55 / F-0105)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('never renders the raw CANCELLED enum — shows the canonical "Closed" label', async () => {
    campaignGet.mockResolvedValue({ ...BASE_CAMPAIGN, applicationStatus: 'CANCELLED' });
    renderPage();

    expect(await screen.findByText('Closed')).toBeInTheDocument();
    expect(screen.queryByText('CANCELLED')).not.toBeInTheDocument();
  });

  it('labels a status the old local map never covered at all (COMPLETED)', async () => {
    campaignGet.mockResolvedValue({ ...BASE_CAMPAIGN, applicationStatus: 'COMPLETED' });
    renderPage();

    expect(await screen.findByText('Completed')).toBeInTheDocument();
    expect(screen.queryByText('COMPLETED')).not.toBeInTheDocument();
  });

  it('still labels a status the old map DID cover, unchanged', async () => {
    campaignGet.mockResolvedValue({ ...BASE_CAMPAIGN, applicationStatus: 'SHORTLISTED' });
    renderPage();

    expect(await screen.findByText('Shortlisted')).toBeInTheDocument();
  });
});
