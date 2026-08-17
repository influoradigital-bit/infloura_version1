import * as React from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
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
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';
import type { Platform, CreatorProfile } from '@/lib/types';
import { api, isApiLive, ApiError, type FeaturedCreatorSection } from '@/lib/api';
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

// Indian cities for filter
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

const languages = ['Hindi', 'English', 'Tamil', 'Telugu', 'Kannada', 'Malayalam', 'Bengali', 'Marathi', 'Gujarati', 'Punjabi'];

export function CreatorDiscovery() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const liveApi = isApiLive();

  const [apiCreators, setApiCreators] = React.useState<CreatorProfile[]>([]);
  const [apiLoading, setApiLoading] = React.useState(false);
  const [apiLoadingMore, setApiLoadingMore] = React.useState(false);
  const [apiPage, setApiPage] = React.useState(1);
  const [apiHasMore, setApiHasMore] = React.useState(false);
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
  const [sortBy, setSortBy] = React.useState<'relevance' | 'followers' | 'engagement' | 'price_low' | 'price_high'>('relevance');
  const [viewMode, setViewMode] = React.useState<'grid' | 'list'>('grid');
  
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
        const result = await api.creators.search({
          q: searchQuery || undefined,
          city: selectedCities.length ? selectedCities.join(',') : undefined,
          platforms: selectedPlatforms.length ? selectedPlatforms : undefined,
          verticals: selectedCategories.length
            ? selectedCategories.map((c) => c.toLowerCase())
            : undefined,
          minFollowers: followerRange[0] > 0 ? followerRange[0] : undefined,
          maxFollowers: followerRange[1] < 10000000 ? followerRange[1] : undefined,
          minRate: priceRange[0] > 5000 ? priceRange[0] : undefined,
          maxRate: priceRange[1] < 200000 ? priceRange[1] : undefined,
          page: pageNum,
          limit: DISCOVER_PAGE_SIZE,
        });
        setApiCreators((prev) => (append ? [...prev, ...result.creators] : result.creators));
        setApiHasMore(result.meta.hasMore);
        setApiPage(pageNum);
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
    [searchQuery, selectedCities, selectedPlatforms, selectedCategories, followerRange, priceRange],
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

  // Filter creators
  const filteredCreators = React.useMemo(() => {
    let result = liveApi ? [...apiCreators] : [...mockCreators];

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
    const priceFilterTouched = priceRange[0] > 5000 || priceRange[1] < 200000;
    if (priceFilterTouched) {
      result = result.filter(
        (c) => c.averageRate != null && c.averageRate >= priceRange[0] && c.averageRate <= priceRange[1]
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

    // Sort
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
      default:
        // relevance - keep original order
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

          {/* Search and Filter Bar */}
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

                      {/* Categories */}
                      <div className="space-y-3">
                        <Label className="text-sm font-medium">Categories</Label>
                        <div className="flex flex-wrap gap-2">
                          {categories.map((category) => (
                            <Badge
                              key={category}
                              variant={selectedCategories.includes(category) ? 'default' : 'outline'}
                              className="cursor-pointer"
                              onClick={() => toggleCategory(category)}
                            >
                              {category}
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
                <DropdownMenuContent align="end">
                  <DropdownMenuItem onClick={() => setSortBy('relevance')}>
                    Relevance
                  </DropdownMenuItem>
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
                  {category}
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
        </div>

        {/* Featured Creators — D-14. Hidden as soon as the brand starts searching/filtering, so
            it never competes with an active query's results. */}
        {featuredSections.length > 0 && !searchQuery && activeFilterCount === 0 && (
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

        {/* Results Count */}
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            {isInitialLoading ? 'Loading creators…' : `Showing ${filteredCreators.length} creators`}
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
