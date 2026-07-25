import * as React from 'react';
import { useNavigate, Link } from 'react-router-dom';
import {
  Plus,
  Search,
  Filter,
  MoreHorizontal,
  Eye,
  Edit,
  Trash2,
  Copy,
  Pause,
  Play,
  Calendar,
  Users,
  DollarSign,
  TrendingUp,
  ArrowUpDown,
  Grid3X3,
  List,
  ChevronDown,
  AlertCircle,
  RefreshCw,
  Loader2,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { IconBadge } from '@/components/shared/icon-badge';
import type { Campaign, CampaignStatus, Platform } from '@/lib/types';
import { api, isApiLive, ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui/alert';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  DropdownMenuLabel,
  DropdownMenuCheckboxItem,
} from '@/components/ui/dropdown-menu';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
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
import { Zap } from 'lucide-react';
import { HypeCampaignCard } from '@/components/brand/hype-campaign-card';
import { demoHypeCampaign } from '@/lib/demo-data';

// Mock campaigns data
const mockCampaigns: (Campaign & { collaboratorsCount: number; progress: number })[] = [
  {
    id: 'active-1',
    workspaceId: 'ws-1',
    title: 'Summer Collection Launch',
    description: 'Promote our new summer fashion line with lifestyle content',
    objectives: ['Brand awareness', 'Drive sales'],
    status: 'ACTIVE',
    budget: { min: 20000, max: 25000, currency: 'INR' },
    timeline: {
      startDate: new Date('2024-05-01'),
      endDate: new Date('2024-06-15'),
    },
    platforms: ['INSTAGRAM', 'TIKTOK', 'YOUTUBE'],
    contentTypes: ['REEL', 'POST', 'STORY'],
    isPrivate: false,
    maxCollaborators: 10,
    createdBy: 'user-1',
    createdAt: new Date('2024-04-01'),
    updatedAt: new Date('2024-05-10'),
    collaboratorsCount: 8,
    progress: 65,
  },
  {
    id: '2',
    workspaceId: 'ws-1',
    title: 'Holiday Gift Guide 2024',
    description: 'Feature products in holiday gift guide content',
    objectives: ['Product discovery', 'Seasonal sales'],
    status: 'ACTIVE',
    budget: { min: 10000, max: 15000, currency: 'INR' },
    timeline: {
      startDate: new Date('2024-06-01'),
      endDate: new Date('2024-07-01'),
    },
    platforms: ['INSTAGRAM', 'YOUTUBE'],
    contentTypes: ['VIDEO', 'POST'],
    isPrivate: false,
    maxCollaborators: 6,
    createdBy: 'user-1',
    createdAt: new Date('2024-05-15'),
    updatedAt: new Date('2024-05-20'),
    collaboratorsCount: 5,
    progress: 40,
  },
  {
    id: '3',
    workspaceId: 'ws-1',
    title: 'Brand Ambassador Program Q3',
    description: 'Long-term partnership with selected creators',
    objectives: ['Brand loyalty', 'Consistent content'],
    status: 'DRAFT',
    budget: { min: 45000, max: 50000, currency: 'INR' },
    timeline: {
      startDate: new Date('2024-07-01'),
      endDate: new Date('2024-09-30'),
    },
    platforms: ['INSTAGRAM', 'TIKTOK', 'YOUTUBE', 'TWITTER'],
    contentTypes: ['REEL', 'POST', 'STORY', 'VIDEO'],
    isPrivate: true,
    maxCollaborators: 20,
    createdBy: 'user-1',
    createdAt: new Date('2024-05-25'),
    updatedAt: new Date('2024-05-25'),
    collaboratorsCount: 0,
    progress: 0,
  },
  {
    id: 'completed-1',
    workspaceId: 'ws-1',
    title: 'Spring Brand Awareness Drive',
    description: 'Multi-creator spring campaign targeting fashion-forward millennials',
    objectives: ['Brand awareness', 'Reach expansion'],
    status: 'COMPLETED',
    budget: { min: 15000, max: 18000, currency: 'INR' },
    timeline: {
      startDate: new Date('2024-03-01'),
      endDate: new Date('2024-04-15'),
    },
    platforms: ['INSTAGRAM', 'YOUTUBE'],
    contentTypes: ['VIDEO', 'REEL', 'POST'],
    isPrivate: false,
    maxCollaborators: 8,
    createdBy: 'user-1',
    createdAt: new Date('2024-02-15'),
    updatedAt: new Date('2024-04-16'),
    collaboratorsCount: 8,
    progress: 100,
  },
  {
    id: '5',
    workspaceId: 'ws-1',
    title: 'Tech Product Launch',
    description: 'New gadget unboxing and review campaign',
    objectives: ['Product awareness', 'Tech audience reach'],
    status: 'PAUSED',
    budget: { min: 30000, max: 35000, currency: 'INR' },
    timeline: {
      startDate: new Date('2024-05-15'),
      endDate: new Date('2024-06-30'),
    },
    platforms: ['YOUTUBE', 'TWITTER', 'TIKTOK'],
    contentTypes: ['VIDEO', 'POST'],
    isPrivate: false,
    maxCollaborators: 12,
    createdBy: 'user-1',
    createdAt: new Date('2024-05-01'),
    updatedAt: new Date('2024-05-18'),
    collaboratorsCount: 4,
    progress: 25,
  },
];

type CampaignRow = Campaign & { collaboratorsCount: number; progress: number };

const statusConfig: Record<CampaignStatus, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  DRAFT: { label: 'Draft', variant: 'secondary' },
  PENDING_APPROVAL: { label: 'Pending', variant: 'outline' },
  ACTIVE: { label: 'Active', variant: 'default' },
  PAUSED: { label: 'Paused', variant: 'outline' },
  COMPLETED: { label: 'Completed', variant: 'secondary' },
  CANCELLED: { label: 'Cancelled', variant: 'destructive' },
};

const platformLabels: Record<Platform, string> = {
  INSTAGRAM: 'Instagram',
  YOUTUBE: 'YouTube',
  TIKTOK: 'TikTok',
  TWITTER: 'X/Twitter',
  LINKEDIN: 'LinkedIn',
  FACEBOOK: 'Facebook',
  TWITCH: 'Twitch',
  OTHER: 'Other',
};

type ViewMode = 'grid' | 'list';
type StatusFilter = CampaignStatus | 'ALL';

export function CampaignsList() {
  const { toast } = useToast();
  const liveApi = isApiLive();

  const [viewMode, setViewMode] = React.useState<ViewMode>('grid');
  const [statusFilter, setStatusFilter] = React.useState<StatusFilter>('ALL');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [sortBy, setSortBy] = React.useState<'date' | 'budget' | 'progress'>('date');

  // Live-mode data (fetched from GET /campaigns). In mock mode we fall back
  // to the hardcoded fixtures below instead of calling the facade, since the
  // facade's own mock branch returns an empty list, not this rich fixture.
  const [liveCampaigns, setLiveCampaigns] = React.useState<CampaignRow[]>([]);
  const [isLoading, setIsLoading] = React.useState(liveApi);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  const [reloadToken, setReloadToken] = React.useState(0);

  // Optimistic UI state for row actions (works in both live + mock mode,
  // since the facade methods themselves branch on isApiLive()).
  const [statusOverrides, setStatusOverrides] = React.useState<Record<string, CampaignStatus>>({});
  const [hiddenIds, setHiddenIds] = React.useState<Set<string>>(new Set());
  const [pendingActionId, setPendingActionId] = React.useState<string | null>(null);
  // Delete goes through a confirm dialog (deleting was previously immediate + irreversible).
  const [deleteTarget, setDeleteTarget] = React.useState<CampaignRow | null>(null);
  // Caller's own workspace role, for UX gating of Edit/Delete. Null = unknown (loading or the
  // members fetch failed) — we fail OPEN in that case (let the server enforce + show its clear
  // error) rather than wrongly disabling a real Owner's buttons on a flaky request.
  const [myRole, setMyRole] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!liveApi) return;
    let cancelled = false;
    api.workspaceMembers
      .list()
      .then((members) => {
        if (cancelled) return;
        const myId = localStorage.getItem('brand_user_id');
        setMyRole(members.find((m) => m.userId === myId)?.role ?? null);
      })
      .catch(() => {
        if (!cancelled) setMyRole(null);
      });
    return () => {
      cancelled = true;
    };
  }, [liveApi]);

  // Backend gates: edit = OWNER/ADMIN/MANAGER, delete = OWNER/ADMIN. Disable ONLY when we KNOW
  // the role is insufficient; when unknown (null) leave enabled so the server stays the source
  // of truth. These are UX hints — never the security boundary.
  const canEdit = !liveApi || myRole == null || ['OWNER', 'ADMIN', 'MANAGER'].includes(myRole);
  const canDelete = !liveApi || myRole == null || ['OWNER', 'ADMIN'].includes(myRole);

  React.useEffect(() => {
    if (!liveApi) return;
    let cancelled = false;
    setIsLoading(true);
    setLoadError(null);
    const t = window.setTimeout(async () => {
      try {
        const rows = await api.campaigns.list({
          status: statusFilter,
          search: searchQuery || undefined,
          limit: 100,
        });
        if (cancelled) return;
        setLiveCampaigns(
          rows.map((row) => {
            const extended = row as Campaign & { collaboratorsCount?: number; progress?: number };
            return {
              ...row,
              collaboratorsCount: extended.collaboratorsCount ?? 0,
              // No `progress` field exists on the API contract yet — default to 0
              // until the backend exposes a computed campaign progress value.
              progress: extended.progress ?? 0,
            };
          }),
        );
      } catch (e) {
        if (!cancelled) {
          setLoadError(e instanceof ApiError ? e.message : 'Could not load campaigns. Try again.');
        }
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }, searchQuery ? 300 : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(t);
    };
  }, [liveApi, statusFilter, searchQuery, reloadToken]);

  const allCampaigns = React.useMemo<CampaignRow[]>(() => {
    // Hype demo campaign renders first in mock mode — it's the demo-day hero.
    const base = liveApi ? liveCampaigns : [demoHypeCampaign, ...mockCampaigns];
    return base
      .filter((c) => !hiddenIds.has(c.id))
      .map((c) => (statusOverrides[c.id] ? { ...c, status: statusOverrides[c.id] } : c));
  }, [liveApi, liveCampaigns, hiddenIds, statusOverrides]);

  const handleDuplicate = async (campaign: CampaignRow) => {
    setPendingActionId(campaign.id);
    try {
      await api.campaigns.duplicate(campaign.id);
      toast({ title: 'Campaign duplicated', description: `A copy of "${campaign.title}" was created.` });
      if (liveApi) setReloadToken((k) => k + 1);
    } catch (e) {
      toast({
        title: 'Could not duplicate campaign',
        description: e instanceof ApiError ? e.message : 'Try again in a moment.',
        variant: 'destructive',
      });
    } finally {
      setPendingActionId(null);
    }
  };

  const handleToggleStatus = async (campaign: CampaignRow, nextStatus: 'PAUSED' | 'ACTIVE') => {
    setPendingActionId(campaign.id);
    try {
      await api.campaigns.update(campaign.id, { status: nextStatus });
      setStatusOverrides((prev) => ({ ...prev, [campaign.id]: nextStatus }));
      toast({ title: nextStatus === 'PAUSED' ? 'Campaign paused' : 'Campaign resumed' });
    } catch (e) {
      toast({
        title: 'Could not update campaign status',
        description: e instanceof ApiError ? e.message : 'Try again in a moment.',
        variant: 'destructive',
      });
    } finally {
      setPendingActionId(null);
    }
  };

  const handleDelete = async (campaign: CampaignRow) => {
    setPendingActionId(campaign.id);
    try {
      await api.campaigns.delete(campaign.id);
      setHiddenIds((prev) => new Set(prev).add(campaign.id));
      toast({ title: 'Campaign deleted', description: `"${campaign.title}" was removed.` });
    } catch (e) {
      // A permission failure (403) previously showed only a generic, easy-to-miss toast, so a
      // blocked delete looked like a dead button. Surface exactly why + what to do instead.
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
      setPendingActionId(null);
    }
  };

  const filteredCampaigns = React.useMemo(() => {
    let filtered = [...allCampaigns];

    // Search filter
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(
        (c) =>
          c.title.toLowerCase().includes(query) ||
          c.description?.toLowerCase().includes(query)
      );
    }

    // Status filter
    if (statusFilter !== 'ALL') {
      filtered = filtered.filter((c) => c.status === statusFilter);
    }

    // Sorting
    filtered.sort((a, b) => {
      switch (sortBy) {
        case 'date':
          return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
        case 'budget':
          // Meera-created drafts legitimately have no `budget` yet (set later
          // in the wizard) — treat missing budget as 0 so it sorts last.
          return (b.budget?.max ?? 0) - (a.budget?.max ?? 0);
        case 'progress':
          return b.progress - a.progress;
        default:
          return 0;
      }
    });

    return filtered;
  }, [allCampaigns, searchQuery, statusFilter, sortBy]);

  const stats = React.useMemo(() => ({
    total: allCampaigns.length,
    active: allCampaigns.filter((c) => c.status === 'ACTIVE').length,
    draft: allCampaigns.filter((c) => c.status === 'DRAFT').length,
    // Budget-less drafts (Meera-created, budget set later in the wizard)
    // contribute 0 rather than crashing the reduce.
    totalBudget: allCampaigns.reduce((sum, c) => sum + (c.budget?.max ?? 0), 0),
  }), [allCampaigns]);

  const formatBudget = (min?: number, max?: number) => {
    if (min == null || max == null) return 'No budget set';
    if (min === max) return `₹${(max / 1000).toFixed(0)}K`;
    return `₹${(min / 1000).toFixed(0)}K – ₹${(max / 1000).toFixed(0)}K`;
  };

  const formatDate = (date?: Date) => {
    // Meera-created drafts have no timeline yet — no deadline to show.
    if (!date) return 'No deadline';
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    }).format(new Date(date));
  };

  return (
    <div className="flex flex-col gap-6 p-4 lg:p-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-semibold tracking-tight lg:text-3xl">Campaigns</h1>
            {liveApi && isLoading && liveCampaigns.length > 0 && (
              <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-label="Refreshing" />
            )}
          </div>
          <p className="text-muted-foreground">
            Create and manage your influencer marketing campaigns
          </p>
        </div>
        <Button asChild>
          <Link to="/brand/campaigns/new">
            <Plus className="mr-2 h-4 w-4" />
            New Campaign
          </Link>
        </Button>
      </div>

      {/* Stats Cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">Total Campaigns</p>
                <p className="text-2xl font-bold">{stats.total}</p>
              </div>
              <IconBadge icon={TrendingUp} variant="primary" size="md" rounded="full" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">Active</p>
                <p className="text-2xl font-bold">{stats.active}</p>
              </div>
              <IconBadge icon={Play} variant="progress" size="md" rounded="full" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">Drafts</p>
                <p className="text-2xl font-bold">{stats.draft}</p>
              </div>
              <IconBadge icon={Edit} variant="draft" size="md" rounded="full" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">Total Budget</p>
                <p className="text-2xl font-bold">₹{(stats.totalBudget / 1000).toFixed(0)}K</p>
              </div>
              <IconBadge icon={DollarSign} variant="approved" size="md" rounded="full" />
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Filters */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-3 sm:flex-row sm:items-center">
          <div className="relative flex-1 sm:max-w-xs">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              type="search"
              placeholder="Search campaigns..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9"
            />
          </div>

          <Tabs
            value={statusFilter}
            onValueChange={(v) => setStatusFilter(v as StatusFilter)}
            className="hidden lg:block"
          >
            <TabsList>
              <TabsTrigger value="ALL">All</TabsTrigger>
              <TabsTrigger value="ACTIVE">Active</TabsTrigger>
              <TabsTrigger value="DRAFT">Drafts</TabsTrigger>
              <TabsTrigger value="PAUSED">Paused</TabsTrigger>
              <TabsTrigger value="COMPLETED">Completed</TabsTrigger>
            </TabsList>
          </Tabs>

          <Select
            value={statusFilter}
            onValueChange={(v) => setStatusFilter(v as StatusFilter)}
          >
            <SelectTrigger className="w-32 lg:hidden">
              <SelectValue placeholder="Status" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All</SelectItem>
              <SelectItem value="ACTIVE">Active</SelectItem>
              <SelectItem value="DRAFT">Drafts</SelectItem>
              <SelectItem value="PAUSED">Paused</SelectItem>
              <SelectItem value="COMPLETED">Completed</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-2">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm">
                <ArrowUpDown className="mr-2 h-4 w-4" />
                Sort
                <ChevronDown className="ml-2 h-3 w-3" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuLabel>Sort by</DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuCheckboxItem
                checked={sortBy === 'date'}
                onCheckedChange={() => setSortBy('date')}
              >
                Date created
              </DropdownMenuCheckboxItem>
              <DropdownMenuCheckboxItem
                checked={sortBy === 'budget'}
                onCheckedChange={() => setSortBy('budget')}
              >
                Budget
              </DropdownMenuCheckboxItem>
              <DropdownMenuCheckboxItem
                checked={sortBy === 'progress'}
                onCheckedChange={() => setSortBy('progress')}
              >
                Progress
              </DropdownMenuCheckboxItem>
            </DropdownMenuContent>
          </DropdownMenu>

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

      {/* Load error banner (kept above stale/last-known list, not blocking it) */}
      {liveApi && loadError && (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Could not load campaigns</AlertTitle>
          <AlertDescription>
            <span>{loadError}</span>
            <Button
              size="sm"
              variant="outline"
              className="mt-2"
              onClick={() => setReloadToken((k) => k + 1)}
            >
              <RefreshCw className="mr-2 h-3.5 w-3.5" />
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {/* Campaigns Grid/List */}
      {liveApi && isLoading && liveCampaigns.length === 0 && !loadError ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Card key={i}>
              <CardHeader className="pb-3">
                <Skeleton className="h-5 w-2/3" />
                <Skeleton className="h-5 w-16" />
              </CardHeader>
              <CardContent className="space-y-4">
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-4/5" />
                <Skeleton className="h-1.5 w-full" />
                <Skeleton className="h-8 w-full" />
              </CardContent>
            </Card>
          ))}
        </div>
      ) : filteredCampaigns.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="flex flex-col items-center justify-center py-16 text-center">
            <div className="flex h-14 w-14 items-center justify-center rounded-full bg-muted">
              <Search className="h-7 w-7 text-muted-foreground" />
            </div>
            <h3 className="mt-4 text-lg font-semibold">No campaigns found</h3>
            <p className="mt-1 text-sm text-muted-foreground">
              {searchQuery
                ? 'Try adjusting your search or filters'
                : 'Get started by creating your first campaign'}
            </p>
            {!searchQuery && (
              <Button asChild className="mt-4">
                <Link to="/brand/campaigns/new">
                  <Plus className="mr-2 h-4 w-4" />
                  Create Campaign
                </Link>
              </Button>
            )}
          </CardContent>
        </Card>
      ) : viewMode === 'grid' ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {filteredCampaigns.map((campaign) =>
            campaign.campaignType === 'HYPE' && campaign.hype ? (
              <HypeCampaignCard key={campaign.id} campaign={campaign} />
            ) : (
            <Card
              key={campaign.id}
              className="group overflow-hidden transition-colors hover:border-primary/50"
            >
              <CardHeader className="pb-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="space-y-1">
                    <Link
                      to={`/brand/campaigns/${campaign.id}`}
                      className="line-clamp-1 font-semibold hover:underline"
                    >
                      {campaign.title}
                    </Link>
                    <Badge variant={statusConfig[campaign.status].variant}>
                      {statusConfig[campaign.status].label}
                    </Badge>
                  </div>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0">
                        <MoreHorizontal className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem asChild>
                        <Link to={`/brand/campaigns/${campaign.id}`}>
                          <Eye className="mr-2 h-4 w-4" />
                          View Details
                        </Link>
                      </DropdownMenuItem>
                      {canEdit ? (
                        <DropdownMenuItem asChild>
                          <Link to={`/brand/campaigns/${campaign.id}/edit`}>
                            <Edit className="mr-2 h-4 w-4" />
                            Edit
                          </Link>
                        </DropdownMenuItem>
                      ) : (
                        <DropdownMenuItem disabled title="Only the Owner, Admin, or Manager can edit a campaign">
                          <Edit className="mr-2 h-4 w-4" />
                          Edit
                        </DropdownMenuItem>
                      )}
                      <DropdownMenuItem
                        disabled={pendingActionId === campaign.id}
                        onClick={() => handleDuplicate(campaign)}
                      >
                        <Copy className="mr-2 h-4 w-4" />
                        Duplicate
                      </DropdownMenuItem>
                      <DropdownMenuSeparator />
                      {campaign.status === 'ACTIVE' ? (
                        <DropdownMenuItem
                          disabled={pendingActionId === campaign.id}
                          onClick={() => handleToggleStatus(campaign, 'PAUSED')}
                        >
                          <Pause className="mr-2 h-4 w-4" />
                          Pause Campaign
                        </DropdownMenuItem>
                      ) : campaign.status === 'PAUSED' ? (
                        <DropdownMenuItem
                          disabled={pendingActionId === campaign.id}
                          onClick={() => handleToggleStatus(campaign, 'ACTIVE')}
                        >
                          <Play className="mr-2 h-4 w-4" />
                          Resume Campaign
                        </DropdownMenuItem>
                      ) : null}
                      <DropdownMenuSeparator />
                      <DropdownMenuItem
                        className="text-destructive-foreground"
                        disabled={!canDelete || pendingActionId === campaign.id}
                        title={!canDelete ? 'Only the workspace Owner or Admin can delete a campaign' : undefined}
                        onClick={() => setDeleteTarget(campaign)}
                      >
                        <Trash2 className="mr-2 h-4 w-4" />
                        Delete
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              </CardHeader>
              <CardContent className="space-y-4">
                <p className="line-clamp-2 text-sm text-muted-foreground">
                  {campaign.description}
                </p>

                <div className="flex flex-wrap gap-1.5">
                  {(campaign.platforms ?? []).slice(0, 3).map((platform) => (
                    <Badge key={platform} variant="outline" className="text-xs">
                      {platformLabels[platform]}
                    </Badge>
                  ))}
                  {(campaign.platforms?.length ?? 0) > 3 && (
                    <Badge variant="outline" className="text-xs">
                      +{campaign.platforms.length - 3}
                    </Badge>
                  )}
                </div>

                <div className="space-y-1.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-muted-foreground">Progress</span>
                    <span className="font-medium">{campaign.progress}%</span>
                  </div>
                  <Progress value={campaign.progress} className="h-1.5" />
                </div>

                <div className="grid grid-cols-3 gap-2 border-t border-border pt-4">
                  <div className="text-center">
                    <p className="text-xs text-muted-foreground">Budget</p>
                    <p className="text-sm font-medium">
                      {formatBudget(campaign.budget?.min, campaign.budget?.max)}
                    </p>
                  </div>
                  <div className="text-center">
                    <p className="text-xs text-muted-foreground">Creators</p>
                    <p className="text-sm font-medium">
                      {campaign.collaboratorsCount}/{campaign.maxCollaborators ?? 0}
                    </p>
                  </div>
                  <div className="text-center">
                    <p className="text-xs text-muted-foreground">Deadline</p>
                    <p className="text-sm font-medium">
                      {formatDate(campaign.timeline?.endDate)}
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>
            ),
          )}
        </div>
      ) : (
        <Card>
          <div className="divide-y divide-border">
            {filteredCampaigns.map((campaign) => (
              <div
                key={campaign.id}
                className="flex flex-col gap-4 p-4 transition-colors hover:bg-muted/50 sm:flex-row sm:items-center sm:justify-between"
              >
                <div className="flex-1 space-y-1">
                  <div className="flex items-center gap-3">
                    <Link
                      to={`/brand/campaigns/${campaign.id}`}
                      className="font-medium hover:underline"
                    >
                      {campaign.title}
                    </Link>
                    <Badge variant={statusConfig[campaign.status].variant}>
                      {statusConfig[campaign.status].label}
                    </Badge>
                    {campaign.campaignType === 'HYPE' && (
                      <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
                        <Zap className="h-3 w-3" aria-hidden="true" /> Hype
                      </Badge>
                    )}
                  </div>
                  <p className="line-clamp-1 text-sm text-muted-foreground">
                    {campaign.description}
                  </p>
                  <div className="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
                    <span className="flex items-center gap-1">
                      <DollarSign className="h-3 w-3" />
                      {formatBudget(campaign.budget?.min, campaign.budget?.max)}
                    </span>
                    <span className="flex items-center gap-1">
                      <Users className="h-3 w-3" />
                      {campaign.collaboratorsCount}/{campaign.maxCollaborators ?? 0} creators
                    </span>
                    <span className="flex items-center gap-1">
                      <Calendar className="h-3 w-3" />
                      {formatDate(campaign.timeline?.endDate)}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-4">
                  <div className="hidden w-32 space-y-1 lg:block">
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-muted-foreground">Progress</span>
                      <span className="font-medium">{campaign.progress}%</span>
                    </div>
                    <Progress value={campaign.progress} className="h-1.5" />
                  </div>

                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon" className="h-8 w-8">
                        <MoreHorizontal className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem asChild>
                        <Link to={`/brand/campaigns/${campaign.id}`}>
                          <Eye className="mr-2 h-4 w-4" />
                          View Details
                        </Link>
                      </DropdownMenuItem>
                      {canEdit ? (
                        <DropdownMenuItem asChild>
                          <Link to={`/brand/campaigns/${campaign.id}/edit`}>
                            <Edit className="mr-2 h-4 w-4" />
                            Edit
                          </Link>
                        </DropdownMenuItem>
                      ) : (
                        <DropdownMenuItem disabled title="Only the Owner, Admin, or Manager can edit a campaign">
                          <Edit className="mr-2 h-4 w-4" />
                          Edit
                        </DropdownMenuItem>
                      )}
                      <DropdownMenuItem
                        disabled={pendingActionId === campaign.id}
                        onClick={() => handleDuplicate(campaign)}
                      >
                        <Copy className="mr-2 h-4 w-4" />
                        Duplicate
                      </DropdownMenuItem>
                      <DropdownMenuSeparator />
                      {campaign.status === 'ACTIVE' ? (
                        <DropdownMenuItem
                          disabled={pendingActionId === campaign.id}
                          onClick={() => handleToggleStatus(campaign, 'PAUSED')}
                        >
                          <Pause className="mr-2 h-4 w-4" />
                          Pause Campaign
                        </DropdownMenuItem>
                      ) : campaign.status === 'PAUSED' ? (
                        <DropdownMenuItem
                          disabled={pendingActionId === campaign.id}
                          onClick={() => handleToggleStatus(campaign, 'ACTIVE')}
                        >
                          <Play className="mr-2 h-4 w-4" />
                          Resume Campaign
                        </DropdownMenuItem>
                      ) : null}
                      <DropdownMenuSeparator />
                      <DropdownMenuItem
                        className="text-destructive-foreground"
                        disabled={!canDelete || pendingActionId === campaign.id}
                        title={!canDelete ? 'Only the workspace Owner or Admin can delete a campaign' : undefined}
                        onClick={() => setDeleteTarget(campaign)}
                      >
                        <Trash2 className="mr-2 h-4 w-4" />
                        Delete
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              </div>
            ))}
          </div>
        </Card>
      )}

      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete campaign?</AlertDialogTitle>
            <AlertDialogDescription>
              This permanently deletes &ldquo;{deleteTarget?.title}&rdquo;. This can&rsquo;t be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={pendingActionId === deleteTarget?.id}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={pendingActionId === deleteTarget?.id}
              onClick={async (e) => {
                e.preventDefault(); // keep the dialog open until the request resolves
                const target = deleteTarget;
                if (!target) return;
                await handleDelete(target);
                setDeleteTarget(null);
              }}
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
