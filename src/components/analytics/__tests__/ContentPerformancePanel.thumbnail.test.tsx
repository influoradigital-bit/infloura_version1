/**
 * Post thumbnails (2026-09-22) — the per-post row shows the post's cover image
 * (`previewImageUrl`, a signed Meta CDN link) in place of the placeholder icon,
 * but only for an https URL on cdninstagram.com / fbcdn.net, and falls back to
 * the placeholder when the image fails to load (the links expire in ~4 days).
 *
 * Run: npx vitest run src/components/analytics/__tests__/ContentPerformancePanel.thumbnail.test.tsx
 */
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import type { ContentPerformanceItem } from '@/lib/api';
import { ContentPerformancePanel } from '../ContentPerformancePanel';

const CDN_URL =
  'https://scontent.cdninstagram.com/v/t51.29350-15/123_n.jpg?stp=dst-jpg&_nc_ht=scontent.cdninstagram.com&oh=00_AbC&oe=66F1A2B3';

function item(overrides: Partial<ContentPerformanceItem> = {}): ContentPerformanceItem {
  return {
    mediaId: 'm1',
    mediaType: 'IMAGE',
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

function expectPlaceholderOnly() {
  expect(screen.queryByTestId('post-thumbnail')).toBeNull();
  expect(document.querySelector('img')).toBeNull();
  expect(screen.getByTestId('post-thumbnail-placeholder')).toBeTruthy();
}

describe('ContentPerformancePanel — post thumbnail', () => {
  it('renders a valid cdninstagram URL as a lazy, no-referrer, decorative img', () => {
    render(<ContentPerformancePanel data={[item({ previewImageUrl: CDN_URL })]} />);

    const img = screen.getByTestId('post-thumbnail');
    expect(img.tagName).toBe('IMG');
    expect(img.getAttribute('src')).toBe(CDN_URL);
    expect(img.getAttribute('referrerpolicy')).toBe('no-referrer');
    expect(img.getAttribute('loading')).toBe('lazy');
    expect(img.getAttribute('decoding')).toBe('async');
    expect(img.getAttribute('alt')).toBe('');
    // Same 40px box as the placeholder it replaces.
    expect(img.className).toContain('h-10');
    expect(img.className).toContain('w-10');
    expect(img.className).toContain('object-cover');
    expect(screen.queryByTestId('post-thumbnail-placeholder')).toBeNull();
  });

  it('accepts an fbcdn.net subdomain', () => {
    const url = 'https://scontent-bom1-1.xx.fbcdn.net/v/t39/abc.jpg?oe=66F1A2B3';
    render(<ContentPerformancePanel data={[item({ previewImageUrl: url })]} />);
    expect(screen.getByTestId('post-thumbnail').getAttribute('src')).toBe(url);
  });

  it.each([
    ['javascript: URL', 'javascript:alert(1)'],
    ['http (not https)', 'http://scontent.cdninstagram.com/v/abc.jpg'],
    ['non-Instagram host', 'https://cdn.example.com/abc.jpg'],
    ['look-alike suffix host', 'https://cdninstagram.com.evil.com/abc.jpg'],
    ['look-alike subdomain host', 'https://scontent.cdninstagram.com.evil.com/abc.jpg'],
    ['look-alike prefix host', 'https://evilcdninstagram.com/abc.jpg'],
    ['embedded credentials', 'https://user:pw@scontent.cdninstagram.com/abc.jpg'],
    ['data: URL', 'data:image/png;base64,AAAA'],
    ['unparseable string', 'not a url'],
    ['empty string', ''],
  ])('renders no img for a %s and shows the placeholder', (_label, url) => {
    render(<ContentPerformancePanel data={[item({ previewImageUrl: url })]} />);
    expectPlaceholderOnly();
  });

  it('shows the placeholder when previewImageUrl is missing (brand route, NON_NULL omission)', () => {
    render(<ContentPerformancePanel data={[item()]} />);
    expectPlaceholderOnly();
  });

  it('shows the placeholder when previewImageUrl is null', () => {
    render(<ContentPerformancePanel data={[item({ previewImageUrl: null })]} />);
    expectPlaceholderOnly();
  });

  it('swaps to the placeholder when the image fails to load, and stays swapped on re-render', () => {
    const data = [item({ previewImageUrl: CDN_URL })];
    const { rerender } = render(<ContentPerformancePanel data={data} />);

    fireEvent.error(screen.getByTestId('post-thumbnail'));
    expectPlaceholderOnly();

    // A re-render with the same (expired) link must not bring the broken image back.
    rerender(<ContentPerformancePanel data={[item({ previewImageUrl: CDN_URL })]} />);
    expectPlaceholderOnly();
  });

  it('retries when a later poll brings a fresh link', () => {
    const { rerender } = render(
      <ContentPerformancePanel data={[item({ previewImageUrl: CDN_URL })]} />,
    );
    fireEvent.error(screen.getByTestId('post-thumbnail'));
    expectPlaceholderOnly();

    const fresh = CDN_URL.replace('oe=66F1A2B3', 'oe=66F9B0C4');
    rerender(<ContentPerformancePanel data={[item({ previewImageUrl: fresh })]} />);
    expect(screen.getByTestId('post-thumbnail').getAttribute('src')).toBe(fresh);
  });

  it('keeps the row link and title intact alongside the image', () => {
    render(<ContentPerformancePanel data={[item({ previewImageUrl: CDN_URL })]} />);
    const link = screen.getByRole('link', { name: /Photo/ });
    expect(link.getAttribute('href')).toBe('https://www.instagram.com/p/ABC123/');
  });
});
