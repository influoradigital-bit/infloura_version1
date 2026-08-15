import * as React from 'react';
import { Loader2, Megaphone, Search, SlidersHorizontal, X } from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { CreatorBrowseCampaignCard } from '@/components/creator/CreatorBrowseCampaignCard';
import { FadeUp } from '@/components/motion/FadeUp';
import { StaggerContainer, StaggerItem } from '@/components/motion/StaggerContainer';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Slider } from '@/components/ui/slider';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet';
import type { CreatorCampaignListItem } from '@/lib/api';
import { api, ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import type { Platform } from '@/lib/types';
import { cn } from '@/lib/utils';

const NICHE_OPTIONS = [
  'Fitness',
  'Fashion',
  'Beauty',
  'Tech',
  'Food',
  'Travel',
  'Lifestyle',
  'Gaming',
];

const PLATFORM_OPTIONS: Platform[] = ['INSTAGRAM', 'YOUTUBE', 'TIKTOK'];

const BUDGET_MIN = 10_000;
const BUDGET_MAX = 1_000_000;

export default function CreatorCampaignsPage() {
  const { toast } = useToast();
  const [searchQuery, setSearchQuery] = React.useState('');
  const [selectedNiche, setSelectedNiche] = React.useState<string | null>(null);
  const [selectedPlatform, setSelectedPlatform] = React.useState<Platform | null>(null);
  const [budgetRange, setBudgetRange] = React.useState<[number, number]>([BUDGET_MIN, BUDGET_MAX]);
  const [draftBudgetRange, setDraftBudgetRange] = React.useState<[number, number]>([
    BUDGET_MIN,
    BUDGET_MAX,
  ]);
  const [isFilterOpen, setIsFilterOpen] = React.useState(false);

  const [campaigns, setCampaigns] = React.useState<CreatorCampaignListItem[]>([]);
  const [page, setPage] = React.useState(1);
  const [hasMore, setHasMore] = React.useState(false);
  const [loading, setLoading] = React.useState(true);
  const [loadingMore, setLoadingMore] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const budgetFilterActive =
    budgetRange[0] > BUDGET_MIN || budgetRange[1] < BUDGET_MAX;
  const activeFilterCount =
    (selectedPlatform ? 1 : 0) + (budgetFilterActive ? 1 : 0);

  const fetchCampaigns = React.useCallback(
    async (pageNum: number, append: boolean) => {
      if (append) {
        setLoadingMore(true);
      } else {
        setLoading(true);
        setError(null);
      }

      try {
        const result = await api.creatorCampaigns.browse({
          niche: selectedNiche ?? undefined,
          platform: selectedPlatform ?? undefined,
          budgetMin: budgetFilterActive ? budgetRange[0] : undefined,
          budgetMax: budgetFilterActive ? budgetRange[1] : undefined,
          page: pageNum,
          limit: 20,
        });

        setCampaigns((prev) =>
          append ? [...prev, ...result.campaigns] : result.campaigns,
        );
        setHasMore(result.meta.hasMore);
        setPage(pageNum);
      } catch (e) {
        const message =
          e instanceof ApiError ? e.message : 'Could not load campaigns. Try again.';
        if (!append) {
          setCampaigns([]);
          setError(message);
        } else {
          // Load-more failure previously did nothing — the list just stopped growing
          // with no explanation. Keep the already-loaded page, surface a toast.
          toast({ title: 'Couldn’t load more campaigns', description: message, variant: 'destructive' });
        }
      } finally {
        setLoading(false);
        setLoadingMore(false);
      }
    },
    [selectedNiche, selectedPlatform, budgetRange, budgetFilterActive],
  );

  React.useEffect(() => {
    const t = window.setTimeout(() => {
      void fetchCampaigns(1, false);
    }, 250);
    return () => window.clearTimeout(t);
  }, [fetchCampaigns]);

  const isSearching = searchQuery.trim().length > 0;

  // The backend (CreatorCampaignController#browse) has no text-search param — only
  // niche/platform/budget/page/limit — so this filters the campaigns already fetched
  // into `campaigns`, not the full catalog. Load More stays visible during a search
  // (see below) so the user can pull in more rows to search within, and the UI is
  // labeled to make clear this isn't a complete search of every campaign.
  const filteredCampaigns = React.useMemo(() => {
    if (!isSearching) return campaigns;
    const q = searchQuery.toLowerCase();
    return campaigns.filter(
      (c) =>
        c.title.toLowerCase().includes(q) ||
        c.description.toLowerCase().includes(q) ||
        (c.brand?.name.toLowerCase().includes(q) ?? false),
    );
  }, [campaigns, searchQuery, isSearching]);

  const toggleNiche = (niche: string) => {
    setSelectedNiche((prev) => (prev === niche ? null : niche));
  };

  const togglePlatform = (platform: Platform) => {
    setSelectedPlatform((prev) => (prev === platform ? null : platform));
  };

  const applyFilters = () => {
    setBudgetRange(draftBudgetRange);
    setIsFilterOpen(false);
  };

  const clearFilters = () => {
    setSelectedPlatform(null);
    setDraftBudgetRange([BUDGET_MIN, BUDGET_MAX]);
    setBudgetRange([BUDGET_MIN, BUDGET_MAX]);
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-4xl px-4 py-6">
        <FadeUp className="mb-6">
          <h1 className="text-2xl font-bold tracking-tight">Find campaigns</h1>
          <p className="text-muted-foreground">
            Discover brand campaigns that match your profile and apply in one click.
          </p>
        </FadeUp>

        <FadeUp delay={0.05} className="mb-5 space-y-3">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                placeholder="Search campaigns..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-10"
                aria-label="Search campaigns"
              />
            </div>

            <Sheet open={isFilterOpen} onOpenChange={setIsFilterOpen}>
              <SheetTrigger asChild>
                <Button variant="outline" className="gap-2">
                  <SlidersHorizontal className="h-4 w-4" />
                  Filters
                  {activeFilterCount > 0 && (
                    <Badge variant="secondary" className="ml-1 h-5 min-w-5 rounded-full px-1.5 text-xs">
                      {activeFilterCount}
                    </Badge>
                  )}
                </Button>
              </SheetTrigger>
              <SheetContent className="w-full sm:max-w-md">
                <SheetHeader>
                  <SheetTitle>Filter campaigns</SheetTitle>
                  <SheetDescription>
                    Narrow results by platform and budget range.
                  </SheetDescription>
                </SheetHeader>

                <ScrollArea className="h-[calc(100vh-220px)] pr-4">
                  <div className="space-y-6 py-6">
                    <div className="space-y-3">
                      <Label className="text-sm font-medium">Platform</Label>
                      <div className="flex flex-wrap gap-2">
                        {PLATFORM_OPTIONS.map((platform) => (
                          <Badge
                            key={platform}
                            variant={selectedPlatform === platform ? 'default' : 'outline'}
                            className="cursor-pointer"
                            onClick={() => togglePlatform(platform)}
                          >
                            {platform.charAt(0) + platform.slice(1).toLowerCase()}
                          </Badge>
                        ))}
                      </div>
                    </div>

                    <Separator />

                    <div className="space-y-4">
                      <Label className="text-sm font-medium">Budget range (INR)</Label>
                      <Slider
                        min={BUDGET_MIN}
                        max={BUDGET_MAX}
                        step={10_000}
                        value={draftBudgetRange}
                        onValueChange={(v) => setDraftBudgetRange(v as [number, number])}
                      />
                      <div className="flex justify-between text-sm text-muted-foreground">
                        <span>₹{draftBudgetRange[0].toLocaleString('en-IN')}</span>
                        <span>₹{draftBudgetRange[1].toLocaleString('en-IN')}</span>
                      </div>
                    </div>
                  </div>
                </ScrollArea>

                <SheetFooter className="flex-row gap-2 sm:justify-between">
                  <Button variant="ghost" onClick={clearFilters}>
                    Clear all
                  </Button>
                  <Button onClick={applyFilters}>Apply filters</Button>
                </SheetFooter>
              </SheetContent>
            </Sheet>
          </div>

          <div className="flex flex-wrap gap-2">
            {NICHE_OPTIONS.map((niche) => (
              <Badge
                key={niche}
                variant={selectedNiche === niche ? 'default' : 'outline'}
                className={cn('cursor-pointer')}
                onClick={() => toggleNiche(niche)}
              >
                {niche}
              </Badge>
            ))}
          </div>

          {(selectedPlatform || budgetFilterActive) && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-muted-foreground">Active:</span>
              {selectedPlatform && (
                <Badge variant="secondary" className="gap-1">
                  {selectedPlatform}
                  <button type="button" onClick={() => setSelectedPlatform(null)} aria-label="Remove platform filter">
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              )}
              {budgetFilterActive && (
                <Badge variant="secondary" className="gap-1">
                  ₹{budgetRange[0].toLocaleString('en-IN')} – ₹{budgetRange[1].toLocaleString('en-IN')}
                  <button
                    type="button"
                    onClick={() => {
                      setBudgetRange([BUDGET_MIN, BUDGET_MAX]);
                      setDraftBudgetRange([BUDGET_MIN, BUDGET_MAX]);
                    }}
                    aria-label="Remove budget filter"
                  >
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              )}
            </div>
          )}
        </FadeUp>

        {error && (
          <Alert className="mb-5" variant="destructive">
            <AlertTitle>Could not load campaigns</AlertTitle>
            <AlertDescription>
              {error}
              <div className="mt-2">
                <Button size="sm" variant="outline" onClick={() => void fetchCampaigns(1, false)}>
                  Try again
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}

        {loading ? (
          <div className="space-y-4">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-44 w-full rounded-xl" />
            ))}
          </div>
        ) : (
          <>
            {isSearching && filteredCampaigns.length > 0 && (
              <p className="mb-3 text-xs text-muted-foreground">
                Searching {campaigns.length} loaded campaign{campaigns.length === 1 ? '' : 's'}
                {hasMore ? ' — load more below to search additional campaigns.' : '.'}
              </p>
            )}

            {filteredCampaigns.length === 0 && !error ? (
              <EmptyState
                hasFilters={Boolean(selectedNiche || selectedPlatform || budgetFilterActive || searchQuery)}
                isSearching={isSearching}
                hasMore={hasMore}
              />
            ) : (
              <StaggerContainer className="space-y-4">
                {filteredCampaigns.map((campaign) => (
                  <StaggerItem key={campaign.id}>
                    <CreatorBrowseCampaignCard campaign={campaign} />
                  </StaggerItem>
                ))}
              </StaggerContainer>
            )}

            {hasMore && !error && (
              <div className="mt-6 flex justify-center">
                <Button
                  variant="outline"
                  disabled={loadingMore}
                  onClick={() => void fetchCampaigns(page + 1, true)}
                >
                  {loadingMore ? (
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
      </div>
    </CreatorLayout>
  );
}

function EmptyState({
  hasFilters,
  isSearching,
  hasMore,
}: {
  hasFilters: boolean;
  isSearching: boolean;
  hasMore: boolean;
}) {
  const title = isSearching
    ? 'No loaded campaigns match your search'
    : hasFilters
      ? 'No campaigns match your filters'
      : 'No campaigns yet';

  const description = isSearching
    ? hasMore
      ? 'None of the campaigns fetched so far match. Load more campaigns to keep searching, or try a different term.'
      : 'None of the loaded campaigns match. Try a different search term.'
    : hasFilters
      ? 'Try adjusting your niche, platform, or budget filters to see more opportunities.'
      : 'New brand campaigns will appear here when they go live. Check back soon.';

  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <Megaphone className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">{title}</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">{description}</p>
    </div>
  );
}
