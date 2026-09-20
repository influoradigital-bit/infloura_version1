/**
 * EV-008 — Meera's live creator-matching canvas and inline show_creators result showed an
 * "Instagram-verified stats" badge gated on `creator.verified` (CreatorProfile.verified, an
 * identity flag) and printed imported follower totals unlabelled. The badge now follows
 * `followersSource` (MeeraToolDtos.CreatorSummary), and an IMPORTED total says so.
 *
 * Run: npx vitest run src/components/feature/meera/StageMatching.ev008-followers-source.test.tsx
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ShowCreatorsPayload } from '@/lib/meera-api';

class MockIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return [];
  }
}
// @ts-expect-error -- jsdom has no IntersectionObserver global (framer-motion useInView needs it).
global.IntersectionObserver = MockIntersectionObserver;

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return { ...actual, isApiLive: () => true };
});

import { StageMatching } from './StageMatching';
import { ShowCreatorsResult } from './ToolResultRenderer';

const payload: ShowCreatorsPayload = {
  creators: [
    {
      creatorProfileId: 'imp',
      displayName: 'Imported Identity-Verified',
      totalFollowers: 80000,
      verified: true,
      followersSource: 'IMPORTED',
    },
    {
      creatorProfileId: 'ver',
      displayName: 'Meta Synced',
      totalFollowers: 12000,
      verified: false,
      followersSource: 'VERIFIED',
    },
  ],
};

describe('EV-008 — Meera creator results never badge imported numbers as verified', () => {
  it('StageMatching: badge follows followersSource, not the identity flag', () => {
    render(<StageMatching toolResult={payload} />);

    const imported = screen.getByText('Imported Identity-Verified').closest('div.rounded-lg') as HTMLElement;
    const synced = screen.getByText('Meta Synced').closest('div.rounded-lg') as HTMLElement;

    expect(imported.innerHTML).not.toContain('Instagram-verified stats');
    expect(imported.textContent).toContain('Followers imported, not verified');
    expect(synced.innerHTML).toContain('Instagram-verified stats');
    expect(synced.textContent).not.toContain('imported');
  });

  it('ToolResultRenderer: an imported total is labelled inline', () => {
    render(<ShowCreatorsResult data={payload} />);
    const row = screen.getByText('Imported Identity-Verified').parentElement as HTMLElement;
    expect(row.textContent).toContain('(imported, not verified)');
    const other = screen.getByText('Meta Synced').parentElement as HTMLElement;
    expect(other.textContent).not.toContain('imported');
  });
});
