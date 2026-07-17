import * as React from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
  ArrowLeft, Calendar, Users, IndianRupee, Edit, Pause, Play,
  Copy, Trash2, MoreHorizontal, Send, MessageSquare, CheckCircle2,
  XCircle, Clock, Star, TrendingUp, Instagram, Youtube, Twitter,
  FileText, Download, AlertCircle, Sparkles, Filter, Search,
  BadgeCheck, Heart, Eye, BarChart2, Target, Award, RefreshCcw,
  ThumbsUp, Zap, TrendingDown, ChevronRight, Activity, Lock, DollarSign,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Separator } from '@/components/ui/separator';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
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
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { CampaignStateMachine, type CampaignState } from '@/components/brand/campaigns/campaign-state-machine';
import { CollaborationTimeline } from '@/components/brand/timeline/collaboration-timeline';

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
    followers: number;
    engagementRate: number;
    rating: number;
    completedCampaigns: number;
    platforms: string[];
  };
  amount: number;
  timeline: string;
  status: BidStatus;
  submittedAt: Date;
  matchScore: number;
  message: string;
  deliverables: { type: string; count: number }[];
};

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

const statusConfig = {
  DRAFT:     { label: 'Draft',     color: 'border bg-stage-draft text-stage-draft-fg border-stage-draft-border' },
  ACTIVE:    { label: 'Active',    color: 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border' },
  PAUSED:    { label: 'Paused',    color: 'border bg-stage-paused text-stage-paused-fg border-stage-paused-border' },
  COMPLETED: { label: 'Completed', color: 'border bg-stage-approved text-stage-approved-fg border-stage-approved-border' },
  CANCELLED: { label: 'Cancelled', color: 'border bg-stage-cancelled text-stage-cancelled-fg border-stage-cancelled-border' },
};

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
  const campaign = getMockCampaign(id);
  const isCompleted = campaign.status === 'COMPLETED';

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
  const [selectedCollaboration, setSelectedCollaboration] = React.useState<typeof mockCollaborators[0] | null>(null);

  const filteredBids = bids.filter((bid) => {
    const matchesFilter = bidFilter === 'all' || bid.status === bidFilter.toUpperCase();
    const matchesSearch =
      bid.creator.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      bid.creator.username.toLowerCase().includes(searchQuery.toLowerCase());
    return matchesFilter && matchesSearch;
  });

  const budgetProgress = ((campaign.budget.spent || 0) / campaign.budget.max) * 100;
  const daysRemaining = Math.ceil(
    (campaign.timeline.endDate.getTime() - Date.now()) / (1000 * 60 * 60 * 24)
  );
  const timelineProgress = (() => {
    const total = campaign.timeline.endDate.getTime() - campaign.timeline.startDate.getTime();
    const elapsed = Date.now() - campaign.timeline.startDate.getTime();
    return Math.min(100, Math.max(0, (elapsed / total) * 100));
  })();

  const handleAccept = () => {
    if (!selectedBid) return;
    setBids((prev) => prev.map((b) => b.id === selectedBid.id ? { ...b, status: 'ACCEPTED' as const } : b));
    setIsAcceptOpen(false);
    setSelectedBid(null);
  };

  const handleReject = () => {
    if (!selectedBid) return;
    setBids((prev) => prev.map((b) => b.id === selectedBid.id ? { ...b, status: 'REJECTED' as const } : b));
    setIsRejectOpen(false);
    setSelectedBid(null);
    setRejectReason('');
  };

  const handleCounter = () => {
    if (!selectedBid) return;
    setBids((prev) => prev.map((b) => b.id === selectedBid.id ? { ...b, status: 'COUNTERED' as const } : b));
    setIsCounterOpen(false);
    setSelectedBid(null);
    setCounterAmount('');
    setCounterMessage('');
  };

  // ── Completed campaign analytics (type narrowed)
  const completed = isCompleted ? (campaign as typeof mockCompletedCampaign) : null;

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
                {!isCompleted && (
                  <Button variant="outline" size="sm" className="hidden sm:flex">
                    <Edit className="h-4 w-4 mr-1.5" />
                    Edit
                  </Button>
                )}
                <Button variant="outline" size="sm" className="gap-1.5">
                  <Download className="h-4 w-4" />
                  <span className="hidden sm:inline">Export</span>
                </Button>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="icon">
                      <MoreHorizontal className="h-4 w-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem><Copy className="mr-2 h-4 w-4" />Duplicate Campaign</DropdownMenuItem>
                    <DropdownMenuItem><Download className="mr-2 h-4 w-4" />Export Full Report</DropdownMenuItem>
                    {!isCompleted && (
                      <>
                        <DropdownMenuSeparator />
                        {campaign.status === 'ACTIVE'
                          ? <DropdownMenuItem><Pause className="mr-2 h-4 w-4" />Pause Campaign</DropdownMenuItem>
                          : <DropdownMenuItem><Play className="mr-2 h-4 w-4" />Resume Campaign</DropdownMenuItem>
                        }
                      </>
                    )}
                    <DropdownMenuSeparator />
                    <DropdownMenuItem className="text-destructive">
                      <Trash2 className="mr-2 h-4 w-4" />Delete Campaign
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
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
                    value: formatCurrency(campaign.budget.spent || 0),
                    sub: `of ${formatCurrency(campaign.budget.max)}`,
                    progress: budgetProgress,
                    progressColor: budgetProgress > 90 ? 'bg-red-500' : 'bg-primary',
                  },
                  {
                    icon: <Users className="h-4 w-4" />,
                    label: 'Collaborators',
                    value: `${campaign.currentCollaborators}`,
                    sub: `of ${campaign.maxCollaborators} slots`,
                    progress: (campaign.currentCollaborators / campaign.maxCollaborators) * 100,
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
                      value: formatNumber(completed?.analytics.totalReach),
                      sub: `${formatNumber(completed?.analytics.totalImpressions)} impressions`,
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
                          <div className={cn('h-full rounded-full transition-all', stat.progressColor)} style={{ width: `${Math.min(100, stat.progress)}%` }} />
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
                        <TabsTrigger value="collaborators" className="gap-1.5"><Users className="h-4 w-4" />Creators <Badge variant="secondary" className="ml-1">{mockCompletedCollaborators.length}</Badge></TabsTrigger>
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
                          <Badge variant="secondary" className="ml-1">{mockCollaborators.length}</Badge>
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
                                    {bid.matchScore >= 90 && (
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
                                  <div className="flex flex-wrap items-center gap-3 mt-2 text-xs text-muted-foreground">
                                    <span className="flex items-center gap-1"><Users className="h-3 w-3" />{formatNumber(bid.creator.followers)}</span>
                                    <span className="flex items-center gap-1"><TrendingUp className="h-3 w-3" />{bid.creator.engagementRate}% eng.</span>
                                    <span className="flex items-center gap-1"><Star className="h-3 w-3 text-yellow-500" />{bid.creator.rating}</span>
                                    <span className="flex items-center gap-1"><CheckCircle2 className="h-3 w-3" />{bid.creator.completedCampaigns} campaigns</span>
                                  </div>
                                  <div className="flex items-center gap-1.5 mt-2">
                                    {bid.creator.platforms.map((p) => <PlatformIcon key={p} platform={p} />)}
                                  </div>
                                </div>
                              </div>

                              {/* Bid amount + actions */}
                              <div className="flex flex-col gap-3 md:items-end md:min-w-36">
                                <div>
                                  <p className="text-2xl font-bold text-primary">{formatCurrency(bid.amount)}</p>
                                  <p className="text-xs text-muted-foreground">{bid.timeline} delivery</p>
                                </div>
                                {bid.status !== 'REJECTED' && bid.status !== 'ACCEPTED' && (
                                  <div className="flex flex-wrap gap-2 md:justify-end">
                                    <Button size="sm" className="h-8 bg-stage-approved-fg hover:opacity-90 text-white"
                                      onClick={() => { setSelectedBid(bid); setIsAcceptOpen(true); }}>
                                      <ThumbsUp className="h-3.5 w-3.5 mr-1" />Accept
                                    </Button>
                                    <Button size="sm" variant="outline" className="h-8"
                                      onClick={() => { setSelectedBid(bid); setCounterAmount(String(bid.amount)); setIsCounterOpen(true); }}>
                                      Counter
                                    </Button>
                                    <Button size="sm" variant="ghost" className="h-8 text-destructive hover:text-destructive"
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
                            <div className="mt-3 p-3 bg-muted/40 rounded-lg border border-border/40">
                              <p className="text-sm text-muted-foreground leading-relaxed">&ldquo;{bid.message}&rdquo;</p>
                            </div>

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
                  {(isCompleted ? mockCompletedCollaborators : mockCollaborators).map((c) => {
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
                <TabsContent value="deliverables" className="mt-4">
                  <Card className="border-dashed">
                    <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                      <CheckCircle2 className="h-12 w-12 text-muted-foreground mb-3" />
                      <h3 className="font-semibold">No deliverables submitted yet</h3>
                      <p className="text-sm text-muted-foreground mt-1 max-w-sm">Deliverables will appear here once collaborators begin submitting content for review.</p>
                    </CardContent>
                  </Card>
                </TabsContent>

                {/* ═══ COMPLETED: Post-Mortem ══════════════════════════════ */}
                <TabsContent value="report" className="mt-4 space-y-4">
                  {completed && (
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
                          <p className="text-sm leading-relaxed text-muted-foreground">{completed.postMortem.summary}</p>
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
                            {completed.postMortem.whatWorked.map((item, i) => (
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
                            {completed.postMortem.whatUnderperformed.map((item, i) => (
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
                          {completed.postMortem.recommendations.map((rec, i) => (
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
                            {completed.postMortem.repeatCreators.map((name) => (
                              <span key={name} className="text-xs px-3 py-1.5 bg-yellow-500/10 text-yellow-400 border border-yellow-500/20 rounded-full font-medium">
                                {name}
                              </span>
                            ))}
                          </div>
                        </CardContent>
                      </Card>
                    </>
                  )}
                </TabsContent>

                {/* ═══ COMPLETED: Analytics ════════════════════════════════ */}
                <TabsContent value="analytics" className="mt-4 space-y-4">
                  {completed && (
                    <>
                      {/* Top metrics */}
                      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                        {[
                          { label: 'Total Reach', value: formatNumber(completed.analytics.totalReach), icon: <Eye className="h-4 w-4 text-blue-400" /> },
                          { label: 'Impressions', value: formatNumber(completed.analytics.totalImpressions), icon: <BarChart2 className="h-4 w-4 text-purple-400" /> },
                          { label: 'Total Likes', value: formatNumber(completed.analytics.totalLikes), icon: <Heart className="h-4 w-4 text-pink-400" /> },
                          { label: 'Total Comments', value: formatNumber(completed.analytics.totalComments), icon: <MessageSquare className="h-4 w-4 text-green-400" /> },
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
                              campaign: completed.analytics.benchmarks.avgEngagementRate.campaign,
                              peer: completed.analytics.benchmarks.avgEngagementRate.peer,
                              suffix: '%',
                              better: 'higher' as const,
                            },
                            {
                              label: 'ROI',
                              campaign: completed.analytics.benchmarks.roi.campaign,
                              peer: completed.analytics.benchmarks.roi.peer,
                              suffix: 'x',
                              better: 'higher' as const,
                            },
                            {
                              label: 'Cost Per Reach',
                              campaign: completed.analytics.benchmarks.costPerReach.campaign,
                              peer: completed.analytics.benchmarks.costPerReach.peer,
                              suffix: '',
                              better: 'lower' as const,
                            },
                          ].map((b) => {
                            const delta = Math.round(((b.campaign - b.peer) / b.peer) * 100);
                            const isGood = b.better === 'higher' ? delta > 0 : delta < 0;
                            const campWidth = b.better === 'higher'
                              ? (b.campaign / Math.max(b.campaign, b.peer)) * 100
                              : (b.peer / Math.max(b.campaign, b.peer)) * 100 * (b.peer / b.campaign);
                            const peerWidth = 60;
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
                                      <div className={cn('h-full rounded-full', isGood ? 'bg-primary' : 'bg-muted-foreground')}
                                        style={{ width: `${Math.min(100, (b.campaign / Math.max(b.campaign, b.peer)) * 100)}%` }} />
                                    </div>
                                    <span className="text-xs text-primary font-medium w-12">You</span>
                                  </div>
                                  <div className="flex items-center gap-2">
                                    <span className="text-xs w-16 text-right text-muted-foreground">{b.peer}{b.suffix}</span>
                                    <div className="flex-1 h-2 bg-muted rounded-full overflow-hidden">
                                      <div className="h-full rounded-full bg-muted-foreground/50"
                                        style={{ width: `${(b.peer / Math.max(b.campaign, b.peer)) * 100}%` }} />
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
                          {[
                            { label: 'Total Campaign Spend', value: formatCurrency(campaign.budget.spent || 0), highlight: false },
                            { label: 'Creator Payouts', value: formatCurrency((campaign.budget.spent || 0) * 0.82), highlight: false },
                            { label: 'Platform Fee (10%)', value: formatCurrency((campaign.budget.spent || 0) * 0.10), highlight: false },
                            { label: 'GST (18% on fee)', value: formatCurrency((campaign.budget.spent || 0) * 0.018), highlight: false },
                            { label: 'Estimated ROI', value: `${completed.analytics.roi}x`, highlight: true },
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

              {/* Budget breakdown */}
              <Card>
                <CardHeader className="pb-2 pt-4 px-4">
                  <CardTitle className="text-sm flex items-center gap-2">
                    <Lock className="h-3.5 w-3.5 text-primary" />Budget Breakdown
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-4 pb-4 space-y-2 text-sm">
                  {[
                    { label: 'Creator Pay (est.)', value: formatCurrency((campaign.budget.max || 0) * 0.82) },
                    { label: 'Platform Fee (10%)', value: formatCurrency((campaign.budget.max || 0) * 0.10) },
                    { label: 'GST (18% on fee)', value: formatCurrency((campaign.budget.max || 0) * 0.018) },
                    { label: 'Contingency', value: formatCurrency((campaign.budget.max || 0) * 0.062) },
                  ].map((row) => (
                    <div key={row.label} className="flex items-center justify-between text-xs">
                      <span className="text-muted-foreground">{row.label}</span>
                      <span className="font-medium">{row.value}</span>
                    </div>
                  ))}
                  <Separator className="my-1" />
                  <div className="flex items-center justify-between text-sm font-bold">
                    <span>Total Budget</span>
                    <span className="text-primary">{formatCurrency(campaign.budget.max)}</span>
                  </div>
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
              <Button className="bg-stage-approved-fg hover:opacity-90 text-white" onClick={handleAccept}>Confirm & Lock Escrow</Button>
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
                <label className="text-sm font-medium">Your Counter Amount (USD)</label>
                <Input type="number" placeholder="e.g. 2500" value={counterAmount} onChange={(e) => setCounterAmount(e.target.value)} />
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium">Message (optional)</label>
                <Textarea placeholder="Explain your counter offer..." value={counterMessage} onChange={(e) => setCounterMessage(e.target.value)} rows={3} />
              </div>
            </div>
            <DialogFooter className="gap-2">
              <Button variant="outline" onClick={() => setIsCounterOpen(false)}>Cancel</Button>
              <Button onClick={handleCounter} disabled={!counterAmount}>Send Counter</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Reject */}
        <Dialog open={isRejectOpen} onOpenChange={setIsRejectOpen}>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <XCircle className="h-5 w-5 text-destructive" />Decline Bid
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
              <Button variant="destructive" onClick={handleReject}>Decline Bid</Button>
            </DialogFooter>
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
            </SheetHeader>
            
            {selectedCollaboration && (
              <div className="flex-1 overflow-hidden px-6 py-4">
                <CollaborationTimeline 
                  collaboration={{
                    id: selectedCollaboration.id,
                    creatorId: selectedCollaboration.creator.id,
                    creatorName: selectedCollaboration.creator.name,
                    // Add other required fields from Collaboration type
                  } as any}
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
