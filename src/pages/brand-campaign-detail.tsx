import * as React from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
  ArrowLeft, Calendar, Users, IndianRupee, Edit, Pause, Play,
  Copy, Trash2, MoreHorizontal, MessageSquare, CheckCircle2,
  XCircle, Clock, Star, TrendingUp, Instagram, Youtube, Twitter,
  FileText, Download, AlertCircle, Sparkles, Filter, Search, FileSignature,
  BadgeCheck, Heart, Eye, BarChart2, Target, Award, RefreshCcw,
  ThumbsUp, Zap, TrendingDown, ChevronRight, Activity, Lock, DollarSign,
  Loader2,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Separator } from '@/components/ui/separator';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { MetricSourceBadge } from '@/components/analytics/metric-source-badge';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Dialog, DialogContent, DialogDescription,
  DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { CampaignStateMachine } from '@/components/brand/campaigns/campaign-state-machine';
import { CollaborationTimeline } from '@/components/brand/timeline/collaboration-timeline';
import { api, isApiLive, ApiError, type Deal, type CampaignAnalytics } from '@/lib/api';
import type { Campaign as ApiCampaign, Collaboration, ContractStatus } from '@/lib/types';
import {
  DealContractGenerate,
  type MilestoneDraft,
} from '@/components/brand/deal-room/deal-contract-generate';
import { useToast } from '@/hooks/use-toast';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';

// ─── Live-mode status buckets ────────────────────────────────────────────────
// `Deal` (src/lib/api.ts) is the real backend equivalent of a "bid" (early stage,
// pre-agreement) and a "collaborator" (post-agreement) — there is no separate
// Bid/Proposal endpoint in influora-api (verified: only DealController exists).
// CollaborationStatus values from src/lib/types.ts bucket into the same three
// tabs this page already renders in mock mode.
const BID_STAGE_STATUSES = new Set(['INVITED', 'APPLIED', 'SHORTLISTED', 'IN_NEGOTIATION']);
const ACTIVE_STAGE_STATUSES = new Set([
  'TERMS_AGREED', 'CONTRACT_PENDING', 'CONTRACTED', 'IN_PROGRESS', 'REVIEW_PENDING', 'REVISION_REQUESTED',
]);
const DONE_STAGE_STATUSES = new Set(['COMPLETED']);

// ─── Mock Data ───────────────────────────────────────────────────────────────
const MOCK_CAMPAIGNS: Record<string, typeof mockActiveCampaign | typeof mockCompletedCampaign> = {};

const mockActiveCampaign = {
  id: 'active-1',
  title: 'Summer Collection Launch',
  description: 'Promote our new summer fashion line with lifestyle content. Looking for creators who can showcase our products in authentic, lifestyle settings that resonate with young adults aged 18-35.',
  objectives: ['Brand awareness', 'Drive sales', 'User-generated content'],
  status: 'ACTIVE' as const,
  budget: { min: 20000, max: 25000, currency: 'INR', spent: 12500 },
  timeline: { startDate: new Date('2024-05-01'), endDate: new Date('2024-08-15') },
  platforms: ['INSTAGRAM', 'TIKTOK', 'YOUTUBE'],
  contentTypes: ['REEL', 'POST', 'STORY'],
  deliverables: [
    { type: 'Instagram Reel', count: 2, price: 1500 },
    { type: 'Instagram Story', count: 3, price: 500 },
    { type: 'TikTok Video', count: 2, price: 1200 },
  ],
  requirements: [
    'Must tag @brandhandle in all posts',
    'Include discount code in caption',
    'No competitor mentions within 30 days',
    'Content approval required before posting',
  ],
  targetAudience: { ageRange: '18-35', gender: 'All', locations: ['United States', 'Canada', 'UK'], interests: ['Fashion', 'Lifestyle', 'Travel'] },
  maxCollaborators: 10,
  currentCollaborators: 6,
  totalBids: 24,
  pendingBids: 8,
  createdAt: new Date('2024-04-01'),
  updatedAt: new Date('2024-05-10'),
};

const mockCompletedCampaign = {
  id: 'completed-1',
  title: 'Spring Brand Awareness Drive',
  description: 'A multi-creator spring campaign targeting fashion-forward millennials across Instagram and TikTok.',
  objectives: ['Brand awareness', 'Reach expansion', 'User-generated content'],
  status: 'COMPLETED' as const,
  budget: { min: 30000, max: 40000, currency: 'INR', spent: 38200 },
  timeline: { startDate: new Date('2024-03-01'), endDate: new Date('2024-04-30') },
  platforms: ['INSTAGRAM', 'TIKTOK'],
  contentTypes: ['REEL', 'POST', 'STORY'],
  deliverables: [
    { type: 'Instagram Reel', count: 8, price: 1500 },
    { type: 'Instagram Story', count: 12, price: 400 },
    { type: 'TikTok Video', count: 6, price: 1200 },
  ],
  requirements: [],
  targetAudience: { ageRange: '18-35', gender: 'Female', locations: ['India', 'UAE', 'UK'], interests: ['Fashion', 'Beauty', 'Lifestyle'] },
  maxCollaborators: 8,
  currentCollaborators: 8,
  totalBids: 31,
  pendingBids: 0,
  createdAt: new Date('2024-02-15'),
  updatedAt: new Date('2024-05-01'),
  // Completed-specific analytics
  analytics: {
    totalReach: 4250000,
    totalImpressions: 7800000,
    totalEngagements: 312000,
    avgEngagementRate: 7.3,
    totalContentPieces: 26,
    totalLikes: 241000,
    totalComments: 38400,
    totalShares: 32600,
    totalViews: 5600000,
    sentimentScore: 92,
    roi: 4.2,
    costPerReach: 0.009,
    costPerEngagement: 0.12,
    // Peer benchmark comparison (anonymous)
    benchmarks: {
      avgEngagementRate: { campaign: 7.3, peer: 5.1 },
      roi: { campaign: 4.2, peer: 2.8 },
      costPerReach: { campaign: 0.009, peer: 0.013 },
    },
  },
  postMortem: {
    summary: 'The Spring Brand Awareness Drive exceeded expectations across all key metrics. Reach surpassed the target by 41%, and the engagement rate was 43% above the peer benchmark. The decision to front-load TikTok creators proved correct — TikTok-originated content drove 62% of total reach despite representing only 35% of budget.',
    whatWorked: [
      'Micro-influencers (50K–200K) delivered 2.4x better engagement than mega-influencers',
      'Morning posting window (8–10 AM IST) outperformed evening by 31%',
      'Reel format drove 3.1x more reach vs static posts',
      'UGC reposts amplified reach by an additional 18%',
    ],
    whatUnderperformed: [
      'YouTube integration added marginal reach vs its cost share',
      'Story-only deliverables showed low save rate — audiences prefer Reels',
    ],
    repeatCreators: ['Sarah Johnson', 'Priya Sharma', 'Alex Rivera'],
    recommendations: [
      'Increase micro-influencer budget to 70% of total in next campaign',
      'Drop YouTube from the platform mix — redirect budget to TikTok',
      'Add UGC repost clause to all creator contracts',
      'Front-load campaign timeline — most engagement happens in first 5 days',
    ],
  },
};

MOCK_CAMPAIGNS['active-1'] = mockActiveCampaign;
MOCK_CAMPAIGNS['completed-1'] = mockCompletedCampaign;
// Fallback for any `:id`
const getMockCampaign = (id?: string) => {
  if (id && MOCK_CAMPAIGNS[id]) return MOCK_CAMPAIGNS[id];
  // return active campaign for any unknown id in active campaigns list
  return mockActiveCampaign;
};

type BidStatus = 'PENDING' | 'SHORTLISTED' | 'ACCEPTED' | 'REJECTED' | 'COUNTERED';

type CampaignBid = {
  id: string;
  creator: {
    id: string;
    name: string;
    username: string;
    avatar: string;
    verified: boolean;
    // Optional (vs. mock's hard-required fields): real `Deal` rows (src/lib/api.ts) don't
    // carry creator engagement/rating/platform stats — only the mock fixtures below do.
    // JSX below conditionally renders each of these rather than defaulting to a fake 0/[]
    // (TECH-STACK.md rule 7 — never fabricate; an absent stat is an honest "unknown", not 0).
    followers?: number;
    engagementRate?: number;
    rating?: number;
    completedCampaigns?: number;
    platforms?: string[];
  };
  amount: number;
  timeline?: string;
  status: BidStatus;
  submittedAt: Date;
  matchScore?: number;
  message?: string;
  deliverables: { type: string; count: number }[];
};

/** View-model the rest of this page's JSX was written against (mock fixture shape). In live
 * mode it's assembled from the real `Campaign` (src/lib/types.ts) plus rollups derived from
 * real `Deal[]` — never from a fabricated field. Fields with no backend equivalent at all
 * (per-campaign deliverable catalog w/ pricing) are left empty rather than invented. */
interface DetailCampaignView {
  id: string;
  title: string;
  description: string;
  objectives: string[];
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
  // Meera-created drafts have no budget until a later wizard step — absent,
  // not fabricated as zeros (TECH-STACK.md rule 7).
  budget?: { min: number; max: number; currency: string; spent: number };
  timeline: { startDate: Date; endDate: Date };
  platforms: string[];
  contentTypes: string[];
  deliverables: { type: string; count: number; price: number }[];
  requirements: string[];
  targetAudience: { ageRange: string; gender: string; locations: string[]; interests: string[] };
  maxCollaborators: number;
  currentCollaborators: number;
  totalBids: number;
  pendingBids: number;
  createdAt: Date;
  updatedAt: Date;
}

function dealValueSum(deals: Deal[]): number {
  return deals.reduce((sum, d) => sum + (d.dealValue || 0), 0);
}

function buildLiveCampaignView(campaign: ApiCampaign, deals: Deal[]): DetailCampaignView {
  const bidStage = deals.filter((d) => BID_STAGE_STATUSES.has(d.status));
  const activeStage = deals.filter((d) => ACTIVE_STAGE_STATUSES.has(d.status));
  const doneStage = deals.filter((d) => DONE_STAGE_STATUSES.has(d.status));
  const engaged = [...activeStage, ...doneStage];
  const ageRange = campaign.targetAudience?.ageRange
    ? `${campaign.targetAudience.ageRange.min}-${campaign.targetAudience.ageRange.max}`
    : 'Not specified';
  return {
    id: campaign.id,
    title: campaign.title,
    description: campaign.description || '',
    objectives: campaign.objectives || [],
    status: campaign.status,
    // Meera-created drafts legitimately have no `budget` yet (set later in the
    // wizard) — pass that absence through rather than defaulting to zeros.
    budget: campaign.budget
      ? {
          min: campaign.budget.min,
          max: campaign.budget.max,
          currency: campaign.budget.currency,
          // No campaign-level "spend" field on the real Campaign DTO — derived from real deal
          // values for deals that reached agreement, not fabricated.
          spent: dealValueSum(engaged),
        }
      : undefined,
    timeline: campaign.timeline,
    platforms: campaign.platforms,
    contentTypes: campaign.contentTypes,
    // Real Campaign DTO has no campaign-level deliverable-type/pricing catalog — left empty
    // (honest gap) rather than invented (TECH-STACK.md rule 7).
    deliverables: [],
    requirements: campaign.requirements || [],
    targetAudience: {
      ageRange,
      gender: campaign.targetAudience?.genders?.join(', ') || 'All',
      locations: campaign.targetAudience?.locations || [],
      interests: campaign.targetAudience?.interests || [],
    },
    maxCollaborators: campaign.maxCollaborators || 0,
    currentCollaborators: engaged.length,
    totalBids: deals.length,
    pendingBids: bidStage.length,
    createdAt: campaign.createdAt,
    updatedAt: campaign.updatedAt,
  };
}

/** Coarse status bucket for the Bids tab's existing 5-value badge vocabulary — a display
 * simplification of the real 4-value negotiation stage, not fabricated underlying data. */
function dealToBidStatus(status: string): BidStatus {
  if (status === 'SHORTLISTED') return 'SHORTLISTED';
  if (status === 'IN_NEGOTIATION') return 'COUNTERED';
  return 'PENDING'; // INVITED, APPLIED
}

function dealToBidView(deal: Deal): CampaignBid {
  return {
    id: deal.id,
    creator: {
      id: deal.counterpartyId,
      name: deal.counterpartyName,
      username: deal.counterpartyHandle || deal.counterpartyName,
      avatar: deal.counterpartyAvatar || '',
      verified: false,
      // followers/engagementRate/rating/completedCampaigns/platforms intentionally omitted —
      // `Deal` carries no creator profile stats; JSX renders each conditionally.
    },
    amount: deal.dealValue,
    status: dealToBidStatus(deal.status),
    submittedAt: deal.lastMessageAt ? new Date(deal.lastMessageAt) : new Date(),
    message: deal.lastMessage,
    deliverables: deal.deliverablesTotal > 0 ? [{ type: 'Deliverables', count: deal.deliverablesTotal }] : [],
  };
}

/** Minimal shape the Active Collaborators tab + timeline sheet actually read (creator id/name
 * for the sheet header, status/deliverables/lastActivity for the card). Both the mock fixture
 * objects (superset) and `dealToActiveCollaboratorView` below satisfy this structurally. */
interface ActiveCollaboratorView {
  id: string;
  status: string;
  lastActivity: string;
  deliverables: { completed: number; total: number };
  creator: { id: string; name: string; username: string; avatar: string };
  /**
   * Real contract linkage, straight off `DealResponse` (DealService.java:1342-1343 populates
   * both from the deal's latest Contract). The data was already on every row this page fetched;
   * it just never reached the UI, so a brand had no route from a campaign to its contracts.
   * Optional because the mock fixtures below satisfy this interface too and have no contract.
   */
  contractId?: string;
  contractStatus?: ContractStatus;
  /** Unbucketed collaboration status — the contract gate keys off TERMS_AGREED exactly. */
  rawStatus?: string;
  /** Pre-fills the single default milestone at the full deal value. */
  dealValue?: number;
}

/** Coarse status bucket for the existing 6-value `collabStatusConfig` vocabulary. */
function dealToCollabStatus(status: string): string {
  if (status === 'REVIEW_PENDING') return 'DELIVERABLE_SUBMITTED';
  if (status === 'REVISION_REQUESTED') return 'REVISION_REQUESTED';
  return 'IN_PROGRESS'; // TERMS_AGREED, CONTRACT_PENDING, CONTRACTED, IN_PROGRESS
}

export function dealToActiveCollaboratorView(deal: Deal): ActiveCollaboratorView {
  return {
    id: deal.id,
    status: dealToCollabStatus(deal.status),
    lastActivity: deal.lastMessageAt
      ? new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(new Date(deal.lastMessageAt))
      : 'No recent activity',
    deliverables: { completed: deal.deliverablesDone, total: deal.deliverablesTotal },
    creator: {
      id: deal.counterpartyId,
      name: deal.counterpartyName,
      username: deal.counterpartyHandle || deal.counterpartyName,
      avatar: deal.counterpartyAvatar || '',
    },
    contractId: deal.contractId,
    contractStatus: deal.contractStatus,
    rawStatus: deal.status,
    dealValue: deal.dealValue,
  };
}

/** Completed-campaign creator row — only fields a real `Deal` actually carries (amount,
 * deliverables). No per-creator reach/likes/comments/rating/review exist on the real backend
 * (only campaign-aggregate `CampaignAnalyticsResponse`), so those are omitted, not invented. */
interface CompletedCollaboratorView {
  id: string;
  amount: number;
  deliverables: { completed: number; total: number };
  creator: { id: string; name: string; username: string; avatar: string; verified: boolean };
}

function dealToCompletedCollaboratorView(deal: Deal): CompletedCollaboratorView {
  return {
    id: deal.id,
    amount: deal.dealValue,
    deliverables: { completed: deal.deliverablesDone, total: deal.deliverablesTotal },
    creator: {
      id: deal.counterpartyId,
      name: deal.counterpartyName,
      username: deal.counterpartyHandle || deal.counterpartyName,
      avatar: deal.counterpartyAvatar || '',
      verified: false,
    },
  };
}

const mockBids: CampaignBid[] = [
  {
    id: 'bid-1',
    creator: { id: 'cr-1', name: 'Sarah Johnson', username: '@sarahjstyle', avatar: 'https://i.pravatar.cc/150?u=sarah', verified: true, followers: 245000, engagementRate: 4.8, rating: 4.9, completedCampaigns: 32, platforms: ['INSTAGRAM', 'TIKTOK'] },
    amount: 2800, timeline: '2 weeks', status: 'PENDING' as const, submittedAt: new Date('2024-05-08'), matchScore: 95,
    message: "I'd love to work on this campaign! My audience is 70% women aged 18-34 into fashion. I can create authentic lifestyle content that showcases your summer collection beautifully.",
    deliverables: [{ type: 'Instagram Reel', count: 2 }, { type: 'Instagram Story', count: 4 }, { type: 'TikTok Video', count: 1 }],
  },
  {
    id: 'bid-2',
    creator: { id: 'cr-2', name: 'Mike Chen', username: '@mikefashion', avatar: 'https://i.pravatar.cc/150?u=mike', verified: true, followers: 180000, engagementRate: 5.2, rating: 4.7, completedCampaigns: 28, platforms: ['INSTAGRAM', 'YOUTUBE'] },
    amount: 3200, timeline: '10 days', status: 'PENDING' as const, submittedAt: new Date('2024-05-09'), matchScore: 88,
    message: "This campaign aligns perfectly with my content style. My previous brand collaborations have achieved 3x average engagement.",
    deliverables: [{ type: 'Instagram Reel', count: 3 }, { type: 'YouTube Short', count: 1 }],
  },
  {
    id: 'bid-3',
    creator: { id: 'cr-3', name: 'Emma Wilson', username: '@emmawilson', avatar: 'https://i.pravatar.cc/150?u=emma', verified: false, followers: 95000, engagementRate: 6.1, rating: 4.8, completedCampaigns: 15, platforms: ['INSTAGRAM', 'TIKTOK'] },
    amount: 1800, timeline: '1 week', status: 'SHORTLISTED' as const, submittedAt: new Date('2024-05-07'), matchScore: 82,
    message: "I'm a micro-influencer with a highly engaged audience. My followers trust my recommendations — I have a 15% link click-through rate on sponsored content.",
    deliverables: [{ type: 'Instagram Reel', count: 2 }, { type: 'Instagram Story', count: 3 }],
  },
  {
    id: 'bid-4',
    creator: { id: 'cr-4', name: 'Alex Rivera', username: '@alexstyle', avatar: 'https://i.pravatar.cc/150?u=alex', verified: true, followers: 320000, engagementRate: 3.9, rating: 4.6, completedCampaigns: 45, platforms: ['INSTAGRAM', 'TIKTOK', 'YOUTUBE'] },
    amount: 4500, timeline: '2 weeks', status: 'PENDING' as const, submittedAt: new Date('2024-05-10'), matchScore: 91,
    message: "With 320K followers and 45+ brand campaigns, I can deliver premium content that drives results. My audience demographics match your target perfectly.",
    deliverables: [{ type: 'Instagram Reel', count: 3 }, { type: 'Instagram Story', count: 5 }, { type: 'TikTok Video', count: 2 }],
  },
  {
    id: 'bid-5',
    creator: { id: 'cr-5', name: 'Jessica Lee', username: '@jessicalee', avatar: 'https://i.pravatar.cc/150?u=jessica', verified: true, followers: 150000, engagementRate: 5.5, rating: 5.0, completedCampaigns: 22, platforms: ['INSTAGRAM'] },
    amount: 2200, timeline: '12 days', status: 'ACCEPTED' as const, submittedAt: new Date('2024-05-06'), matchScore: 93,
    message: "Fashion is my passion! I've worked with similar brands and consistently delivered above-average engagement.",
    deliverables: [{ type: 'Instagram Reel', count: 2 }, { type: 'Instagram Post', count: 2 }, { type: 'Instagram Story', count: 4 }],
  },
];

const mockCollaborators = [
  {
    id: 'collab-1', amount: 2200, startedAt: new Date('2024-05-08'),
    status: 'IN_PROGRESS' as const, deliverables: { completed: 5, total: 8, approved: 3, pending: 2 },
    creator: mockBids[4].creator,
    lastActivity: '2 hours ago',
    onTime: true,
  },
  {
    id: 'collab-2', amount: 2800, startedAt: new Date('2024-05-09'),
    status: 'DELIVERABLE_SUBMITTED' as const, deliverables: { completed: 7, total: 7, approved: 6, pending: 1 },
    creator: mockBids[0].creator,
    lastActivity: '30 min ago',
    onTime: true,
  },
];

const mockCompletedCollaborators = [
  {
    id: 'cc-1', amount: 4800, status: 'SETTLED' as const,
    creator: { id: 'cr-6', name: 'Sarah Johnson', username: '@sarahjstyle', avatar: 'https://i.pravatar.cc/150?u=sarah', verified: true, followers: 245000, engagementRate: 4.8, rating: 4.9, completedCampaigns: 32, platforms: ['INSTAGRAM', 'TIKTOK'] },
    metrics: { reach: 680000, impressions: 1200000, engagements: 52400, likes: 41200, comments: 6800, shares: 4400 },
    deliverables: { completed: 7, total: 7 }, rating: 5, review: 'Outstanding content quality and on-time delivery.',
    verdict: 'REPEAT' as const,
  },
  {
    id: 'cc-2', amount: 5200, status: 'SETTLED' as const,
    creator: { id: 'cr-7', name: 'Priya Sharma', username: '@priyasharma', avatar: 'https://i.pravatar.cc/150?u=priya', verified: true, followers: 310000, engagementRate: 6.2, rating: 4.9, completedCampaigns: 41, platforms: ['INSTAGRAM', 'TIKTOK'] },
    metrics: { reach: 920000, impressions: 1700000, engagements: 71300, likes: 56000, comments: 9100, shares: 6200 },
    deliverables: { completed: 8, total: 8 }, rating: 5, review: 'Best ROI in the campaign. Audience engagement was extraordinary.',
    verdict: 'REPEAT' as const,
  },
  {
    id: 'cc-3', amount: 2800, status: 'SETTLED' as const,
    creator: { id: 'cr-8', name: 'James Park', username: '@jamespark', avatar: 'https://i.pravatar.cc/150?u=james', verified: false, followers: 88000, engagementRate: 5.9, rating: 4.3, completedCampaigns: 11, platforms: ['INSTAGRAM'] },
    metrics: { reach: 310000, impressions: 490000, engagements: 18300, likes: 14200, comments: 2600, shares: 1500 },
    deliverables: { completed: 4, total: 5 }, rating: 3, review: 'Good content but missed one deliverable deadline.',
    verdict: 'NEUTRAL' as const,
  },
];

// ─── Helpers ─────────────────────────────────────────────────────────────────
const formatNumber = (num: number | undefined | null): string => {
  if (num == null) return '0';
  if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`;
  if (num >= 1000) return `${(num / 1000).toFixed(1)}K`;
  return num.toString();
};

const formatCurrency = (amount: number, currency = 'INR'): string =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency, minimumFractionDigits: 0 }).format(amount);

const formatDate = (date: Date): string =>
  new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' }).format(date);

const bidStatusConfig: Record<string, { label: string; color: string }> = {
  PENDING:     { label: 'Pending Review',  color: 'border bg-stage-outreach text-stage-outreach-fg border-stage-outreach-border' },
  SHORTLISTED: { label: 'Shortlisted',     color: 'border bg-stage-outreach text-stage-outreach-fg border-stage-outreach-border' },
  ACCEPTED:    { label: 'Accepted',        color: 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border' },
  REJECTED:    { label: 'Rejected',        color: 'border bg-stage-disputed text-stage-disputed-fg border-stage-disputed-border' },
  COUNTERED:   { label: 'Countered',       color: 'border bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border' },
};

const collabStatusConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  IN_PROGRESS:            { label: 'In Progress',         color: 'text-stage-progress-fg',   icon: <Activity className="h-3.5 w-3.5" /> },
  DELIVERABLE_SUBMITTED:  { label: 'Awaiting Review',     color: 'text-stage-review-fg',     icon: <Clock className="h-3.5 w-3.5" /> },
  REVISION_REQUESTED:     { label: 'Revision Requested',  color: 'text-stage-review-fg',     icon: <RefreshCcw className="h-3.5 w-3.5" /> },
  APPROVED:               { label: 'Approved',            color: 'text-stage-approved-fg',   icon: <CheckCircle2 className="h-3.5 w-3.5" /> },
  SETTLING:               { label: 'Settling',            color: 'text-stage-contracted-fg', icon: <Zap className="h-3.5 w-3.5" /> },
  SETTLED:                { label: 'Settled',             color: 'text-stage-approved-fg',   icon: <CheckCircle2 className="h-3.5 w-3.5" /> },
  DISPUTED:               { label: 'Disputed',            color: 'text-stage-disputed-fg',   icon: <AlertCircle className="h-3.5 w-3.5" /> },
};

/**
 * Button label for a deal's real `ContractStatus`. Says what the brand can do next rather than
 * echoing the enum; an absent status still links out ("View contract") because a `contractId`
 * with no status is a real contract whose status simply wasn't resolved — never "no contract".
 */
export const contractStatusLabel = (status?: ContractStatus): string => {
  switch (status) {
    case 'DRAFT': return 'Contract: draft';
    case 'PENDING_SIGNATURES': return 'Sign contract';
    case 'ACTIVE': return 'Contract active';
    case 'COMPLETED': return 'Contract complete';
    // F-0252: was 'TERMINATED'/'DISPUTED' — neither is a real backend ContractStatus (the
    // backend enum has no such members); CANCELLED is the real terminal state and previously
    // fell through to the uninformative default below.
    // F-0321: contracts-and-deliverables.tsx's status badge called this same backend value
    // "Expired" — a contract a party cancelled is not one whose expiration date passed. That
    // file's mapApiContractStatus/statusConfig now say "Cancelled" too; see
    // src/pages/__tests__/contract-status-label-agreement.test.ts for the cross-surface check.
    case 'CANCELLED': return 'Contract cancelled';
    default: return 'View contract';
  }
};

const PlatformIcon = ({ platform, size = 'h-4 w-4' }: { platform: string; size?: string }) => {
  switch (platform) {
    case 'INSTAGRAM': return <Instagram className={cn(size, 'text-pink-500')} />;
    case 'YOUTUBE':   return <Youtube className={cn(size, 'text-red-500')} />;
    case 'TIKTOK':    return (
      <svg className={size} viewBox="0 0 24 24" fill="currentColor">
        <path d="M19.59 6.69a4.83 4.83 0 0 1-3.77-4.25V2h-3.45v13.67a2.89 2.89 0 0 1-5.2 1.74 2.89 2.89 0 0 1 2.31-4.64c.3 0 .59.04.88.13V9.4a6.84 6.84 0 0 0-1-.05A6.33 6.33 0 0 0 5 20.1a6.34 6.34 0 0 0 10.86-4.43v-7a8.16 8.16 0 0 0 4.77 1.52v-3.4a4.85 4.85 0 0 1-1-.1z" />
      </svg>
    );
    case 'TWITTER': return <Twitter className={cn(size, 'text-sky-500')} />;
    default: return null;
  }
};

const MetricDelta = ({ value, better = 'higher' }: { value: number; better?: 'higher' | 'lower' }) => {
  const isGood = better === 'higher' ? value > 0 : value < 0;
  return (
    <span className={cn('flex items-center gap-0.5 text-xs font-medium', isGood ? 'text-green-400' : 'text-red-400')}>
      {isGood ? <TrendingUp className="h-3 w-3" /> : <TrendingDown className="h-3 w-3" />}
      {Math.abs(value)}%
    </span>
  );
};

// ─── Component ────────────────────────────────────────────────────────────────
export default function BrandCampaignDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { toast } = useToast();
  const liveApi = isApiLive();

  // ── Live-mode data — GET /campaigns/:id, GET /deals (filtered client-side to this
  // campaign; DealController has no campaignId query param, verified), and GET
  // /campaigns/:id/analytics. Gated exactly like CampaignsList's isApiLive() branch. ──
  const [liveCampaign, setLiveCampaign] = React.useState<ApiCampaign | null>(null);
  const [liveDeals, setLiveDeals] = React.useState<Deal[]>([]);
  const [liveAnalytics, setLiveAnalytics] = React.useState<CampaignAnalytics | null>(null);
  // F-0258 — the ONLY real fee schedule this app exposes to a brand: GET /brand/platform-fee
  // (BrandPlatformFeeController.java:29, wired at api.wallet.brandPlatformFee). It returns a
  // single feeBps/feePercent for the platform's cut at publish — never a Creator Pay/GST/
  // Contingency split, which no endpoint anywhere computes. `null` until it resolves (or on a
  // fetch failure, or in mock mode) — every render below must treat that as "rate unknown", not 0.
  const [platformFee, setPlatformFee] = React.useState<
    { feeBps: number; feePercent: number; source: string; copy: string } | null
  >(null);
  const [, setIsLoading] = React.useState(liveApi);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  const [notFound, setNotFound] = React.useState(false);
  const [reloadToken, setReloadToken] = React.useState(0);
  const [mutatingId, setMutatingId] = React.useState<string | null>(null);
  const [deleteOpen, setDeleteOpen] = React.useState(false);

  // BR-37 — GET /campaigns/:id/export?format=csv|pdf (ReportExportController). Pro-gated
  // (@RequiresPlan EXPORT); a non-Pro workspace gets a 402, surfaced below as an upgrade
  // prompt rather than a silent failure.
  const [exportingFormat, setExportingFormat] = React.useState<'csv' | 'pdf' | null>(null);
  const handleExportReport = async (format: 'csv' | 'pdf') => {
    if (!id) return;
    setExportingFormat(format);
    try {
      const blob = await api.reports.exportCampaign(id, format);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `campaign-${id}-report.${format}`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Failed to export campaign report', err);
      const message =
        err instanceof ApiError && err.status === 402
          ? 'Report export is a Pro feature. Upgrade your plan to export campaign reports.'
          : err instanceof ApiError
            ? err.message
            : 'Could not export the report. Try again.';
      toast({ title: 'Export failed', description: message, variant: 'destructive' });
    } finally {
      setExportingFormat(null);
    }
  };

  React.useEffect(() => {
    if (!liveApi || !id) return;
    let cancelled = false;
    setIsLoading(true);
    setLoadError(null);
    setNotFound(false);
    (async () => {
      try {
        const [campaignRow, dealRows, analyticsRow, feeRow] = await Promise.all([
          api.campaigns.get(id),
          api.deals.list('brand', 'all'),
          api.campaigns.analytics(id).catch(() => null),
          // F-0258 — real fee schedule; a failed/unavailable fetch leaves `platformFee` null
          // rather than blocking the rest of the page.
          api.wallet.brandPlatformFee().catch(() => null),
        ]);
        if (cancelled) return;
        if (!campaignRow) {
          setNotFound(true);
        } else {
          setLiveCampaign(campaignRow);
        }
        setLiveDeals(dealRows);
        setLiveAnalytics(analyticsRow);
        setPlatformFee(feeRow);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 404) {
          setNotFound(true);
        } else {
          setLoadError(e instanceof ApiError ? e.message : 'Could not load campaign. Try again.');
        }
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [liveApi, id, reloadToken]);

  const campaignDeals = React.useMemo(
    () => liveDeals.filter((d) => d.campaignId === id),
    [liveDeals, id],
  );
  const liveBidDeals = React.useMemo(
    () => campaignDeals.filter((d) => BID_STAGE_STATUSES.has(d.status)),
    [campaignDeals],
  );
  const liveActiveDeals = React.useMemo(
    () => campaignDeals.filter((d) => ACTIVE_STAGE_STATUSES.has(d.status)),
    [campaignDeals],
  );
  const liveDoneDeals = React.useMemo(
    () => campaignDeals.filter((d) => DONE_STAGE_STATUSES.has(d.status)),
    [campaignDeals],
  );

  // Resolved view-model — mock mode is always synchronously available; live mode is `null`
  // until the fetch above resolves, at which point the early-return below guarantees every
  // reference to `campaign` past that point sees a non-null value.
  const campaign: DetailCampaignView | null = !liveApi
    ? (getMockCampaign(id) as unknown as DetailCampaignView)
    : liveCampaign
      ? buildLiveCampaignView(liveCampaign, campaignDeals)
      : null;
  const isCompleted = campaign?.status === 'COMPLETED';

  const [activeTab, setActiveTab] = React.useState(isCompleted ? 'report' : 'bids');
  const [bidFilter, setBidFilter] = React.useState('all');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [selectedBid, setSelectedBid] = React.useState<CampaignBid | null>(null);
  const [isAcceptOpen, setIsAcceptOpen] = React.useState(false);
  const [isRejectOpen, setIsRejectOpen] = React.useState(false);
  const [isCounterOpen, setIsCounterOpen] = React.useState(false);
  const [counterAmount, setCounterAmount] = React.useState('');
  const [counterMessage, setCounterMessage] = React.useState('');
  const [rejectReason, setRejectReason] = React.useState('');
  const [bids, setBids] = React.useState(mockBids);
  const [timelineOpen, setTimelineOpen] = React.useState(false);
  const [selectedCollaboration, setSelectedCollaboration] = React.useState<ActiveCollaboratorView | null>(null);
  // Contract generation — same POST /contracts { collaborationId, milestones } the deal-room
  // chat already uses (brand-chat.tsx:906). Reuses that page's milestone editor rather than
  // growing a second one that could drift from the server's re-summed totalAmount.
  const [contractDraftFor, setContractDraftFor] = React.useState<ActiveCollaboratorView | null>(null);
  const [isGeneratingContract, setIsGeneratingContract] = React.useState(false);

  // Live mode: once a real campaign/deals fetch resolves, land on the right default tab and
  // replace the mock `bids` seed with the real bid-stage deals.
  React.useEffect(() => {
    if (!liveApi) return;
    setActiveTab(isCompleted ? 'report' : 'bids');
    setBids(liveBidDeals.map(dealToBidView));
  }, [liveApi, isCompleted, liveBidDeals]);

  const displayActiveCollaborators: ActiveCollaboratorView[] = liveApi
    ? liveActiveDeals.map(dealToActiveCollaboratorView)
    : mockCollaborators;
  const displayCompletedCollaborators: CompletedCollaboratorView[] = liveApi
    ? liveDoneDeals.map(dealToCompletedCollaboratorView)
    : mockCompletedCollaborators;

  const filteredBids = bids.filter((bid) => {
    const matchesFilter = bidFilter === 'all' || bid.status === bidFilter.toUpperCase();
    const matchesSearch =
      bid.creator.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      bid.creator.username.toLowerCase().includes(searchQuery.toLowerCase());
    return matchesFilter && matchesSearch;
  });

  const budgetProgress = campaign?.budget ? ((campaign.budget.spent || 0) / (campaign.budget.max || 1)) * 100 : 0;
  const daysRemaining = campaign
    ? Math.ceil((campaign.timeline.endDate.getTime() - Date.now()) / (1000 * 60 * 60 * 24))
    : 0;
  const timelineProgress = campaign
    ? (() => {
        const total = campaign.timeline.endDate.getTime() - campaign.timeline.startDate.getTime();
        const elapsed = Date.now() - campaign.timeline.startDate.getTime();
        return total > 0 ? Math.min(100, Math.max(0, (elapsed / total) * 100)) : 0;
      })()
    : 0;

  const handleAccept = async () => {
    if (!selectedBid) return;
    if (liveApi) {
      setMutatingId(selectedBid.id);
      try {
        await api.deals.accept(selectedBid.id, 'brand');
        setReloadToken((k) => k + 1);
      } catch (e) {
        toast({
          title: 'Could not accept bid',
          description: e instanceof ApiError ? e.message : 'Try again in a moment.',
          variant: 'destructive',
        });
      } finally {
        setMutatingId(null);
      }
    } else {
      setBids((prev) => prev.map((b) => b.id === selectedBid.id ? { ...b, status: 'ACCEPTED' as const } : b));
    }
    setIsAcceptOpen(false);
    setSelectedBid(null);
  };

  const handleReject = async () => {
    if (!selectedBid) return;
    if (liveApi) {
      setMutatingId(selectedBid.id);
      try {
        await api.deals.reject(selectedBid.id, rejectReason || undefined, 'brand');
        setReloadToken((k) => k + 1);
      } catch (e) {
        toast({
          title: 'Could not decline bid',
          description: e instanceof ApiError ? e.message : 'Try again in a moment.',
          variant: 'destructive',
        });
      } finally {
        setMutatingId(null);
      }
    } else {
      setBids((prev) => prev.map((b) => b.id === selectedBid.id ? { ...b, status: 'REJECTED' as const } : b));
    }
    setIsRejectOpen(false);
    setSelectedBid(null);
    setRejectReason('');
  };

  const handleCounter = async () => {
    if (!selectedBid) return;
    if (liveApi) {
      const amount = Number(counterAmount);
      if (!amount || Number.isNaN(amount)) return;
      setMutatingId(selectedBid.id);
      try {
        await api.deals.counter(
          selectedBid.id,
          { amount, message: counterMessage || undefined },
          'brand',
          // Fresh key per submit. Without one the server derives a key from dealId + amount
          // (DealService.counter:290), so a brand re-countering at the SAME figure collides with
          // its own earlier counter and IdempotencyService replays the first result — the second
          // counter silently no-ops with a 200 and never reaches the thread. Mirrors the
          // creator-side call in creator-chat.tsx.
          `${selectedBid.id}-counter-${Date.now()}`,
        );
        setReloadToken((k) => k + 1);
      } catch (e) {
        toast({
          title: 'Could not send counter offer',
          description: e instanceof ApiError ? e.message : 'Try again in a moment.',
          variant: 'destructive',
        });
      } finally {
        setMutatingId(null);
      }
    } else {
      setBids((prev) => prev.map((b) => b.id === selectedBid.id ? { ...b, status: 'COUNTERED' as const } : b));
    }
    setIsCounterOpen(false);
    setSelectedBid(null);
    setCounterAmount('');
    setCounterMessage('');
  };

  /**
   * POST /contracts. `collaborationId` is the deal id (DealResponse.id IS the Collaboration id —
   * brand-chat.tsx:907 passes it the same way). The server re-sums `totalAmount` from these
   * milestones, so the editor's running total is cosmetic and never trusted.
   *
   * Contract creation is OWNER/ADMIN/MANAGER-only server-side (api.ts:2386). This page does not
   * pre-gate on role — there is no client-side source of the caller's workspace role, and
   * inventing one would either hide a control the server would have allowed or show one it
   * refuses. It follows the campaign-delete precedent below instead: attempt, then surface the
   * real 403 in the operator's own words.
   */
  const handleGenerateContract = async (milestones: MilestoneDraft[]) => {
    const target = contractDraftFor;
    if (!target) return;
    setIsGeneratingContract(true);
    try {
      await api.contracts.generate({
        collaborationId: target.id,
        milestones: milestones.map((m, i) => ({
          sequenceNo: i + 1,
          description: m.description,
          amount: m.amount,
          dueDate: m.dueDate || undefined,
        })),
      });
      toast({
        title: 'Contract sent',
        description: `Review and sign it in Contracts, then ${target.creator.name} will be notified.`,
      });
      setContractDraftFor(null);
      // Re-fetch so the row's now-real contractId/contractStatus replace the generate button.
      setReloadToken((k) => k + 1);
    } catch (e) {
      const isPermission =
        e instanceof ApiError &&
        (e.status === 403 || e.message.includes('Insufficient workspace permissions'));
      toast({
        title: isPermission ? "You can't create this contract" : 'Could not create contract',
        description: isPermission
          ? 'Only the workspace Owner, Admin or Manager can generate a contract. Ask one of them to send it.'
          : e instanceof ApiError
            ? e.message
            : 'Try again in a moment.',
        variant: 'destructive',
      });
    } finally {
      setIsGeneratingContract(false);
    }
  };

  // ── Header action-menu handlers. These were dead controls (no onClick) — now wired to the
  // same api.campaigns.* the campaigns list uses, with loading + toast on failure. ──
  const handleDuplicateCampaign = async () => {
    if (!id) return;
    setMutatingId(id);
    try {
      await api.campaigns.duplicate(id);
      toast({ title: 'Campaign duplicated', description: 'A copy was created in your campaigns list.' });
      navigate('/brand/campaigns');
    } catch (e) {
      toast({
        title: 'Could not duplicate campaign',
        description: e instanceof ApiError ? e.message : 'Try again in a moment.',
        variant: 'destructive',
      });
    } finally {
      setMutatingId(null);
    }
  };

  const handleToggleCampaignStatus = async (next: 'ACTIVE' | 'PAUSED') => {
    if (!id) return;
    setMutatingId(id);
    try {
      await api.campaigns.update(id, { status: next });
      toast({ title: next === 'PAUSED' ? 'Campaign paused' : 'Campaign resumed' });
      if (liveApi) setReloadToken((k) => k + 1);
    } catch (e) {
      toast({
        title: 'Could not update campaign',
        description: e instanceof ApiError ? e.message : 'Try again in a moment.',
        variant: 'destructive',
      });
    } finally {
      setMutatingId(null);
    }
  };

  const handleDeleteCampaign = async () => {
    if (!id) return;
    setMutatingId(id);
    try {
      await api.campaigns.delete(id);
      toast({ title: 'Campaign deleted' });
      navigate('/brand/campaigns');
    } catch (e) {
      const isPermission =
        e instanceof ApiError &&
        (e.status === 403 || e.message.includes('Insufficient workspace permissions'));
      toast({
        title: isPermission ? "You can't delete this campaign" : 'Could not delete campaign',
        description: isPermission
          ? 'Only the workspace Owner or Admin can delete a campaign. Ask an Owner/Admin, or pause it instead.'
          : e instanceof ApiError
            ? e.message
            : 'Try again in a moment.',
        variant: 'destructive',
      });
    } finally {
      setMutatingId(null);
      setDeleteOpen(false);
    }
  };

  // ── Completed campaign analytics — real (`liveAnalytics`, creator-reported) in live mode,
  // mock fixture otherwise. No live equivalent exists for AI post-mortem/peer-benchmark
  // fields (verified: no such endpoint in influora-api) — those render an honest "not
  // available yet" state in live mode instead of fabricated copy (TECH-STACK.md rule 7).
  const mockCompleted = !liveApi && isCompleted ? (campaign as unknown as typeof mockCompletedCampaign) : null;
  // Kept separate from `mockCompleted.analytics` (rather than one `liveApi ? a : b` union)
  // so TypeScript narrows each to its own real shape at every call site below.
  const completedAnalytics: CampaignAnalytics | null = liveApi ? liveAnalytics : null;
  const postMortem = mockCompleted?.postMortem ?? null;

  // ── Loading / error / not-found states (live mode only — mock mode always resolves
  // synchronously above) — every reference to `campaign` below this point is non-null.
  if (!campaign) {
    if (loadError) {
      return (
        <div className="container mx-auto max-w-2xl px-4 py-10">
          <Alert variant="destructive">
            <AlertCircle className="h-4 w-4" />
            <AlertTitle>Could not load campaign</AlertTitle>
            <AlertDescription>
              <span>{loadError}</span>
              <Button size="sm" variant="outline" className="mt-2" onClick={() => setReloadToken((k) => k + 1)}>
                <RefreshCcw className="mr-2 h-3.5 w-3.5" />
                Retry
              </Button>
            </AlertDescription>
          </Alert>
        </div>
      );
    }
    if (notFound) {
      return (
        <div className="container mx-auto max-w-2xl px-4 py-10 text-center">
          <h1 className="text-lg font-semibold">Campaign not found</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            This campaign doesn&apos;t exist or you don&apos;t have access to it.
          </p>
          <Button variant="outline" className="mt-4" onClick={() => navigate('/brand/campaigns')}>
            <ArrowLeft className="mr-2 h-4 w-4" />
            Back to campaigns
          </Button>
        </div>
      );
    }
    return (
      <div className="container mx-auto px-4 py-6">
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="lg:col-span-2 space-y-6">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              {Array.from({ length: 4 }).map((_, i) => (
                <Card key={i}>
                  <CardContent className="p-4 space-y-2">
                    <Skeleton className="h-4 w-2/3" />
                    <Skeleton className="h-6 w-1/2" />
                  </CardContent>
                </Card>
              ))}
            </div>
            <Skeleton className="h-64 w-full" />
          </div>
          <Skeleton className="h-96 w-full" />
        </div>
      </div>
    );
  }

  return (
    <TooltipProvider>
      <div className="min-h-screen bg-background">

        {/* ── Sticky Header ───────────────────────────────────────────────── */}
        <div className="sticky top-0 z-20 border-b bg-card/80 backdrop-blur-md">
          <div className="container mx-auto px-4 py-3">
            <div className="flex items-center gap-3">
              <Button variant="ghost" size="icon" className="shrink-0" onClick={() => navigate('/brand/campaigns')}>
                <ArrowLeft className="h-5 w-5" />
              </Button>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <h1 className="text-lg font-bold truncate">{campaign.title}</h1>
                  {/* Campaign State Machine - Mini variant in header */}
                  <CampaignStateMachine 
                    currentState={isCompleted ? 'SETTLED' : 'IN_PRODUCTION'} 
                    variant="mini"
                  />
                </div>
                <p className="text-xs text-muted-foreground mt-0.5 hidden sm:block">
                  {formatDate(campaign.timeline.startDate)} — {formatDate(campaign.timeline.endDate)} · {campaign.totalBids} bids received
                </p>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                {/* D-7 (BrandF.md §13): /brand/campaigns/:id/tracking was registered
                    in App.tsx with no inbound link anywhere in the UI — reachable only
                    by typing the URL. This is the entry point. */}
                <Button
                  variant="outline"
                  size="sm"
                  className="hidden sm:flex"
                  onClick={() => navigate(`/brand/campaigns/${id}/tracking`)}
                >
                  <Activity className="h-4 w-4 mr-1.5" />
                  Tracking
                </Button>
                {!isCompleted && (
                  campaign.status === 'ACTIVE' ? (
                    <Button
                      variant="outline"
                      size="sm"
                      className="hidden sm:flex"
                      disabled
                      title="Pause the campaign before editing its details"
                    >
                      <Edit className="h-4 w-4 mr-1.5" />
                      Edit
                    </Button>
                  ) : (
                    <Button
                      variant="outline"
                      size="sm"
                      className="hidden sm:flex"
                      onClick={() => navigate(`/brand/campaigns/${id}/edit`)}
                    >
                      <Edit className="h-4 w-4 mr-1.5" />
                      Edit
                    </Button>
                  )
                )}
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="sm" className="gap-1.5" disabled={exportingFormat !== null}>
                      {exportingFormat ? (
                        <Loader2 className="h-4 w-4 animate-spin" />
                      ) : (
                        <Download className="h-4 w-4" />
                      )}
                      <span className="hidden sm:inline">Export</span>
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem
                      disabled={exportingFormat !== null}
                      onClick={() => void handleExportReport('csv')}
                    >
                      <Download className="mr-2 h-4 w-4" />Export as CSV
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      disabled={exportingFormat !== null}
                      onClick={() => void handleExportReport('pdf')}
                    >
                      <Download className="mr-2 h-4 w-4" />Export as PDF
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="icon">
                      <MoreHorizontal className="h-4 w-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem disabled={mutatingId === id} onClick={handleDuplicateCampaign}>
                      <Copy className="mr-2 h-4 w-4" />Duplicate Campaign
                    </DropdownMenuItem>
                    {!isCompleted && (
                      <>
                        <DropdownMenuSeparator />
                        {campaign.status === 'ACTIVE' ? (
                          <DropdownMenuItem
                            disabled={mutatingId === id}
                            onClick={() => handleToggleCampaignStatus('PAUSED')}
                          >
                            <Pause className="mr-2 h-4 w-4" />Pause Campaign
                          </DropdownMenuItem>
                        ) : (
                          <DropdownMenuItem
                            disabled={mutatingId === id}
                            onClick={() => handleToggleCampaignStatus('ACTIVE')}
                          >
                            <Play className="mr-2 h-4 w-4" />Resume Campaign
                          </DropdownMenuItem>
                        )}
                      </>
                    )}
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                      className="text-destructive-foreground"
                      disabled={mutatingId === id}
                      onClick={() => setDeleteOpen(true)}
                    >
                      <Trash2 className="mr-2 h-4 w-4" />Delete Campaign
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
                <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Delete campaign?</AlertDialogTitle>
                      <AlertDialogDescription>
                        This permanently deletes &ldquo;{campaign.title}&rdquo;. This can&rsquo;t be undone.
                      </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel disabled={mutatingId === id}>Cancel</AlertDialogCancel>
                      <AlertDialogAction
                        className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                        disabled={mutatingId === id}
                        onClick={(e) => {
                          e.preventDefault(); // keep dialog open until the request resolves
                          void handleDeleteCampaign();
                        }}
                      >
                        Delete
                      </AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>
            </div>
          </div>
        </div>

        <div className="container mx-auto px-4 py-6">
          <div className="grid gap-6 lg:grid-cols-3">

            {/* ── Left / Main ──────────────────────────────────────────────── */}
            <div className="lg:col-span-2 space-y-6">

              {/* Quick-stat strip */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                {[
                  {
                    icon: <IndianRupee className="h-4 w-4" />,
                    label: 'Budget Used',
                    value: campaign.budget ? formatCurrency(campaign.budget.spent || 0) : 'No budget set',
                    sub: campaign.budget ? `of ${formatCurrency(campaign.budget.max)}` : 'set in campaign wizard',
                    progress: budgetProgress,
                    progressColor: budgetProgress > 90 ? 'bg-red-500' : 'bg-primary',
                  },
                  {
                    icon: <Users className="h-4 w-4" />,
                    label: 'Collaborators',
                    value: `${campaign.currentCollaborators}`,
                    sub: `of ${campaign.maxCollaborators} slots`,
                    progress: campaign.maxCollaborators > 0 ? (campaign.currentCollaborators / campaign.maxCollaborators) * 100 : 0,
                    progressColor: 'bg-primary',
                  },
                  {
                    icon: <FileText className="h-4 w-4" />,
                    label: isCompleted ? 'Total Bids' : 'Pending Bids',
                    value: `${isCompleted ? campaign.totalBids : campaign.pendingBids}`,
                    sub: isCompleted ? 'received overall' : 'awaiting review',
                    progress: null,
                  },
                  isCompleted
                    ? {
                      icon: <BarChart2 className="h-4 w-4" />,
                      label: 'Total Reach',
                      value: formatNumber(liveApi ? completedAnalytics?.totalReach : mockCompleted?.analytics.totalReach),
                      sub: `${formatNumber(liveApi ? completedAnalytics?.totalImpressions : mockCompleted?.analytics.totalImpressions)} impressions`,
                      progress: null,
                    }
                    : {
                      icon: <Calendar className="h-4 w-4" />,
                      label: 'Days Left',
                      value: `${Math.max(0, daysRemaining)}`,
                      sub: daysRemaining < 0 ? 'campaign ended' : 'remaining',
                      progress: timelineProgress,
                      progressColor: daysRemaining < 7 ? 'bg-red-500' : 'bg-primary',
                    },
                ].map((stat, i) => (
                  <Card key={i} className="overflow-hidden">
                    <CardContent className="p-4">
                      <div className="flex items-center gap-2 text-muted-foreground text-xs mb-1">
                        {stat.icon}
                        <span>{stat.label}</span>
                      </div>
                      <p className="text-2xl font-bold leading-none">{stat.value}</p>
                      <p className="text-xs text-muted-foreground mt-1">{stat.sub}</p>
                      {stat.progress != null && (
                        <div className="mt-2 h-1.5 bg-muted rounded-full overflow-hidden">
                          <div className={cn('h-full rounded-full transition-all w-[var(--stat-progress-w)]', stat.progressColor)} ref={cssVars({ '--stat-progress-w': `${Math.min(100, stat.progress)}%` })} />
                        </div>
                      )}
                    </CardContent>
                  </Card>
                ))}
              </div>

              {/* ── Tabs ─────────────────────────────────────────────────── */}
              <Tabs value={activeTab} onValueChange={setActiveTab}>
                <div className="overflow-x-auto">
                  <TabsList className="inline-flex">
                    {isCompleted ? (
                      <>
                        <TabsTrigger value="report" className="gap-1.5"><Sparkles className="h-4 w-4" />Post-Mortem</TabsTrigger>
                        <TabsTrigger value="collaborators" className="gap-1.5"><Users className="h-4 w-4" />Creators <Badge variant="secondary" className="ml-1">{displayCompletedCollaborators.length}</Badge></TabsTrigger>
                        <TabsTrigger value="analytics" className="gap-1.5"><BarChart2 className="h-4 w-4" />Analytics</TabsTrigger>
                      </>
                    ) : (
                      <>
                        <TabsTrigger value="bids" className="gap-1.5">
                          <FileText className="h-4 w-4" />Bids
                          <Badge variant="secondary" className="ml-1">{bids.length}</Badge>
                        </TabsTrigger>
                        <TabsTrigger value="collaborators" className="gap-1.5">
                          <Users className="h-4 w-4" />Active
                          <Badge variant="secondary" className="ml-1">{displayActiveCollaborators.length}</Badge>
                        </TabsTrigger>
                        <TabsTrigger value="deliverables" className="gap-1.5"><CheckCircle2 className="h-4 w-4" />Deliverables</TabsTrigger>
                      </>
                    )}
                  </TabsList>
                </div>

                {/* ═══ ACTIVE: Bids Tab ═══════════════════════════════════════ */}
                <TabsContent value="bids" className="mt-4 space-y-4">
                  <div className="flex flex-col sm:flex-row gap-3">
                    <div className="relative flex-1">
                      <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                      <Input placeholder="Search creators..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} className="pl-9" />
                    </div>
                    <Select value={bidFilter} onValueChange={setBidFilter}>
                      <SelectTrigger className="w-full sm:w-44">
                        <Filter className="h-4 w-4 mr-2" />
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="all">All Bids ({bids.length})</SelectItem>
                        <SelectItem value="pending">Pending ({bids.filter((b) => b.status === 'PENDING').length})</SelectItem>
                        <SelectItem value="shortlisted">Shortlisted ({bids.filter((b) => b.status === 'SHORTLISTED').length})</SelectItem>
                        <SelectItem value="accepted">Accepted ({bids.filter((b) => b.status === 'ACCEPTED').length})</SelectItem>
                        <SelectItem value="rejected">Rejected ({bids.filter((b) => b.status === 'REJECTED').length})</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  {filteredBids.length === 0 ? (
                    <Card className="border-dashed">
                      <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                        <FileText className="h-12 w-12 text-muted-foreground mb-4" />
                        <h3 className="font-semibold">No bids found</h3>
                        <p className="text-sm text-muted-foreground mt-1">
                          {searchQuery ? 'Try adjusting your search' : 'No bids have been submitted yet'}
                        </p>
                      </CardContent>
                    </Card>
                  ) : (
                    <div className="space-y-3">
                      {filteredBids.map((bid) => (
                        <Card key={bid.id} className={cn('overflow-hidden transition-all hover:border-primary/40', bid.status === 'ACCEPTED' && 'border-stage-approved-border bg-stage-approved', bid.status === 'REJECTED' && 'opacity-60')}>
                          <CardContent className="p-4">
                            <div className="flex flex-col md:flex-row gap-4">
                              {/* Creator */}
                              <div className="flex items-start gap-3 flex-1 min-w-0">
                                <Link to={`/brand/creators/${bid.creator.id}`}>
                                  <Avatar className="h-12 w-12 border-2 border-background shrink-0">
                                    <AvatarImage src={bid.creator.avatar} />
                                    <AvatarFallback>{bid.creator.name[0]}</AvatarFallback>
                                  </Avatar>
                                </Link>
                                <div className="flex-1 min-w-0">
                                  <div className="flex items-center gap-2 flex-wrap">
                                    <Link to={`/brand/creators/${bid.creator.id}`} className="font-semibold hover:underline">
                                      {bid.creator.name}
                                    </Link>
                                    {bid.creator.verified && <BadgeCheck className="h-4 w-4 text-primary shrink-0" />}
                                    <span className={cn('text-xs px-2 py-0.5 rounded-full border font-medium', bidStatusConfig[bid.status]?.color ?? '')}>
                                      {bidStatusConfig[bid.status]?.label}
                                    </span>
                                    {bid.matchScore != null && bid.matchScore >= 90 && (
                                      <Tooltip>
                                        <TooltipTrigger asChild>
                                          <span className="text-xs px-2 py-0.5 rounded-full border bg-purple-500/20 text-purple-400 border-purple-500/30 flex items-center gap-1 cursor-default">
                                            <Sparkles className="h-3 w-3" />{bid.matchScore}% match
                                          </span>
                                        </TooltipTrigger>
                                        <TooltipContent>AI-ranked top match for this campaign</TooltipContent>
                                      </Tooltip>
                                    )}
                                  </div>
                                  <p className="text-xs text-muted-foreground mt-0.5">{bid.creator.username}</p>
                                  {(bid.creator.followers != null || bid.creator.engagementRate != null || bid.creator.rating != null || bid.creator.completedCampaigns != null) && (
                                    <div className="flex flex-wrap items-center gap-3 mt-2 text-xs text-muted-foreground">
                                      {bid.creator.followers != null && (
                                        <span className="flex items-center gap-1"><Users className="h-3 w-3" />{formatNumber(bid.creator.followers)}</span>
                                      )}
                                      {bid.creator.engagementRate != null && (
                                        <span className="flex items-center gap-1"><TrendingUp className="h-3 w-3" />{bid.creator.engagementRate}% eng.</span>
                                      )}
                                      {bid.creator.rating != null && (
                                        <span className="flex items-center gap-1"><Star className="h-3 w-3 text-yellow-500" />{bid.creator.rating}</span>
                                      )}
                                      {bid.creator.completedCampaigns != null && (
                                        <span className="flex items-center gap-1"><CheckCircle2 className="h-3 w-3" />{bid.creator.completedCampaigns} campaigns</span>
                                      )}
                                    </div>
                                  )}
                                  {bid.creator.platforms && bid.creator.platforms.length > 0 && (
                                    <div className="flex items-center gap-1.5 mt-2">
                                      {bid.creator.platforms.map((p) => <PlatformIcon key={p} platform={p} />)}
                                    </div>
                                  )}
                                </div>
                              </div>

                              {/* Bid amount + actions */}
                              <div className="flex flex-col gap-3 md:items-end md:min-w-36">
                                <div>
                                  <p className="text-2xl font-bold text-primary">{formatCurrency(bid.amount)}</p>
                                  {bid.timeline && <p className="text-xs text-muted-foreground">{bid.timeline} delivery</p>}
                                </div>
                                {bid.status !== 'REJECTED' && bid.status !== 'ACCEPTED' && (
                                  <div className="flex flex-wrap gap-2 md:justify-end">
                                    <Button size="sm" className="h-8 bg-stage-approved-fg hover:opacity-90 text-white"
                                      disabled={mutatingId === bid.id}
                                      onClick={() => { setSelectedBid(bid); setIsAcceptOpen(true); }}>
                                      {mutatingId === bid.id ? <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" /> : <ThumbsUp className="h-3.5 w-3.5 mr-1" />}Accept
                                    </Button>
                                    <Button size="sm" variant="outline" className="h-8"
                                      disabled={mutatingId === bid.id}
                                      onClick={() => { setSelectedBid(bid); setCounterAmount(String(bid.amount)); setIsCounterOpen(true); }}>
                                      Counter
                                    </Button>
                                    <Button size="sm" variant="ghost" className="h-8 text-destructive-foreground hover:text-destructive-foreground"
                                      disabled={mutatingId === bid.id}
                                      onClick={() => { setSelectedBid(bid); setIsRejectOpen(true); }}>
                                      <XCircle className="h-3.5 w-3.5" />
                                    </Button>
                                  </div>
                                )}
                                <div className="flex gap-2 md:justify-end">
                                  <Button size="sm" variant="ghost" className="h-8 gap-1.5 text-xs"
                                    onClick={() => navigate(`/brand/chat?creator=${bid.creator.id}`)}>
                                    <MessageSquare className="h-3.5 w-3.5" />Message
                                  </Button>
                                  <Button size="sm" variant="ghost" className="h-8 gap-1.5 text-xs" asChild>
                                    <Link to={`/brand/creators/${bid.creator.id}`}>
                                      <Eye className="h-3.5 w-3.5" />Profile
                                    </Link>
                                  </Button>
                                </div>
                              </div>
                            </div>

                            {/* Bid message */}
                            {bid.message && (
                              <div className="mt-3 p-3 bg-muted/40 rounded-lg border border-border/40">
                                <p className="text-sm text-muted-foreground leading-relaxed">&ldquo;{bid.message}&rdquo;</p>
                              </div>
                            )}

                            {/* Deliverables */}
                            <div className="flex flex-wrap gap-1.5 mt-3">
                              {bid.deliverables.map((d, di) => (
                                <span key={di} className="text-xs px-2 py-0.5 bg-muted rounded-full">
                                  {d.count}x {d.type}
                                </span>
                              ))}
                              <span className="text-xs px-2 py-0.5 text-muted-foreground">
                                Submitted {formatDate(bid.submittedAt)}
                              </span>
                            </div>
                          </CardContent>
                        </Card>
                      ))}
                    </div>
                  )}
                </TabsContent>

                {/* ═══ ACTIVE: Collaborators ═══════════════════════════════ */}
                <TabsContent value="collaborators" className="mt-4 space-y-3">
                  {liveApi && (isCompleted ? displayCompletedCollaborators : displayActiveCollaborators).length === 0 && (
                    <Card className="border-dashed">
                      <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                        <Users className="h-12 w-12 text-muted-foreground mb-3" />
                        <h3 className="font-semibold">No {isCompleted ? 'settled creators' : 'active collaborators'} yet</h3>
                        <p className="text-sm text-muted-foreground mt-1 max-w-sm">
                          {isCompleted
                            ? 'No deals on this campaign reached completion.'
                            : 'Accept a bid to start a collaboration.'}
                        </p>
                      </CardContent>
                    </Card>
                  )}
                  {liveApi && isCompleted && displayCompletedCollaborators.map((cc) => (
                    <Card key={cc.id} className="overflow-hidden">
                      <CardContent className="p-4">
                        <div className="flex flex-col sm:flex-row gap-4">
                          <div className="flex items-start gap-3 flex-1">
                            <Avatar className="h-10 w-10 shrink-0">
                              <AvatarImage src={cc.creator.avatar} />
                              <AvatarFallback>{cc.creator.name[0]}</AvatarFallback>
                            </Avatar>
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 flex-wrap">
                                <span className="font-semibold text-sm">{cc.creator.name}</span>
                                {cc.creator.verified && <BadgeCheck className="h-3.5 w-3.5 text-primary" />}
                              </div>
                              <p className="text-xs text-muted-foreground mt-0.5">{cc.creator.username}</p>
                              {/* No per-creator reach/likes/comments/rating/review exist on the
                                  real backend (only campaign-aggregate creator-reported analytics,
                                  see the Analytics tab) — omitted here rather than fabricated. */}
                            </div>
                          </div>
                          <div className="flex flex-col items-end gap-2 shrink-0">
                            <p className="text-lg font-bold text-green-400">{formatCurrency(cc.amount)}</p>
                            <span className="text-xs text-muted-foreground">Settled</span>
                            <span className="text-xs">{cc.deliverables.completed}/{cc.deliverables.total} deliverables</span>
                          </div>
                        </div>
                      </CardContent>
                    </Card>
                  ))}
                  {liveApi && !isCompleted && displayActiveCollaborators.map((ac) => (
                    <Card key={ac.id}>
                      <CardContent className="p-4">
                        <div className="flex items-start gap-3">
                          <Avatar className="h-10 w-10 shrink-0">
                            <AvatarImage src={ac.creator.avatar} />
                            <AvatarFallback>{ac.creator.name[0]}</AvatarFallback>
                          </Avatar>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center justify-between gap-2">
                              <span className="font-semibold text-sm">{ac.creator.name}</span>
                              <span className={cn('flex items-center gap-1.5 text-xs font-medium', collabStatusConfig[ac.status]?.color)}>
                                {collabStatusConfig[ac.status]?.icon}
                                {collabStatusConfig[ac.status]?.label}
                              </span>
                            </div>
                            <p className="text-xs text-muted-foreground mb-2">{ac.creator.username} · Last active {ac.lastActivity}</p>
                            <div className="flex items-center justify-between text-xs text-muted-foreground mb-1">
                              <span>Deliverables</span>
                              <span>{ac.deliverables.completed}/{ac.deliverables.total}</span>
                            </div>
                            <Progress value={ac.deliverables.total > 0 ? (ac.deliverables.completed / ac.deliverables.total) * 100 : 0} className="h-1.5" />
                          </div>
                          <div className="flex flex-col gap-2">
                            <Button
                              size="sm"
                              variant="default"
                              className="h-8 gap-1.5 text-xs w-full"
                              onClick={() => {
                                setSelectedCollaboration(ac);
                                setTimelineOpen(true);
                              }}
                            >
                              <MessageSquare className="h-3.5 w-3.5" />
                              Open Timeline
                            </Button>
                            {/* The campaign had no route to its own contracts — the timeline
                                can't show them (the backend writes no `contract` deal-message
                                kind, see collaboration-timeline.tsx:253), so this is the link. */}
                            {ac.contractId ? (
                              <Button
                                size="sm"
                                variant="outline"
                                className="h-8 gap-1.5 text-xs w-full"
                                onClick={() => navigate(`/brand/contracts?contract=${ac.contractId}`)}
                              >
                                <FileSignature className="h-3.5 w-3.5" />
                                {contractStatusLabel(ac.contractStatus)}
                              </Button>
                            ) : ac.rawStatus === 'TERMS_AGREED' ? (
                              <Button
                                size="sm"
                                variant="outline"
                                className="h-8 gap-1.5 text-xs w-full"
                                onClick={() => setContractDraftFor(ac)}
                              >
                                <FileSignature className="h-3.5 w-3.5" />
                                Generate contract
                              </Button>
                            ) : (
                              <span className="text-[11px] text-muted-foreground text-center">
                                No contract yet
                              </span>
                            )}
                          </div>
                        </div>
                      </CardContent>
                    </Card>
                  ))}
                  {!liveApi && (isCompleted ? mockCompletedCollaborators : mockCollaborators).map((c) => {
                    if (isCompleted) {
                      const cc = c as typeof mockCompletedCollaborators[0];
                      return (
                        <Card key={cc.id} className="overflow-hidden">
                          <CardContent className="p-4">
                            <div className="flex flex-col sm:flex-row gap-4">
                              <div className="flex items-start gap-3 flex-1">
                                <Avatar className="h-10 w-10 shrink-0">
                                  <AvatarImage src={cc.creator.avatar} />
                                  <AvatarFallback>{cc.creator.name[0]}</AvatarFallback>
                                </Avatar>
                                <div className="flex-1 min-w-0">
                                  <div className="flex items-center gap-2 flex-wrap">
                                    <span className="font-semibold text-sm">{cc.creator.name}</span>
                                    {cc.creator.verified && <BadgeCheck className="h-3.5 w-3.5 text-primary" />}
                                    <span className={cn('text-xs px-1.5 py-0.5 rounded-full border font-medium',
                                      cc.verdict === 'REPEAT' ? 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border' : 'bg-muted text-muted-foreground border-border'
                                    )}>
                                      {cc.verdict === 'REPEAT' ? 'Repeat Recommended' : 'Neutral'}
                                    </span>
                                  </div>
                                  <div className="grid grid-cols-3 gap-2 mt-3 text-xs">
                                    {[
                                      { icon: <Eye className="h-3 w-3" />, label: 'Reach', val: formatNumber(cc.metrics.reach) },
                                      { icon: <Heart className="h-3 w-3" />, label: 'Likes', val: formatNumber(cc.metrics.likes) },
                                      { icon: <MessageSquare className="h-3 w-3" />, label: 'Comments', val: formatNumber(cc.metrics.comments) },
                                    ].map((m) => (
                                      <div key={m.label} className="flex flex-col gap-0.5 p-2 bg-muted/50 rounded-md">
                                        <span className="flex items-center gap-1 text-muted-foreground">{m.icon}{m.label}</span>
                                        <span className="font-bold">{m.val}</span>
                                      </div>
                                    ))}
                                  </div>
                                  <div className="flex items-center gap-1 mt-2">
                                    {Array.from({ length: 5 }).map((_, si) => (
                                      <Star key={si} className={cn('h-3.5 w-3.5', si < cc.rating ? 'text-yellow-500 fill-yellow-500' : 'text-muted-foreground')} />
                                    ))}
                                    <span className="text-xs text-muted-foreground ml-1">{cc.review}</span>
                                  </div>
                                </div>
                              </div>
                              <div className="flex flex-col items-end gap-2 shrink-0">
                                <p className="text-lg font-bold text-green-400">{formatCurrency(cc.amount)}</p>
                                <span className="text-xs text-muted-foreground">Settled</span>
                                <span className="text-xs">{cc.deliverables.completed}/{cc.deliverables.total} deliverables</span>
                              </div>
                            </div>
                          </CardContent>
                        </Card>
                      );
                    }
                    const ac = c as typeof mockCollaborators[0];
                    return (
                      <Card key={ac.id}>
                        <CardContent className="p-4">
                          <div className="flex items-start gap-3">
                            <Avatar className="h-10 w-10 shrink-0">
                              <AvatarImage src={ac.creator.avatar} />
                              <AvatarFallback>{ac.creator.name[0]}</AvatarFallback>
                            </Avatar>
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center justify-between gap-2">
                                <span className="font-semibold text-sm">{ac.creator.name}</span>
                                <span className={cn('flex items-center gap-1.5 text-xs font-medium', collabStatusConfig[ac.status]?.color)}>
                                  {collabStatusConfig[ac.status]?.icon}
                                  {collabStatusConfig[ac.status]?.label}
                                </span>
                              </div>
                              <p className="text-xs text-muted-foreground mb-2">{ac.creator.username} · Last active {ac.lastActivity}</p>
                              <div className="flex items-center justify-between text-xs text-muted-foreground mb-1">
                                <span>Deliverables</span>
                                <span>{ac.deliverables.completed}/{ac.deliverables.total}</span>
                              </div>
                              <Progress value={(ac.deliverables.completed / ac.deliverables.total) * 100} className="h-1.5" />
                            </div>
                            <div className="flex flex-col gap-2">
                              {/* Timeline Button - NEW */}
                              <Button 
                                size="sm" 
                                variant="default"
                                className="h-8 gap-1.5 text-xs w-full"
                                onClick={() => {
                                  setSelectedCollaboration(ac);
                                  setTimelineOpen(true);
                                }}
                              >
                                <MessageSquare className="h-3.5 w-3.5" />
                                Open Timeline
                              </Button>
                            </div>
                          </div>
                        </CardContent>
                      </Card>
                    );
                  })}
                </TabsContent>

                {/* ═══ ACTIVE: Deliverables ════════════════════════════════ */}
                <TabsContent value="deliverables" className="mt-4 space-y-3">
                  {liveApi && completedAnalytics && completedAnalytics.deliverables.length > 0 ? (
                    <>
                      {/* verified-analytics-0804 — per-row provenance, driven by the real
                          DeliverableMetric.source (verified vs self-reported), not a static banner. */}
                      {completedAnalytics.deliverables.map((d) => (
                        <Card key={d.id}>
                          <CardContent className="p-4 flex items-center justify-between gap-4">
                            <div className="flex-1 space-y-2">
                              <MetricSourceBadge source={d.source} />
                              <div className="grid grid-cols-3 gap-4 text-xs">
                                <div>
                                  <p className="text-muted-foreground">Reach</p>
                                  <p className="font-bold">{formatNumber(d.reach)}</p>
                                </div>
                                <div>
                                  <p className="text-muted-foreground">Impressions</p>
                                  <p className="font-bold">{formatNumber(d.impressions)}</p>
                                </div>
                                <div>
                                  <p className="text-muted-foreground">Engagements</p>
                                  <p className="font-bold">{formatNumber(d.engagements)}</p>
                                </div>
                              </div>
                            </div>
                            {d.link && (
                              <Button size="sm" variant="ghost" asChild>
                                <a href={d.link} target="_blank" rel="noreferrer">
                                  <Eye className="h-3.5 w-3.5 mr-1" />View
                                </a>
                              </Button>
                            )}
                          </CardContent>
                        </Card>
                      ))}
                    </>
                  ) : (
                    <Card className="border-dashed">
                      <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                        <CheckCircle2 className="h-12 w-12 text-muted-foreground mb-3" />
                        <h3 className="font-semibold">No deliverables submitted yet</h3>
                        <p className="text-sm text-muted-foreground mt-1 max-w-sm">Deliverables will appear here once collaborators begin submitting content for review.</p>
                      </CardContent>
                    </Card>
                  )}
                </TabsContent>

                {/* ═══ COMPLETED: Post-Mortem ══════════════════════════════ */}
                <TabsContent value="report" className="mt-4 space-y-4">
                  {postMortem ? (
                    <>
                      {/* AI Summary card */}
                      <Card className="border-primary/30 bg-primary/5">
                        <CardContent className="p-4">
                          <div className="flex items-center gap-2 mb-3">
                            <div className="p-1.5 rounded-md bg-primary/20">
                              <Sparkles className="h-4 w-4 text-primary" />
                            </div>
                            <h3 className="font-semibold text-sm">AI Campaign Post-Mortem</h3>
                            <span className="text-xs text-muted-foreground ml-auto">Auto-generated within 24h of campaign end</span>
                          </div>
                          <p className="text-sm leading-relaxed text-muted-foreground">{postMortem.summary}</p>
                        </CardContent>
                      </Card>

                      {/* What worked / underperformed */}
                      <div className="grid md:grid-cols-2 gap-4">
                        <Card className="border-stage-approved-border bg-stage-approved">
                          <CardHeader className="pb-3 pt-4 px-4">
                            <CardTitle className="text-sm flex items-center gap-2 text-green-400">
                              <ThumbsUp className="h-4 w-4" />What Worked
                            </CardTitle>
                          </CardHeader>
                          <CardContent className="px-4 pb-4 space-y-2">
                            {postMortem.whatWorked.map((item, i) => (
                              <div key={i} className="flex gap-2 text-sm">
                                <CheckCircle2 className="h-4 w-4 text-green-500 shrink-0 mt-0.5" />
                                <span className="text-muted-foreground">{item}</span>
                              </div>
                            ))}
                          </CardContent>
                        </Card>
                        <Card className="border-red-500/20 bg-red-500/5">
                          <CardHeader className="pb-3 pt-4 px-4">
                            <CardTitle className="text-sm flex items-center gap-2 text-red-400">
                              <AlertCircle className="h-4 w-4" />What Underperformed
                            </CardTitle>
                          </CardHeader>
                          <CardContent className="px-4 pb-4 space-y-2">
                            {postMortem.whatUnderperformed.map((item, i) => (
                              <div key={i} className="flex gap-2 text-sm">
                                <XCircle className="h-4 w-4 text-red-400 shrink-0 mt-0.5" />
                                <span className="text-muted-foreground">{item}</span>
                              </div>
                            ))}
                          </CardContent>
                        </Card>
                      </div>

                      {/* Recommendations */}
                      <Card>
                        <CardHeader className="pb-3 pt-4 px-4">
                          <CardTitle className="text-sm flex items-center gap-2">
                            <Target className="h-4 w-4 text-primary" />Recommendations for Next Campaign
                          </CardTitle>
                        </CardHeader>
                        <CardContent className="px-4 pb-4 space-y-2">
                          {postMortem.recommendations.map((rec, i) => (
                            <div key={i} className="flex gap-2 text-sm">
                              <ChevronRight className="h-4 w-4 text-primary shrink-0 mt-0.5" />
                              <span className="text-muted-foreground">{rec}</span>
                            </div>
                          ))}
                        </CardContent>
                      </Card>

                      {/* Repeat creators */}
                      <Card>
                        <CardHeader className="pb-3 pt-4 px-4">
                          <CardTitle className="text-sm flex items-center gap-2">
                            <Award className="h-4 w-4 text-yellow-500" />Recommended to Repeat
                          </CardTitle>
                        </CardHeader>
                        <CardContent className="px-4 pb-4">
                          <div className="flex flex-wrap gap-2">
                            {postMortem.repeatCreators.map((name) => (
                              <span key={name} className="text-xs px-3 py-1.5 bg-yellow-500/10 text-yellow-400 border border-yellow-500/20 rounded-full font-medium">
                                {name}
                              </span>
                            ))}
                          </div>
                        </CardContent>
                      </Card>
                    </>
                  ) : (
                    <Card className="border-dashed">
                      <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                        <Sparkles className="h-12 w-12 text-muted-foreground mb-3" />
                        <h3 className="font-semibold">AI post-mortem not available yet</h3>
                        <p className="text-sm text-muted-foreground mt-1 max-w-sm">
                          Automated campaign post-mortems aren&apos;t generated by the backend yet. Check the
                          Analytics tab for real creator-reported performance numbers.
                        </p>
                      </CardContent>
                    </Card>
                  )}
                </TabsContent>

                {/* ═══ COMPLETED: Analytics ════════════════════════════════ */}
                <TabsContent value="analytics" className="mt-4 space-y-4">
                  {liveApi && (
                    completedAnalytics ? (
                      <>
                        {/* verified-analytics-0804 — aggregate provenance derived from the real
                            per-deliverable source, not a static "creator-reported" banner. */}
                        {(() => {
                          const dels = completedAnalytics.deliverables;
                          const verifiedCount = dels.filter(
                            (d) => d.source === 'PLATFORM_VERIFIED',
                          ).length;
                          const allVerified = dels.length > 0 && verifiedCount === dels.length;
                          const someVerified = verifiedCount > 0;
                          return (
                            <Alert>
                              <AlertCircle className="h-4 w-4" />
                              <AlertTitle>
                                {allVerified
                                  ? 'Verified by Instagram'
                                  : someVerified
                                    ? `${verifiedCount} of ${dels.length} deliverables verified by Instagram`
                                    : 'Creator-reported, not platform-verified'}
                              </AlertTitle>
                              <AlertDescription>
                                {allVerified
                                  ? 'These figures are pulled directly from Instagram via the Meta Graph API.'
                                  : someVerified
                                    ? 'Verified rows come straight from Instagram; the rest are creator self-reported. See the Deliverables tab for per-post provenance.'
                                    : 'These figures are self-declared by creators when they submit deliverables. Verified numbers appear here once creators connect Instagram and the post is read via the Meta Graph API.'}
                              </AlertDescription>
                            </Alert>
                          );
                        })()}
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                          {[
                            { label: 'Total Reach', value: formatNumber(completedAnalytics.totalReach), icon: <Eye className="h-4 w-4 text-blue-400" /> },
                            { label: 'Impressions', value: formatNumber(completedAnalytics.totalImpressions), icon: <BarChart2 className="h-4 w-4 text-purple-400" /> },
                            { label: 'Engagements', value: formatNumber(completedAnalytics.totalEngagements), icon: <Heart className="h-4 w-4 text-pink-400" /> },
                            {
                              label: 'Engagement Rate',
                              value: completedAnalytics.derivedEngagementRate != null ? `${completedAnalytics.derivedEngagementRate}%` : '—',
                              icon: <TrendingUp className="h-4 w-4 text-green-400" />,
                            },
                          ].map((m) => (
                            <Card key={m.label}>
                              <CardContent className="p-4">
                                <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">{m.icon}{m.label}</div>
                                <p className="text-2xl font-bold">{m.value}</p>
                              </CardContent>
                            </Card>
                          ))}
                        </div>
                        <Card>
                          <CardHeader className="pb-3 pt-4 px-4">
                            <CardTitle className="text-sm flex items-center gap-2">
                              <FileText className="h-4 w-4 text-primary" />Deliverables Reported
                            </CardTitle>
                          </CardHeader>
                          <CardContent className="px-4 pb-4">
                            <div className="flex items-center justify-between text-sm mb-2">
                              <span className="text-muted-foreground">{completedAnalytics.deliverablesReported} of {completedAnalytics.deliverablesTotal} reported</span>
                            </div>
                            <Progress
                              value={completedAnalytics.deliverablesTotal > 0
                                ? (completedAnalytics.deliverablesReported / completedAnalytics.deliverablesTotal) * 100
                                : 0}
                              className="h-1.5"
                            />
                          </CardContent>
                        </Card>
                      </>
                    ) : (
                      <Card className="border-dashed">
                        <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                          <BarChart2 className="h-12 w-12 text-muted-foreground mb-3" />
                          <h3 className="font-semibold">No analytics reported yet</h3>
                          <p className="text-sm text-muted-foreground mt-1 max-w-sm">
                            Creator-reported performance numbers will appear here once collaborators submit deliverables.
                          </p>
                        </CardContent>
                      </Card>
                    )
                  )}
                  {!liveApi && mockCompleted && (
                    <>
                      {/* Top metrics */}
                      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                        {[
                          { label: 'Total Reach', value: formatNumber(mockCompleted.analytics.totalReach), icon: <Eye className="h-4 w-4 text-blue-400" /> },
                          { label: 'Impressions', value: formatNumber(mockCompleted.analytics.totalImpressions), icon: <BarChart2 className="h-4 w-4 text-purple-400" /> },
                          { label: 'Total Likes', value: formatNumber(mockCompleted.analytics.totalLikes), icon: <Heart className="h-4 w-4 text-pink-400" /> },
                          { label: 'Total Comments', value: formatNumber(mockCompleted.analytics.totalComments), icon: <MessageSquare className="h-4 w-4 text-green-400" /> },
                        ].map((m) => (
                          <Card key={m.label}>
                            <CardContent className="p-4">
                              <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">{m.icon}{m.label}</div>
                              <p className="text-2xl font-bold">{m.value}</p>
                            </CardContent>
                          </Card>
                        ))}
                      </div>

                      {/* Peer benchmarks */}
                      <Card>
                        <CardHeader className="pb-3 pt-4 px-4">
                          <CardTitle className="text-sm flex items-center gap-2">
                            <TrendingUp className="h-4 w-4 text-primary" />vs. Peer Benchmark
                            <span className="text-xs font-normal text-muted-foreground ml-1">(anonymous, same vertical + budget tier)</span>
                          </CardTitle>
                        </CardHeader>
                        <CardContent className="px-4 pb-4 space-y-4">
                          {[
                            {
                              label: 'Avg. Engagement Rate',
                              campaign: mockCompleted.analytics.benchmarks.avgEngagementRate.campaign,
                              peer: mockCompleted.analytics.benchmarks.avgEngagementRate.peer,
                              suffix: '%',
                              better: 'higher' as const,
                            },
                            {
                              label: 'ROI',
                              campaign: mockCompleted.analytics.benchmarks.roi.campaign,
                              peer: mockCompleted.analytics.benchmarks.roi.peer,
                              suffix: 'x',
                              better: 'higher' as const,
                            },
                            {
                              label: 'Cost Per Reach',
                              campaign: mockCompleted.analytics.benchmarks.costPerReach.campaign,
                              peer: mockCompleted.analytics.benchmarks.costPerReach.peer,
                              suffix: '',
                              better: 'lower' as const,
                            },
                          ].map((b) => {
                            const delta = Math.round(((b.campaign - b.peer) / b.peer) * 100);
                            const isGood = b.better === 'higher' ? delta > 0 : delta < 0;
                            return (
                              <div key={b.label}>
                                <div className="flex items-center justify-between mb-2 text-sm">
                                  <span className="text-muted-foreground">{b.label}</span>
                                  <MetricDelta value={Math.abs(delta)} better={b.better} />
                                </div>
                                <div className="space-y-1.5">
                                  <div className="flex items-center gap-2">
                                    <span className="text-xs w-16 text-right font-medium">{b.campaign}{b.suffix}</span>
                                    <div className="flex-1 h-2 bg-muted rounded-full overflow-hidden">
                                      <div className={cn('h-full rounded-full w-[var(--peer-bar-w)]', isGood ? 'bg-primary' : 'bg-muted-foreground')}
                                        ref={cssVars({ '--peer-bar-w': `${Math.min(100, (b.campaign / Math.max(b.campaign, b.peer)) * 100)}%` })} />
                                    </div>
                                    <span className="text-xs text-primary font-medium w-12">You</span>
                                  </div>
                                  <div className="flex items-center gap-2">
                                    <span className="text-xs w-16 text-right text-muted-foreground">{b.peer}{b.suffix}</span>
                                    <div className="flex-1 h-2 bg-muted rounded-full overflow-hidden">
                                      <div className="h-full rounded-full bg-muted-foreground/50 w-[var(--peer-bar-w2)]"
                                        ref={cssVars({ '--peer-bar-w2': `${(b.peer / Math.max(b.campaign, b.peer)) * 100}%` })} />
                                    </div>
                                    <span className="text-xs text-muted-foreground w-12">Peers</span>
                                  </div>
                                </div>
                              </div>
                            );
                          })}
                        </CardContent>
                      </Card>

                      {/* Financial summary */}
                      <Card>
                        <CardHeader className="pb-3 pt-4 px-4">
                          <CardTitle className="text-sm flex items-center gap-2">
                            <DollarSign className="h-4 w-4 text-green-400" />Settlement Summary
                          </CardTitle>
                        </CardHeader>
                        <CardContent className="px-4 pb-4 space-y-2">
                          {/* F-0258 — this campaign's real per-creator payout / platform-fee /
                              GST split is never computed anywhere (no invoice-level rollup
                              endpoint exists yet); Total Campaign Spend is the only figure
                              actually known here, so that's the only money line shown. */}
                          {[
                            { label: 'Total Campaign Spend', value: formatCurrency(campaign.budget?.spent ?? 0), highlight: false },
                            { label: 'Estimated ROI', value: `${mockCompleted.analytics.roi}x`, highlight: true },
                          ].map((row) => (
                            <div key={row.label} className={cn('flex items-center justify-between text-sm py-1', row.highlight && 'border-t border-border mt-2 pt-3 font-bold text-green-400')}>
                              <span className={row.highlight ? '' : 'text-muted-foreground'}>{row.label}</span>
                              <span>{row.value}</span>
                            </div>
                          ))}
                        </CardContent>
                      </Card>
                    </>
                  )}
                </TabsContent>
              </Tabs>
            </div>

            {/* ── Right Sidebar ─────────────────────────────────────────────── */}
            <div className="space-y-4">
              {/* Brief */}
              <Card>
                <CardHeader className="pb-2 pt-4 px-4">
                  <CardTitle className="text-sm">Campaign Brief</CardTitle>
                </CardHeader>
                <CardContent className="px-4 pb-4 text-sm text-muted-foreground space-y-3">
                  <p className="leading-relaxed">{campaign.description}</p>
                  <Separator />
                  <div>
                    <p className="font-medium text-foreground text-xs mb-1.5">Platforms</p>
                    <div className="flex flex-wrap gap-1.5">
                      {campaign.platforms.map((p) => (
                        <span key={p} className="flex items-center gap-1 text-xs px-2 py-1 bg-muted rounded-md">
                          <PlatformIcon platform={p} size="h-3 w-3" />{p}
                        </span>
                      ))}
                    </div>
                  </div>
                  <div>
                    <p className="font-medium text-foreground text-xs mb-1.5">Objectives</p>
                    <div className="flex flex-col gap-1">
                      {campaign.objectives.map((o) => (
                        <div key={o} className="flex items-center gap-2 text-xs">
                          <Target className="h-3 w-3 text-primary shrink-0" />{o}
                        </div>
                      ))}
                    </div>
                  </div>
                  {campaign.deliverables.length > 0 && (
                    <div>
                      <p className="font-medium text-foreground text-xs mb-1.5">Deliverables</p>
                      <div className="space-y-1">
                        {campaign.deliverables.map((d, i) => (
                          <div key={i} className="flex items-center justify-between text-xs">
                            <span>{d.count}x {d.type}</span>
                            <span className="text-muted-foreground">{formatCurrency(d.price)} each</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  {campaign.requirements.length > 0 && (
                    <div>
                      <p className="font-medium text-foreground text-xs mb-1.5">Requirements</p>
                      <div className="space-y-1">
                        {campaign.requirements.map((r, i) => (
                          <div key={i} className="flex gap-1.5 text-xs">
                            <Lock className="h-3 w-3 text-muted-foreground shrink-0 mt-0.5" />
                            <span>{r}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  <div>
                    <p className="font-medium text-foreground text-xs mb-1.5">Target Audience</p>
                    <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
                      <span className="text-muted-foreground">Age:</span><span>{campaign.targetAudience.ageRange}</span>
                      <span className="text-muted-foreground">Gender:</span><span>{campaign.targetAudience.gender}</span>
                      <span className="text-muted-foreground">Locations:</span><span>{campaign.targetAudience.locations.join(', ')}</span>
                      <span className="text-muted-foreground">Interests:</span><span>{campaign.targetAudience.interests.join(', ')}</span>
                    </div>
                  </div>
                </CardContent>
              </Card>

              {/* Budget breakdown — F-0258. No endpoint anywhere computes a per-campaign
                  Creator Pay / GST / Contingency split, so nothing here invents one. The only
                  server-real number is the platform's take rate (GET /brand/platform-fee); when
                  it's loaded, it's shown as a clearly-labeled estimate against the max budget —
                  never under a Lock icon, since nothing is escrowed or apportioned at this
                  stage. */}
              <Card>
                <CardHeader className="pb-2 pt-4 px-4">
                  <CardTitle className="text-sm flex items-center gap-2">
                    <DollarSign className="h-3.5 w-3.5 text-primary" />Budget
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-4 pb-4 space-y-2 text-sm">
                  {campaign.budget ? (
                    <>
                      <div className="flex items-center justify-between text-sm font-bold">
                        <span>Total Budget</span>
                        <span className="text-primary">{formatCurrency(campaign.budget.max)}</span>
                      </div>
                      {platformFee ? (
                        <>
                          <div className="flex items-center justify-between text-xs">
                            <span className="text-muted-foreground">Platform Fee ({platformFee.feePercent}%, est.)</span>
                            <span className="font-medium">
                              {formatCurrency(campaign.budget.max * (platformFee.feePercent / 100))}
                            </span>
                          </div>
                          <p className="text-[11px] text-muted-foreground pt-1">
                            Estimate only, not a quote — computed against the max budget at
                            the current platform rate.
                          </p>
                          {platformFee.copy ? (
                            <p className="text-[11px] text-muted-foreground">{platformFee.copy}</p>
                          ) : null}
                        </>
                      ) : (
                        <p className="text-xs text-muted-foreground">
                          Fee breakdown isn&apos;t available yet.
                        </p>
                      )}
                    </>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      No budget set yet — add one from the campaign wizard.
                    </p>
                  )}
                </CardContent>
              </Card>
            </div>
          </div>
        </div>

        {/* ── Dialogs ─────────────────────────────────────────────────────── */}
        {/* Accept */}
        <Dialog open={isAcceptOpen} onOpenChange={setIsAcceptOpen}>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <CheckCircle2 className="h-5 w-5 text-green-500" />Accept Bid
              </DialogTitle>
              <DialogDescription>
                Accept {selectedBid?.creator.name}&apos;s proposal of {selectedBid && formatCurrency(selectedBid.amount)}.
                This will lock funds in escrow and move to the contract stage.
              </DialogDescription>
            </DialogHeader>
            <div className="py-3 space-y-2 text-sm">
              {selectedBid?.deliverables.map((d, i) => (
                <div key={i} className="flex items-center justify-between p-2 bg-muted rounded-md">
                  <span>{d.count}x {d.type}</span>
                </div>
              ))}
              <div className="flex items-center gap-2 mt-3 p-3 bg-primary/10 border border-primary/20 rounded-md text-xs text-primary">
                <Lock className="h-3.5 w-3.5 shrink-0" />
                {selectedBid && formatCurrency(selectedBid.amount)} will be locked in escrow until deliverables are approved.
              </div>
            </div>
            <DialogFooter className="gap-2">
              <Button variant="outline" onClick={() => setIsAcceptOpen(false)}>Cancel</Button>
              <Button
                className="bg-stage-approved-fg hover:opacity-90 text-white gap-2"
                onClick={handleAccept}
                disabled={!!selectedBid && mutatingId === selectedBid.id}
              >
                {selectedBid && mutatingId === selectedBid.id && <Loader2 className="h-4 w-4 animate-spin" />}
                Confirm &amp; Lock Escrow
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Counter */}
        <Dialog open={isCounterOpen} onOpenChange={setIsCounterOpen}>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle>Counter Proposal</DialogTitle>
              <DialogDescription>Suggest a different amount to {selectedBid?.creator.name}.</DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-3">
              <div className="space-y-2">
                <label className="text-sm font-medium">Original Bid</label>
                <div className="p-2 bg-muted rounded-md text-sm text-muted-foreground">
                  {selectedBid && formatCurrency(selectedBid.amount)}
                </div>
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium">Your Counter Amount (₹)</label>
                <Input type="number" placeholder="e.g. 2500" value={counterAmount} onChange={(e) => setCounterAmount(e.target.value)} />
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium">Message (optional)</label>
                <Textarea placeholder="Explain your counter offer..." value={counterMessage} onChange={(e) => setCounterMessage(e.target.value)} rows={3} />
              </div>
            </div>
            <DialogFooter className="gap-2">
              <Button variant="outline" onClick={() => setIsCounterOpen(false)}>Cancel</Button>
              <Button
                onClick={handleCounter}
                disabled={!counterAmount || (!!selectedBid && mutatingId === selectedBid.id)}
                className="gap-2"
              >
                {selectedBid && mutatingId === selectedBid.id && <Loader2 className="h-4 w-4 animate-spin" />}
                Send Counter
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Reject */}
        <Dialog open={isRejectOpen} onOpenChange={setIsRejectOpen}>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <XCircle className="h-5 w-5 text-destructive-foreground" />Decline Bid
              </DialogTitle>
              <DialogDescription>
                Decline {selectedBid?.creator.name}&apos;s bid. This action cannot be undone.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-3 py-3">
              <label className="text-sm font-medium">Reason (optional)</label>
              <Select value={rejectReason} onValueChange={setRejectReason}>
                <SelectTrigger><SelectValue placeholder="Select a reason" /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="budget">Budget too high</SelectItem>
                  <SelectItem value="audience">Audience mismatch</SelectItem>
                  <SelectItem value="quality">Content quality concerns</SelectItem>
                  <SelectItem value="slots">All slots filled</SelectItem>
                  <SelectItem value="other">Other</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <DialogFooter className="gap-2">
              <Button variant="outline" onClick={() => setIsRejectOpen(false)}>Cancel</Button>
              <Button
                variant="destructive"
                onClick={handleReject}
                disabled={!!selectedBid && mutatingId === selectedBid.id}
                className="gap-2"
              >
                {selectedBid && mutatingId === selectedBid.id && <Loader2 className="h-4 w-4 animate-spin" />}
                Decline Bid
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Generate contract */}
        <Dialog
          open={contractDraftFor !== null}
          onOpenChange={(open) => {
            if (!open && !isGeneratingContract) setContractDraftFor(null);
          }}
        >
          <DialogContent className="sm:max-w-lg">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <FileSignature className="h-5 w-5 text-primary" />
                Send contract
              </DialogTitle>
              <DialogDescription>
                Split the agreed amount into milestones. The server re-sums the total from these
                rows, so what you set here is what the contract is worth.
              </DialogDescription>
            </DialogHeader>
            {contractDraftFor && (
              <DealContractGenerate
                creatorName={contractDraftFor.creator.name}
                campaignName={campaign.title}
                dealValue={contractDraftFor.dealValue ?? 0}
                isGenerating={isGeneratingContract}
                onGenerate={(milestones) => void handleGenerateContract(milestones)}
              />
            )}
          </DialogContent>
        </Dialog>

        {/* Timeline Sheet Panel */}
        <Sheet open={timelineOpen} onOpenChange={setTimelineOpen}>
          <SheetContent side="right" className="w-full sm:w-[600px] p-0 flex flex-col">
            <SheetHeader className="px-6 py-4 border-b border-border">
              <SheetTitle className="flex items-center gap-2">
                <MessageSquare className="h-4 w-4" />
                {selectedCollaboration?.creator.name} - Collaboration Timeline
              </SheetTitle>
              {/* Radix warns ("Missing `Description` or `aria-describedby`") and leaves the panel
                  without an accessible description when this is absent — the dialogs on this page
                  all have one, this sheet never did. */}
              <SheetDescription>
                Messages, proposals, contract and deliverable activity for this collaboration.
              </SheetDescription>
            </SheetHeader>
            
            {selectedCollaboration && (
              <div className="flex-1 overflow-hidden px-6 py-4">
                <CollaborationTimeline 
                  collaboration={{
                    id: selectedCollaboration.id,
                    creatorId: selectedCollaboration.creator.id,
                    creatorName: selectedCollaboration.creator.name,
                    // Partial view-model: the timeline only reads id/creatorId/
                    // creatorName. Asserted to Collaboration rather than `any` so
                    // the component's own field access stays type-checked.
                  } as unknown as Collaboration}
                  currentUserType="brand"
                />
              </div>
            )}
          </SheetContent>
        </Sheet>

      </div>
    </TooltipProvider>
  );
}
