import * as React from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft,
  CheckCircle2,
  MapPin,
  Mail,
  Heart,
  Bookmark,
  BookmarkCheck,
  Users,
  Star,
  Play,
  Eye,
  Instagram,
  Youtube,
  Twitter,
  Sparkles,
  TrendingUp,
  Calendar,
  Globe,
  Languages,
  Briefcase,
  IndianRupee,
  Lock,
  type LucideIcon,
} from 'lucide-react';
import {
  api,
  isApiLive,
  ApiError,
  type CreatorPublicProfile,
  type SimilarCreator,
  type PortfolioRateRow,
} from '@/lib/api';
import type { Platform, CreatorDemographics } from '@/lib/types';
import { cn } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';
import { followersCaption } from '@/components/brand/discover/creator-discovery';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Progress } from '@/components/ui/progress';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Separator } from '@/components/ui/separator';

// View-model shape the JSX below renders. Mock data and the live-mapped API
// response (buildLiveCreatorView, further down) both produce this exact shape
// so the JSX never needs to branch on data source.
interface CreatorPlatformView {
  name: string;
  icon: LucideIcon;
  handle: string;
  followers: number;
  engagement: number | null;
  verified: boolean;
  color: string;
}

interface CreatorDisplayModel {
  id: string;
  displayName: string;
  username: string;
  bio: string;
  avatarUrl: string | null;
  location: string;
  languages: string[];
  website: string | null;
  isVerified: boolean;
  isAvailable: boolean;
  joinedDate: string;
  categories: string[];
  stats: {
    totalFollowers: number;
    avgEngagement: number | null;
    /** EV-008 — provenance of totalFollowers/avgEngagement (absent in mock mode). */
    followersSource?: 'VERIFIED' | 'IMPORTED' | 'NONE';
    /**
     * F-0260 — DiscoveryDtos.CreatorPublicProfileResponse has no per-post average likes/
     * comments/views field. `null` in live mode, rendered as an explicit "—" instead of a
     * fabricated "0 Avg Likes" presented as a measured fact (same rule as `rating` below).
     */
    avgLikes: number | null;
    avgComments: number | null;
    avgViews: number | null;
    completedCampaigns: number;
    /**
     * PR-1 (BrandF.md §87) — real avg of brand→creator star reviews from
     * GET /creators/profile/:usernameOrId, or `null` when this creator has no reviews yet.
     * Never coerced to a fabricated 0 (Priya, UI Honesty rule).
     */
    rating: number | null;
    /**
     * PR-1 — the DTO backing `rating` (`avgRating`) has no companion review-count field, so
     * this stays `null` in live mode rather than a fabricated `0` sitting next to a real
     * average (see buildLiveCreatorView and the Reviews-tab render below).
     */
    reviewCount: number | null;
  };
  platforms: CreatorPlatformView[];
  audience: {
    interests: string[];
    /**
     * BR-18 — real `100 - fakeFollowerScore` from `creator.scores.authenticity`
     * (DiscoveryDtos.CreatorScores), or `null` when the creator hasn't been scored yet. Never
     * coerce to `0`: a `0` here used to render as "0% — Excellent authenticity", live misleading
     * UI (Priya, Score Exposure §4).
     */
    authenticity: number | null;
  };
  /**
   * [F-0972] The creator's own pinned posts, from PortfolioBrandView.contentPortfolio.
   * `caption` replaces the old `brand` field: a pinned post carries no brand attribution,
   * so the previous shape could only have been filled by inventing one. `views`/`likes`
   * are optional on the wire and stay nullable here — absent is not zero.
   */
  portfolio: {
    id: string;
    platform: Platform;
    caption: string | null;
    thumbnailUrl: string | null;
    views: number | null;
    likes: number | null;
  }[];
  /**
   * [F-0974] `rows` is the creator's real per-deliverable rate card, served to a brand when
   * they set it to 'public' or 'brands_only' and empty when they set it to 'hidden'.
   * `floor`/`ceiling` are the profile-level rateMin/rateMax, which the DTO has always
   * carried and this page used to discard — the fallback for a creator whose only pricing
   * ever came from onboarding rather than the rate-card editor.
   */
  rates: {
    rows: PortfolioRateRow[];
    floor: number | null;
    ceiling: number | null;
    currency: string | null;
  };
  pastBrands: string[];
  reviews: { brand: string; rating: number; comment: string; date: string }[];
  metrics: {
    responseTime: string;
    /**
     * PR-1 — none of these three has a backend field yet (confirmed against
     * DiscoveryDtos.CreatorPublicProfileResponse: no completionRate/onTimeDelivery/
     * repeatClients). `null` in live mode renders an honest "—" instead of a fabricated
     * "0%" presented as fact for every creator.
     */
    completionRate: number | null;
    onTimeDelivery: number | null;
    /** [F-0589] Collabs onTimeDelivery was measured over; 0 exactly when it is null. */
    onTimeSampleSize: number;
    /**
     * [F-0972] A COUNT of brands who hired this creator more than once, not a percentage.
     * PortfolioStats.repeatBrands. The tile below renders it bare for that reason — the
     * old 'Repeat Clients' tile appended a '%', which would have turned a count of 3 into
     * a fabricated '3%'.
     */
    repeatBrands: number | null;
  };
}

/**
 * F-0795/F-0796 [ananya · 2026-09-17] — audience demographics view-model, rendered by the
 * Audience tab below. A discriminated union rather than nullable fields: the pre-/post-consent
 * split in wiki/decisions/2026-09-15-brand-preconsent-visibility.md (LOCKED) is a difference in
 * *kind* of information (band vs. exact), not just presence/absence, so the two cannot share one
 * shape without either fabricating a band from nothing or exposing exact numbers through a
 * generic "has data" flag.
 *
 * 'locked' = AnalyticsController's /demographics 403'd (MetricsAuthorizationService found no
 * active MetaOAuthToken pairing, i.e. no collaboration exists yet). The ruling calls for coarse
 * bands here (an age-skew description, a gender-majority description, a no-percentage top-3
 * city list), but CreatorDemographicsResponse itself returns nothing at all on a 403 — no
 * breakdown to summarize into a band. Computing a *real* aggregate band pre-consent is a backend
 * capability this ticket's FE-only scope cannot add, so 'locked' renders an honest "hidden until
 * connected" message instead of an invented skew — never a fabricated band presented as read
 * data, same rule as the null-vs-zero pattern elsewhere in this file. Wording is explicitly left
 * to Ananya's call per the ruling's closing note.
 * Source: wiki/decisions/2026-09-15-brand-preconsent-visibility.md
 */
type DemographicsView =
  | { status: 'loading' }
  | { status: 'locked' }
  | { status: 'unavailable' }
  | {
      status: 'available';
      ageGroups: { range: string; percentage: number }[];
      gender: { female: number; male: number; other: number };
      topCities: { city: string; percentage: number }[];
    };

/**
 * F-0795/F-0796 [ananya · 2026-09-17] — CreatorDemographicsResponse (AnalyticsDtos.java) carries
 * raw counts (`ageGenderBreakdown: Map<String, Long>`, keys like "18-24_female"), never
 * pre-computed percentages. This page owns the count-to-percentage math so it can render exact
 * numbers post-connection while never inventing a percentage the DTO didn't send.
 * Source: wiki/decisions/2026-09-15-brand-preconsent-visibility.md
 */
function deriveAgeGroups(breakdown: Record<string, number>): { range: string; percentage: number }[] {
  const byRange = new Map<string, number>();
  let total = 0;
  for (const [key, count] of Object.entries(breakdown)) {
    const range = key.split('_')[0] ?? key;
    byRange.set(range, (byRange.get(range) ?? 0) + count);
    total += count;
  }
  if (total === 0) return [];
  return Array.from(byRange.entries())
    .map(([range, count]) => ({ range, percentage: Math.round((count / total) * 1000) / 10 }))
    .sort((a, b) => b.percentage - a.percentage);
}

function deriveGender(breakdown: Record<string, number>): { female: number; male: number; other: number } {
  let female = 0;
  let male = 0;
  let other = 0;
  let total = 0;
  for (const [key, count] of Object.entries(breakdown)) {
    total += count;
    const g = key.split('_')[1] ?? '';
    if (g === 'female') female += count;
    else if (g === 'male') male += count;
    else other += count;
  }
  if (total === 0) return { female: 0, male: 0, other: 0 };
  return {
    female: Math.round((female / total) * 1000) / 10,
    male: Math.round((male / total) * 1000) / 10,
    other: Math.round((other / total) * 1000) / 10,
  };
}

function deriveTopCities(breakdown: Record<string, number>): { city: string; percentage: number }[] {
  const total = Object.values(breakdown).reduce((s, n) => s + n, 0);
  if (total === 0) return [];
  return Object.entries(breakdown)
    .map(([city, count]) => ({ city, percentage: Math.round((count / total) * 1000) / 10 }))
    .sort((a, b) => b.percentage - a.percentage)
    .slice(0, 5);
}

function deriveDemographicsView(data: CreatorDemographics): DemographicsView {
  if (!data.hasData) return { status: 'unavailable' };
  const ageGender = data.ageGenderBreakdown ?? {};
  const city = data.cityBreakdown ?? {};
  return {
    status: 'available',
    ageGroups: deriveAgeGroups(ageGender),
    gender: deriveGender(ageGender),
    topCities: deriveTopCities(city),
  };
}

// Mock Creator Data - Indian Context
const mockCreator: CreatorDisplayModel = {
  id: '1',
  displayName: 'Priya Sharma',
  username: '@priyacreates',
  bio: 'Fashion & lifestyle creator from Mumbai. Passionate about sustainable fashion, travel, and authentic storytelling. Let us create something beautiful together.',
  avatarUrl: null,
  location: 'Mumbai, Maharashtra',
  languages: ['Hindi', 'English', 'Marathi'],
  website: 'priyacreates.com',
  isVerified: true,
  isAvailable: true,
  joinedDate: 'March 2020',
  categories: ['Fashion', 'Lifestyle', 'Beauty', 'Travel'],
  
  stats: {
    totalFollowers: 892000,
    avgEngagement: 5.2,
    avgLikes: 46500,
    avgComments: 1820,
    avgViews: 245000,
    completedCampaigns: 67,
    rating: 4.9,
    reviewCount: 52,
  },

  platforms: [
    {
      name: 'Instagram',
      icon: Instagram,
      handle: '@priyacreates',
      followers: 485000,
      engagement: 5.8,
      verified: true,
      color: '#E4405F',
    },
    {
      name: 'YouTube',
      icon: Youtube,
      handle: 'Priya Creates',
      followers: 312000,
      engagement: 4.2,
      // CR-119 — was `true`, which was already wrong (there is no YouTube OAuth or data-fetch
      // integration anywhere in this codebase, so no YouTube figure can be platform-verified)
      // but rendered only as a small unlabelled check icon. Now that both provenance states are
      // spelled out in words below, leaving this true would print a literal "Verified" under a
      // YouTube follower count in every non-live build — a louder lie than the one it replaced.
      verified: false,
      color: '#FF0000',
    },
    {
      name: 'Twitter',
      icon: Twitter,
      handle: '@priyacreates',
      followers: 95000,
      engagement: 3.1,
      verified: false,
      color: '#1DA1F2',
    },
  ],

  audience: {
    interests: ['Fashion', 'Beauty', 'Travel', 'Fitness', 'Food'],
    authenticity: 96,
  },

  portfolio: [
    { id: 'p1', platform: 'INSTAGRAM', caption: 'Summer edit with Myntra', thumbnailUrl: null, views: 1250000, likes: 89000 },
    { id: 'p2', platform: 'INSTAGRAM', caption: 'Nykaa skincare routine', thumbnailUrl: null, views: 450000, likes: 32000 },
    { id: 'p3', platform: 'YOUTUBE', caption: 'boAt unboxing', thumbnailUrl: null, views: 890000, likes: 45000 },
    { id: 'p4', platform: 'INSTAGRAM', caption: 'Sugar Cosmetics festive look', thumbnailUrl: null, views: 2100000, likes: 156000 },
    { id: 'p5', platform: 'INSTAGRAM', caption: 'FabIndia handloom haul', thumbnailUrl: null, views: 320000, likes: 28000 },
    { id: 'p6', platform: 'INSTAGRAM', caption: null, thumbnailUrl: null, views: 280000, likes: 21000 },
  ],

  rates: {
    rows: [
      { id: 'instagram_post', label: 'Instagram Post', min: 45000, max: 60000, currency: 'INR' },
      { id: 'instagram_reel', label: 'Instagram Reel', min: 75000, max: 95000, currency: 'INR' },
      { id: 'instagram_story', label: 'Instagram Story (3 frames)', min: 15000, max: 20000, currency: 'INR' },
      { id: 'youtube_integration', label: 'YouTube Integration (60-90s)', min: 150000, max: 180000, currency: 'INR' },
    ],
    floor: 15000,
    ceiling: 180000,
    currency: 'INR',
  },

  pastBrands: ['Myntra', 'Nykaa', 'Boat', 'Sugar Cosmetics', 'FabIndia', 'Mamaearth', 'Puma India', 'H&M India'],

  reviews: [
    { brand: 'Myntra', rating: 5, comment: 'Exceptional content quality and professionalism. Delivered ahead of schedule.', date: '2024-04' },
    { brand: 'Nykaa', rating: 5, comment: 'Great engagement on the campaign. Would definitely work again.', date: '2024-03' },
    { brand: 'Boat', rating: 4, comment: 'Creative approach and good communication throughout.', date: '2024-02' },
  ],

  metrics: {
    responseTime: '< 4 hours',
    completionRate: 98,
    onTimeDelivery: 96,
    onTimeSampleSize: 24,
    repeatBrands: 7,
  },
};

// Mock-mode-only demographics (clearly synthetic, matches the mockDemographics pattern in
// src/lib/api.ts). Always 'available' — mock mode never needs to demo the locked/pre-consent
// state, since there's no real MetricsAuthorizationService gate to simulate here.
const mockAudienceDemographics: DemographicsView = {
  status: 'available',
  ageGroups: [
    { range: '18-24', percentage: 32 },
    { range: '25-34', percentage: 45 },
    { range: '35-44', percentage: 18 },
    { range: '45+', percentage: 5 },
  ],
  gender: { female: 68, male: 30, other: 2 },
  topCities: [
    { city: 'Mumbai', percentage: 22 },
    { city: 'Delhi', percentage: 18 },
    { city: 'Bangalore', percentage: 15 },
    { city: 'Hyderabad', percentage: 12 },
    { city: 'Pune', percentage: 8 },
  ],
};

// Format helpers
const formatNumber = (n: number): string => {
  if (n >= 10000000) return `${(n / 10000000).toFixed(1)}Cr`;
  if (n >= 100000) return `${(n / 100000).toFixed(1)}L`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return n.toString();
};

const formatINR = (n: number): string => {
  if (n >= 100000) return `${(n / 100000).toFixed(1)}L`;
  if (n >= 1000) return `${(n / 1000).toFixed(0)}K`;
  return n.toLocaleString('en-IN');
};

// ---------------------------------------------------------------------------
// Live mode mapping — GET /creators/profile/:usernameOrId (CreatorController.getPublicProfile,
// verified at influora-api/.../web/CreatorController.java:159) returns
// DiscoveryDtos.CreatorPublicProfileResponse.
//
// PR-1 fix (BrandF.md §87, wiki/errors/BRAND-BUG-TRACKER.md): this page previously called
// GET /creators/:id (CreatorDtos.CreatorResponse), whose DTO has no completedCampaigns/
// avgRating fields at all — both were hardcoded to 0 below, rendering as fabricated zeros
// under "Based on verified brand collaborations". CreatorPublicProfileResponse actually
// carries both (real completedCampaigns count, real avgRating — null, not 0, when unrated).
//
// The rest of what this page renders (reviews list, portfolio grid, past brands, per-platform
// rate cards, work-quality metrics, website/availability/joinedDate) still has no backend
// equivalent in either DTO — those keep their honest empty/zero fallback below (each flagged
// // TODO(vikram)) rather than invented numbers.
//
// F-0795/F-0796 [ananya · 2026-09-17] — audience demographics is NOT one of those gaps anymore:
// GET /analytics/creators/{creatorId}/demographics (AnalyticsController.getDemographics) exists
// and is wired below via the component's own effect (deriveDemographicsView), not through this
// synchronous row-mapping function — the endpoint is gated per-creator by
// MetricsAuthorizationService (403 pre-consent) and needs its own request/loading lifecycle,
// which buildLiveCreatorView's synchronous shape can't express.
// Source: wiki/decisions/2026-09-15-brand-preconsent-visibility.md
// ---------------------------------------------------------------------------

type LiveCreatorRow = CreatorPublicProfile;

const PLATFORM_LABEL: Partial<Record<Platform, string>> = {
  INSTAGRAM: 'Instagram',
  YOUTUBE: 'YouTube',
  TIKTOK: 'TikTok',
  TWITTER: 'Twitter/X',
  LINKEDIN: 'LinkedIn',
  FACEBOOK: 'Facebook',
  TWITCH: 'Twitch',
  OTHER: 'Other',
};

const PLATFORM_ICON: Partial<Record<Platform, LucideIcon>> = {
  INSTAGRAM: Instagram,
  YOUTUBE: Youtube,
  TWITTER: Twitter,
};

const PLATFORM_COLOR: Partial<Record<Platform, string>> = {
  INSTAGRAM: '#E4405F',
  YOUTUBE: '#FF0000',
  TWITTER: '#1DA1F2',
};

function buildLiveCreatorView(row: LiveCreatorRow): CreatorDisplayModel {
  return {
    id: row.id,
    displayName: row.displayName,
    username: row.username ? `@${row.username}` : row.displayName,
    bio: row.bio ?? '',
    avatarUrl: row.profilePhoto ?? null,
    location: row.city ?? '',
    languages: row.languages ?? [],
    website: null, // TODO(vikram): DTO has no website field
    isVerified: row.isVerified,
    isAvailable: false, // TODO(vikram): DTO has no availability field
    joinedDate: '', // TODO(vikram): DTO has no joinedDate/createdAt field
    categories: row.categories ?? [],
    stats: {
      totalFollowers: row.totalFollowers,
      avgEngagement: row.engagementRate,
      followersSource: row.followersSource,
      // F-0260 — was hardcoded to 0, rendering as a fabricated "0 Avg Likes"/"0 Avg Views" for
      // every creator. `null`: DTO has no per-post average likes/comments/views field.
      avgLikes: null,
      avgComments: null,
      avgViews: null,
      // PR-1 fix (BrandF.md §87) — real count from GET /creators/profile/:usernameOrId.
      // The old GET /creators/:id (CreatorResponse) had no such field at all, so this was
      // hardcoded to 0 and rendered as a fabricated "0 campaigns" for every creator.
      completedCampaigns: row.completedCampaigns,
      // PR-1 fix — real avg of brand→creator star reviews, or `null` (not a fabricated 0)
      // when this creator has no reviews yet (CreatorDiscoveryService H-22 comment).
      rating: row.avgRating,
      // [F-0972] Real count of reviewed collabs now that pastCollabs carries ratings and
      // quotes. Still `null` — not 0 — when the portfolio section is hidden or empty, so a
      // real average rating is never captioned "0 reviews".
      reviewCount:
        (row.portfolio?.pastCollabs ?? []).filter(
          (c) => c.rating != null || (c.publicQuote ?? '').trim().length > 0
        ).length || null,
    },
    platforms: (row.platforms ?? []).map((p) => ({
      name: PLATFORM_LABEL[p.platform] ?? p.platform,
      icon: PLATFORM_ICON[p.platform] ?? Globe,
      handle: p.handle,
      followers: p.followers,
      engagement: p.engagementRate,
      verified: p.isVerified,
      color: PLATFORM_COLOR[p.platform] ?? '#6B7280',
    })),
    audience: {
      // F-0795/F-0796 [ananya · 2026-09-17] — age/gender/city breakdown moved out of this
      // synchronous view-model entirely; see `deriveDemographicsView` and the component's own
      // demographics effect below, which now call the real
      // GET /analytics/creators/{creatorId}/demographics endpoint.
      interests: [],
      // BR-18 fix: was hardcoded to 0, which rendered as "0% — Excellent authenticity" — live
      // misleading UI. `row.scores.authenticity` is the real `100 - fakeFollowerScore` value;
      // `null` (not yet scored) stays null all the way to the ring below.
      authenticity: row.scores?.authenticity ?? null,
    },
    // [F-0972] All four of these were hardcoded empty because GET /creators/profile/:x did not
    // carry the portfolio. It does now (PortfolioBrandView, assembled by
    // PortfolioService#getForBrand), already filtered by the creator's own visibility
    // toggles — so an empty array here means the creator chose to hide that section or has
    // nothing in it, never that the field is unimplemented. Do not re-filter it client-side.
    portfolio: (row.portfolio?.contentPortfolio ?? []).map((post) => ({
      id: post.id,
      platform: post.platform,
      caption: post.caption ?? null,
      thumbnailUrl: post.thumbnailUrl ?? null,
      views: post.views ?? null,
      likes: post.likes ?? null,
    })),
    rates: {
      rows: row.portfolio?.rateCard ?? [],
      floor: row.rateMin,
      ceiling: row.rateMax,
      currency: row.currency,
    },
    // A 'category' or 'hidden' collab is anonymised or dropped server-side, so brandName is
    // already safe to print; dedupe because one brand can run several campaigns.
    pastBrands: Array.from(
      new Set((row.portfolio?.pastCollabs ?? []).map((c) => c.brandName).filter(Boolean))
    ),
    // Only collabs the brand actually reviewed. A collab with neither a rating nor a quote
    // is a completed campaign, not a review, and must not appear as a blank one.
    reviews: (row.portfolio?.pastCollabs ?? [])
      .filter((c) => c.rating != null || (c.publicQuote ?? '').trim().length > 0)
      .map((c) => ({
        brand: c.brandName,
        rating: c.rating ?? 0,
        comment: c.publicQuote ?? '',
        date: c.completedAt,
      })),
    metrics: {
      // responseTime and completionRate still have no backend field anywhere — PortfolioStats
      // measures delivery, not responsiveness — so they stay honestly absent. on-time and
      // repeat-brands are real now (F-0972).
      responseTime: '—',
      completionRate: null,
      onTimeDelivery: row.portfolio?.stats?.onTimeRate ?? null,
      onTimeSampleSize: row.portfolio?.stats?.onTimeSampleSize ?? 0,
      repeatBrands: row.portfolio?.stats?.repeatBrands ?? null,
    },
  };
}

/**
 * F-0795/F-0796 [ananya · 2026-09-17] — the pre-consent "band" state for one audience-demographics
 * card. Deliberately shows no number of any kind (not even a placeholder percentage) — see the
 * DemographicsView 'locked' comment above for why a real band description isn't available here.
 * Semantic tokens only (`text-muted-foreground`/`text-foreground`): `text-destructive` is
 * unreadable in this theme, and this is an informational lock state, not an error.
 * Source: wiki/decisions/2026-09-15-brand-preconsent-visibility.md
 */
function LockedAudienceBand({ copy }: { copy: string }) {
  return (
    <div className="flex items-start gap-2.5 text-sm">
      <Lock className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
      <div>
        <p className="font-medium text-foreground">Locked pre-connection</p>
        <p className="mt-0.5 text-muted-foreground">{copy}</p>
      </div>
    </div>
  );
}

export default function BrandCreatorProfilePage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const liveApi = isApiLive();

  // Live mode: GET /creators/profile/:id, mapped through buildLiveCreatorView above.
  // Mock mode keeps rendering mockCreator exactly as before.
  const [liveCreator, setLiveCreator] = React.useState<CreatorDisplayModel | null>(null);
  // Raw (unprefixed) username, kept alongside `liveCreator` — GET /creators/:username/similar
  // (below) requires the real username, not the `@`-prefixed display value or the :id route param
  // (which may itself be a creatorId, not a username; CreatorController.similar resolves by
  // username only — CreatorDiscoveryService.getSimilar → requireDiscoverableByUsername).
  const [liveUsername, setLiveUsername] = React.useState<string | null>(null);
  // The canonical CreatorProfile id from the loaded row. The `:id` route param is NOT usable for
  // the analytics endpoints: CreatorDiscoveryService.resolveDiscoverableProfile accepts a profile
  // id, a user id OR a username, and links into this page carry a user id in practice (the deal
  // room's "View Profile" passes Deal.counterpartyId). AnalyticsService keys on the profile id, so
  // a user id there 403s — indistinguishable from the genuine pre-consent state below.
  const [liveProfileId, setLiveProfileId] = React.useState<string | null>(null);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  const [notFound, setNotFound] = React.useState(false);
  const [reloadToken, setReloadToken] = React.useState(0);

  React.useEffect(() => {
    if (!liveApi || !id) return;
    let cancelled = false;
    setLoadError(null);
    setNotFound(false);
    (async () => {
      try {
        const row = await api.creators.getProfile(id);
        if (cancelled) return;
        if (!row) {
          setNotFound(true);
        } else {
          setLiveCreator(buildLiveCreatorView(row));
          setLiveUsername(row.username || null);
          setLiveProfileId(row.id || null);
        }
      } catch (e) {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 404) {
          setNotFound(true);
        } else {
          setLoadError(e instanceof ApiError ? e.message : 'Could not load creator. Try again.');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi, id, reloadToken]);

  const creator: CreatorDisplayModel | null = liveApi ? liveCreator : mockCreator;

  /**
   * F-0795/F-0796 [ananya · 2026-09-17] — GET /analytics/creators/{creatorId}/demographics
   * (AnalyticsController.getDemographics), gated by MetricsAuthorizationService. Kept as its own
   * effect/state (not folded into buildLiveCreatorView above) because a 403 here is an expected,
   * routine pre-consent response — not a page-level load error — and must resolve into the
   * 'locked' band state below, never the error/toast path `loadError` uses for the profile fetch.
   * Source: wiki/decisions/2026-09-15-brand-preconsent-visibility.md
   */
  const [demographicsState, setDemographicsState] = React.useState<DemographicsView>({ status: 'loading' });
  React.useEffect(() => {
    // Waits for the profile row: `liveProfileId` is the CreatorProfile id these endpoints need.
    if (!liveApi || !liveProfileId) return;
    let cancelled = false;
    setDemographicsState({ status: 'loading' });
    (async () => {
      try {
        const data = await api.analytics.getCreatorDemographics(liveProfileId);
        if (cancelled) return;
        setDemographicsState(deriveDemographicsView(data));
      } catch (e) {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 403) {
          // Pre-consent — MetricsAuthorizationService found no active MetaOAuthToken pairing.
          // Render the locked band state, never an error toast (ruling, LOCKED).
          setDemographicsState({ status: 'locked' });
        } else {
          // Any other failure (network, 5xx) — a secondary panel, so fail into the same honest
          // "nothing to show yet" state rather than surfacing a raw error on the whole page.
          setDemographicsState({ status: 'unavailable' });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi, liveProfileId]);

  const audienceDemographics: DemographicsView = liveApi ? demographicsState : mockAudienceDemographics;

  const [isSaved, setIsSaved] = React.useState(false);
  const [isInviteOpen, setIsInviteOpen] = React.useState(false);
  const [selectedCampaign, setSelectedCampaign] = React.useState('');
  const [inviteMessage, setInviteMessage] = React.useState('');
  const [isSubmitting, setIsSubmitting] = React.useState(false);

  const mockCampaigns = [
    { id: 'c1', name: 'Summer Fashion 2024' },
    { id: 'c2', name: 'Festive Collection Launch' },
    { id: 'c3', name: 'New Product Reveal' },
    { id: 'new', name: '+ Create New Campaign' },
  ];

  // Live mode: load the brand's real campaigns for the invite dropdown; mock
  // mode keeps the demo rows so the offline/demo experience is unchanged.
  const [liveCampaigns, setLiveCampaigns] = React.useState<{ id: string; name: string }[] | null>(null);
  React.useEffect(() => {
    if (!liveApi) return;
    let cancelled = false;
    (async () => {
      try {
        const { campaigns: rows } = await api.campaigns.list({});
        if (!cancelled) setLiveCampaigns(rows.map((c) => ({ id: c.id, name: c.title })));
      } catch {
        if (!cancelled) setLiveCampaigns([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi]);

  // D-14 — GET /creators/:username/similar (CreatorController.similar), fired once the real
  // username resolves above. A LOW-severity backend-complete endpoint with no prior FE consumer
  // (wiki/errors/BRAND-BUG-TRACKER.md); this profile page — the natural place a brand asks "who
  // else looks like this creator" — is the slot for it. Best-effort: an empty/failed fetch just
  // hides the section rather than surfacing an error, since it's a secondary recommendation, not
  // core profile content.
  const [similarCreators, setSimilarCreators] = React.useState<SimilarCreator[]>([]);
  React.useEffect(() => {
    if (!liveApi || !liveUsername) return;
    let cancelled = false;
    (async () => {
      try {
        const { similar } = await api.creators.similar(liveUsername, 6);
        if (!cancelled) setSimilarCreators(similar);
      } catch {
        if (!cancelled) setSimilarCreators([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi, liveUsername]);

  const campaignOptions = liveApi
    ? [...(liveCampaigns ?? []), { id: 'new', name: '+ Create New Campaign' }]
    : mockCampaigns;

  const [inviteError, setInviteError] = React.useState<string | null>(null);

  const handleInvite = async () => {
    if (selectedCampaign === 'new') {
      navigate('/brand/campaigns/new');
      return;
    }
    setIsSubmitting(true);
    setInviteError(null);
    try {
      if (liveApi && id) {
        await api.creators.invite(id, selectedCampaign, inviteMessage || undefined);
      } else {
        // Mock mode: keep the original simulated send.
        await new Promise((r) => setTimeout(r, 1500));
      }
      setIsInviteOpen(false);
      setSelectedCampaign('');
      setInviteMessage('');
    } catch (e) {
      setInviteError(e instanceof ApiError ? e.message : 'Could not send invite. Try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Loading / error / not-found states (live mode only — mock mode always
  // resolves `creator` synchronously above).
  if (!creator) {
    if (loadError) {
      return (
        <div className="mx-auto max-w-md px-4 py-16 text-center">
          <p className="text-sm font-medium text-destructive-foreground">Could not load creator</p>
          <p className="mt-1 text-sm text-muted-foreground">{loadError}</p>
          <Button variant="outline" className="mt-4" onClick={() => setReloadToken((k) => k + 1)}>
            Try again
          </Button>
        </div>
      );
    }
    if (notFound) {
      return (
        <div className="mx-auto max-w-md px-4 py-16 text-center">
          <p className="text-sm font-medium">Creator not found</p>
          <p className="mt-1 text-sm text-muted-foreground">
            This creator profile doesn&apos;t exist or is no longer available.
          </p>
          <Button variant="outline" className="mt-4" onClick={() => navigate(-1)}>
            <ArrowLeft className="mr-2 h-4 w-4" />
            Go back
          </Button>
        </div>
      );
    }
    return (
      <div className="flex min-h-[50vh] items-center justify-center text-sm text-muted-foreground">
        Loading creator profile…
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <div className="sticky top-0 z-10 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="flex h-14 items-center gap-4 px-4 md:px-6">
          <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div className="flex-1" />
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setIsSaved(!isSaved)}
            className={isSaved ? 'text-primary' : ''}
          >
            {isSaved ? <BookmarkCheck className="h-4 w-4" /> : <Bookmark className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      <div className="mx-auto max-w-5xl px-4 py-6 md:px-6 md:py-8">
        {/* Profile Header */}
        <div className="flex flex-col gap-6 md:flex-row md:items-start md:gap-8">
          {/* Avatar */}
          <Avatar className="h-24 w-24 md:h-32 md:w-32 ring-2 ring-border">
            <AvatarImage src={creator.avatarUrl || undefined} />
            <AvatarFallback className="bg-muted text-2xl md:text-3xl font-medium">
              {creator.displayName.split(' ').map(n => n[0]).join('')}
            </AvatarFallback>
          </Avatar>

          {/* Info */}
          <div className="flex-1 space-y-4">
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-2xl font-semibold tracking-tight">{creator.displayName}</h1>
                {creator.isVerified && (
                  <CheckCircle2 className="h-5 w-5 fill-primary text-primary-foreground" />
                )}
              </div>
              <p className="text-muted-foreground">{creator.username}</p>
            </div>

            <p className="text-sm text-muted-foreground leading-relaxed max-w-2xl">
              {creator.bio}
            </p>

            {/* Meta */}
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <MapPin className="h-3.5 w-3.5" />
                {creator.location}
              </span>
              <span className="flex items-center gap-1.5">
                <Languages className="h-3.5 w-3.5" />
                {creator.languages.join(', ')}
              </span>
              {creator.joinedDate && (
                <span className="flex items-center gap-1.5">
                  <Calendar className="h-3.5 w-3.5" />
                  Joined {creator.joinedDate}
                </span>
              )}
              {creator.isAvailable && (
                <Badge variant="secondary" className="bg-green-500/10 text-stage-approved-fg border-0">
                  <span className="mr-1.5 h-1.5 w-1.5 rounded-full bg-green-500 animate-pulse" />
                  Available
                </Badge>
              )}
            </div>

            {/* Categories */}
            <div className="flex flex-wrap gap-1.5">
              {creator.categories.map((cat) => (
                <Badge key={cat} variant="secondary" className="font-normal">
                  {cat}
                </Badge>
              ))}
            </div>

            {/* Actions */}
            <div className="flex flex-wrap gap-3 pt-2">
              <Button onClick={() => setIsInviteOpen(true)}>
                <Sparkles className="mr-2 h-4 w-4" />
                Invite to Campaign
              </Button>
              <Button
                variant="outline"
                onClick={() => navigate(`/brand/messages?creator=${creator.id}`)}
              >
                <Mail className="mr-2 h-4 w-4" />
                Message
              </Button>
              {creator.website && (
                <Button variant="outline" asChild>
                  <a href={`https://${creator.website}`} target="_blank" rel="noopener noreferrer">
                    <Globe className="mr-2 h-4 w-4" />
                    Website
                  </a>
                </Button>
              )}
            </div>
          </div>
        </div>

        {/* Stats Grid */}
        <div className="mt-8 grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
          {[
            // EV-008 — the headline total is Meta-synced (VERIFIED) or Marketplace/admin-imported
            // (IMPORTED); an imported figure is labelled so a brand never reads it as verified, and
            // NONE (nothing counted yet) renders "—" instead of a measured-looking 0.
            {
              label: followersCaption(creator.stats.followersSource),
              value:
                creator.stats.followersSource === 'NONE'
                  ? '—'
                  : formatNumber(creator.stats.totalFollowers),
              icon: Users,
            },
            {
              label:
                creator.stats.followersSource === 'IMPORTED'
                  ? 'Engagement · imported, not verified'
                  : 'Engagement',
              value:
                creator.stats.avgEngagement != null && creator.stats.followersSource !== 'NONE'
                  ? `${creator.stats.avgEngagement}%`
                  : '—',
              icon: TrendingUp,
            },
            {
              // F-0260 — `null` means the DTO has no per-post average; render an explicit
              // not-available state instead of a fabricated "0" (same rule as Rating below).
              label: 'Avg Likes',
              value: creator.stats.avgLikes != null ? formatNumber(creator.stats.avgLikes) : '—',
              icon: Heart,
            },
            {
              label: 'Avg Views',
              value: creator.stats.avgViews != null ? formatNumber(creator.stats.avgViews) : '—',
              icon: Eye,
            },
            { label: 'Campaigns', value: creator.stats.completedCampaigns.toString(), icon: Briefcase },
            {
              label: 'Rating',
              // PR-1 — `null` means no reviews yet; render an explicit not-available state
              // instead of a fabricated "0" (Priya, UI Honesty rule).
              value: creator.stats.rating != null ? creator.stats.rating.toFixed(1) : 'Not yet rated',
              icon: Star,
            },
          ].map((stat) => (
            <div
              key={stat.label}
              className="rounded-lg border bg-card p-4 text-center"
            >
              <stat.icon className="mx-auto mb-2 h-4 w-4 text-muted-foreground" />
              <p className="text-lg font-semibold">{stat.value}</p>
              <p className="text-xs text-muted-foreground">{stat.label}</p>
            </div>
          ))}
        </div>

        {/* Platforms */}
        <div className="mt-8">
          <h2 className="mb-4 text-sm font-medium text-muted-foreground uppercase tracking-wider">
            Platforms
          </h2>
          {/* F-0980 — the server now honours the creator's "Platform stats" switch, so this list
              can legitimately be empty for a creator who has real platforms. An empty grid
              renders NOTHING, and a brand reads a missing section as "this creator has no
              platforms" rather than "this creator chose not to publish their handles yet" —
              the fabricated-absence failure F-0589/F-0972 exist to prevent. State it instead.
              Headline reach and the verified/self-reported provenance above are deliberately
              unaffected: only the per-platform handles are withheld. */}
          {creator.platforms.length === 0 ? (
            <div className="rounded-lg border bg-card p-4">
              <p className="text-sm font-medium">This creator has hidden their platform handles</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Their total reach and engagement above are unchanged. Handles and profile links
                are typically shared once a deal is agreed.
              </p>
            </div>
          ) : (
          <div className="grid gap-3 sm:grid-cols-3">
            {creator.platforms.map((platform) => (
              <div
                key={platform.name}
                className="flex items-center gap-4 rounded-lg border bg-card p-4"
              >
                <div
                  className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--platform-bg)]"
                  ref={cssVars({ '--platform-bg': `${platform.color}15` })}
                >
                  <platform.icon className="h-5 w-5 text-[var(--platform-fg)]" ref={cssVars({ '--platform-fg': platform.color })} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1.5">
                    <p className="font-medium truncate">{platform.name}</p>
                    {/* CR-119 — the ✓ was the ONLY provenance signal here, and it never rendered:
                        the backend wrote PlatformStat.verified=false unconditionally, so genuinely
                        Meta-verified Instagram looked exactly like a creator-typed YouTube/TikTok
                        number. The flag is real now, but "no badge" is far too quiet a way to say
                        "this figure is self-reported" to a brand about to spend money on it —
                        absence of a mark reads as an oversight, not as a claim. Both states are
                        stated explicitly instead. */}
                    {platform.verified ? (
                      <span
                        className="flex items-center gap-1 text-primary flex-shrink-0"
                        title="Followers confirmed directly with the platform's API"
                      >
                        <CheckCircle2 className="h-3.5 w-3.5 fill-primary text-primary-foreground" />
                        <span className="sr-only">Platform-verified</span>
                      </span>
                    ) : null}
                  </div>
                  <p className="text-sm text-muted-foreground">{platform.handle}</p>
                </div>
                <div className="text-right">
                  <p className="font-semibold">{formatNumber(platform.followers)}</p>
                  <p className="text-xs text-muted-foreground">{platform.engagement != null ? `${platform.engagement}% eng` : '— eng'}</p>
                  <p
                    className={
                      platform.verified
                        ? 'text-[11px] text-primary'
                        : 'text-[11px] text-muted-foreground'
                    }
                  >
                    {platform.verified ? 'Followers verified' : 'Self-reported'}
                  </p>
                </div>
              </div>
            ))}
          </div>
          )}
        </div>

        <Separator className="my-8" />

        {/* Tabs */}
        <Tabs defaultValue="overview" className="space-y-6">
          <TabsList className="w-full justify-start h-auto p-1 bg-muted/50">
            <TabsTrigger value="overview" className="text-sm">Overview</TabsTrigger>
            <TabsTrigger value="audience" className="text-sm">Audience</TabsTrigger>
            <TabsTrigger value="portfolio" className="text-sm">Portfolio</TabsTrigger>
            <TabsTrigger value="rates" className="text-sm">Rates</TabsTrigger>
            <TabsTrigger value="reviews" className="text-sm">Reviews</TabsTrigger>
          </TabsList>

          {/* Overview Tab */}
          <TabsContent value="overview" className="space-y-6">
            {/* Work Metrics — PR-1: completion/on-time/repeat have no backend field yet
                (DiscoveryDtos.CreatorPublicProfileResponse), so `null` renders an honest "—"
                instead of a fabricated "0%" presented as fact for every creator. */}
            <div className="grid gap-4 sm:grid-cols-4">
              {[
                { label: 'Response Time', value: creator.metrics.responseTime },
                {
                  label: 'Completion Rate',
                  value: creator.metrics.completionRate != null ? `${creator.metrics.completionRate}%` : '—',
                },
                {
                  // [F-0589] onTimeRate is null when nothing was measurable — not 0%.
                  label:
                    creator.metrics.onTimeSampleSize > 0
                      ? `On-Time Delivery (${creator.metrics.onTimeSampleSize})`
                      : 'On-Time Delivery',
                  value: creator.metrics.onTimeDelivery != null ? `${creator.metrics.onTimeDelivery}%` : '—',
                },
                {
                  // [F-0972] A count, rendered bare. The old tile said 'Repeat Clients' and
                  // appended '%', which would print a count of 3 as '3%'.
                  label: 'Repeat Brands',
                  value: creator.metrics.repeatBrands != null ? String(creator.metrics.repeatBrands) : '—',
                },
              ].map((metric) => (
                <div key={metric.label} className="rounded-lg border p-4">
                  <p className="text-2xl font-semibold">{metric.value}</p>
                  <p className="text-sm text-muted-foreground">{metric.label}</p>
                </div>
              ))}
            </div>

            {/* Past Brands */}
            <div>
              <h3 className="mb-3 text-sm font-medium text-muted-foreground uppercase tracking-wider">
                Brands Worked With
              </h3>
              <div className="flex flex-wrap gap-2">
                {creator.pastBrands.map((brand) => (
                  <Badge key={brand} variant="outline" className="font-normal">
                    {brand}
                  </Badge>
                ))}
              </div>
            </div>
          </TabsContent>

          {/* Audience Tab */}
          <TabsContent value="audience" className="space-y-6">
            <div className="grid gap-6 md:grid-cols-2">
              {/*
                F-0795/F-0796 [ananya · 2026-09-17] — Age Distribution / Gender Split / Top
                Cities all key off the shared `audienceDemographics` state (deriveDemographicsView
                / the component's demographics effect above). 'locked' renders the pre-consent
                band state instead of exact numbers: MetricsAuthorizationService 403s this
                endpoint until a MetaOAuthToken pairing exists, and the ruling forbids ever
                showing an exact age/gender/city breakdown before that connection.
                Source: wiki/decisions/2026-09-15-brand-preconsent-visibility.md
              */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Age Distribution</h3>
                {audienceDemographics.status === 'loading' ? (
                  <p className="text-sm text-muted-foreground">Loading…</p>
                ) : audienceDemographics.status === 'locked' ? (
                  <LockedAudienceBand copy="Age breakdown is hidden until this creator connects with your brand." />
                ) : audienceDemographics.status === 'unavailable' || audienceDemographics.ageGroups.length === 0 ? (
                  <p className="text-sm text-muted-foreground">Not available</p>
                ) : (
                  <div className="space-y-3">
                    {audienceDemographics.ageGroups.map((group) => (
                      <div key={group.range} className="space-y-1.5">
                        <div className="flex justify-between text-sm">
                          <span>{group.range}</span>
                          <span className="text-muted-foreground">{group.percentage}%</span>
                        </div>
                        <Progress value={group.percentage} className="h-2" />
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Gender Split</h3>
                {audienceDemographics.status === 'loading' ? (
                  <p className="text-sm text-muted-foreground">Loading…</p>
                ) : audienceDemographics.status === 'locked' ? (
                  <LockedAudienceBand copy="Gender split is hidden until this creator connects with your brand." />
                ) : audienceDemographics.status === 'unavailable' ? (
                  <p className="text-sm text-muted-foreground">Not available</p>
                ) : (
                  <>
                    <div className="flex h-4 overflow-hidden rounded-full">
                      <div
                        className="bg-pink-500 w-[var(--gender-female-w)]"
                        ref={cssVars({ '--gender-female-w': `${audienceDemographics.gender.female}%` })}
                      />
                      <div
                        className="bg-blue-500 w-[var(--gender-male-w)]"
                        ref={cssVars({ '--gender-male-w': `${audienceDemographics.gender.male}%` })}
                      />
                      <div
                        className="bg-purple-500 w-[var(--gender-other-w)]"
                        ref={cssVars({ '--gender-other-w': `${audienceDemographics.gender.other}%` })}
                      />
                    </div>
                    <div className="mt-3 flex gap-4 text-sm">
                      <span className="flex items-center gap-1.5">
                        <span className="h-2.5 w-2.5 rounded-full bg-pink-500" />
                        Female {audienceDemographics.gender.female}%
                      </span>
                      <span className="flex items-center gap-1.5">
                        <span className="h-2.5 w-2.5 rounded-full bg-blue-500" />
                        Male {audienceDemographics.gender.male}%
                      </span>
                    </div>
                  </>
                )}
              </div>

              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Top Cities</h3>
                {audienceDemographics.status === 'loading' ? (
                  <p className="text-sm text-muted-foreground">Loading…</p>
                ) : audienceDemographics.status === 'locked' ? (
                  <LockedAudienceBand copy="Top cities are hidden until this creator connects with your brand." />
                ) : audienceDemographics.status === 'unavailable' || audienceDemographics.topCities.length === 0 ? (
                  <p className="text-sm text-muted-foreground">Not available</p>
                ) : (
                  <div className="space-y-3">
                    {audienceDemographics.topCities.map((city, i) => (
                      <div key={city.city} className="flex items-center gap-3">
                        <span className="flex h-6 w-6 items-center justify-center rounded-full bg-muted text-xs font-medium">
                          {i + 1}
                        </span>
                        <span className="flex-1">{city.city}</span>
                        <span className="text-muted-foreground">{city.percentage}%</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Authenticity — BR-18. `null` means this creator hasn't been scored yet (the
                  common case for anyone not yet polled by Meta), and must render as an explicit
                  "not yet scored" state rather than a fabricated 0%/ring. When a real score
                  exists, the caption reflects the actual tier instead of a hardcoded "Excellent". */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Audience Authenticity</h3>
                {creator.audience.authenticity == null ? (
                  <p className="text-sm text-muted-foreground">Not yet scored</p>
                ) : (
                  <div className="flex items-center gap-4">
                    <div className="relative h-24 w-24">
                      <svg className="h-24 w-24 -rotate-90" viewBox="0 0 36 36">
                        <circle cx="18" cy="18" r="16" fill="none" stroke="currentColor" strokeWidth="2" className="text-muted" />
                        <circle
                          cx="18" cy="18" r="16" fill="none" stroke="currentColor" strokeWidth="2"
                          strokeDasharray={`${creator.audience.authenticity} 100`}
                          className="text-green-500"
                        />
                      </svg>
                      <div className="absolute inset-0 flex items-center justify-center">
                        <span className="text-xl font-semibold">{Math.round(creator.audience.authenticity)}%</span>
                      </div>
                    </div>
                    <div className="text-sm text-muted-foreground">
                      <p>Real followers verified</p>
                      <p className="mt-1 text-stage-approved-fg font-medium">
                        {creator.audience.authenticity >= 80
                          ? 'Excellent authenticity'
                          : creator.audience.authenticity >= 50
                            ? 'Fair authenticity'
                            : 'Needs review'}
                      </p>
                    </div>
                  </div>
                )}
              </div>
            </div>
          </TabsContent>

          {/* Portfolio Tab */}
          <TabsContent value="portfolio">
            {/* [F-0972] Empty means the creator hid this section or has pinned nothing —
                say which rather than rendering a silent blank grid. */}
            {creator.portfolio.length === 0 ? (
              <div className="rounded-lg border border-dashed p-8 text-center">
                <p className="font-medium">No content to show</p>
                <p className="mt-1 text-sm text-muted-foreground">
                  This creator hasn't pinned any content to their portfolio, or has chosen not to
                  display it.
                </p>
              </div>
            ) : (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {creator.portfolio.map((item) => (
                <div
                  key={item.id}
                  className="group relative aspect-square overflow-hidden rounded-lg bg-muted"
                >
                  {item.thumbnailUrl ? (
                    <img
                      src={item.thumbnailUrl}
                      alt={item.caption ?? 'Portfolio content'}
                      className="absolute inset-0 h-full w-full object-cover"
                      loading="lazy"
                    />
                  ) : (
                    <div className="absolute inset-0 flex items-center justify-center">
                      {item.platform === 'YOUTUBE' ? (
                        <Play className="h-12 w-12 text-muted-foreground/50" />
                      ) : (
                        <Eye className="h-12 w-12 text-muted-foreground/50" />
                      )}
                    </div>
                  )}
                  <div className="absolute inset-0 flex flex-col justify-end bg-gradient-to-t from-black/80 via-black/20 to-transparent p-4 opacity-0 transition-opacity group-hover:opacity-100">
                    <Badge variant="secondary" className="w-fit mb-2">
                      {PLATFORM_LABEL[item.platform] ?? item.platform}
                    </Badge>
                    {/* [F-0972] A pinned post carries a caption, not a brand. The old markup
                        printed `for {item.brand}` from a field the wire never had. */}
                    {item.caption && (
                      <p className="line-clamp-2 text-sm font-medium text-white">{item.caption}</p>
                    )}
                    {/* views/likes are optional on the wire — absent is not zero (F-0589). */}
                    {(item.views != null || item.likes != null) && (
                      <div className="mt-2 flex gap-4 text-xs text-white/80">
                        {item.views != null && (
                          <span className="flex items-center gap-1">
                            <Eye className="h-3 w-3" />
                            {formatNumber(item.views)}
                          </span>
                        )}
                        {item.likes != null && (
                          <span className="flex items-center gap-1">
                            <Heart className="h-3 w-3" />
                            {formatNumber(item.likes)}
                          </span>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
            )}
          </TabsContent>

          {/* Rates Tab */}
          <TabsContent value="rates" className="space-y-6">
            {/* [F-0974] The creator's real per-deliverable rate card, served here when they
                set it to 'public' or 'brands_only'. Until 2026-09-20 this tab mapped to two
                hardcoded empty arrays and rendered two empty bordered cards under an
                'Instagram'/'YouTube' split the rate card does not actually have. */}
            {creator.rates.rows.length > 0 ? (
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Rate card</h3>
                <div className="grid gap-3 sm:grid-cols-2">
                  {creator.rates.rows.map((rate) => (
                    <div
                      key={rate.id}
                      className="flex items-center justify-between gap-3 rounded-lg bg-muted/50 p-4"
                    >
                      <span>{rate.label}</span>
                      <span className="flex shrink-0 items-center gap-1 font-semibold">
                        <IndianRupee className="h-3.5 w-3.5" />
                        {rate.min === rate.max
                          ? formatINR(rate.min)
                          : `${formatINR(rate.min)}–${formatINR(rate.max)}`}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            ) : creator.rates.floor != null || creator.rates.ceiling != null ? (
              /* No per-deliverable card, but the profile-level range exists — the DTO has
                 always carried it and this page used to throw it away. */
              <div className="rounded-lg border p-5">
                <h3 className="mb-1 font-medium">Typical range</h3>
                <p className="flex items-center gap-1 text-2xl font-semibold">
                  <IndianRupee className="h-5 w-5" />
                  {creator.rates.floor != null && creator.rates.ceiling != null
                    ? `${formatINR(creator.rates.floor)}–${formatINR(creator.rates.ceiling)}`
                    : formatINR((creator.rates.floor ?? creator.rates.ceiling) as number)}
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  This creator hasn't published a per-deliverable rate card.
                </p>
              </div>
            ) : (
              <div className="rounded-lg border border-dashed p-8 text-center">
                <p className="font-medium">Rates not shared</p>
                <p className="mt-1 text-sm text-muted-foreground">
                  This creator has chosen not to publish pricing. Send them a brief to get a quote.
                </p>
              </div>
            )}

            {(creator.rates.rows.length > 0 ||
              creator.rates.floor != null ||
              creator.rates.ceiling != null) && (
              <p className="text-sm text-muted-foreground">
                * Rates are indicative and may vary based on campaign requirements, exclusivity, and usage rights.
              </p>
            )}
          </TabsContent>

          {/* Reviews Tab */}
          <TabsContent value="reviews" className="space-y-4">
            {/* Rating Summary — PR-1: `rating` is `null` when this creator has no reviews yet;
                render an explicit not-available state instead of a fabricated "0" / 0 filled
                stars (Priya, UI Honesty rule). */}
            <div className="flex items-center gap-6 rounded-lg border p-5">
              <div className="text-center">
                <p className="text-4xl font-semibold">
                  {creator.stats.rating != null ? creator.stats.rating.toFixed(1) : '—'}
                </p>
                <div className="mt-1 flex gap-0.5">
                  {Array.from({ length: 5 }).map((_, i) => (
                    <Star
                      key={i}
                      className={cn(
                        'h-4 w-4',
                        creator.stats.rating != null && i < Math.floor(creator.stats.rating)
                          ? 'fill-yellow-400 text-yellow-400'
                          : 'fill-muted text-muted'
                      )}
                    />
                  ))}
                </div>
                <p className="mt-1 text-sm text-muted-foreground">
                  {creator.stats.rating != null
                    ? creator.stats.reviewCount != null
                      ? `${creator.stats.reviewCount} reviews`
                      : 'Average brand rating'
                    : 'Not yet rated'}
                </p>
              </div>
              <Separator orientation="vertical" className="h-16" />
              {/* PR-1 — this used to assert "Based on verified brand collaborations" next to a
                  fabricated 0/0-stars for every unrated creator, i.e. a false attestation beside
                  no data. Only shown once a real rating exists; otherwise mirrors the app's
                  existing "No reviews yet" empty state (collaboration-reviews-panel.tsx). */}
              {creator.stats.rating != null ? (
                <div className="text-sm text-muted-foreground">
                  <p>Based on verified brand collaborations</p>
                  <p className="mt-1">All reviews are from completed campaigns</p>
                </div>
              ) : (
                <div className="text-sm text-muted-foreground">
                  <p className="font-medium text-foreground">No reviews yet</p>
                  <p className="mt-1">
                    Reviews appear here once brands rate a completed collaboration with this creator.
                  </p>
                </div>
              )}
            </div>

            {/* Review List */}
            <div className="space-y-4">
              {creator.reviews.map((review, i) => (
                <div key={i} className="rounded-lg border p-5">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="flex h-10 w-10 items-center justify-center rounded-full bg-muted font-medium">
                        {review.brand.charAt(0)}
                      </div>
                      <div>
                        <p className="font-medium">{review.brand}</p>
                        <p className="text-sm text-muted-foreground">{review.date}</p>
                      </div>
                    </div>
                    <div className="flex gap-0.5">
                      {Array.from({ length: 5 }).map((_, i) => (
                        <Star
                          key={i}
                          className={cn(
                            'h-4 w-4',
                            i < review.rating
                              ? 'fill-yellow-400 text-yellow-400'
                              : 'fill-muted text-muted'
                          )}
                        />
                      ))}
                    </div>
                  </div>
                  <p className="mt-3 text-sm text-muted-foreground leading-relaxed">
                    {review.comment}
                  </p>
                </div>
              ))}
            </div>
          </TabsContent>
        </Tabs>

        {/* Similar Creators — D-14, GET /creators/:username/similar. Only rendered once real
            results arrive; absent (not an empty-state placeholder) otherwise, since this is a
            secondary recommendation rather than core profile content. */}
        {similarCreators.length > 0 && (
          <div className="mt-8">
            <h2 className="mb-4 text-sm font-medium text-muted-foreground uppercase tracking-wider">
              Similar Creators
            </h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {similarCreators.map((sc) => (
                <button
                  key={sc.id}
                  type="button"
                  onClick={() => navigate(`/brand/creators/${sc.id}`)}
                  className="flex items-center gap-3 rounded-lg border bg-card p-3 text-left transition-colors hover:bg-accent/50"
                >
                  <Avatar className="h-10 w-10 shrink-0">
                    <AvatarImage src={sc.avatarUrl || undefined} />
                    <AvatarFallback>{sc.displayName.charAt(0)}</AvatarFallback>
                  </Avatar>
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">{sc.displayName}</p>
                    <p className="truncate text-xs text-muted-foreground">
                      {formatNumber(sc.totalFollowers)} {followersCaption(sc.followersSource).toLowerCase()}
                      {sc.engagementRate != null ? ` · ${sc.engagementRate}% ER` : ''}
                    </p>
                  </div>
                  <Badge variant="outline" className="shrink-0 text-xs">
                    {Math.round(sc.matchScore)}% match
                  </Badge>
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Invite Dialog */}
      <Dialog open={isInviteOpen} onOpenChange={setIsInviteOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Invite {creator.displayName}</DialogTitle>
            <DialogDescription>
              Send a collaboration invitation for one of your campaigns.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label>Select Campaign</Label>
              <Select value={selectedCampaign} onValueChange={setSelectedCampaign}>
                <SelectTrigger>
                  <SelectValue placeholder="Choose a campaign" />
                </SelectTrigger>
                <SelectContent>
                  {campaignOptions.map((c) => (
                    <SelectItem key={c.id} value={c.id}>
                      {c.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            {selectedCampaign && selectedCampaign !== 'new' && (
              <div className="space-y-2">
                <Label>Message (optional)</Label>
                <Textarea
                  placeholder="Add a personal message..."
                  value={inviteMessage}
                  onChange={(e) => setInviteMessage(e.target.value)}
                  rows={3}
                />
              </div>
            )}
            {inviteError && (
              <p className="text-sm text-destructive-foreground">{inviteError}</p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setIsInviteOpen(false)}>
              Cancel
            </Button>
            <Button onClick={handleInvite} disabled={!selectedCampaign || isSubmitting}>
              {isSubmitting ? 'Sending...' : selectedCampaign === 'new' ? 'Create Campaign' : 'Send Invite'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
