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
  type LucideIcon,
} from 'lucide-react';
import { api, isApiLive, ApiError, type CreatorPublicProfile, type SimilarCreator } from '@/lib/api';
import type { Platform } from '@/lib/types';
import { cn } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';
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
  engagement: number;
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
    avgEngagement: number;
    avgLikes: number;
    avgComments: number;
    avgViews: number;
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
    ageGroups: { range: string; percentage: number }[];
    gender: { female: number; male: number; other: number };
    topCities: { city: string; percentage: number }[];
    interests: string[];
    /**
     * BR-18 — real `100 - fakeFollowerScore` from `creator.scores.authenticity`
     * (DiscoveryDtos.CreatorScores), or `null` when the creator hasn't been scored yet. Never
     * coerce to `0`: a `0` here used to render as "0% — Excellent authenticity", live misleading
     * UI (Priya, Score Exposure §4).
     */
    authenticity: number | null;
  };
  portfolio: { id: number | string; type: string; brand: string; views: number; likes: number }[];
  rates: {
    instagram: { type: string; price: number }[];
    youtube: { type: string; price: number }[];
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
    repeatClients: number | null;
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
    interests: ['Fashion', 'Beauty', 'Travel', 'Fitness', 'Food'],
    authenticity: 96,
  },

  portfolio: [
    { id: 1, type: 'Reel', brand: 'Myntra', views: 1250000, likes: 89000 },
    { id: 2, type: 'Post', brand: 'Nykaa', views: 450000, likes: 32000 },
    { id: 3, type: 'Video', brand: 'Boat', views: 890000, likes: 45000 },
    { id: 4, type: 'Reel', brand: 'Sugar Cosmetics', views: 2100000, likes: 156000 },
    { id: 5, type: 'Post', brand: 'FabIndia', views: 320000, likes: 28000 },
    { id: 6, type: 'Story', brand: 'Mamaearth', views: 280000, likes: 21000 },
  ],

  rates: {
    instagram: [
      { type: 'Single Post', price: 45000 },
      { type: 'Reel', price: 75000 },
      { type: 'Story (3 frames)', price: 15000 },
      { type: 'Post + Reel Bundle', price: 100000 },
    ],
    youtube: [
      { type: 'Integration (60-90s)', price: 150000 },
      { type: 'Dedicated Video', price: 300000 },
    ],
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
    repeatClients: 72,
  },
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
// rate cards, work-quality metrics, audience demographics, website/availability/joinedDate)
// still has no backend equivalent in either DTO — those keep their honest empty/zero fallback
// below (each flagged // TODO(vikram)) rather than invented numbers.
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
      avgLikes: 0, // TODO(vikram): DTO has no per-post average likes
      avgComments: 0, // TODO(vikram): DTO has no per-post average comments
      avgViews: 0, // TODO(vikram): DTO has no per-post average views
      // PR-1 fix (BrandF.md §87) — real count from GET /creators/profile/:usernameOrId.
      // The old GET /creators/:id (CreatorResponse) had no such field at all, so this was
      // hardcoded to 0 and rendered as a fabricated "0 campaigns" for every creator.
      completedCampaigns: row.completedCampaigns,
      // PR-1 fix — real avg of brand→creator star reviews, or `null` (not a fabricated 0)
      // when this creator has no reviews yet (CreatorDiscoveryService H-22 comment).
      rating: row.avgRating,
      // TODO(vikram): DTO still has no reviewCount field — `null`, not a fabricated 0, so the
      // Reviews-tab render below never prints "0 reviews" next to a real average rating.
      reviewCount: null,
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
      // TODO(vikram): DTO has no audience-demographics fields/endpoint
      ageGroups: [],
      gender: { female: 0, male: 0, other: 0 },
      topCities: [],
      interests: [],
      // BR-18 fix: was hardcoded to 0, which rendered as "0% — Excellent authenticity" — live
      // misleading UI. `row.scores.authenticity` is the real `100 - fakeFollowerScore` value;
      // `null` (not yet scored) stays null all the way to the ring below.
      authenticity: row.scores?.authenticity ?? null,
    },
    // TODO(vikram): PortfolioItemResponse (id/title/description/thumbnailUrl/mediaUrl/platform)
    // has no brand/type/views/likes fields, so it can't be mapped into this grid without inventing data
    portfolio: [],
    // TODO(vikram): DTO only has rateMin/rateMax/currency, no per-platform/per-type rate cards
    rates: { instagram: [], youtube: [] },
    pastBrands: [], // TODO(vikram): DTO has no past-brands history
    reviews: [], // TODO(vikram): DTO has no individual reviews, only the avgRating aggregate
    metrics: {
      // TODO(vikram): DTO has no work-quality metrics (response time / completion / on-time / repeat).
      // `null` (not 0) for the three percentage metrics — PR-1: a "0%" here rendered as fact for
      // every creator is the exact fabricated-zero bug this ticket exists to close.
      responseTime: '—',
      completionRate: null,
      onTimeDelivery: null,
      repeatClients: null,
    },
  };
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
            { label: 'Followers', value: formatNumber(creator.stats.totalFollowers), icon: Users },
            { label: 'Engagement', value: `${creator.stats.avgEngagement}%`, icon: TrendingUp },
            { label: 'Avg Likes', value: formatNumber(creator.stats.avgLikes), icon: Heart },
            { label: 'Avg Views', value: formatNumber(creator.stats.avgViews), icon: Eye },
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
                  <p className="text-xs text-muted-foreground">{platform.engagement}% eng</p>
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
                  label: 'On-Time Delivery',
                  value: creator.metrics.onTimeDelivery != null ? `${creator.metrics.onTimeDelivery}%` : '—',
                },
                {
                  label: 'Repeat Clients',
                  value: creator.metrics.repeatClients != null ? `${creator.metrics.repeatClients}%` : '—',
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
              {/* Age Distribution */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Age Distribution</h3>
                <div className="space-y-3">
                  {creator.audience.ageGroups.map((group) => (
                    <div key={group.range} className="space-y-1.5">
                      <div className="flex justify-between text-sm">
                        <span>{group.range}</span>
                        <span className="text-muted-foreground">{group.percentage}%</span>
                      </div>
                      <Progress value={group.percentage} className="h-2" />
                    </div>
                  ))}
                </div>
              </div>

              {/* Gender */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Gender Split</h3>
                <div className="flex h-4 overflow-hidden rounded-full">
                  <div
                    className="bg-pink-500 w-[var(--gender-female-w)]"
                    ref={cssVars({ '--gender-female-w': `${creator.audience.gender.female}%` })}
                  />
                  <div
                    className="bg-blue-500 w-[var(--gender-male-w)]"
                    ref={cssVars({ '--gender-male-w': `${creator.audience.gender.male}%` })}
                  />
                  <div
                    className="bg-purple-500 w-[var(--gender-other-w)]"
                    ref={cssVars({ '--gender-other-w': `${creator.audience.gender.other}%` })}
                  />
                </div>
                <div className="mt-3 flex gap-4 text-sm">
                  <span className="flex items-center gap-1.5">
                    <span className="h-2.5 w-2.5 rounded-full bg-pink-500" />
                    Female {creator.audience.gender.female}%
                  </span>
                  <span className="flex items-center gap-1.5">
                    <span className="h-2.5 w-2.5 rounded-full bg-blue-500" />
                    Male {creator.audience.gender.male}%
                  </span>
                </div>
              </div>

              {/* Top Cities */}
              <div className="rounded-lg border p-5">
                <h3 className="mb-4 font-medium">Top Cities</h3>
                <div className="space-y-3">
                  {creator.audience.topCities.map((city, i) => (
                    <div key={city.city} className="flex items-center gap-3">
                      <span className="flex h-6 w-6 items-center justify-center rounded-full bg-muted text-xs font-medium">
                        {i + 1}
                      </span>
                      <span className="flex-1">{city.city}</span>
                      <span className="text-muted-foreground">{city.percentage}%</span>
                    </div>
                  ))}
                </div>
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
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {creator.portfolio.map((item) => (
                <div
                  key={item.id}
                  className="group relative aspect-square overflow-hidden rounded-lg bg-muted"
                >
                  <div className="absolute inset-0 flex items-center justify-center">
                    {item.type === 'Reel' || item.type === 'Video' ? (
                      <Play className="h-12 w-12 text-muted-foreground/50" />
                    ) : (
                      <Eye className="h-12 w-12 text-muted-foreground/50" />
                    )}
                  </div>
                  <div className="absolute inset-0 flex flex-col justify-end bg-gradient-to-t from-black/80 via-black/20 to-transparent p-4 opacity-0 transition-opacity group-hover:opacity-100">
                    <Badge variant="secondary" className="w-fit mb-2">{item.type}</Badge>
                    <p className="text-sm font-medium text-white">for {item.brand}</p>
                    <div className="mt-2 flex gap-4 text-xs text-white/80">
                      <span className="flex items-center gap-1">
                        <Eye className="h-3 w-3" />
                        {formatNumber(item.views)}
                      </span>
                      <span className="flex items-center gap-1">
                        <Heart className="h-3 w-3" />
                        {formatNumber(item.likes)}
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </TabsContent>

          {/* Rates Tab */}
          <TabsContent value="rates" className="space-y-6">
            {/* Instagram Rates */}
            <div className="rounded-lg border p-5">
              <div className="mb-4 flex items-center gap-2">
                <Instagram className="h-5 w-5 text-[#E4405F]" />
                <h3 className="font-medium">Instagram</h3>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {creator.rates.instagram.map((rate) => (
                  <div
                    key={rate.type}
                    className="flex items-center justify-between rounded-lg bg-muted/50 p-4"
                  >
                    <span>{rate.type}</span>
                    <span className="font-semibold flex items-center gap-1">
                      <IndianRupee className="h-3.5 w-3.5" />
                      {formatINR(rate.price)}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            {/* YouTube Rates */}
            <div className="rounded-lg border p-5">
              <div className="mb-4 flex items-center gap-2">
                <Youtube className="h-5 w-5 text-[#FF0000]" />
                <h3 className="font-medium">YouTube</h3>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                {creator.rates.youtube.map((rate) => (
                  <div
                    key={rate.type}
                    className="flex items-center justify-between rounded-lg bg-muted/50 p-4"
                  >
                    <span>{rate.type}</span>
                    <span className="font-semibold flex items-center gap-1">
                      <IndianRupee className="h-3.5 w-3.5" />
                      {formatINR(rate.price)}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            <p className="text-sm text-muted-foreground">
              * Rates are indicative and may vary based on campaign requirements, exclusivity, and usage rights.
            </p>
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
                      {formatNumber(sc.totalFollowers)} followers · {sc.engagementRate}% ER
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
