'use client';

import * as React from 'react';
import Link from 'next/link';
import {
  Search,
  Filter,
  X,
  Heart,
  MessageSquare,
  Send,
  ExternalLink,
  CheckCircle2,
  Users,
  TrendingUp,
  MapPin,
  Grid3X3,
  List,
  ChevronDown,
  SlidersHorizontal,
  Bookmark,
  BookmarkCheck,
  Sparkles,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import type { Platform, CreatorProfile } from '@/lib/types';
import { useDiscoveryStore, useUIStore } from '@/lib/store';
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

// Mock creator data
const mockCreators: CreatorProfile[] = [
  {
    id: '1',
    userId: 'u1',
    displayName: 'Sarah Creates',
    bio: 'Lifestyle & fashion content creator. Sharing daily inspiration and style tips with my community.',
    avatarUrl: null,
    coverImageUrl: null,
    location: 'Los Angeles, CA',
    categories: ['Fashion', 'Lifestyle', 'Beauty'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@sarahcreates', followers: 285000, engagementRate: 4.8, isVerified: true },
      { platform: 'TIKTOK', handle: '@sarahcreates', followers: 520000, engagementRate: 6.2, isVerified: true },
      { platform: 'YOUTUBE', handle: 'SarahCreates', followers: 145000, engagementRate: 3.5, isVerified: false },
    ],
    totalFollowers: 950000,
    engagementRate: 4.8,
    averageRate: 2500,
    currency: 'USD',
    isVerified: true,
    portfolioItems: [],
    languages: ['English', 'Spanish'],
    contentStyles: ['Aesthetic', 'Minimalist', 'Trendy'],
  },
  {
    id: '2',
    userId: 'u2',
    displayName: 'Mike Photography',
    bio: 'Professional photographer & videographer. Specializing in product and lifestyle content.',
    avatarUrl: null,
    coverImageUrl: null,
    location: 'New York, NY',
    categories: ['Photography', 'Tech', 'Travel'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@mikephoto', followers: 156000, engagementRate: 5.2, isVerified: true },
      { platform: 'YOUTUBE', handle: 'MikePhoto', followers: 89000, engagementRate: 4.1, isVerified: true },
    ],
    totalFollowers: 245000,
    engagementRate: 4.6,
    averageRate: 1800,
    currency: 'USD',
    isVerified: true,
    portfolioItems: [],
    languages: ['English'],
    contentStyles: ['Cinematic', 'Professional', 'Editorial'],
  },
  {
    id: '3',
    userId: 'u3',
    displayName: 'Lifestyle Jane',
    bio: 'Mom of 2, home decor enthusiast, sharing our family adventures and home transformation journey.',
    avatarUrl: null,
    coverImageUrl: null,
    location: 'Austin, TX',
    categories: ['Lifestyle', 'Home', 'Family'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@lifestylejane', followers: 420000, engagementRate: 6.1, isVerified: true },
      { platform: 'TIKTOK', handle: '@lifestylejane', followers: 380000, engagementRate: 7.8, isVerified: false },
    ],
    totalFollowers: 800000,
    engagementRate: 6.9,
    averageRate: 3200,
    currency: 'USD',
    isVerified: true,
    portfolioItems: [],
    languages: ['English'],
    contentStyles: ['Warm', 'Authentic', 'Family-friendly'],
  },
  {
    id: '4',
    userId: 'u4',
    displayName: 'Tech Review Pro',
    bio: 'Your go-to source for honest tech reviews. Gadgets, apps, and everything digital.',
    avatarUrl: null,
    coverImageUrl: null,
    location: 'San Francisco, CA',
    categories: ['Tech', 'Gaming', 'Reviews'],
    platforms: [
      { platform: 'YOUTUBE', handle: 'TechReviewPro', followers: 1200000, engagementRate: 3.2, isVerified: true },
      { platform: 'TWITTER', handle: '@techreviewpro', followers: 89000, engagementRate: 2.8, isVerified: true },
    ],
    totalFollowers: 1289000,
    engagementRate: 3.0,
    averageRate: 5000,
    currency: 'USD',
    isVerified: true,
    portfolioItems: [],
    languages: ['English'],
    contentStyles: ['Informative', 'Technical', 'Engaging'],
  },
  {
    id: '5',
    userId: 'u5',
    displayName: 'Foodie Adventures',
    bio: 'Food blogger & recipe developer. Bringing delicious recipes from around the world to your kitchen.',
    avatarUrl: null,
    coverImageUrl: null,
    location: 'Chicago, IL',
    categories: ['Food', 'Lifestyle', 'Travel'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@foodieadventures', followers: 320000, engagementRate: 5.5, isVerified: true },
      { platform: 'TIKTOK', handle: '@foodieadventures', followers: 890000, engagementRate: 8.2, isVerified: true },
      { platform: 'YOUTUBE', handle: 'FoodieAdventures', followers: 210000, engagementRate: 4.0, isVerified: false },
    ],
    totalFollowers: 1420000,
    engagementRate: 5.9,
    averageRate: 4000,
    currency: 'USD',
    isVerified: true,
    portfolioItems: [],
    languages: ['English', 'Italian'],
    contentStyles: ['Colorful', 'Appetizing', 'Tutorial'],
  },
  {
    id: '6',
    userId: 'u6',
    displayName: 'Fitness with Alex',
    bio: 'Certified personal trainer. Sharing workout tips, nutrition advice, and fitness motivation.',
    avatarUrl: null,
    coverImageUrl: null,
    location: 'Miami, FL',
    categories: ['Fitness', 'Health', 'Lifestyle'],
    platforms: [
      { platform: 'INSTAGRAM', handle: '@fitwithalex', followers: 580000, engagementRate: 4.9, isVerified: true },
      { platform: 'YOUTUBE', handle: 'FitnessWithAlex', followers: 340000, engagementRate: 3.8, isVerified: true },
      { platform: 'TIKTOK', handle: '@fitwithalex', followers: 720000, engagementRate: 7.1, isVerified: true },
    ],
    totalFollowers: 1640000,
    engagementRate: 5.3,
    averageRate: 4500,
    currency: 'USD',
    isVerified: true,
    portfolioItems: [],
    languages: ['English', 'Portuguese'],
    contentStyles: ['Motivational', 'Educational', 'High-energy'],
  },
];

const platformOptions: { value: Platform; label: string }[] = [
  { value: 'INSTAGRAM', label: 'Instagram' },
  { value: 'YOUTUBE', label: 'YouTube' },
  { value: 'TIKTOK', label: 'TikTok' },
  { value: 'TWITTER', label: 'X/Twitter' },
  { value: 'LINKEDIN', label: 'LinkedIn' },
  { value: 'TWITCH', label: 'Twitch' },
];

const categoryOptions = [
  'Fashion',
  'Beauty',
  'Lifestyle',
  'Tech',
  'Gaming',
  'Food',
  'Travel',
  'Fitness',
  'Health',
  'Home',
  'Family',
  'Photography',
  'Reviews',
];

const followerRanges = [
  { label: 'Nano (1K-10K)', min: 1000, max: 10000 },
  { label: 'Micro (10K-100K)', min: 10000, max: 100000 },
  { label: 'Mid-tier (100K-500K)', min: 100000, max: 500000 },
  { label: 'Macro (500K-1M)', min: 500000, max: 1000000 },
  { label: 'Mega (1M+)', min: 1000000, max: 100000000 },
];

type ViewMode = 'grid' | 'list';
type SortOption = 'relevance' | 'followers' | 'engagement' | 'rate';

export function CreatorDiscovery() {
  const [viewMode, setViewMode] = React.useState<ViewMode>('grid');
  const [sortBy, setSortBy] = React.useState<SortOption>('relevance');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [selectedPlatforms, setSelectedPlatforms] = React.useState<Platform[]>([]);
  const [selectedCategories, setSelectedCategories] = React.useState<string[]>([]);
  const [followerRange, setFollowerRange] = React.useState<[number, number]>([0, 100000000]);
  const [engagementRange, setEngagementRange] = React.useState<[number, number]>([0, 15]);
  const [verifiedOnly, setVerifiedOnly] = React.useState(false);
  const [savedCreators, setSavedCreators] = React.useState<string[]>([]);
  const [isFilterOpen, setIsFilterOpen] = React.useState(false);

  const togglePlatform = (platform: Platform) => {
    setSelectedPlatforms((prev) =>
      prev.includes(platform)
        ? prev.filter((p) => p !== platform)
        : [...prev, platform]
    );
  };

  const toggleCategory = (category: string) => {
    setSelectedCategories((prev) =>
      prev.includes(category)
        ? prev.filter((c) => c !== category)
        : [...prev, category]
    );
  };

  const toggleSaved = (creatorId: string) => {
    setSavedCreators((prev) =>
      prev.includes(creatorId)
        ? prev.filter((id) => id !== creatorId)
        : [...prev, creatorId]
    );
  };

  const clearFilters = () => {
    setSelectedPlatforms([]);
    setSelectedCategories([]);
    setFollowerRange([0, 100000000]);
    setEngagementRange([0, 15]);
    setVerifiedOnly(false);
  };

  const hasActiveFilters =
    selectedPlatforms.length > 0 ||
    selectedCategories.length > 0 ||
    verifiedOnly ||
    followerRange[0] > 0 ||
    followerRange[1] < 100000000;

  const filteredCreators = React.useMemo(() => {
    let result = [...mockCreators];

    // Search filter
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      result = result.filter(
        (c) =>
          c.displayName.toLowerCase().includes(query) ||
          c.bio?.toLowerCase().includes(query) ||
          c.categories.some((cat) => cat.toLowerCase().includes(query))
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

    // Follower range filter
    result = result.filter(
      (c) =>
        c.totalFollowers >= followerRange[0] && c.totalFollowers <= followerRange[1]
    );

    // Engagement range filter
    result = result.filter(
      (c) =>
        c.engagementRate >= engagementRange[0] && c.engagementRate <= engagementRange[1]
    );

    // Verified filter
    if (verifiedOnly) {
      result = result.filter((c) => c.isVerified);
    }

    // Sorting
    switch (sortBy) {
      case 'followers':
        result.sort((a, b) => b.totalFollowers - a.totalFollowers);
        break;
      case 'engagement':
        result.sort((a, b) => b.engagementRate - a.engagementRate);
        break;
      case 'rate':
        result.sort((a, b) => (a.averageRate || 0) - (b.averageRate || 0));
        break;
      default:
        // Keep original order for relevance
        break;
    }

    return result;
  }, [
    searchQuery,
    selectedPlatforms,
    selectedCategories,
    followerRange,
    engagementRange,
    verifiedOnly,
    sortBy,
  ]);

  const formatFollowers = (count: number) => {
    if (count >= 1000000) return `${(count / 1000000).toFixed(1)}M`;
    if (count >= 1000) return `${(count / 1000).toFixed(0)}K`;
    return count.toString();
  };

  const FilterContent = () => (
    <div className="space-y-6">
      {/* Platforms */}
      <div className="space-y-3">
        <Label className="text-sm font-medium">Platforms</Label>
        <div className="flex flex-wrap gap-2">
          {platformOptions.map((platform) => (
            <Badge
              key={platform.value}
              variant={
                selectedPlatforms.includes(platform.value) ? 'default' : 'outline'
              }
              className="cursor-pointer"
              onClick={() => togglePlatform(platform.value)}
            >
              {platform.label}
            </Badge>
          ))}
        </div>
      </div>

      <Separator />

      {/* Categories */}
      <div className="space-y-3">
        <Label className="text-sm font-medium">Categories</Label>
        <div className="flex flex-wrap gap-2">
          {categoryOptions.map((category) => (
            <Badge
              key={category}
              variant={
                selectedCategories.includes(category) ? 'default' : 'outline'
              }
              className="cursor-pointer"
              onClick={() => toggleCategory(category)}
            >
              {category}
            </Badge>
          ))}
        </div>
      </div>

      <Separator />

      {/* Follower Range */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <Label className="text-sm font-medium">Followers</Label>
          <span className="text-xs text-muted-foreground">
            {formatFollowers(followerRange[0])} - {formatFollowers(followerRange[1])}
          </span>
        </div>
        <div className="flex flex-wrap gap-2">
          {followerRanges.map((range) => (
            <Badge
              key={range.label}
              variant={
                followerRange[0] === range.min && followerRange[1] === range.max
                  ? 'default'
                  : 'outline'
              }
              className="cursor-pointer"
              onClick={() => setFollowerRange([range.min, range.max])}
            >
              {range.label}
            </Badge>
          ))}
        </div>
      </div>

      <Separator />

      {/* Engagement Rate */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <Label className="text-sm font-medium">Engagement Rate</Label>
          <span className="text-xs text-muted-foreground">
            {engagementRange[0]}% - {engagementRange[1]}%
          </span>
        </div>
        <Slider
          min={0}
          max={15}
          step={0.5}
          value={engagementRange}
          onValueChange={(value) => setEngagementRange(value as [number, number])}
        />
      </div>

      <Separator />

      {/* Verified Only */}
      <div className="flex items-center justify-between">
        <div className="space-y-0.5">
          <Label className="text-sm font-medium">Verified Creators Only</Label>
          <p className="text-xs text-muted-foreground">
            Show only platform-verified creators
          </p>
        </div>
        <Checkbox
          checked={verifiedOnly}
          onCheckedChange={(checked) => setVerifiedOnly(checked === true)}
        />
      </div>
    </div>
  );

  return (
    <TooltipProvider>
      <div className="flex flex-col gap-6 p-4 lg:p-6">
        {/* Header */}
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight lg:text-3xl">
              Discover Creators
            </h1>
            <p className="text-muted-foreground">
              Find the perfect creators for your campaigns
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant="secondary" className="gap-1.5">
              <Sparkles className="h-3.5 w-3.5" />
              AI Matching Coming Soon
            </Badge>
          </div>
        </div>

        {/* Search & Filters Bar */}
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-1 flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative flex-1 lg:max-w-md">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                type="search"
                placeholder="Search creators by name, category..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-9"
              />
            </div>

            {/* Desktop Filters Toggle */}
            <Sheet open={isFilterOpen} onOpenChange={setIsFilterOpen}>
              <SheetTrigger asChild>
                <Button variant="outline" className="gap-2">
                  <SlidersHorizontal className="h-4 w-4" />
                  Filters
                  {hasActiveFilters && (
                    <Badge variant="secondary" className="ml-1 h-5 w-5 rounded-full p-0">
                      {selectedPlatforms.length +
                        selectedCategories.length +
                        (verifiedOnly ? 1 : 0)}
                    </Badge>
                  )}
                </Button>
              </SheetTrigger>
              <SheetContent className="w-full sm:max-w-md">
                <SheetHeader>
                  <SheetTitle>Filter Creators</SheetTitle>
                  <SheetDescription>
                    Narrow down creators based on your criteria
                  </SheetDescription>
                </SheetHeader>
                <ScrollArea className="h-[calc(100vh-12rem)] py-6">
                  <FilterContent />
                </ScrollArea>
                <SheetFooter className="flex-row gap-3">
                  <Button
                    variant="outline"
                    onClick={clearFilters}
                    disabled={!hasActiveFilters}
                    className="flex-1"
                  >
                    Clear All
                  </Button>
                  <Button onClick={() => setIsFilterOpen(false)} className="flex-1">
                    Apply Filters
                  </Button>
                </SheetFooter>
              </SheetContent>
            </Sheet>
          </div>

          <div className="flex items-center gap-2">
            <Select
              value={sortBy}
              onValueChange={(value) => setSortBy(value as SortOption)}
            >
              <SelectTrigger className="w-36">
                <SelectValue placeholder="Sort by" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="relevance">Relevance</SelectItem>
                <SelectItem value="followers">Followers</SelectItem>
                <SelectItem value="engagement">Engagement</SelectItem>
                <SelectItem value="rate">Rate (Low-High)</SelectItem>
              </SelectContent>
            </Select>

            <div className="hidden sm:flex">
              <Button
                variant={viewMode === 'grid' ? 'secondary' : 'ghost'}
                size="icon"
                className="rounded-r-none"
                onClick={() => setViewMode('grid')}
              >
                <Grid3X3 className="h-4 w-4" />
              </Button>
              <Button
                variant={viewMode === 'list' ? 'secondary' : 'ghost'}
                size="icon"
                className="rounded-l-none"
                onClick={() => setViewMode('list')}
              >
                <List className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </div>

        {/* Active Filters Display */}
        {hasActiveFilters && (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm text-muted-foreground">Active filters:</span>
            {selectedPlatforms.map((platform) => (
              <Badge
                key={platform}
                variant="secondary"
                className="gap-1 pr-1"
              >
                {platformOptions.find((p) => p.value === platform)?.label}
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-4 w-4 p-0 hover:bg-transparent"
                  onClick={() => togglePlatform(platform)}
                >
                  <X className="h-3 w-3" />
                </Button>
              </Badge>
            ))}
            {selectedCategories.map((category) => (
              <Badge
                key={category}
                variant="secondary"
                className="gap-1 pr-1"
              >
                {category}
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-4 w-4 p-0 hover:bg-transparent"
                  onClick={() => toggleCategory(category)}
                >
                  <X className="h-3 w-3" />
                </Button>
              </Badge>
            ))}
            {verifiedOnly && (
              <Badge variant="secondary" className="gap-1 pr-1">
                Verified Only
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-4 w-4 p-0 hover:bg-transparent"
                  onClick={() => setVerifiedOnly(false)}
                >
                  <X className="h-3 w-3" />
                </Button>
              </Badge>
            )}
            <Button
              variant="ghost"
              size="sm"
              className="h-7 text-xs"
              onClick={clearFilters}
            >
              Clear all
            </Button>
          </div>
        )}

        {/* Results Count */}
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            {filteredCreators.length} creator{filteredCreators.length !== 1 ? 's' : ''}{' '}
            found
          </p>
        </div>

        {/* Creator Grid/List */}
        {filteredCreators.length === 0 ? (
          <Card className="border-dashed">
            <CardContent className="flex flex-col items-center justify-center py-16 text-center">
              <div className="flex h-14 w-14 items-center justify-center rounded-full bg-muted">
                <Users className="h-7 w-7 text-muted-foreground" />
              </div>
              <h3 className="mt-4 text-lg font-semibold">No creators found</h3>
              <p className="mt-1 text-sm text-muted-foreground">
                Try adjusting your filters or search query
              </p>
              <Button variant="outline" className="mt-4" onClick={clearFilters}>
                Clear Filters
              </Button>
            </CardContent>
          </Card>
        ) : viewMode === 'grid' ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {filteredCreators.map((creator) => (
              <Card
                key={creator.id}
                className="group overflow-hidden transition-colors hover:border-primary/50"
              >
                <CardContent className="p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex items-center gap-3">
                      <Avatar className="h-14 w-14">
                        <AvatarImage src={creator.avatarUrl || undefined} />
                        <AvatarFallback className="bg-primary/10 text-primary text-lg">
                          {creator.displayName.charAt(0)}
                        </AvatarFallback>
                      </Avatar>
                      <div>
                        <div className="flex items-center gap-1.5">
                          <h3 className="font-semibold">{creator.displayName}</h3>
                          {creator.isVerified && (
                            <CheckCircle2 className="h-4 w-4 text-primary" />
                          )}
                        </div>
                        {creator.location && (
                          <p className="flex items-center gap-1 text-xs text-muted-foreground">
                            <MapPin className="h-3 w-3" />
                            {creator.location}
                          </p>
                        )}
                      </div>
                    </div>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <Button
                          variant="ghost"
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

                  <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">
                    {creator.bio}
                  </p>

                  <div className="mt-3 flex flex-wrap gap-1.5">
                    {creator.categories.slice(0, 3).map((category) => (
                      <Badge key={category} variant="secondary" className="text-xs">
                        {category}
                      </Badge>
                    ))}
                  </div>

                  <div className="mt-4 grid grid-cols-3 gap-2 rounded-lg border border-border p-3">
                    <div className="text-center">
                      <p className="text-lg font-bold">
                        {formatFollowers(creator.totalFollowers)}
                      </p>
                      <p className="text-xs text-muted-foreground">Followers</p>
                    </div>
                    <div className="text-center">
                      <p className="text-lg font-bold">{creator.engagementRate}%</p>
                      <p className="text-xs text-muted-foreground">Engagement</p>
                    </div>
                    <div className="text-center">
                      <p className="text-lg font-bold">
                        ${creator.averageRate?.toLocaleString() || 'N/A'}
                      </p>
                      <p className="text-xs text-muted-foreground">Avg Rate</p>
                    </div>
                  </div>

                  <div className="mt-3 flex flex-wrap gap-1.5">
                    {creator.platforms.map((p) => (
                      <Badge key={p.platform} variant="outline" className="text-xs gap-1">
                        {p.platform === 'INSTAGRAM' && 'IG'}
                        {p.platform === 'YOUTUBE' && 'YT'}
                        {p.platform === 'TIKTOK' && 'TK'}
                        {p.platform === 'TWITTER' && 'X'}
                        {p.platform === 'LINKEDIN' && 'LI'}
                        {p.platform === 'TWITCH' && 'TW'}
                        <span className="text-muted-foreground">
                          {formatFollowers(p.followers)}
                        </span>
                      </Badge>
                    ))}
                  </div>
                </CardContent>
                <CardFooter className="gap-2 border-t border-border p-3">
                  <Button variant="outline" size="sm" className="flex-1" asChild>
                    <Link href={`/brand/creators/${creator.id}`}>
                      View Profile
                    </Link>
                  </Button>
                  <Button size="sm" className="flex-1 gap-1.5">
                    <Send className="h-3.5 w-3.5" />
                    Invite
                  </Button>
                </CardFooter>
              </Card>
            ))}
          </div>
        ) : (
          <Card>
            <div className="divide-y divide-border">
              {filteredCreators.map((creator) => (
                <div
                  key={creator.id}
                  className="flex flex-col gap-4 p-4 transition-colors hover:bg-muted/50 sm:flex-row sm:items-center"
                >
                  <div className="flex flex-1 items-start gap-4">
                    <Avatar className="h-12 w-12">
                      <AvatarImage src={creator.avatarUrl || undefined} />
                      <AvatarFallback className="bg-primary/10 text-primary">
                        {creator.displayName.charAt(0)}
                      </AvatarFallback>
                    </Avatar>
                    <div className="flex-1 space-y-1">
                      <div className="flex items-center gap-2">
                        <h3 className="font-semibold">{creator.displayName}</h3>
                        {creator.isVerified && (
                          <CheckCircle2 className="h-4 w-4 text-primary" />
                        )}
                      </div>
                      <p className="line-clamp-1 text-sm text-muted-foreground">
                        {creator.bio}
                      </p>
                      <div className="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
                        <span className="flex items-center gap-1">
                          <Users className="h-3.5 w-3.5" />
                          {formatFollowers(creator.totalFollowers)}
                        </span>
                        <span className="flex items-center gap-1">
                          <TrendingUp className="h-3.5 w-3.5" />
                          {creator.engagementRate}% engagement
                        </span>
                        {creator.location && (
                          <span className="flex items-center gap-1">
                            <MapPin className="h-3.5 w-3.5" />
                            {creator.location}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 sm:shrink-0">
                    <span className="text-sm font-medium">
                      ${creator.averageRate?.toLocaleString() || 'N/A'}
                    </span>
                    <Button
                      variant="ghost"
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
                    <Button variant="outline" size="sm" asChild>
                      <Link href={`/brand/creators/${creator.id}`}>View</Link>
                    </Button>
                    <Button size="sm" className="gap-1.5">
                      <Send className="h-3.5 w-3.5" />
                      Invite
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        )}
      </div>
    </TooltipProvider>
  );
}
