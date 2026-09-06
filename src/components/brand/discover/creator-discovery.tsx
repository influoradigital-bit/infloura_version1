import * as React from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { formatDistanceToNow } from 'date-fns';
import {
  Search,
  X,
  Send,
  CheckCircle2,
  MapPin,
  Grid3X3,
  List,
  ChevronDown,
  SlidersHorizontal,
  Bookmark,
  BookmarkCheck,
  Plus,
  Loader2,
  ShieldAlert,
  ShieldCheck,
  Instagram as InstagramIcon,
  AtSign,
  Inbox,
  AlertTriangle,
  RefreshCw,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';
import type { Platform, CreatorProfile } from '@/lib/types';
import {
  api,
  isApiLive,
  ApiError,
  type CreatorSortOrder,
  type FeaturedCreatorSection,
  type ExternalCreator,
  type ConnectionRequest,
  type CreatorCategoryFacet,
} from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Slider } from '@/components/ui/slider';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
  SheetFooter,
} from '@/components/ui/sheet';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { Separator } from '@/components/ui/separator';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
// Platform pills shown in the discover hero — brand colors from the 2026-07-17 palette.
const HERO_PILLS = [
  { label: 'Instagram', color: '#E1306C', className: 'left-[8%] top-[22%]', delay: 0 },
  { label: 'YouTube', color: '#FF4444', className: 'left-[30%] top-[58%]', delay: 0.6 },
  { label: 'LinkedIn', color: '#0A66C2', className: 'right-[26%] top-[18%]', delay: 1.1 },
  { label: 'Creators', color: '#7ec8e8', className: 'right-[8%] top-[52%]', delay: 0.3 },
] as const;

/** Lightweight 2D discover hero — soft gradient, pulsing glow, gently drifting platform
    pills. Replaces the WebGL orbit with simple framer-motion; respects reduced-motion. */
function DiscoverHero() {
  const reduceMotion = useReducedMotion();

  return (
    <div className="relative h-full w-full overflow-hidden bg-gradient-to-br from-[#ddd6fe]/85 via-[#f0ebfa] to-[#c4b5fd]/60">
      {/* Soft blurred glow blobs */}
      <motion.div
        aria-hidden
        className="absolute -left-[8%] -top-[20%] h-[70%] w-[45%] rounded-full bg-[#7c6ae8]/35 blur-[90px]"
        animate={reduceMotion ? undefined : { scale: [1, 1.12, 1], opacity: [0.7, 1, 0.7] }}
        transition={{ duration: 7, repeat: Infinity, ease: 'easeInOut' }}
      />
      <motion.div
        aria-hidden
        className="absolute -right-[6%] top-[8%] h-[60%] w-[45%] rounded-full bg-[#7ec8e8]/30 blur-[90px]"
        animate={reduceMotion ? undefined : { scale: [1.1, 1, 1.1], opacity: [0.6, 0.9, 0.6] }}
        transition={{ duration: 8, repeat: Infinity, ease: 'easeInOut', delay: 1 }}
      />

      {/* Central pulsing hub */}
      <motion.div
        aria-hidden
        className="absolute left-1/2 top-1/2 h-28 w-28 -translate-x-1/2 -translate-y-1/2 rounded-full bg-gradient-to-br from-[#7c6ae8] to-[#c4b5fd] shadow-[0_0_60px_-10px_rgba(124,106,232,0.7)]"
        animate={reduceMotion ? undefined : { scale: [1, 1.06, 1] }}
        transition={{ duration: 4, repeat: Infinity, ease: 'easeInOut' }}
      />

      {/* Floating platform pills */}
      {HERO_PILLS.map((pill) => (
        <motion.div
          key={pill.label}
          className={cn('absolute flex items-center gap-1.5', pill.className)}
          initial={{ opacity: 0, y: 8 }}
          animate={
            reduceMotion
              ? { opacity: 1, y: 0 }
              : { opacity: 1, y: [0, -10, 0] }
          }
          transition={
            reduceMotion
              ? { duration: 0.4 }
              : { duration: 5, repeat: Infinity, ease: 'easeInOut', delay: pill.delay }
          }
        >
          <span className="flex items-center gap-1.5 rounded-full bg-card/90 px-3 py-1 text-xs font-medium text-foreground shadow-sm backdrop-blur-sm">
            <span className="h-2 w-2 rounded-full bg-[var(--pill-color)]" ref={cssVars({ '--pill-color': pill.color })} />
            {pill.label}
          </span>
        </motion.div>
      ))}
    </div>
  );
}

// F-0660 — deliberately hardcoded, unlike the Categories filter below (`categoryOptions`). The
// only facets `GET /creators/search` returns are `categories` and `followerRanges`
// (`DiscoveryDtos.AvailableFiltersMeta`, verified server-side) — there is no city facet anywhere
// on the backend to wire this list from, so it stays a fixed reference list until one exists.
const INDIAN_CITIES = [
  'Mumbai',
  'Delhi',
  'Bangalore',
  'Hyderabad',
  'Chennai',
  'Kolkata',
  'Pune',
  'Ahmedabad',
  'Jaipur',
  'Lucknow',
  'Chandigarh',
  'Goa',
  'Kochi',
  'Indore',
  'Surat',
];

// Mock creator data with Indian locations and INR pricing
const mockCreators: CreatorProfile[] = [
  {
    id: '1',
    userId: 'u1',
    displayName: 'Priya Sharma',
    bio: 'Fashion & lifestyle creator from Mumbai. Sharing daily OOTDs, beauty tips and travel diaries with 9L+ followers.',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Mumbai, Maharashtra',
    categories: ['Fashion', 'Lifestyle', 'Beauty'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@priyasharma', followers: 920000, engagementRate: 4.8, isVerified: true },
      { platform: 'YOUTUBE', handle: 'PriyaSharmaVlogs', followers: 450000, engagementRate: 3.5, isVerified: true },
    ],
    totalFollowers: 1370000,
    engagementRate: 4.2,
    averageRate: 75000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Hindi', 'English'],
    contentStyles: ['Aesthetic', 'Minimalist', 'Trendy'],
  },
  {
    id: '2',
    userId: 'u2',
    displayName: 'Arjun Kapoor',
    bio: 'Tech reviewer & gadget enthusiast. Honest reviews in Hindi & English. 5L+ tech-savvy community.',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Bangalore, Karnataka',
    categories: ['Technology', 'Gadgets', 'Reviews'],
    platforms: [
      { platform: 'YOUTUBE', handle: 'ArjunTechReview', followers: 680000, engagementRate: 5.2, isVerified: true },
      { platform: 'INSTAGRAM', handle: '@arjuntech', followers: 220000, engagementRate: 4.1, isVerified: true },
    ],
    totalFollowers: 900000,
    engagementRate: 4.6,
    averageRate: 50000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Hindi', 'English'],
    contentStyles: ['Educational', 'Professional', 'Detailed'],
  },
  {
    id: '3',
    userId: 'u3',
    displayName: 'Sneha Reddy',
    bio: 'Mom of twins, home decor lover. Sharing parenting tips, DIY home hacks, and family vlogs from Hyderabad.',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Hyderabad, Telangana',
    categories: ['Lifestyle', 'Home Decor', 'Parenting'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@snehareddy', followers: 540000, engagementRate: 6.1, isVerified: true },
      { platform: 'YOUTUBE', handle: 'SnehaFamilyVlogs', followers: 320000, engagementRate: 5.8, isVerified: false },
    ],
    totalFollowers: 860000,
    engagementRate: 5.9,
    averageRate: 45000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Telugu', 'Hindi', 'English'],
    contentStyles: ['Warm', 'Authentic', 'Family-friendly'],
  },
  {
    id: '4',
    userId: 'u4',
    displayName: 'Vikram Singh',
    bio: 'Fitness coach & nutrition expert. Helping India get fit, one workout at a time. 7L+ fitness community.',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Delhi, NCR',
    categories: ['Fitness', 'Health', 'Nutrition'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@vikramfitness', followers: 780000, engagementRate: 5.5, isVerified: true },
      { platform: 'YOUTUBE', handle: 'VikramFitIndia', followers: 420000, engagementRate: 4.8, isVerified: true },
    ],
    totalFollowers: 1200000,
    engagementRate: 5.2,
    averageRate: 65000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Hindi', 'English', 'Punjabi'],
    contentStyles: ['Motivational', 'Educational', 'High-energy'],
  },
  {
    id: '5',
    userId: 'u5',
    displayName: 'Ananya Menon',
    bio: 'Food blogger & home chef. South Indian recipes with a modern twist. 4L+ foodies following!',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Chennai, Tamil Nadu',
    categories: ['Food', 'Cooking', 'Recipes'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@ananyacooks', followers: 380000, engagementRate: 7.2, isVerified: true },
      { platform: 'YOUTUBE', handle: 'AnanyaKitchen', followers: 290000, engagementRate: 6.5, isVerified: false },
    ],
    totalFollowers: 670000,
    engagementRate: 6.8,
    averageRate: 35000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Tamil', 'English'],
    contentStyles: ['Authentic', 'Tutorial', 'Homestyle'],
  },
  {
    id: '6',
    userId: 'u6',
    displayName: 'Rohan Mehta',
    bio: 'Travel vlogger exploring India & beyond. Adventure, culture, and hidden gems. 8L+ travel enthusiasts.',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Pune, Maharashtra',
    categories: ['Travel', 'Adventure', 'Culture'],
    platforms: [
      { platform: 'YOUTUBE', handle: 'RohanTravels', followers: 920000, engagementRate: 4.9, isVerified: true },
      { platform: 'INSTAGRAM', handle: '@rohanmehta', followers: 450000, engagementRate: 5.1, isVerified: true },
    ],
    totalFollowers: 1370000,
    engagementRate: 5.0,
    averageRate: 80000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Hindi', 'English', 'Marathi'],
    contentStyles: ['Cinematic', 'Adventure', 'Documentary'],
  },
  {
    id: '7',
    userId: 'u7',
    displayName: 'Kavya Nair',
    bio: 'Beauty & skincare creator. Honest product reviews, makeup tutorials, and skincare routines for Indian skin.',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Kochi, Kerala',
    categories: ['Beauty', 'Skincare', 'Makeup'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@kavyabeauty', followers: 620000, engagementRate: 5.8, isVerified: true },
      { platform: 'YOUTUBE', handle: 'KavyaGlam', followers: 380000, engagementRate: 4.5, isVerified: true },
    ],
    totalFollowers: 1000000,
    engagementRate: 5.2,
    averageRate: 55000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Malayalam', 'Hindi', 'English'],
    contentStyles: ['Tutorial', 'Review', 'Aesthetic'],
  },
  {
    id: '8',
    userId: 'u8',
    displayName: 'Aditya Joshi',
    bio: 'Finance & investing made simple. Helping millennials build wealth. 3L+ financially savvy followers.',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Ahmedabad, Gujarat',
    categories: ['Finance', 'Investing', 'Education'],
    platforms: [
      { platform: 'YOUTUBE', handle: 'AdityaFinance', followers: 420000, engagementRate: 4.2, isVerified: true },
      { platform: 'INSTAGRAM', handle: '@adityamoney', followers: 180000, engagementRate: 3.8, isVerified: false },
    ],
    totalFollowers: 600000,
    engagementRate: 4.0,
    averageRate: 40000,
    currency: 'INR',
    isVerified: true,
    portfolioItems: [],
    languages: ['Hindi', 'Gujarati', 'English'],
    contentStyles: ['Educational', 'Explainer', 'Data-driven'],
  },
  {
    // F-0409 — newly onboarded, not priced yet. The only mock-mode fixture with `averageRate`
    // unset; needed so the price-range filter's OR-isNull path (mirroring the server's
    // `rateOverlap` spec) has a real row to keep once the price slider is touched.
    id: '9',
    userId: 'u9',
    displayName: 'Rahul Verma',
    bio: 'Just joined Influora. Comedy sketches and relatable everyday content from Jaipur.',
    avatarUrl: undefined,
    coverImageUrl: undefined,
    location: 'Jaipur, Rajasthan',
    categories: ['Entertainment', 'Comedy'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@rahulvermacomedy', followers: 150000, engagementRate: 3.5, isVerified: false },
    ],
    totalFollowers: 150000,
    engagementRate: 3.5,
    averageRate: undefined,
    currency: 'INR',
    isVerified: false,
    portfolioItems: [],
    languages: ['Hindi', 'English'],
    contentStyles: ['Comedy', 'Relatable'],
  },
];

// Mock campaigns for invite modal
const mockCampaigns = [
  { id: 'c1', name: 'Diwali Collection Launch 2024', status: 'ACTIVE' },
  { id: 'c2', name: 'Summer Skincare Range', status: 'ACTIVE' },
  { id: 'c3', name: 'Fitness App Promotion', status: 'DRAFT' },
];

// BR-18 — score badges rendered from `creator.scores` (DiscoveryDtos.CreatorScores: quality,
// authenticity, brandSafety). Per Priya's Score Exposure rule, `null` is a value — it means "not
// yet scored", never "0%". `brandSafety` is null for every creator until BR-42 ships.
function ScoreBadge({ label, value }: { label: string; value: number | null | undefined }) {
  if (value == null) {
    return (
      <Badge variant="outline" className="text-[10px] font-normal text-muted-foreground">
        {label}: Not yet scored
      </Badge>
    );
  }
  const tone =
    value >= 80
      ? 'border-stage-approved-border bg-stage-approved text-stage-approved-fg'
      : value >= 50
        ? 'border-stage-negotiating-border bg-stage-negotiating text-stage-negotiating-fg'
        : 'border-destructive text-destructive-foreground';
  return (
    <Badge variant="outline" className={cn('text-[10px] font-medium', tone)}>
      {label}: {Math.round(value)}%
    </Badge>
  );
}

// Format currency in INR
const formatINR = (amount: number): string => {
  if (amount >= 100000) {
    return `₹${(amount / 100000).toFixed(1)}L`;
  }
  if (amount >= 1000) {
    return `₹${(amount / 1000).toFixed(0)}K`;
  }
  return `₹${amount}`;
};

// Format followers
const formatFollowers = (num: number): string => {
  if (num >= 10000000) return `${(num / 10000000).toFixed(1)}Cr`;
  if (num >= 100000) return `${(num / 100000).toFixed(1)}L`;
  if (num >= 1000) return `${(num / 1000).toFixed(1)}K`;
  return num.toString();
};

const platforms: Platform[] = ['INSTAGRAM', 'YOUTUBE', 'TIKTOK', 'TWITTER'];
const categories = [
  'Fashion',
  'Beauty',
  'Lifestyle',
  'Technology',
  'Fitness',
  'Food',
  'Travel',
  'Parenting',
  'Finance',
  'Education',
  'Entertainment',
  'Gaming',
];

// F-0660 — same ruling as INDIAN_CITIES above: no language facet exists on the backend, so this
// stays a fixed reference list rather than being wired to something that doesn't exist.
const languages = ['Hindi', 'English', 'Tamil', 'Telugu', 'Kannada', 'Malayalam', 'Bengali', 'Marathi', 'Gujarati', 'Punjabi'];

/**
 * F-0660 — the live category facet (`api.creators.searchWithFacets`) returns lowercase ids
 * (`CreatorDiscoveryService#buildAvailableFacets` lowercases every stored category before
 * counting it). Title-cased purely for display; `toggleCategory`/the `verticals` query param
 * both round-trip it straight back to lowercase, so this never changes what gets selected or
 * sent — only how it reads on the badge.
 */
const titleCaseCategory = (id: string): string => id.replace(/\b\w/g, (ch) => ch.toUpperCase());

export function CreatorDiscovery() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const liveApi = isApiLive();

  const [apiCreators, setApiCreators] = React.useState<CreatorProfile[]>([]);
  const [apiLoading, setApiLoading] = React.useState(false);
  const [apiLoadingMore, setApiLoadingMore] = React.useState(false);
  const [apiPage, setApiPage] = React.useState(1);
  const [apiHasMore, setApiHasMore] = React.useState(false);
  /** F-0410 — server total for the current filter set; `undefined` when the envelope omits it. */
  const [apiTotal, setApiTotal] = React.useState<number | undefined>(undefined);
  /**
   * F-0660 — real category counts from `GET /creators/search`'s facets (categories only; the
   * backend has no city/language facet — see `CreatorSearchFacets`'s doc comment in api.ts).
   * Empty until the first live fetch resolves, which is exactly when the Categories filter below
   * falls back to the hardcoded `categories` list rather than rendering nothing.
   */
  const [categoryFacets, setCategoryFacets] = React.useState<CreatorCategoryFacet[]>([]);
  // F-0256 — the mock fixture used to seed this as unconditional initial state, so in live mode
  // a brand could pick one of three fabricated campaigns (fake ids `c1`/`c2`/`c3`) before
  // `GET /campaigns` had a chance to resolve, and submit an invite/offer against a campaignId
  // that does not exist server-side. The fixture is now only reachable in mock mode — live mode
  // starts empty and `campaignsLoading` (below) gates the selector until the real list lands.
  const [inviteCampaigns, setInviteCampaigns] = React.useState<{ id: string; name: string; status: string }[]>(
    liveApi ? [] : mockCampaigns,
  );
  const [campaignsLoading, setCampaignsLoading] = React.useState(liveApi);
  const DISCOVER_PAGE_SIZE = 20;

  // Search and filter state
  const [searchQuery, setSearchQuery] = React.useState('');
  const [selectedPlatforms, setSelectedPlatforms] = React.useState<Platform[]>([]);
  const [selectedCategories, setSelectedCategories] = React.useState<string[]>([]);
  const [selectedCities, setSelectedCities] = React.useState<string[]>([]);
  const [selectedLanguages, setSelectedLanguages] = React.useState<string[]>([]);
  const [followerRange, setFollowerRange] = React.useState<[number, number]>([0, 10000000]);
  const [priceRange, setPriceRange] = React.useState<[number, number]>([5000, 200000]);
  const [engagementRange, setEngagementRange] = React.useState<[number, number]>([0, 15]);
  const [verifiedOnly, setVerifiedOnly] = React.useState(false);
  // F-0408 — was `'relevance'`, and a "Relevance" item sat at the top of the Sort menu. The
  // server has no relevance ranking: CreatorDiscoveryService#toSort maps `relevance` to plain
  // totalFollowers DESC, which is exactly what "Most Followers" already does, and it is also the
  // controller's own default. So the option named a ranking that did not exist and was
  // indistinguishable from the one below it. Removed, same call as `exclusivity`/`revisionCap`
  // below: a control that does not do what it says is worse than an absent one. `followers` is
  // now the default here, which matches the server default the page already got.
  const [sortBy, setSortBy] = React.useState<CreatorSortOrder>('followers');
  const [viewMode, setViewMode] = React.useState<'grid' | 'list'>('grid');

  // T-CREATORCONNECT-0902 — Influora creators (existing behaviour, untouched below) vs
  // Instagram creators (Meta-sourced, not yet on Influora — see InstagramCreatorsTab).
  const [sourceTab, setSourceTab] = React.useState<'influora' | 'instagram'>('influora');

  // UI state
  const [savedCreators, setSavedCreators] = React.useState<string[]>([]);
  const [isFilterOpen, setIsFilterOpen] = React.useState(false);
  const [isInviteOpen, setIsInviteOpen] = React.useState(false);
  const [inviteCreator, setInviteCreator] = React.useState<CreatorProfile | null>(null);
  const [selectedCampaign, setSelectedCampaign] = React.useState('');
  const [inviteMessage, setInviteMessage] = React.useState('');
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [createNewCampaign, setCreateNewCampaign] = React.useState(false);
  
  // Structured Proposal State
  const [proposalStep, setProposalStep] = React.useState<'campaign' | 'proposal' | 'confirm'>('campaign');
  /**
   * Terms collected by the 'proposal' step. Every field here is one the server actually honours.
   *
   * `exclusivity` and `revisionCap` were removed 2026-07-26 (CEO call). Both had real, working
   * controls in the proposal step and appeared in the review summary — they just reached nothing:
   * the backend discarded `exclusivity` (one occurrence in the whole API, the DTO field) and
   * never accepted a revision cap at all. Live inputs wired to nothing are worse than absent
   * ones, because the brand believes it bought the term.
   */
  const [proposalData, setProposalData] = React.useState({
    deliverables: [{ type: 'REEL', count: 1 }],
    budget: 0,
    deadline: '',
    usageRights: '3_MONTHS',
  });

  const handleOpenInvite = (creator: CreatorProfile) => {
    setInviteCreator(creator);
    setIsInviteOpen(true);
    setCreateNewCampaign(false);
    setSelectedCampaign('');
    setProposalStep('campaign');
    setProposalData({
      deliverables: [{ type: 'REEL', count: 1 }],
      // Was `|| 50000` — a fabricated default budget. This form already treats budget === 0
      // as "not priced" everywhere else (see `proposalData.budget > 0` / `priced` below), so
      // ?? 0 keeps that same honest "not set" convention instead of inventing a fake ₹50,000.
      budget: creator.averageRate ?? 0,
      deadline: '',
      usageRights: '3_MONTHS',
    });
    setInviteMessage('');
  };

  const handleInvite = async () => {
    if (createNewCampaign) {
      navigate(`/brand/campaigns/new?creator=${inviteCreator?.id}`);
      setIsInviteOpen(false);
      return;
    }

    // Step flow for structured proposal
    if (proposalStep === 'campaign' && selectedCampaign) {
      setProposalStep('proposal');
      return;
    }

    if (proposalStep === 'proposal') {
      setProposalStep('confirm');
      return;
    }

    if (!inviteCreator?.id || !selectedCampaign) return;

    setIsSubmitting(true);
    let dealParam = '';
    try {
      if (liveApi) {
        // One modal, two fidelities (CTO call 2026-07-26). Both endpoints are campaign-scoped and
        // write the SAME Collaboration row keyed on (campaignId, creatorId) — they 409 against
        // each other — so exactly one fires per submit, chosen by whether the brand actually
        // priced the offer:
        //   budget > 0 → POST /deals       — lands IN_NEGOTIATION with agreedRate set, writes a
        //                                    proposal message, fires ProposalSentEvent.
        //   budget = 0 → POST /creators/:id/invite — lands INVITED, no terms, no notification.
        // Until now this branch always took the invite path and silently discarded every term the
        // two preceding steps collected.
        if (proposalData.budget > 0) {
          const deal = await api.deals.create({
            campaignId: selectedCampaign,
            // CreatorProfile id — what `creators.search` returns and what the server resolves.
            creatorId: inviteCreator.id,
            amount: proposalData.budget,
            // Local shape uses `count`; the API contract is `qty` (DealDtos.DeliverableSlot).
            deliverables: proposalData.deliverables
              .filter((d) => d.type && d.count > 0)
              .map((d) => ({ type: d.type, qty: d.count })),
            deadline: proposalData.deadline || undefined,
            usageRights: proposalData.usageRights || undefined,
            message: inviteMessage || undefined,
          });
          dealParam = `?deal=${deal.id}`;
          toast({
            title: 'Offer sent',
            description: `${inviteCreator.displayName} received your offer of ${formatINR(proposalData.budget)}.`,
          });
        } else {
          const { collaborationId } = await api.creators.invite(
            inviteCreator.id,
            selectedCampaign,
            inviteMessage || undefined,
          );
          dealParam = `?deal=${collaborationId}`;
          toast({
            title: 'Invitation sent',
            description: `${inviteCreator.displayName} has been invited to the campaign.`,
          });
        }
      } else {
        await new Promise((resolve) => setTimeout(resolve, 800));
      }
      setIsInviteOpen(false);
      setSelectedCampaign('');
      setInviteMessage('');
      setInviteCreator(null);
      setProposalStep('campaign');
      // brand-chat.tsx:~630 reads `?deal=` to select the conversation the offer/invite just
      // opened; without it the brand lands on an arbitrary deal room (D-11).
      navigate(`/brand/chat${dealParam}`);
    } catch (e) {
      const priced = proposalData.budget > 0;
      // Both paths share the (campaignId, creatorId) uniqueness constraint, so this is the most
      // likely failure once a creator has been approached before — say what actually happened
      // rather than a generic retry prompt the brand can't act on.
      const message =
        e instanceof ApiError && e.code === 'COLLABORATION_EXISTS'
          ? `${inviteCreator.displayName} is already on this campaign. Open the deal room to continue there.`
          : e instanceof ApiError
            ? e.message
            : `Could not send the ${priced ? 'offer' : 'invitation'}. Try again.`;
      toast({
        title: priced ? 'Offer failed' : 'Invite failed',
        description: message,
        variant: 'destructive',
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  const togglePlatform = (platform: Platform) => {
    setSelectedPlatforms((prev) =>
      prev.includes(platform) ? prev.filter((p) => p !== platform) : [...prev, platform]
    );
  };

  const toggleCategory = (category: string) => {
    setSelectedCategories((prev) =>
      prev.includes(category) ? prev.filter((c) => c !== category) : [...prev, category]
    );
  };

  const toggleCity = (city: string) => {
    setSelectedCities((prev) =>
      prev.includes(city) ? prev.filter((c) => c !== city) : [...prev, city]
    );
  };

  const toggleLanguage = (language: string) => {
    setSelectedLanguages((prev) =>
      prev.includes(language) ? prev.filter((l) => l !== language) : [...prev, language]
    );
  };

  const toggleSaved = async (creatorId: string) => {
    const nextSaved = !savedCreators.includes(creatorId);
    setSavedCreators((prev) =>
      nextSaved ? [...prev, creatorId] : prev.filter((id) => id !== creatorId),
    );
    if (!liveApi) return;
    try {
      await api.creators.toggleSaved(creatorId, nextSaved);
    } catch {
      setSavedCreators((prev) =>
        nextSaved ? prev.filter((id) => id !== creatorId) : [...prev, creatorId],
      );
      toast({ title: 'Could not update saved list', variant: 'destructive' });
    }
  };

  const fetchCreators = React.useCallback(
    async (pageNum: number, append: boolean) => {
      if (append) {
        setApiLoadingMore(true);
      } else {
        setApiLoading(true);
      }
      try {
        // F-0405/F-0431 — every enabled control on this screen is a query param. Language,
        // engagement rate, verified-only and sort order used to stop at the browser: they
        // re-filtered the 20 rows this call had already returned, so a brand narrowing by
        // language was really narrowing the current page, not the creator base. Each `undefined`
        // below is the "untouched" sentinel — the server treats an absent param as no filter, so
        // an untouched slider must not be sent as its own default bounds.
        const result = await api.creators.searchWithFacets({
          q: searchQuery || undefined,
          city: selectedCities.length ? selectedCities.join(',') : undefined,
          platforms: selectedPlatforms.length ? selectedPlatforms : undefined,
          verticals: selectedCategories.length
            ? selectedCategories.map((c) => c.toLowerCase())
            : undefined,
          languages: selectedLanguages.length ? selectedLanguages : undefined,
          minFollowers: followerRange[0] > 0 ? followerRange[0] : undefined,
          maxFollowers: followerRange[1] < 10000000 ? followerRange[1] : undefined,
          minRate: priceRange[0] > 5000 ? priceRange[0] : undefined,
          maxRate: priceRange[1] < 200000 ? priceRange[1] : undefined,
          minEngagementRate: engagementRange[0] > 0 ? engagementRange[0] : undefined,
          maxEngagementRate: engagementRange[1] < 15 ? engagementRange[1] : undefined,
          isVerified: verifiedOnly || undefined,
          sortBy,
          page: pageNum,
          limit: DISCOVER_PAGE_SIZE,
        });
        setApiCreators((prev) => (append ? [...prev, ...result.creators] : result.creators));
        setApiHasMore(result.meta.hasMore);
        // F-0410 — the server's total for this filter set, so the count below can say how many
        // creators actually match rather than how many rows this page happened to return.
        setApiTotal(result.meta.total);
        setApiPage(pageNum);
        // F-0660 — real category counts, replacing nothing that worked before (this call used to
        // be plain `search`, which returned no facets at all). Optional-chained: defensive
        // against any caller still resolving the pre-F-0660 `{ creators, meta }` shape with no
        // `facets` at all.
        if (result.facets?.categories?.length) setCategoryFacets(result.facets.categories);
        const saved = result.creators
          .filter((c) => (c as CreatorProfile & { saved?: boolean }).saved)
          .map((c) => c.id);
        if (saved.length) setSavedCreators((prev) => [...new Set([...prev, ...saved])]);
      } catch (err) {
        // Was a silent empty-grid — a failed search looked identical to "no
        // creators match." Surface it and keep the current page on load-more.
        if (!append) setApiCreators([]);
        toast({
          title: append ? 'Couldn’t load more creators' : 'Couldn’t load creators',
          description: err instanceof ApiError ? err.message : 'Please adjust your filters or try again.',
          variant: 'destructive',
        });
      } finally {
        setApiLoading(false);
        setApiLoadingMore(false);
      }
    },
    [
      searchQuery,
      selectedCities,
      selectedPlatforms,
      selectedCategories,
      selectedLanguages,
      followerRange,
      priceRange,
      engagementRange,
      verifiedOnly,
      sortBy,
    ],
  );

  React.useEffect(() => {
    if (!liveApi) return;
    let cancelled = false;
    const t = window.setTimeout(() => {
      if (!cancelled) void fetchCreators(1, false);
    }, 300);
    return () => {
      cancelled = true;
      window.clearTimeout(t);
    };
  }, [liveApi, fetchCreators]);

  React.useEffect(() => {
    if (!liveApi) return;
    setCampaignsLoading(true);
    api.campaigns
      .list({ limit: 50 })
      .then(({ campaigns: rows }) =>
        setInviteCampaigns(
          rows.map((c) => ({ id: c.id, name: c.title, status: c.status })),
        ),
      )
      .catch(() => setInviteCampaigns([]))
      .finally(() => setCampaignsLoading(false));
  }, [liveApi]);

  // D-14 — GET /creators/featured (CreatorController.featured), a backend-complete endpoint with
  // no prior FE consumer (wiki/errors/BRAND-BUG-TRACKER.md). Fetched once on mount; rendered below
  // only while the brand hasn't started searching/filtering (see `activeFilterCount` further
  // down) — the same "default landing state" convention featured rails use elsewhere.
  const [featuredSections, setFeaturedSections] = React.useState<FeaturedCreatorSection[]>([]);
  React.useEffect(() => {
    if (!liveApi) return;
    let cancelled = false;
    api.creators
      .featured({ limit: 8 })
      .then(({ featured }) => {
        if (!cancelled) setFeaturedSections(featured);
      })
      .catch(() => {
        if (!cancelled) setFeaturedSections([]);
      });
    return () => {
      cancelled = true;
    };
  }, [liveApi]);

  const clearFilters = () => {
    setSelectedPlatforms([]);
    setSelectedCategories([]);
    setSelectedCities([]);
    setSelectedLanguages([]);
    setFollowerRange([0, 10000000]);
    setPriceRange([5000, 200000]);
    setEngagementRange([0, 15]);
    setVerifiedOnly(false);
  };

  // Filter creators.
  //
  // F-0405/F-0409/F-0410 — this whole block is MOCK-MODE ONLY now. In live mode the server has
  // already applied every one of these predicates and the sort (see fetchCreators above), over
  // the entire creator base rather than the 20 rows this page fetched. Re-running them here was
  // not a harmless double-check:
  //   - it silently disagreed with the server on unpriced creators. `rateOverlap` deliberately
  //     ORs an isNull check so a creator with no rate set still matches a price filter; the
  //     predicate below required `averageRate != null` and dropped exactly those rows (F-0409).
  //   - it made the result count a page length rather than a match count (F-0410).
  //   - it made language/engagement/verified/sort look like they worked while they only ever
  //     touched the current page (F-0405).
  const filteredCreators = React.useMemo(() => {
    if (liveApi) return apiCreators;

    let result = [...mockCreators];

    // Search filter
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      result = result.filter(
        (c) =>
          c.displayName.toLowerCase().includes(query) ||
          (c.bio?.toLowerCase().includes(query) ?? false) ||
          c.categories.some((cat) => cat.toLowerCase().includes(query)) ||
          (c.location?.toLowerCase().includes(query) ?? false)
      );
    }

    // Platform filter
    if (selectedPlatforms.length > 0) {
      result = result.filter((c) =>
        c.platforms.some((p) => selectedPlatforms.includes(p.platform))
      );
    }

    // Category filter
    if (selectedCategories.length > 0) {
      result = result.filter((c) =>
        c.categories.some((cat) => selectedCategories.includes(cat))
      );
    }

    // City filter
    if (selectedCities.length > 0) {
      result = result.filter((c) =>
        selectedCities.some((city) => c.location?.includes(city))
      );
    }

    // Language filter
    if (selectedLanguages.length > 0) {
      result = result.filter((c) =>
        c.languages?.some((lang) => selectedLanguages.includes(lang))
      );
    }

    // Follower range filter
    result = result.filter(
      (c) => c.totalFollowers >= followerRange[0] && c.totalFollowers <= followerRange[1]
    );

    // Price range filter — F-0259: this used to run unconditionally at its untouched default
    // (5000–200000). `(c.averageRate ?? 0)` maps "no rate set yet" to 0, which sits below the
    // 5000 floor, so every unpriced creator was silently excluded even though the brand never
    // touched this slider. Only enforce the range once the brand has actually moved it; a
    // creator with no rate is a legitimate match at the untouched default.
    // F-0409 — and once it IS touched, an unpriced creator still matches, mirroring the server's
    // `rateOverlap` spec, which ORs an isNull check into both bounds for exactly this reason.
    const priceFilterTouched = priceRange[0] > 5000 || priceRange[1] < 200000;
    if (priceFilterTouched) {
      result = result.filter(
        (c) => c.averageRate == null || (c.averageRate >= priceRange[0] && c.averageRate <= priceRange[1])
      );
    }

    // Engagement range filter
    result = result.filter(
      (c) => c.engagementRate >= engagementRange[0] && c.engagementRate <= engagementRange[1]
    );

    // Verified filter
    if (verifiedOnly) {
      result = result.filter((c) => c.isVerified);
    }

    // Sort — same four orders the server honours by name (CreatorSortOrder in api.ts).
    switch (sortBy) {
      case 'followers':
        result.sort((a, b) => b.totalFollowers - a.totalFollowers);
        break;
      case 'engagement':
        result.sort((a, b) => b.engagementRate - a.engagementRate);
        break;
      case 'price_low':
        result.sort((a, b) => (a.averageRate ?? 0) - (b.averageRate ?? 0));
        break;
      case 'price_high':
        result.sort((a, b) => (b.averageRate ?? 0) - (a.averageRate ?? 0));
        break;
    }

    return result;
  }, [
    liveApi,
    apiCreators,
    searchQuery,
    selectedPlatforms,
    selectedCategories,
    selectedCities,
    selectedLanguages,
    followerRange,
    priceRange,
    engagementRange,
    verifiedOnly,
    sortBy,
  ]);

  /**
   * F-0660 — the Categories filter badges. Live mode sources them from the real `categories`
   * facet (`categoryFacets`) once the first search resolves; mock mode, and live mode before
   * that first resolve, fall back to the hardcoded `categories` list so the panel is never
   * empty. Facet ids are already lowercase (`toggleCategory`/the `verticals` query param expect
   * exactly that), so `label` is the only thing title-cased for display.
   */
  const categoryOptions = React.useMemo<{ id: string; label: string; count?: number }[]>(() => {
    if (liveApi && categoryFacets.length) {
      return categoryFacets.map((f) => ({ id: f.id, label: titleCaseCategory(f.id), count: f.count }));
    }
    return categories.map((label) => ({ id: label, label }));
  }, [liveApi, categoryFacets]);

  const activeFilterCount =
    selectedPlatforms.length +
    selectedCategories.length +
    selectedCities.length +
    selectedLanguages.length +
    (verifiedOnly ? 1 : 0) +
    (followerRange[0] > 0 || followerRange[1] < 10000000 ? 1 : 0) +
    (priceRange[0] > 5000 || priceRange[1] < 200000 ? 1 : 0) +
    (engagementRange[0] > 0 || engagementRange[1] < 15 ? 1 : 0);

  // F-0259 — the first live search (nothing fetched yet) is the only state that should render
  // the loading skeleton. `apiLoadingMore` (the "Load more" pagination spinner) and mock mode
  // are both deliberately excluded: mock mode has no network round trip to wait on, and a
  // load-more in flight already has a full page of real results on screen.
  const isInitialLoading = liveApi && apiLoading && apiCreators.length === 0;

  return (
    <TooltipProvider>
      <div className="flex flex-col gap-6 p-4 md:p-6">
        {/* Discover hero — desktop only */}
        <div className="hidden lg:block -mx-4 md:-mx-6 overflow-hidden rounded-2xl border border-border h-[320px]">
          <DiscoverHero />
        </div>

        {/* Header */}
        <div className="flex flex-col gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">Discover Creators</h1>
            <p className="text-muted-foreground">
              Find verified creators across India for your next campaign
            </p>
          </div>

          {/* Source toggle — T-CREATORCONNECT-0902. Influora creators is the existing search/
              filter/grid below, completely untouched; Instagram creators is new. */}
          <Tabs value={sourceTab} onValueChange={(v) => setSourceTab(v as 'influora' | 'instagram')}>
            <TabsList>
              <TabsTrigger value="influora">Influora creators</TabsTrigger>
              <TabsTrigger value="instagram" className="gap-1.5">
                <InstagramIcon className="h-3.5 w-3.5" aria-hidden="true" />
                Instagram creators
              </TabsTrigger>
            </TabsList>

            <TabsContent value="instagram" className="pt-4">
              <InstagramCreatorsTab />
            </TabsContent>
          </Tabs>

          {/* Search and Filter Bar — Influora creators tab only. */}
          {sourceTab === 'influora' && (
          <>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                placeholder="Search by name, category, city..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-10"
              />
            </div>

            <div className="flex items-center gap-2">
              {/* Filter Sheet */}
              <Sheet open={isFilterOpen} onOpenChange={setIsFilterOpen}>
                <SheetTrigger asChild>
                  <Button variant="outline" className="gap-2">
                    <SlidersHorizontal className="h-4 w-4" />
                    Filters
                    {activeFilterCount > 0 && (
                      <Badge variant="secondary" className="ml-1 h-5 w-5 rounded-full p-0 text-xs">
                        {activeFilterCount}
                      </Badge>
                    )}
                  </Button>
                </SheetTrigger>
                <SheetContent className="w-full sm:max-w-md">
                  <SheetHeader>
                    <SheetTitle>Filter Creators</SheetTitle>
                    <SheetDescription>
                      Narrow down creators based on your requirements
                    </SheetDescription>
                  </SheetHeader>

                  <ScrollArea className="h-[calc(100vh-200px)] pr-4">
                    <div className="space-y-6 py-6">
                      {/* Platforms */}
                      <div className="space-y-3">
                        <Label className="text-sm font-medium">Platforms</Label>
                        <div className="flex flex-wrap gap-2">
                          {platforms.map((platform) => (
                            <Badge
                              key={platform}
                              variant={selectedPlatforms.includes(platform) ? 'default' : 'outline'}
                              className="cursor-pointer"
                              onClick={() => togglePlatform(platform)}
                            >
                              {platform}
                            </Badge>
                          ))}
                        </div>
                      </div>

                      <Separator />

                      {/* Categories — F-0660: real facet counts in live mode (categoryOptions
                          falls back to the hardcoded list in mock mode / before the first fetch
                          resolves). No city/language facet exists on the backend to match this
                          against — those two filters below stay hardcoded on purpose. */}
                      <div className="space-y-3">
                        <Label className="text-sm font-medium">Categories</Label>
                        <div className="flex flex-wrap gap-2">
                          {categoryOptions.map((option) => (
                            <Badge
                              key={option.id}
                              variant={selectedCategories.includes(option.id) ? 'default' : 'outline'}
                              className="cursor-pointer"
                              onClick={() => toggleCategory(option.id)}
                            >
                              {option.label}
                              {option.count != null && (
                                <span className="ml-1 text-xs opacity-70">({option.count})</span>
                              )}
                            </Badge>
                          ))}
                        </div>
                      </div>

                      <Separator />

                      {/* Cities */}
                      <div className="space-y-3">
                        <Label className="text-sm font-medium">City</Label>
                        <div className="flex flex-wrap gap-2">
                          {INDIAN_CITIES.map((city) => (
                            <Badge
                              key={city}
                              variant={selectedCities.includes(city) ? 'default' : 'outline'}
                              className="cursor-pointer"
                              onClick={() => toggleCity(city)}
                            >
                              {city}
                            </Badge>
                          ))}
                        </div>
                      </div>

                      <Separator />

                      {/* Languages */}
                      <div className="space-y-3">
                        <Label className="text-sm font-medium">Languages</Label>
                        <div className="flex flex-wrap gap-2">
                          {languages.map((language) => (
                            <Badge
                              key={language}
                              variant={selectedLanguages.includes(language) ? 'default' : 'outline'}
                              className="cursor-pointer"
                              onClick={() => toggleLanguage(language)}
                            >
                              {language}
                            </Badge>
                          ))}
                        </div>
                      </div>

                      <Separator />

                      {/* Price Range */}
                      <div className="space-y-3">
                        <div className="flex items-center justify-between">
                          <Label className="text-sm font-medium">Price Range (per post)</Label>
                          <span className="text-sm text-muted-foreground">
                            {formatINR(priceRange[0])} - {formatINR(priceRange[1])}
                          </span>
                        </div>
                        <Slider
                          value={priceRange}
                          onValueChange={(value) => setPriceRange(value as [number, number])}
                          min={5000}
                          max={200000}
                          step={5000}
                          className="py-2"
                        />
                        <div className="flex justify-between text-xs text-muted-foreground">
                          <span>₹5K</span>
                          <span>₹2L</span>
                        </div>
                      </div>

                      <Separator />

                      {/* Follower Range */}
                      <div className="space-y-3">
                        <div className="flex items-center justify-between">
                          <Label className="text-sm font-medium">Followers</Label>
                          <span className="text-sm text-muted-foreground">
                            {formatFollowers(followerRange[0])} - {formatFollowers(followerRange[1])}
                          </span>
                        </div>
                        <Slider
                          value={followerRange}
                          onValueChange={(value) => setFollowerRange(value as [number, number])}
                          min={0}
                          max={10000000}
                          step={10000}
                          className="py-2"
                        />
                        <div className="flex justify-between text-xs text-muted-foreground">
                          <span>0</span>
                          <span>1Cr</span>
                        </div>
                      </div>

                      <Separator />

                      {/* Engagement Range */}
                      <div className="space-y-3">
                        <div className="flex items-center justify-between">
                          <Label className="text-sm font-medium">Engagement Rate</Label>
                          <span className="text-sm text-muted-foreground">
                            {engagementRange[0]}% - {engagementRange[1]}%
                          </span>
                        </div>
                        <Slider
                          value={engagementRange}
                          onValueChange={(value) => setEngagementRange(value as [number, number])}
                          min={0}
                          max={15}
                          step={0.5}
                          className="py-2"
                        />
                      </div>

                      <Separator />

                      {/* Verified Only */}
                      <div className="flex items-center space-x-2">
                        <Checkbox
                          id="verified"
                          checked={verifiedOnly}
                          onCheckedChange={(checked) => setVerifiedOnly(checked as boolean)}
                        />
                        <Label htmlFor="verified" className="text-sm font-medium cursor-pointer">
                          Verified creators only
                        </Label>
                      </div>
                    </div>
                  </ScrollArea>

                  <SheetFooter className="flex-row gap-2 pt-4">
                    <Button variant="outline" onClick={clearFilters} className="flex-1">
                      Clear All
                    </Button>
                    <Button onClick={() => setIsFilterOpen(false)} className="flex-1">
                      Apply Filters
                    </Button>
                  </SheetFooter>
                </SheetContent>
              </Sheet>

              {/* Sort Dropdown */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="outline" className="gap-2">
                    Sort
                    <ChevronDown className="h-4 w-4" />
                  </Button>
                </DropdownMenuTrigger>
                {/* F-0408 — "Relevance" removed: the server has no relevance ranking, it maps
                    `relevance` onto totalFollowers DESC, i.e. the item directly below. */}
                <DropdownMenuContent align="end">
                  <DropdownMenuItem onClick={() => setSortBy('followers')}>
                    Most Followers
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setSortBy('engagement')}>
                    Highest Engagement
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setSortBy('price_low')}>
                    Price: Low to High
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setSortBy('price_high')}>
                    Price: High to Low
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>

              {/* View Toggle */}
              <div className="flex items-center rounded-lg border p-1">
                <Button
                  variant={viewMode === 'grid' ? 'secondary' : 'ghost'}
                  size="sm"
                  className="h-8 w-8 p-0"
                  onClick={() => setViewMode('grid')}
                >
                  <Grid3X3 className="h-4 w-4" />
                </Button>
                <Button
                  variant={viewMode === 'list' ? 'secondary' : 'ghost'}
                  size="sm"
                  className="h-8 w-8 p-0"
                  onClick={() => setViewMode('list')}
                >
                  <List className="h-4 w-4" />
                </Button>
              </div>
            </div>
          </div>

          {/* Active Filters Display */}
          {activeFilterCount > 0 && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm text-muted-foreground">Active filters:</span>
              {selectedPlatforms.map((platform) => (
                <Badge key={platform} variant="secondary" className="gap-1">
                  {platform}
                  <X
                    className="h-3 w-3 cursor-pointer"
                    onClick={() => togglePlatform(platform)}
                  />
                </Badge>
              ))}
              {selectedCategories.map((category) => (
                <Badge key={category} variant="secondary" className="gap-1">
                  {/* F-0660 — live mode stores the lowercase facet id (see categoryOptions);
                      title-case it back for display, same as the filter panel's own badges. */}
                  {liveApi ? titleCaseCategory(category) : category}
                  <X
                    className="h-3 w-3 cursor-pointer"
                    onClick={() => toggleCategory(category)}
                  />
                </Badge>
              ))}
              {selectedCities.map((city) => (
                <Badge key={city} variant="secondary" className="gap-1">
                  {city}
                  <X
                    className="h-3 w-3 cursor-pointer"
                    onClick={() => toggleCity(city)}
                  />
                </Badge>
              ))}
              {selectedLanguages.map((language) => (
                <Badge key={language} variant="secondary" className="gap-1">
                  {language}
                  <X
                    className="h-3 w-3 cursor-pointer"
                    onClick={() => toggleLanguage(language)}
                  />
                </Badge>
              ))}
              {verifiedOnly && (
                <Badge variant="secondary" className="gap-1">
                  Verified
                  <X
                    className="h-3 w-3 cursor-pointer"
                    onClick={() => setVerifiedOnly(false)}
                  />
                </Badge>
              )}
              {(priceRange[0] > 5000 || priceRange[1] < 200000) && (
                <Badge variant="secondary" className="gap-1">
                  {formatINR(priceRange[0])} - {formatINR(priceRange[1])}
                  <X
                    className="h-3 w-3 cursor-pointer"
                    onClick={() => setPriceRange([5000, 200000])}
                  />
                </Badge>
              )}
              <Button variant="ghost" size="sm" onClick={clearFilters} className="h-6 text-xs">
                Clear all
              </Button>
            </div>
          )}
          </>
          )}
        </div>

        {/* Featured Creators — D-14. Hidden as soon as the brand starts searching/filtering, so
            it never competes with an active query's results. Influora creators tab only. */}
        {sourceTab === 'influora' && featuredSections.length > 0 && !searchQuery && activeFilterCount === 0 && (
          <div className="flex flex-col gap-6">
            {featuredSections.map((section) => (
              <div key={section.category}>
                <h2 className="mb-3 text-sm font-medium text-muted-foreground uppercase tracking-wider">
                  {section.title}
                </h2>
                <div className="flex gap-3 overflow-x-auto pb-2">
                  {section.creators.map((sc) => (
                    <button
                      key={sc.id}
                      type="button"
                      onClick={() => navigate(`/brand/creators/${sc.id}`)}
                      className="flex w-56 shrink-0 items-center gap-3 rounded-lg border bg-card p-3 text-left transition-colors hover:bg-accent/50"
                    >
                      <Avatar className="h-10 w-10 shrink-0">
                        <AvatarImage src={sc.avatarUrl || undefined} />
                        <AvatarFallback>{sc.displayName.charAt(0)}</AvatarFallback>
                      </Avatar>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{sc.displayName}</p>
                        <p className="truncate text-xs text-muted-foreground">
                          {formatFollowers(sc.totalFollowers)} followers
                        </p>
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Results Count, Creator Grid/List, Load more — Influora creators tab only. F-0410:
            this used to read `Showing ${filteredCreators.length} creators`, which in live mode
            is the length of the current page (20 at most), not the number of creators matching
            the filters. A brand who filtered down to 20 rows out of 4,000 matches read it as
            "there are 20 such creators". Say both numbers when the server reports a total, and
            fall back to the honest page count when it doesn't. */}
        {sourceTab === 'influora' && (
        <>
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            {isInitialLoading
              ? 'Loading creators…'
              : liveApi && apiTotal != null
                ? `Showing ${filteredCreators.length} of ${apiTotal} creators`
                : `Showing ${filteredCreators.length} creators`}
          </p>
        </div>

        {/* Creator Grid/List — F-0259: three mutually-exclusive states, distinguishable from
            each other. A blank grid used to mean both "still loading" and "zero results" —
            nothing told the brand which one they were looking at. An API failure is already
            handled separately (see fetchCreators' catch → toast + apiCreators cleared to []),
            so it lands in the empty branch below with a toast still visible, rather than a
            fourth silent state. */}
        {isInitialLoading ? (
          <div
            data-testid="discover-loading"
            className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
            aria-busy="true"
            aria-live="polite"
          >
            {Array.from({ length: 8 }).map((_, i) => (
              <Card key={i} className="overflow-hidden">
                <div className="h-24 animate-pulse bg-muted" />
                <CardContent className="space-y-3 pt-10">
                  <div className="h-4 w-2/3 animate-pulse rounded bg-muted" />
                  <div className="h-3 w-1/2 animate-pulse rounded bg-muted" />
                  <div className="h-3 w-full animate-pulse rounded bg-muted" />
                  <div className="h-16 w-full animate-pulse rounded bg-muted" />
                </CardContent>
              </Card>
            ))}
          </div>
        ) : filteredCreators.length === 0 ? (
          <div
            data-testid="discover-empty"
            className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed py-16 text-center"
          >
            <p className="text-sm font-medium">No creators match your filters</p>
            <p className="text-sm text-muted-foreground">
              Try widening your search or clearing a few filters.
            </p>
            {activeFilterCount > 0 && (
              <Button variant="outline" size="sm" onClick={clearFilters}>
                Clear all filters
              </Button>
            )}
          </div>
        ) : viewMode === 'grid' ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {filteredCreators.map((creator) => (
              <Card key={creator.id} className="group overflow-hidden transition-all hover:shadow-lg">
                {/* Cover/Header */}
                <div className="relative h-24 bg-gradient-to-br from-primary/20 to-primary/5">
                  <div className="absolute -bottom-8 left-4">
                    <Avatar className="h-16 w-16 border-4 border-background">
                      <AvatarImage src={creator.avatarUrl || undefined} />
                      <AvatarFallback className="text-lg bg-primary/10">
                        {creator.displayName.charAt(0)}
                      </AvatarFallback>
                    </Avatar>
                  </div>
                  <div className="absolute right-2 top-2 flex gap-1">
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <Button
                          variant="secondary"
                          size="icon"
                          className="h-8 w-8"
                          onClick={() => toggleSaved(creator.id)}
                        >
                          {savedCreators.includes(creator.id) ? (
                            <BookmarkCheck className="h-4 w-4 text-primary" />
                          ) : (
                            <Bookmark className="h-4 w-4" />
                          )}
                        </Button>
                      </TooltipTrigger>
                      <TooltipContent>
                        {savedCreators.includes(creator.id) ? 'Remove from saved' : 'Save creator'}
                      </TooltipContent>
                    </Tooltip>
                  </div>
                </div>

                <CardContent className="pt-10">
                  {/* Name and Verification */}
                  <div className="flex items-center gap-1.5">
                    <h3 className="font-semibold truncate">{creator.displayName}</h3>
                    {creator.isVerified && (
                      <CheckCircle2 className="h-4 w-4 flex-shrink-0 text-primary" />
                    )}
                  </div>

                  {/* Location */}
                  <div className="mt-1 flex items-center gap-1 text-sm text-muted-foreground">
                    <MapPin className="h-3 w-3" />
                    <span className="truncate">{creator.location}</span>
                  </div>

                  {/* Bio */}
                  <p className="mt-2 line-clamp-2 text-sm text-muted-foreground">
                    {creator.bio}
                  </p>

                  {/* Categories */}
                  <div className="mt-3 flex flex-wrap gap-1">
                    {creator.categories.slice(0, 3).map((cat) => (
                      <Badge key={cat} variant="secondary" className="text-xs">
                        {cat}
                      </Badge>
                    ))}
                  </div>

                  {/* BR-18 score badges — only meaningful in live mode (mock creators carry no
                      `scores` field, so both render as "Not yet scored", which is honest). */}
                  <div className="mt-2 flex flex-wrap gap-1">
                    <ScoreBadge label="Quality" value={creator.scores?.quality} />
                    <ScoreBadge label="Authenticity" value={creator.scores?.authenticity} />
                    <ScoreBadge label="Brand safety" value={creator.scores?.brandSafety} />
                  </div>

                  {/* Stats */}
                  <div className="mt-4 grid grid-cols-3 gap-2 text-center">
                    <div className="rounded-lg bg-muted/50 p-2">
                      <p className="text-sm font-semibold">{formatFollowers(creator.totalFollowers)}</p>
                      <p className="text-xs text-muted-foreground">Followers</p>
                    </div>
                    <div className="rounded-lg bg-muted/50 p-2">
                      <p className="text-sm font-semibold">{creator.engagementRate}%</p>
                      <p className="text-xs text-muted-foreground">Engagement</p>
                    </div>
                    <div className="rounded-lg bg-muted/50 p-2">
                      <p className="text-sm font-semibold">{formatINR(creator.averageRate ?? 0)}</p>
                      <p className="text-xs text-muted-foreground">Avg Rate</p>
                    </div>
                  </div>

                  {/* Platforms */}
                  <div className="mt-3 flex gap-1.5">
                    {creator.platforms.map((p) => (
                      <Tooltip key={p.platform}>
                        <TooltipTrigger>
                          <Badge variant="outline" className="text-xs">
                            {p.platform === 'INSTAGRAM' && 'IG'}
                            {p.platform === 'YOUTUBE' && 'YT'}
                            {p.platform === 'TIKTOK' && 'TT'}
                            {p.platform === 'TWITTER' && 'X'}
                          </Badge>
                        </TooltipTrigger>
                        <TooltipContent>
                          {p.platform}: {formatFollowers(p.followers)} followers, {p.engagementRate}% ER
                        </TooltipContent>
                      </Tooltip>
                    ))}
                  </div>
                </CardContent>

                <CardFooter className="flex gap-2 border-t pt-4">
                  <Button variant="outline" size="sm" className="flex-1" asChild>
                    <Link to={`/brand/creators/${creator.id}`}>
                      View Profile
                    </Link>
                  </Button>
                  <Button size="sm" className="flex-1 gap-1.5" onClick={() => handleOpenInvite(creator)}>
                    <Send className="h-3.5 w-3.5" />
                    Invite
                  </Button>
                </CardFooter>
              </Card>
            ))}
          </div>
        ) : (
          <Card>
            <div className="divide-y">
              {filteredCreators.map((creator) => (
                <div key={creator.id} className="flex items-center gap-4 p-4">
                  <Avatar className="h-12 w-12">
                    <AvatarImage src={creator.avatarUrl || undefined} />
                    <AvatarFallback>{creator.displayName.charAt(0)}</AvatarFallback>
                  </Avatar>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-1.5">
                      <h3 className="font-semibold truncate">{creator.displayName}</h3>
                      {creator.isVerified && (
                        <CheckCircle2 className="h-4 w-4 flex-shrink-0 text-primary" />
                      )}
                    </div>
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                      <span className="flex items-center gap-1">
                        <MapPin className="h-3 w-3" />
                        {creator.location}
                      </span>
                      <span>|</span>
                      <span>{creator.categories.slice(0, 2).join(', ')}</span>
                    </div>
                  </div>

                  <div className="hidden md:flex items-center gap-6 text-sm">
                    <div className="text-center">
                      <p className="font-semibold">{formatFollowers(creator.totalFollowers)}</p>
                      <p className="text-xs text-muted-foreground">Followers</p>
                    </div>
                    <div className="text-center">
                      <p className="font-semibold">{creator.engagementRate}%</p>
                      <p className="text-xs text-muted-foreground">ER</p>
                    </div>
                    <div className="text-center">
                      <p className="font-semibold">{formatINR(creator.averageRate ?? 0)}</p>
                      <p className="text-xs text-muted-foreground">Rate</p>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => toggleSaved(creator.id)}
                    >
                      {savedCreators.includes(creator.id) ? (
                        <BookmarkCheck className="h-4 w-4 text-primary" />
                      ) : (
                        <Bookmark className="h-4 w-4" />
                      )}
                    </Button>
                    <Button variant="outline" size="sm" asChild>
                      <Link to={`/brand/creators/${creator.id}`}>View</Link>
                    </Button>
                    <Button size="sm" className="gap-1.5" onClick={() => handleOpenInvite(creator)}>
                      <Send className="h-3.5 w-3.5" />
                      Invite
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        )}

        {/* Load more — server-side pagination (GET /creators?page=&limit=), live mode only.
            Mock mode's `filteredCreators` is the full local fixture, already unpaginated. */}
        {liveApi && apiHasMore && !apiLoading && (
          <div className="flex justify-center pt-2">
            <Button
              variant="outline"
              disabled={apiLoadingMore}
              onClick={() => void fetchCreators(apiPage + 1, true)}
            >
              {apiLoadingMore ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Loading...
                </>
              ) : (
                'Load more'
              )}
            </Button>
          </div>
        )}
        </>
        )}

        {/* Invite Dialog - Multi-Step Proposal */}
        <Dialog open={isInviteOpen} onOpenChange={setIsInviteOpen}>
          <DialogContent className="sm:max-w-lg">
            <DialogHeader>
              <DialogTitle>
                {proposalStep === 'campaign' && `Invite ${inviteCreator?.displayName}`}
                {proposalStep === 'proposal' && 'Create Proposal'}
                {proposalStep === 'confirm' && 'Review & Send'}
              </DialogTitle>
              <DialogDescription>
                {proposalStep === 'campaign' && 'Select a campaign to invite this creator to collaborate.'}
                {proposalStep === 'proposal' && 'Define the deliverables, budget, and terms for this collaboration.'}
                {proposalStep === 'confirm' && 'Review your proposal before sending to the creator.'}
              </DialogDescription>
            </DialogHeader>

            {/* Step Indicator */}
            {!createNewCampaign && (
              <div className="flex items-center justify-center gap-2 py-2">
                {['campaign', 'proposal', 'confirm'].map((step, idx) => (
                  <React.Fragment key={step}>
                    <div className={cn(
                      'h-8 w-8 rounded-full flex items-center justify-center text-sm font-medium transition-colors',
                      proposalStep === step ? 'bg-primary text-primary-foreground' :
                      ['campaign', 'proposal', 'confirm'].indexOf(proposalStep) > idx ? 'bg-stage-approved text-stage-approved-fg' :
                      'bg-muted text-muted-foreground'
                    )}>
                      {idx + 1}
                    </div>
                    {idx < 2 && <div className="w-8 h-0.5 bg-muted" />}
                  </React.Fragment>
                ))}
              </div>
            )}

            <div className="space-y-4 py-4 max-h-[60vh] overflow-y-auto">
              {/* Step 1: Campaign Selection */}
              {proposalStep === 'campaign' && (
                <>
                  <div className="space-y-3">
                    <Label>Select Campaign</Label>
                    <Select 
                      value={createNewCampaign ? 'new' : selectedCampaign} 
                      onValueChange={(value) => {
                        if (value === 'new') {
                          setCreateNewCampaign(true);
                          setSelectedCampaign('');
                        } else {
                          setCreateNewCampaign(false);
                          setSelectedCampaign(value);
                        }
                      }}
                    >
                      {/* F-0256 — disabled while campaignsLoading so the brand cannot pick a
                          campaign (real or otherwise) before GET /campaigns has resolved. */}
                      <SelectTrigger disabled={campaignsLoading}>
                        <SelectValue
                          placeholder={campaignsLoading ? 'Loading campaigns…' : 'Choose a campaign'}
                        />
                      </SelectTrigger>
                      <SelectContent>
                        {inviteCampaigns.filter(c => c.status === 'ACTIVE').map((campaign) => (
                          <SelectItem key={campaign.id} value={campaign.id}>
                            <div className="flex items-center gap-2">
                              <span>{campaign.name}</span>
                              <Badge variant="outline" className="text-xs">Active</Badge>
                            </div>
                          </SelectItem>
                        ))}
                        <SelectItem value="new">
                          <div className="flex items-center gap-2 text-primary">
                            <Plus className="h-4 w-4" />
                            <span>Create New Campaign</span>
                          </div>
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  {createNewCampaign && (
                    <div className="rounded-lg border border-primary/20 bg-primary/5 p-4">
                      {/* F-0257 — this copy used to claim the creator would already be chosen
                          for the brand on arrival. That landing page
                          (src/pages/brand-new-campaign.tsx, owned by another producer, out of
                          scope here) never reads a creator id off the URL, so the claim was
                          never true and the creator was silently dropped. Say what actually
                          happens instead of a handoff this component can't deliver; `navigate`
                          still sends `?creator=` (see handleInvite) so whoever fixes the
                          destination has it ready to read. */}
                      <p className="text-sm text-muted-foreground">
                        You&apos;ll be redirected to create a new campaign. {inviteCreator?.displayName} won&apos;t
                        be added automatically — invite them from Discover again once the campaign is live.
                      </p>
                    </div>
                  )}
                </>
              )}

              {/* Step 2: Proposal Details */}
              {proposalStep === 'proposal' && (
                <>
                  {/* Deliverables */}
                  <div className="space-y-3">
                    <Label>Deliverables</Label>
                    <div className="space-y-2">
                      {proposalData.deliverables.map((del, idx) => (
                        <div key={idx} className="flex items-center gap-2">
                          <Select 
                            value={del.type}
                            onValueChange={(value) => {
                              const updated = [...proposalData.deliverables];
                              updated[idx].type = value;
                              setProposalData({ ...proposalData, deliverables: updated });
                            }}
                          >
                            <SelectTrigger className="w-32">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="REEL">Reel</SelectItem>
                              <SelectItem value="POST">Post</SelectItem>
                              <SelectItem value="STORY">Story</SelectItem>
                              <SelectItem value="VIDEO">Video</SelectItem>
                              <SelectItem value="SHORT">Short</SelectItem>
                            </SelectContent>
                          </Select>
                          <span className="text-muted-foreground">x</span>
                          <Input 
                            type="number" 
                            value={del.count}
                            onChange={(e) => {
                              const updated = [...proposalData.deliverables];
                              updated[idx].count = parseInt(e.target.value) || 1;
                              setProposalData({ ...proposalData, deliverables: updated });
                            }}
                            className="w-16"
                            min={1}
                          />
                          {proposalData.deliverables.length > 1 && (
                            <Button 
                              variant="ghost" 
                              size="sm"
                              onClick={() => {
                                const updated = proposalData.deliverables.filter((_, i) => i !== idx);
                                setProposalData({ ...proposalData, deliverables: updated });
                              }}
                            >
                              <X className="h-4 w-4" />
                            </Button>
                          )}
                        </div>
                      ))}
                      <Button 
                        variant="outline" 
                        size="sm"
                        onClick={() => setProposalData({
                          ...proposalData,
                          deliverables: [...proposalData.deliverables, { type: 'POST', count: 1 }]
                        })}
                      >
                        <Plus className="h-4 w-4 mr-1" /> Add Deliverable
                      </Button>
                    </div>
                  </div>

                  {/* Budget */}
                  <div className="space-y-2">
                    <Label>Budget Offer</Label>
                    <div className="flex items-center gap-2">
                      <span className="text-muted-foreground">₹</span>
                      <Input 
                        type="number"
                        value={proposalData.budget}
                        onChange={(e) => setProposalData({ ...proposalData, budget: parseInt(e.target.value) || 0 })}
                        className="flex-1"
                      />
                    </div>
                    <p className="text-xs text-muted-foreground">
                      Creator&apos;s avg rate: {formatINR(inviteCreator?.averageRate || 0)}
                    </p>
                  </div>

                  {/* Deadline */}
                  <div className="space-y-2">
                    <Label>Deadline</Label>
                    <Input 
                      type="date"
                      value={proposalData.deadline}
                      onChange={(e) => setProposalData({ ...proposalData, deadline: e.target.value })}
                    />
                  </div>

                  {/* Usage Rights */}
                  <div className="space-y-2">
                    <Label>Usage Rights</Label>
                    <Select 
                      value={proposalData.usageRights}
                      onValueChange={(value) => setProposalData({ ...proposalData, usageRights: value })}
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="1_MONTH">1 Month</SelectItem>
                        <SelectItem value="3_MONTHS">3 Months</SelectItem>
                        <SelectItem value="6_MONTHS">6 Months</SelectItem>
                        <SelectItem value="1_YEAR">1 Year</SelectItem>
                        <SelectItem value="PERPETUAL">Perpetual</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  {/* Removed 2026-07-26 (CEO call): "Revisions Included" and "Require
                      exclusivity". Both were live controls the brand could set that reached
                      nothing — POST /deals discarded `exclusivity` server-side (it appeared in
                      exactly one place in the backend, the DTO field) and never accepted a
                      revision cap at all. A brand ticking "no competitor collabs" and believing
                      it bought exclusivity is a dispute we would lose. They return as real
                      contract clauses, with an enforcement window and breach handling, after
                      escrow/Route ships. */}

                  {/* Personal Message */}
                  <div className="space-y-2">
                    <Label>Personal Message (Optional)</Label>
                    <Textarea
                      placeholder="Write a personalized message to the creator..."
                      value={inviteMessage}
                      onChange={(e) => setInviteMessage(e.target.value)}
                      rows={3}
                    />
                  </div>
                </>
              )}

              {/* Step 3: Confirmation */}
              {proposalStep === 'confirm' && (
                <div className="space-y-4">
                  <div className="rounded-lg border p-4 space-y-3">
                    <div className="flex items-center gap-3">
                      <Avatar className="h-12 w-12">
                        <AvatarFallback>{inviteCreator?.displayName?.charAt(0)}</AvatarFallback>
                      </Avatar>
                      <div>
                        <p className="font-medium">{inviteCreator?.displayName}</p>
                        <p className="text-sm text-muted-foreground">{inviteCreator?.location}</p>
                      </div>
                    </div>
                    <Separator />
                    <div className="grid grid-cols-2 gap-3 text-sm">
                      <div>
                        <p className="text-muted-foreground">Deliverables</p>
                        <p className="font-medium">
                          {proposalData.deliverables.map(d => `${d.count} ${d.type}`).join(', ')}
                        </p>
                      </div>
                      <div>
                        <p className="text-muted-foreground">Budget</p>
                        <p className="font-medium">{formatINR(proposalData.budget)}</p>
                      </div>
                      <div>
                        <p className="text-muted-foreground">Deadline</p>
                        <p className="font-medium">{proposalData.deadline || 'Not set'}</p>
                      </div>
                      <div>
                        <p className="text-muted-foreground">Usage Rights</p>
                        <p className="font-medium">{proposalData.usageRights.replace('_', ' ')}</p>
                      </div>
                    </div>
                    {inviteMessage && (
                      <>
                        <Separator />
                        <div>
                          <p className="text-muted-foreground text-sm">Your Message</p>
                          <p className="text-sm mt-1">{inviteMessage}</p>
                        </div>
                      </>
                    )}
                  </div>
                  <div className="rounded-lg bg-amber-50 border border-stage-negotiating-border p-3">
                    <p className="text-sm text-amber-800">
                      This proposal will be sent to the creator&apos;s Deal Room. They can accept, counter, or decline.
                    </p>
                  </div>
                </div>
              )}
            </div>

            <DialogFooter className="flex-col sm:flex-row gap-2">
              {proposalStep !== 'campaign' && !createNewCampaign && (
                <Button 
                  variant="outline" 
                  onClick={() => setProposalStep(proposalStep === 'confirm' ? 'proposal' : 'campaign')}
                >
                  Back
                </Button>
              )}
              <Button variant="outline" onClick={() => setIsInviteOpen(false)}>
                Cancel
              </Button>
              <Button 
                onClick={handleInvite} 
                disabled={
                  (!selectedCampaign && !createNewCampaign) ||
                  isSubmitting ||
                  (proposalStep === 'campaign' && campaignsLoading)
                }
              >
                {isSubmitting ? 'Sending...' : 
                 createNewCampaign ? 'Create Campaign' : 
                 proposalStep === 'campaign' ? 'Next: Define Proposal' :
                 proposalStep === 'proposal' ? 'Next: Review' :
                 'Send Proposal'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </TooltipProvider>
  );
}

// =============================================================================
// Instagram creators tab — T-CREATORCONNECT-0902.
//
// Meta-sourced creators (Business Discovery lookup + the admin-imported/Marketplace table) who
// are NOT yet Influora members. Self-contained: its own state, its own fetches, mounted fresh by
// Radix Tabs each time the brand selects this tab. Never invents a creator or coerces a missing
// metric to 0 — a 503 (Meta off / no usable token) renders an honest "unavailable" state with no
// cards, distinct from "loading" and from "zero results" (F-0259/F-0260).
// =============================================================================

const IG_PAGE_SIZE = 20;

/** JOINED → green "Verified with Influora"; INVITED → amber "Invited to Influora" with the date
 * (Q2.5 — split out of the generic UNVERIFIED copy so an admin's outreach is visible); UNVERIFIED
 * → amber "Unverified with Influora". */
function ExternalCreatorBadge({
  status,
  invitedAt,
}: {
  status: ExternalCreator['status'];
  invitedAt?: string | null;
}) {
  if (status === 'JOINED') {
    return (
      <Badge className="gap-1 border-transparent bg-stage-approved text-stage-approved-fg hover:bg-stage-approved">
        <ShieldCheck className="h-3 w-3" aria-hidden="true" />
        Verified with Influora
      </Badge>
    );
  }
  if (status === 'INVITED') {
    return (
      <Badge className="gap-1 border-transparent bg-stage-negotiating text-stage-negotiating-fg hover:bg-stage-negotiating">
        <ShieldAlert className="h-3 w-3" aria-hidden="true" />
        Invited to Influora{invitedAt ? ` · ${formatDistanceToNow(new Date(invitedAt), { addSuffix: true })}` : ''}
      </Badge>
    );
  }
  return (
    <Badge className="gap-1 border-transparent bg-stage-negotiating text-stage-negotiating-fg hover:bg-stage-negotiating">
      <ShieldAlert className="h-3 w-3" aria-hidden="true" />
      Unverified with Influora
    </Badge>
  );
}

function externalFollowers(n: number | null): string {
  return n == null ? '—' : formatFollowers(n);
}

/** Q2.5/formerly-relative-date helper — used by the connection-requests panel to render
 * createdAt/handledAt so a brand can tell a 3-week-stale request from yesterday's. */
function relativeDate(iso: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return formatDistanceToNow(d, { addSuffix: true });
}

function connectionRequestStatusLabel(status: ConnectionRequest['status']): string {
  switch (status) {
    case 'PENDING':
      return 'Request sent';
    case 'CONTACTED':
      return 'Team reached out';
    case 'JOINED':
      return 'Joined Influora';
    case 'DECLINED':
    default:
      return 'Not available';
  }
}

/** One external-creator card — used both for the lookup result and the browse list. */
function ExternalCreatorCard({
  creator,
  onConnect,
}: {
  creator: ExternalCreator;
  onConnect: (creator: ExternalCreator) => void;
}) {
  // Q2.2 — trust the server's own computed flag (status === 'JOINED' && linkedCreatorProfileId
  // != null) rather than OR-ing in a redundant client-side status check. The OR admitted a
  // JOINED-but-unlinked state the server flag deliberately excludes, which the "Create campaign"
  // button already guarded against but "View profile" did not — dropping the OR removes the
  // inconsistency at its source instead of patching the symptom.
  const verified = creator.verifiedWithInfluora;
  // Q6.5 — every upstream surface (this card, the connect dialog, the toasts below) refers to
  // this creator as `@igUsername`; the handoff must carry that same handle rather than let the
  // campaign wizard resolve and show a possibly-different Influora username.
  const handoffParams = new URLSearchParams({ creatorId: creator.linkedCreatorProfileId ?? '' });
  handoffParams.set('ig', creator.igUsername);
  if (creator.connectionRequestId) handoffParams.set('crq', creator.connectionRequestId);

  return (
    <Card className="overflow-hidden">
      <CardContent className="flex flex-col gap-3 pt-5">
        <div className="flex items-start gap-3">
          <Avatar className="h-12 w-12 shrink-0">
            <AvatarImage src={creator.avatarUrl || undefined} />
            <AvatarFallback>{creator.igUsername.charAt(0).toUpperCase()}</AvatarFallback>
          </Avatar>
          <div className="min-w-0 flex-1">
            <p className="truncate font-semibold">@{creator.igUsername}</p>
            {creator.displayName && (
              <p className="truncate text-sm text-muted-foreground">{creator.displayName}</p>
            )}
          </div>
        </div>

        <ExternalCreatorBadge status={creator.status} invitedAt={creator.invitedAt} />

        {creator.bio && <p className="line-clamp-2 text-sm text-muted-foreground">{creator.bio}</p>}

        {/* Q2.1 — engagementRate is write-never on every creation path (Marketplace/Business
            Discovery/admin import all pass null), so this tile could only ever render "—". Dropped
            rather than shipped as permanently dead weight; re-add alongside a real writer (e.g. a
            future Marketplace `insights` mapping) if one lands. */}
        <div className="grid grid-cols-2 gap-2 text-center">
          <div className="rounded-lg bg-muted/50 p-2">
            <p className="text-sm font-semibold">{externalFollowers(creator.followers)}</p>
            <p className="text-xs text-muted-foreground">Followers</p>
          </div>
          <div className="rounded-lg bg-muted/50 p-2">
            <p className="truncate text-sm font-semibold">{creator.country ?? '—'}</p>
            <p className="text-xs text-muted-foreground">Country</p>
          </div>
        </div>
      </CardContent>
      <CardFooter className="flex gap-2 border-t pt-4">
        {verified ? (
          <>
            <Button variant="outline" size="sm" className="flex-1" asChild>
              <Link to={`/brand/creators/${creator.linkedCreatorProfileId ?? ''}`}>View profile</Link>
            </Button>
            <Button size="sm" className="flex-1" disabled={!creator.linkedCreatorProfileId} asChild={Boolean(creator.linkedCreatorProfileId)}>
              {creator.linkedCreatorProfileId ? (
                <Link to={`/brand/campaigns/new?${handoffParams.toString()}`}>Create campaign</Link>
              ) : (
                <span>Create campaign</span>
              )}
            </Button>
          </>
        ) : creator.connectionStatus == null ? (
          <Button size="sm" className="flex-1 gap-1.5" onClick={() => onConnect(creator)}>
            <AtSign className="h-3.5 w-3.5" aria-hidden="true" />
            Connect this creator
          </Button>
        ) : creator.connectionStatus === 'DECLINED' ? (
          <Button size="sm" variant="ghost" className="flex-1 text-muted-foreground" disabled>
            Not available
          </Button>
        ) : (
          <Button size="sm" variant="secondary" className="flex-1" disabled>
            {connectionRequestStatusLabel(creator.connectionStatus)}
          </Button>
        )}
      </CardFooter>
    </Card>
  );
}

function InstagramCreatorsTab() {
  const { toast } = useToast();
  const liveApi = isApiLive();

  // Handle lookup (GET /creators/external/lookup)
  const [lookupInput, setLookupInput] = React.useState('');
  const [lookupResult, setLookupResult] = React.useState<ExternalCreator | null>(null);
  const [lookupLoading, setLookupLoading] = React.useState(false);
  const [lookupError, setLookupError] = React.useState<{ code: string; message: string } | null>(null);

  // Browse list (GET /creators/external)
  const [listCreators, setListCreators] = React.useState<ExternalCreator[]>([]);
  const [listLoading, setListLoading] = React.useState(false);
  const [listLoadingMore, setListLoadingMore] = React.useState(false);
  const [listPage, setListPage] = React.useState(1);
  const [listHasMore, setListHasMore] = React.useState(false);
  // Q1.5 (T-CREATORCONNECT-0902) — the browse list shipped with no way to narrow it (contract
  // TASKS.md §157: "the existing follower filters where they apply"); the backend half
  // (ExternalCreatorSpecs) already ORs a NULL followers value into every follower predicate so an
  // un-enriched row is never hidden by these filters — see externalFollowers below for the
  // matching FE display convention (never coerce an unknown follower count to 0).
  const [listQuery, setListQuery] = React.useState('');
  const [listFollowerRange, setListFollowerRange] = React.useState<[number, number]>([0, 10000000]);
  const [listUnavailable, setListUnavailable] = React.useState(false);
  // Q2.4 — distinct from listUnavailable: a real outage (502/503/504/SERVER_UNAVAILABLE, or a
  // rejected fetch that never reached an ApiError at all) must render an error state with Retry,
  // never the honest "no data yet" empty copy — see showEmpty below.
  const [listFailed, setListFailed] = React.useState(false);
  const [listError, setListError] = React.useState<string | null>(null);

  // My connection requests (GET /creators/external/connection-requests)
  const [requests, setRequests] = React.useState<ConnectionRequest[]>([]);
  const [requestsLoading, setRequestsLoading] = React.useState(false);

  // Connect dialog
  const [connectTarget, setConnectTarget] = React.useState<ExternalCreator | null>(null);
  const [connectMessage, setConnectMessage] = React.useState('');
  const [connectSubmitting, setConnectSubmitting] = React.useState(false);

  const fetchList = React.useCallback(
    async (
      pageNum: number,
      append: boolean,
      // Q1.5 — explicit overrides for the follower range only: the Slider's onValueCommit fires
      // with the just-committed value in the SAME gesture that also calls setListFollowerRange,
      // so reading listFollowerRange from this closure risks acting on the previous render's
      // value. `q` has no such race (the search form's onSubmit runs after the controlled
      // input's own onChange already committed listQuery) so it always reads current state.
      followerOverride?: { minFollowers?: number; maxFollowers?: number },
    ) => {
      if (append) setListLoadingMore(true);
      else setListLoading(true);
      setListUnavailable(false);
      setListFailed(false);
      if (!append) setListError(null);
      try {
        const trimmedQuery = listQuery.trim();
        const result = await api.externalCreators.list({
          page: pageNum,
          limit: IG_PAGE_SIZE,
          q: trimmedQuery || undefined,
          minFollowers:
            followerOverride && 'minFollowers' in followerOverride
              ? followerOverride.minFollowers
              : listFollowerRange[0] > 0
                ? listFollowerRange[0]
                : undefined,
          maxFollowers:
            followerOverride && 'maxFollowers' in followerOverride
              ? followerOverride.maxFollowers
              : listFollowerRange[1] < 10000000
                ? listFollowerRange[1]
                : undefined,
        });
        setListCreators((prev) => (append ? [...prev, ...result.creators] : result.creators));
        setListHasMore(result.meta.hasMore);
        setListPage(pageNum);
      } catch (err) {
        if (!append) setListCreators([]);
        // Q2.4 — GET /creators/external never actually returns the honest business-logic 503
        // (`INSTAGRAM_LOOKUP_UNAVAILABLE`; that's `lookup()`'s error, not list()'s), so the ONLY
        // way this call ever reports 503 is via `parseEnvelope`'s outage detection — the same
        // 502/504/`SERVER_UNAVAILABLE` bucket. That bucket, and a network error that never
        // produced an ApiError at all (a rejected fetch — dropped connection, CORS failure,
        // offline), both mean "the server could not be reached", never "there is no data" —
        // rendering the honest-empty state for either was mistaking an outage for a fact about
        // the data (F-0259/F-0260 class). Reserve `listUnavailable` for a genuine
        // `INSTAGRAM_LOOKUP_UNAVAILABLE` code, defensively, in case a future change ever does
        // route it through this call.
        if (err instanceof ApiError && err.code === 'INSTAGRAM_LOOKUP_UNAVAILABLE') {
          setListUnavailable(true);
        } else {
          const message =
            err instanceof ApiError ? err.message : 'The server was briefly unavailable. Please try again.';
          setListFailed(true);
          setListError(message);
          toast({
            title: append ? "Couldn't load more creators" : "Couldn't load Instagram creators",
            description: message,
            variant: 'destructive',
          });
        }
      } finally {
        setListLoading(false);
        setListLoadingMore(false);
      }
    },
    [toast, listQuery, listFollowerRange],
  );

  const fetchRequests = React.useCallback(async () => {
    setRequestsLoading(true);
    try {
      const rows = await api.externalCreators.connectionRequests();
      setRequests(rows);
    } catch {
      setRequests([]);
    } finally {
      setRequestsLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (!liveApi) return;
    void fetchList(1, false);
    void fetchRequests();
    // Fetch once on mount — this component remounts fresh every time the tab is selected
    // (Radix Tabs unmounts inactive content), so no dependency-driven refetch is needed here.
    // `fetchList`/`fetchRequests` deliberately omitted from deps (react-hooks v7 policy —
    // exhaustive-deps is 'warn', not fixed here; same as the pre-existing warnings elsewhere).
  }, [liveApi]);

  async function handleLookup(e: React.FormEvent) {
    e.preventDefault();
    const username = lookupInput.trim().replace(/^@/, '');
    if (!username) return;
    setLookupLoading(true);
    setLookupError(null);
    setLookupResult(null);
    try {
      const creator = await api.externalCreators.lookup(username);
      setLookupResult(creator);
    } catch (err) {
      if (err instanceof ApiError) {
        setLookupError({ code: err.code, message: err.message });
      } else {
        setLookupError({ code: 'UNKNOWN', message: 'Could not look up that handle. Try again.' });
      }
    } finally {
      setLookupLoading(false);
    }
  }

  function openConnect(creator: ExternalCreator) {
    setConnectTarget(creator);
    setConnectMessage('');
  }

  function applyConnectionResult(creatorId: string, request: ConnectionRequest) {
    const patch = (c: ExternalCreator) =>
      c.id === creatorId
        ? { ...c, connectionStatus: request.status, connectionRequestId: request.id }
        : c;
    setListCreators((prev) => prev.map(patch));
    setLookupResult((prev) => (prev ? patch(prev) : prev));
  }

  async function submitConnect() {
    if (!connectTarget) return;
    setConnectSubmitting(true);
    try {
      const request = await api.externalCreators.connect(connectTarget.id, {
        message: connectMessage.trim() || undefined,
      });
      applyConnectionResult(connectTarget.id, request);
      setRequests((prev) => [request, ...prev.filter((r) => r.id !== request.id)]);
      toast({
        title: 'Request sent',
        description: `The Influora team will reach out to @${connectTarget.igUsername} and email you when they join.`,
      });
      setConnectTarget(null);
      setConnectMessage('');
    } catch (err) {
      // Q2.3 — the 409 carries linkedCreatorProfileId (ApiErrorBody.creatorAlreadyOnInfluora),
      // now surfaced on ApiError. Rather than a dead-end generic toast, flip the card to JOINED
      // right here so its own "Create campaign" button becomes available immediately — no refetch
      // needed, and no more disagreement between the toast and what the card still offers.
      if (err instanceof ApiError && err.code === 'CREATOR_ALREADY_ON_INFLUORA' && err.linkedCreatorProfileId) {
        const linkedId = err.linkedCreatorProfileId;
        const patch = (c: ExternalCreator) =>
          c.id === connectTarget.id
            ? { ...c, status: 'JOINED' as const, verifiedWithInfluora: true, linkedCreatorProfileId: linkedId }
            : c;
        setListCreators((prev) => prev.map(patch));
        setLookupResult((prev) => (prev ? patch(prev) : prev));
        toast({
          title: 'Already on Influora',
          description: `@${connectTarget.igUsername} is already verified on Influora — use Create campaign on their card to work with them.`,
        });
        setConnectTarget(null);
        setConnectMessage('');
        return;
      }
      toast({
        title: 'Could not send request',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setConnectSubmitting(false);
    }
  }

  const showUnavailable = listUnavailable && listCreators.length === 0;
  // Q2.4 — a failed load must render its own error+Retry state, in front of loading/empty, and
  // showEmpty must exclude it: a first-load outage is not the same fact as "no creators sourced
  // yet" and must never render as if it were.
  const showFailed = listFailed && listCreators.length === 0;
  const showInitialLoading = listLoading && listCreators.length === 0 && !showUnavailable && !showFailed;
  const showEmpty = !showInitialLoading && !showUnavailable && !showFailed && listCreators.length === 0;

  return (
    <div className="flex flex-col gap-6">
      {/* Handle lookup */}
      <form onSubmit={handleLookup} className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <AtSign className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search an Instagram handle, e.g. @foodie.mumbai"
            value={lookupInput}
            onChange={(e) => setLookupInput(e.target.value)}
            className="pl-10"
          />
        </div>
        <Button type="submit" disabled={lookupLoading || !lookupInput.trim()}>
          {lookupLoading ? <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" /> : null}
          Look up
        </Button>
      </form>

      {lookupError && (
        <div
          data-testid={lookupError.code === 'INSTAGRAM_LOOKUP_UNAVAILABLE' ? 'instagram-lookup-unavailable' : undefined}
          className="rounded-lg border border-dashed p-4 text-sm text-destructive-foreground"
        >
          {lookupError.code === 'INSTAGRAM_LOOKUP_UNAVAILABLE'
            ? "Instagram lookup isn't connected yet. Try browsing the list below, or ask an admin to import this handle."
            : lookupError.message}
        </div>
      )}

      {lookupResult && (
        <div className="max-w-sm">
          <ExternalCreatorCard creator={lookupResult} onConnect={openConnect} />
        </div>
      )}

      {/* My connection requests */}
      {requests.length > 0 && (
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="mb-3 flex items-center gap-2">
            <Inbox className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
            <h2 className="text-sm font-semibold">My connection requests</h2>
          </div>
          <div className="flex flex-col divide-y">
            {requests.map((r) => (
              <div key={r.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                <div className="min-w-0">
                  <p className="truncate font-medium">@{r.igUsername}</p>
                  {/* Q2.5 — createdAt/handledAt were on the wire and unused; a brand had no way
                      to tell a 3-week-stale CONTACTED from yesterday's. */}
                  <p className="truncate text-xs text-muted-foreground">
                    Sent {relativeDate(r.createdAt)}
                    {r.handledAt ? ` · Updated ${relativeDate(r.handledAt)}` : ''}
                  </p>
                </div>
                <Badge variant="outline" className="shrink-0 text-xs">
                  {connectionRequestStatusLabel(r.status)}
                </Badge>
              </div>
            ))}
          </div>
        </div>
      )}
      {requestsLoading && requests.length === 0 && (
        <p className="text-sm text-muted-foreground">Loading your connection requests…</p>
      )}

      {/* Browse list */}
      <div>
        <h2 className="mb-3 text-sm font-medium text-muted-foreground uppercase tracking-wider">
          Instagram creators
        </h2>

        {/* Q1.5 (T-CREATORCONNECT-0902) — search + follower-range narrow the BROWSE list (GET
            /creators/external?q=&minFollowers=&maxFollowers=), distinct from the exact-handle
            lookup form above. ExternalCreatorSpecs ORs a NULL followers value into both bounds, so
            an un-enriched row (externalFollowers renders it "—", never 0) is never hidden by
            these — narrowing the range only ever excludes rows with a KNOWN, out-of-range value. */}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void fetchList(1, false);
          }}
          className="mb-4 flex flex-col gap-4 rounded-lg border border-border bg-card p-4"
        >
          <div className="relative">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="Search Instagram creators by name or handle"
              value={listQuery}
              onChange={(e) => setListQuery(e.target.value)}
              className="pl-10"
              aria-label="Search Instagram creators"
            />
          </div>
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <Label className="text-sm font-medium">Followers</Label>
              <span className="text-sm text-muted-foreground">
                {formatFollowers(listFollowerRange[0])} - {formatFollowers(listFollowerRange[1])}
              </span>
            </div>
            <Slider
              value={listFollowerRange}
              onValueChange={(value) => setListFollowerRange(value as [number, number])}
              onValueCommit={(value) => {
                const range = value as [number, number];
                void fetchList(1, false, {
                  minFollowers: range[0] > 0 ? range[0] : undefined,
                  maxFollowers: range[1] < 10000000 ? range[1] : undefined,
                });
              }}
              min={0}
              max={10000000}
              step={10000}
              className="py-2"
              aria-label="Follower range"
            />
          </div>
          <div>
            <Button type="submit" variant="outline" size="sm" disabled={listLoading}>
              {listLoading ? <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" /> : null}
              Search
            </Button>
          </div>
        </form>

        {showUnavailable ? (
          <div
            data-testid="instagram-unavailable"
            className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed py-16 text-center"
          >
            <p className="text-sm font-medium">Instagram lookup isn&apos;t connected yet</p>
            <p className="text-sm text-muted-foreground">
              We can&apos;t reach Instagram right now — try again shortly, or ask an admin to import
              handles directly.
            </p>
          </div>
        ) : showFailed ? (
          <div
            data-testid="instagram-error"
            className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed py-16 text-center"
          >
            <AlertTriangle className="h-6 w-6 text-destructive-foreground" aria-hidden="true" />
            <p className="text-sm font-medium">Couldn&apos;t load Instagram creators</p>
            <p className="max-w-sm text-sm text-destructive-foreground">
              {listError ?? 'The server was briefly unavailable.'}
            </p>
            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => void fetchList(1, false)}>
              <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
              Retry
            </Button>
          </div>
        ) : showInitialLoading ? (
          <div
            data-testid="instagram-loading"
            className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
            aria-busy="true"
            aria-live="polite"
          >
            {Array.from({ length: 4 }).map((_, i) => (
              <Card key={i} className="overflow-hidden">
                <CardContent className="space-y-3 pt-5">
                  <div className="h-12 w-12 animate-pulse rounded-full bg-muted" />
                  <div className="h-4 w-2/3 animate-pulse rounded bg-muted" />
                  <div className="h-16 w-full animate-pulse rounded bg-muted" />
                </CardContent>
              </Card>
            ))}
          </div>
        ) : showEmpty ? (
          <div
            data-testid="instagram-empty"
            className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed py-16 text-center"
          >
            <p className="text-sm font-medium">No Instagram creators sourced yet</p>
            <p className="text-sm text-muted-foreground">
              Look up a handle above, or ask an admin to import one.
            </p>
          </div>
        ) : (
          <>
            {listError && (
              <p className="mb-3 text-sm text-destructive-foreground">{listError}</p>
            )}
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {listCreators.map((creator) => (
                <ExternalCreatorCard key={creator.id} creator={creator} onConnect={openConnect} />
              ))}
            </div>
            {listHasMore && !listLoading && (
              <div className="flex justify-center pt-4">
                <Button
                  variant="outline"
                  disabled={listLoadingMore}
                  onClick={() => void fetchList(listPage + 1, true)}
                >
                  {listLoadingMore ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                      Loading...
                    </>
                  ) : (
                    'Load more'
                  )}
                </Button>
              </div>
            )}
          </>
        )}
      </div>

      {/* Connect dialog */}
      <Dialog open={Boolean(connectTarget)} onOpenChange={(open) => !open && setConnectTarget(null)}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Connect with @{connectTarget?.igUsername}</DialogTitle>
            <DialogDescription>
              We&apos;ll send this to the Influora team, who will reach out to the creator and invite
              them to join. You&apos;ll be emailed when they do.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2 py-2">
            <Label htmlFor="ig-connect-message">Message (optional)</Label>
            <Textarea
              id="ig-connect-message"
              value={connectMessage}
              onChange={(e) => setConnectMessage(e.target.value.slice(0, 1000))}
              placeholder="Tell us why you'd like to work with this creator…"
              rows={3}
              maxLength={1000}
            />
            <p className="text-right text-xs text-muted-foreground">{connectMessage.length}/1000</p>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConnectTarget(null)}>
              Cancel
            </Button>
            <Button onClick={() => void submitConnect()} disabled={connectSubmitting}>
              {connectSubmitting ? <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" /> : null}
              Send request
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
