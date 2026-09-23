/**
 * F-1784 / F-1785 / F-1786 — the per-post row: links to the post on Instagram (only
 * for a validated https instagram.com permalink), titles it with the humanised media
 * type (never the raw enum; no caption - the API never sends one, ADR 2026-07-06),
 * formats the date en-IN with an honest
 * "—" fallback, labels the per-post rate "Eng./reach" with an explanation, and renders
 * no trend arrow.
 *
 * NOT covered here: F-1783 (the 375px mobile overflow). jsdom does no layout, so the
 * stacked-below-sm / single-line-from-sm row can only be measured in a real browser.
 *
 * Run: npx vitest run src/components/analytics/__tests__/ContentPerformancePanel.post-row.test.tsx
 */
import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';

import type { ContentPerformanceItem } from '@/lib/api';
import { ContentPerformancePanel } from '../ContentPerformancePanel';

function item(overrides: Partial<ContentPerformanceItem> = {}): ContentPerformanceItem {
  return {
    mediaId: 'm1',
    mediaType: 'CAROUSEL_ALBUM',
    // Midday UTC so the calendar day is the 30th in every CI timezone (UTC and IST alike).
    postedAt: '2026-03-30T06:00:00Z',
    permalink: 'https://www.instagram.com/p/ABC123/',
    reach: 1000,
    impressions: 1500,
    engagementRate: 4.2,
    likes: 30,
    comments: 5,
    saves: 7,
    shares: 2,
    ...overrides,
  };
}

describe('ContentPerformancePanel — post link (F-1784)', () => {
  it('links the row to a valid instagram permalink in a new tab with noopener', () => {
    render(<ContentPerformancePanel data={[item()]} />);

    const link = screen.getByRole('link', { name: /Carousel/ });
    expect(link.getAttribute('href')).toBe('https://www.instagram.com/p/ABC123/');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel') ?? '').toContain('noopener');
    expect(link.getAttribute('rel') ?? '').toContain('noreferrer');
    // The accessible name also says what the link does.
    expect(link.getAttribute('aria-label')).toMatch(/Instagram/);
    // Visible cue next to the title.
    expect(screen.getByTestId('post-external-link-icon')).toBeTruthy();
  });

  it('accepts the bare instagram.com host', () => {
    render(<ContentPerformancePanel data={[item({ permalink: 'https://instagram.com/reel/XYZ/' })]} />);
    expect(screen.getByRole('link').getAttribute('href')).toBe('https://instagram.com/reel/XYZ/');
  });

  it.each([
    ['a javascript: URL', 'javascript:alert(document.cookie)'],
    ['a javascript: URL dressed as instagram', 'javascript://www.instagram.com/%0aalert(1)'],
    ['a data: URL', 'data:text/html,<script>alert(1)</script>'],
    ['plain http', 'http://www.instagram.com/p/ABC123/'],
    ['a non-instagram host', 'https://evil.example.com/p/ABC123/'],
    ['a look-alike host', 'https://instagram.com.evil.example/p/ABC123/'],
    ['a suffix look-alike', 'https://notinstagram.com/p/ABC123/'],
    ['embedded credentials', 'https://user:pw@www.instagram.com/p/ABC123/'],
    ['not a URL', 'instagram.com/p/ABC123'],
    ['an empty string', ''],
    ['null', null],
    ['omitted', undefined],
  ])('renders NO link for %s', (_label, permalink) => {
    const { container } = render(<ContentPerformancePanel data={[item({ permalink })]} />);

    expect(screen.queryByRole('link')).toBeNull();
    expect(container.querySelector('a')).toBeNull();
    expect(screen.queryByTestId('post-external-link-icon')).toBeNull();
    // The row still renders its title.
    expect(within(screen.getByRole('listitem')).getByText('Carousel')).toBeTruthy();
  });
});

describe('ContentPerformancePanel — title and media type (F-1784)', () => {
  it('titles the row with the humanised type and never shows the raw enum', () => {
    const { container } = render(<ContentPerformancePanel data={[item()]} />);

    const row = screen.getByRole('listitem');
    expect(within(row).getByRole('link').textContent).toBe('Carousel');
    expect(container.textContent).not.toContain('CAROUSEL_ALBUM');
    expect(container.innerHTML).not.toContain('CAROUSEL_ALBUM');
  });

  it.each([
    ['VIDEO', 'Video'],
    ['CAROUSEL_ALBUM', 'Carousel'],
    ['IMAGE', 'Photo'],
    ['REELS', 'Reel'],
  ])('titles %s as %s', (mediaType, label) => {
    const { container } = render(
      <ContentPerformancePanel data={[item({ mediaType, permalink: null })]} />,
    );

    expect(within(screen.getByRole('listitem')).getByText(label, { selector: 'p' })).toBeTruthy();
    expect(container.textContent).not.toContain(mediaType);
  });

  it('an unknown enum reads "Post", not the raw value', () => {
    const { container } = render(
      <ContentPerformancePanel data={[item({ mediaType: 'SOME_NEW_TYPE' })]} />,
    );
    expect(container.textContent).not.toContain('SOME_NEW_TYPE');
    expect(screen.getByRole('link').textContent).toBe('Post');
  });
});

describe('ContentPerformancePanel — posted date (F-1786)', () => {
  it('renders postedAt as en-IN "30 Mar 2026"', () => {
    render(<ContentPerformancePanel data={[item()]} />);
    expect(within(screen.getByRole('listitem')).getByText(/30 Mar 2026/)).toBeTruthy();
    // Not the US M/D/YYYY the old unlocalised toLocaleDateString() produced.
    expect(screen.queryByText(/3\/30\/2026/)).toBeNull();
  });

  it.each([
    ['an unparseable postedAt', 'not-a-date'],
    ['a null postedAt', null],
    ['an omitted postedAt', undefined],
  ])('renders "—" and never "Invalid Date" for %s', (_label, postedAt) => {
    const { container } = render(
      <ContentPerformancePanel data={[item({ postedAt, mediaType: 'IMAGE' })]} />,
    );

    expect(container.textContent).not.toContain('Invalid Date');
    // The secondary line is the date alone.
    expect(within(screen.getByRole('listitem')).getByText('—', { selector: 'p.text-xs' })).toBeTruthy();
  });
});

describe('ContentPerformancePanel — Eng./reach and trend arrow (F-1785)', () => {
  it('explains the per-post rate and does not rename it "Eng. rate"', () => {
    render(<ContentPerformancePanel data={[item()]} />);

    const label = within(screen.getByRole('listitem')).getByText('Eng./reach');
    expect(label.getAttribute('title')).toMatch(/interactions ÷ that post's reach/);
    expect(label.getAttribute('title')).toMatch(/likes \+ comments ÷ followers/);
    expect(screen.getByTestId('eng-per-reach-note').textContent).toMatch(/not the profile/);
    expect(screen.queryByText('Eng. rate')).toBeNull();
  });

  it('renders no trend icon — only the thumbnail and external-link icons', () => {
    const { container } = render(<ContentPerformancePanel data={[item()]} />);

    expect(container.querySelector('.lucide-trending-up')).toBeNull();
    const row = screen.getByRole('listitem');
    expect(row.querySelectorAll('svg')).toHaveLength(2);
  });

  it('with no link, the thumbnail is the only icon in the row', () => {
    render(<ContentPerformancePanel data={[item({ permalink: null })]} />);
    expect(screen.getByRole('listitem').querySelectorAll('svg')).toHaveLength(1);
  });
});

describe('ContentPerformancePanel — counts use the Indian scale (K -> L -> Cr)', () => {
  // Found by rendering the real component at 375px: the old K-only formatter printed
  // 12,345,678 views as "12345.7K". Matches formatNumber on brand-creator-profile.tsx.
  it.each([
    [999, '999'],
    [1_000, '1.0K'],
    [99_000, '99.0K'],
    [100_000, '1.0L'],
    [1_234_567, '12.3L'],
    [10_000_000, '1.0Cr'],
    [12_345_678, '1.2Cr'],
  ])('%i renders as %s', (views, expected) => {
    render(<ContentPerformancePanel data={[item({ impressions: views })]} />);
    const views_cell = within(screen.getByRole('listitem')).getByText('Views').parentElement!;
    expect(views_cell.textContent).toContain(expected);
    expect(views_cell.textContent).not.toMatch(/\d{4,}\.\dK/);
  });

  it('an unreported count is still "—", never 0', () => {
    render(<ContentPerformancePanel data={[item({ impressions: null })]} />);
    const views_cell = within(screen.getByRole('listitem')).getByText('Views').parentElement!;
    expect(views_cell.textContent).toContain('—');
  });
});
