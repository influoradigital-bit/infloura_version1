/**
 * Influora demo data — single source for seeded demo entities across the
 * brand, creator, and admin flows. Consumed by the mock branches in
 * `lib/api.ts` and directly by pages that render demo state.
 *
 * Pages that predate this file keep their own inline mocks; new demo data
 * (Hype campaigns, admin console) lives here so all three roles stay
 * consistent with each other (same brands, creators, and deal amounts).
 */

import type {
  Campaign,
  CreatorProfile,
  HypeConfig,
} from './types';

const HOURS = 60 * 60 * 1000;
const DAYS = 24 * HOURS;

// ---------------------------------------------------------------------------
// HYPE CAMPAIGNS — 72-hr blitz campaigns (brand side)
// ---------------------------------------------------------------------------

export const demoHypeConfig: HypeConfig = {
  sourceReelUrl: 'https://instagram.com/reel/influora-launch-anthem',
  audioTrack: 'Bombay Dreams (Sped Up) — Ritviz',
  hashtag: '#GlowDropChallenge',
  formatLanes: ['Remix the hook', 'Duet reaction', 'Original spin'],
  perReelRate: 3500,
  currency: 'INR',
  slotCap: 100,
  slotsFilled: 63,
  liveUntil: new Date(Date.now() + 41 * HOURS),
};

export const demoHypeCampaign: Campaign & {
  collaboratorsCount: number;
  progress: number;
} = {
  id: 'hype-1',
  workspaceId: 'ws-1',
  title: 'Glow Drop Challenge',
  description:
    'Blitz launch for the Glow Drop serum — creators remix our anthem reel with the campaign audio and hashtag within the 72-hour window.',
  objectives: ['Viral reach', 'Launch buzz'],
  campaignType: 'HYPE',
  hype: demoHypeConfig,
  status: 'ACTIVE',
  budget: { min: 350000, max: 350000, currency: 'INR' },
  timeline: {
    startDate: new Date(Date.now() - 31 * HOURS),
    endDate: new Date(Date.now() + 41 * HOURS),
  },
  platforms: ['INSTAGRAM'],
  contentTypes: ['REEL'],
  isPrivate: false,
  maxCollaborators: 100,
  createdBy: 'user-1',
  createdAt: new Date(Date.now() - 2 * DAYS),
  updatedAt: new Date(Date.now() - 3 * HOURS),
  collaboratorsCount: 63,
  progress: 63,
};

/** Hype invite as it appears in the creator inbox (one-tap accept). */
export interface HypeInvite {
  id: string;
  brandName: string;
  brandVerified: boolean;
  campaignTitle: string;
  hashtag: string;
  audioTrack?: string;
  formatLanes: string[];
  perReelRate: number;
  slotCap: number;
  slotsFilled: number;
  liveUntil: Date;
  sourceReelUrl: string;
}

// ---------------------------------------------------------------------------
// CREATOR DISCOVERY — richer seed for api.creators.search mock branch
// ---------------------------------------------------------------------------

export const demoCreators: CreatorProfile[] = [
  {
    id: 'cr_1',
    userId: 'cr_1',
    displayName: 'Priya Creates',
    bio: 'Fashion creator helping brands tell stories through aesthetic content.',
    location: 'Mumbai',
    categories: ['Fashion & Lifestyle', 'Beauty'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@priya_creates', followers: 125000, engagementRate: 4.2, isVerified: true },
      { platform: 'YOUTUBE', handle: 'Priya Creates', followers: 50000, engagementRate: 3.8, isVerified: true },
    ],
    totalFollowers: 175000,
    engagementRate: 4.1,
    averageRate: 35000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Hindi', 'English', 'Marathi'],
  },
  {
    id: 'cr_2',
    userId: 'cr_2',
    displayName: 'Arjun Kapoor',
    bio: 'Tech reviews people actually finish watching.',
    location: 'Bangalore',
    categories: ['Tech', 'Gadgets'],
    platforms: [
      { platform: 'YOUTUBE', handle: 'TechTakes', followers: 480000, engagementRate: 5.1, isVerified: true },
      { platform: 'INSTAGRAM', handle: '@arjun.techtakes', followers: 92000, engagementRate: 3.4, isVerified: true },
    ],
    totalFollowers: 572000,
    engagementRate: 4.8,
    averageRate: 90000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['English', 'Hindi'],
  },
  {
    id: 'cr_3',
    userId: 'cr_3',
    displayName: 'Sneha Reddy',
    bio: 'Wellness, yoga and honest morning routines from Hyderabad.',
    location: 'Hyderabad',
    categories: ['Wellness', 'Fitness'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@sneha.moves', followers: 68000, engagementRate: 6.2, isVerified: false },
    ],
    totalFollowers: 68000,
    engagementRate: 6.2,
    averageRate: 18000,
    currency: 'INR',
    isVerified: false,
    portfolioItems: [],
    languages: ['Telugu', 'English'],
  },
];
