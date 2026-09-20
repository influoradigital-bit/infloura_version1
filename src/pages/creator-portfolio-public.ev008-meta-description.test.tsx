/**
 * EV-008 — the public portfolio page (/c/:username, the creator's media kit) summed EVERY
 * platform's followers, creator-declared ones included, into its search-engine description
 * ("... is a verified ... creator with 912K followers"), and its Platform Stats footnote claimed
 * every figure was "synced directly from each platform's API" even when a card said Self-reported.
 *
 * Run: npx vitest run src/pages/creator-portfolio-public.ev008-meta-description.test.tsx
 */
import { describe, it, expect } from 'vitest';
import type { PortfolioPage, PortfolioPlatformStats } from '@/lib/api';
import { metaDescription, platformStatsFootnote, verifiedFollowers } from '@/lib/portfolio-provenance';

function platform(p: Partial<PortfolioPlatformStats>): PortfolioPlatformStats {
  return {
    platform: 'INSTAGRAM',
    handle: 'h',
    profileUrl: 'https://example.com',
    isVerified: false,
    followers: 0,
    engagementRate: 0,
    ...p,
  } as PortfolioPlatformStats;
}

function page(platforms: PortfolioPlatformStats[]): PortfolioPage {
  return {
    username: 'ira',
    displayName: 'Ira',
    bio: '',
    city: 'Pune',
    niches: ['Fashion'],
    verified: true,
    stats: { totalCollabs: 3, avgRating: 0, onTimeRate: 0 },
    platforms,
    collabs: [],
    pinnedPosts: [],
    customLinks: [],
    rateCard: [],
    languages: [],
    topAudienceCities: [],
    visibility: {},
  } as unknown as PortfolioPage;
}

const IG_VERIFIED = platform({ platform: 'INSTAGRAM', isVerified: true, followers: 12000 });
const YT_DECLARED = platform({ platform: 'YOUTUBE', isVerified: false, followers: 900000 });

describe('EV-008 — public portfolio never counts self-reported followers as its headline', () => {
  it('counts only platform-verified followers', () => {
    expect(verifiedFollowers(page([IG_VERIFIED, YT_DECLARED]))).toBe(12000);
  });

  it('description cites the verified count, never the declared 900K', () => {
    const d = metaDescription(page([IG_VERIFIED, YT_DECLARED]));
    expect(d).toContain('12K platform-verified followers');
    expect(d).not.toContain('912');
    expect(d).not.toContain('9.1L');
  });

  it('with only declared platforms, the description claims no follower count at all', () => {
    const d = metaDescription(page([YT_DECLARED]));
    expect(d).not.toMatch(/followers/i);
    expect(d).toContain('3 brand collaborations');
  });

  it('footnote claims API-synced numbers only when every platform is verified', () => {
    expect(platformStatsFootnote(page([IG_VERIFIED]))).toBe(
      "Numbers synced directly from each platform's API. Updated daily.",
    );
    const mixed = platformStatsFootnote(page([IG_VERIFIED, YT_DECLARED]));
    expect(mixed).not.toContain('Numbers synced directly');
    expect(mixed).toContain('Self-reported');
  });
});
