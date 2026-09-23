/**
 * F-0952 — per-post likes, comments, saves and shares were sent by
 * GET /me/media (AnalyticsDtos.ContentPerformanceResponse) and never shown. The panel
 * rendered only type, date, reach, impressions and engagement %.
 *
 * Run: npx vitest run src/components/analytics/__tests__/ContentPerformancePanel.post-counts.test.tsx
 */
import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';

import type { ContentPerformanceItem } from '@/lib/api';
import { ContentPerformancePanel } from '../ContentPerformancePanel';

function statValue(row: HTMLElement, label: string): string | null {
  const labelEl = within(row).getByText(label, { selector: 'p' });
  return labelEl.previousElementSibling?.textContent ?? null;
}

describe('ContentPerformancePanel — per-post counts (F-0952)', () => {
  it('shows likes, comments, saves and shares for each post, and one Views figure', () => {
    const item: ContentPerformanceItem = {
      mediaId: 'm1',
      mediaType: 'REEL',
      postedAt: '2026-09-10T10:00:00Z',
      reach: 5400,
      impressions: 7200,
      engagementRate: 6.1,
      likes: 812,
      comments: 45,
      saves: 120,
      shares: 33,
      videoViews: 15300,
    };
    render(<ContentPerformancePanel data={[item]} />);

    const row = screen.getByRole('listitem');
    expect(statValue(row, 'Likes')).toBe('812');
    expect(statValue(row, 'Comments')).toBe('45');
    expect(statValue(row, 'Saves')).toBe('120');
    expect(statValue(row, 'Shares')).toBe('33');
    // Views is the impressions field (MediaMetricMapper stores Meta's `views` there). The
    // retired video_views is never written, so it must not get a column of its own that
    // would read "—" on every real post.
    expect(statValue(row, 'Views')).toBe('7.2K');
    expect(within(row).getAllByText('Views', { selector: 'p' })).toHaveLength(1);
    expect(within(row).queryByText('Impressions')).toBeNull();
    expect(statValue(row, 'Reach')).toBe('5.4K');
    // F-1785: the per-post rate is interactions ÷ reach, not the profile rate — relabelled.
    expect(statValue(row, 'Eng./reach')).toBe('6.1%');
    expect(within(row).queryByText('Eng. rate')).toBeNull();
  });

  it('a count Meta did not report shows "—", never 0 (null and omitted key alike)', () => {
    // The wire DTO is @JsonInclude(NON_NULL): an unreported field is omitted, not sent as null.
    const omitted = {
      mediaId: 'm2',
      mediaType: 'IMAGE',
      postedAt: '2026-09-11T10:00:00Z',
      reach: null,
      impressions: null,
      engagementRate: null,
      likes: null,
    } as ContentPerformanceItem;
    render(<ContentPerformancePanel data={[omitted]} />);

    const row = screen.getByRole('listitem');
    expect(statValue(row, 'Likes')).toBe('—');
    expect(statValue(row, 'Comments')).toBe('—');
    expect(statValue(row, 'Saves')).toBe('—');
    expect(statValue(row, 'Shares')).toBe('—');
    expect(statValue(row, 'Eng./reach')).toBe('—');
  });

  it('a real zero stays 0 (a post with no shares is not "unreported")', () => {
    render(
      <ContentPerformancePanel
        data={[
          {
            mediaId: 'm3',
            mediaType: 'IMAGE',
            postedAt: '2026-09-12T10:00:00Z',
            reach: 100,
            impressions: 120,
            engagementRate: 2,
            shares: 0,
          },
        ]}
      />,
    );

    expect(statValue(screen.getByRole('listitem'), 'Shares')).toBe('0');
  });
});
