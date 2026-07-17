'use client';

import * as React from 'react';
import Link from 'next/link';
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
} from 'lucide-react';

import { cn } from '@/lib/utils';
import type { Campaign, CampaignStatus, Platform } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
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

// Mock campaigns data
const mockCampaigns: (Campaign & { collaboratorsCount: number; progress: number })[] = [
  {
    id: '1',
    workspaceId: 'ws-1',
    title: 'Summer Collection Launch',
    description: 'Promote our new summer fashion line with lifestyle content',
    objectives: ['Brand awareness', 'Drive sales'],
    status: 'ACTIVE',
    budget: { min: 20000, max: 25000, currency: 'USD' },
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
    budget: { min: 10000, max: 15000, currency: 'USD' },
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
    budget: { min: 45000, max: 50000, currency: 'USD' },
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
    id: '4',
    workspaceId: 'ws-1',
    title: 'Spring Wellness Series',
    description: 'Health and wellness focused content campaign',
    objectives: ['Brand positioning', 'Engagement'],
    status: 'COMPLETED',
    budget: { min: 15000, max: 18000, currency: 'USD' },
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
    budget: { min: 30000, max: 35000, currency: 'USD' },
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
  const [viewMode, setViewMode] = React.useState<ViewMode>('grid');
  const [statusFilter, setStatusFilter] = React.useState<StatusFilter>('ALL');
  const [searchQuery, setSearchQuery] = React.useState('');
  const [sortBy, setSortBy] = React.useState<'date' | 'budget' | 'progress'>('date');

  const filteredCampaigns = React.useMemo(() => {
    let filtered = [...mockCampaigns];

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
          return b.budget.max - a.budget.max;
        case 'progress':
          return b.progress - a.progress;
        default:
          return 0;
      }
    });

    return filtered;
  }, [searchQuery, statusFilter, sortBy]);

  const stats = React.useMemo(() => ({
    total: mockCampaigns.length,
    active: mockCampaigns.filter((c) => c.status === 'ACTIVE').length,
    draft: mockCampaigns.filter((c) => c.status === 'DRAFT').length,
    totalBudget: mockCampaigns.reduce((sum, c) => sum + c.budget.max, 0),
  }), []);

  const formatBudget = (min: number, max: number) => {
    if (min === max) return `$${(max / 1000).toFixed(0)}K`;
    return `$${(min / 1000).toFixed(0)}K - $${(max / 1000).toFixed(0)}K`;
  };

  const formatDate = (date: Date) => {
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
          <h1 className="text-2xl font-semibold tracking-tight lg:text-3xl">Campaigns</h1>
          <p className="text-muted-foreground">
            Create and manage your influencer marketing campaigns
          </p>
        </div>
        <Button asChild>
          <Link href="/brand/campaigns/new">
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
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10">
                <TrendingUp className="h-5 w-5 text-primary" />
              </div>
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
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-chart-2/10">
                <Play className="h-5 w-5 text-chart-2" />
              </div>
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
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-muted">
                <Edit className="h-5 w-5 text-muted-foreground" />
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">Total Budget</p>
                <p className="text-2xl font-bold">${(stats.totalBudget / 1000).toFixed(0)}K</p>
              </div>
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-chart-3/10">
                <DollarSign className="h-5 w-5 text-chart-3" />
              </div>
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

      {/* Campaigns Grid/List */}
      {filteredCampaigns.length === 0 ? (
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
                <Link href="/brand/campaigns/new">
                  <Plus className="mr-2 h-4 w-4" />
                  Create Campaign
                </Link>
              </Button>
            )}
          </CardContent>
        </Card>
      ) : viewMode === 'grid' ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {filteredCampaigns.map((campaign) => (
            <Card
              key={campaign.id}
              className="group overflow-hidden transition-colors hover:border-primary/50"
            >
              <CardHeader className="pb-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="space-y-1">
                    <Link
                      href={`/brand/campaigns/${campaign.id}`}
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
                        <Link href={`/brand/campaigns/${campaign.id}`}>
                          <Eye className="mr-2 h-4 w-4" />
                          View Details
                        </Link>
                      </DropdownMenuItem>
                      <DropdownMenuItem asChild>
                        <Link href={`/brand/campaigns/${campaign.id}/edit`}>
                          <Edit className="mr-2 h-4 w-4" />
                          Edit
                        </Link>
                      </DropdownMenuItem>
                      <DropdownMenuItem>
                        <Copy className="mr-2 h-4 w-4" />
                        Duplicate
                      </DropdownMenuItem>
                      <DropdownMenuSeparator />
                      {campaign.status === 'ACTIVE' ? (
                        <DropdownMenuItem>
                          <Pause className="mr-2 h-4 w-4" />
                          Pause Campaign
                        </DropdownMenuItem>
                      ) : campaign.status === 'PAUSED' ? (
                        <DropdownMenuItem>
                          <Play className="mr-2 h-4 w-4" />
                          Resume Campaign
                        </DropdownMenuItem>
                      ) : null}
                      <DropdownMenuSeparator />
                      <DropdownMenuItem className="text-destructive">
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
                  {campaign.platforms.slice(0, 3).map((platform) => (
                    <Badge key={platform} variant="outline" className="text-xs">
                      {platformLabels[platform]}
                    </Badge>
                  ))}
                  {campaign.platforms.length > 3 && (
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
                      {formatBudget(campaign.budget.min, campaign.budget.max)}
                    </p>
                  </div>
                  <div className="text-center">
                    <p className="text-xs text-muted-foreground">Creators</p>
                    <p className="text-sm font-medium">
                      {campaign.collaboratorsCount}/{campaign.maxCollaborators}
                    </p>
                  </div>
                  <div className="text-center">
                    <p className="text-xs text-muted-foreground">Deadline</p>
                    <p className="text-sm font-medium">
                      {formatDate(campaign.timeline.endDate)}
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
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
                      href={`/brand/campaigns/${campaign.id}`}
                      className="font-medium hover:underline"
                    >
                      {campaign.title}
                    </Link>
                    <Badge variant={statusConfig[campaign.status].variant}>
                      {statusConfig[campaign.status].label}
                    </Badge>
                  </div>
                  <p className="line-clamp-1 text-sm text-muted-foreground">
                    {campaign.description}
                  </p>
                  <div className="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
                    <span className="flex items-center gap-1">
                      <DollarSign className="h-3 w-3" />
                      {formatBudget(campaign.budget.min, campaign.budget.max)}
                    </span>
                    <span className="flex items-center gap-1">
                      <Users className="h-3 w-3" />
                      {campaign.collaboratorsCount}/{campaign.maxCollaborators} creators
                    </span>
                    <span className="flex items-center gap-1">
                      <Calendar className="h-3 w-3" />
                      {formatDate(campaign.timeline.endDate)}
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
                        <Link href={`/brand/campaigns/${campaign.id}`}>
                          <Eye className="mr-2 h-4 w-4" />
                          View Details
                        </Link>
                      </DropdownMenuItem>
                      <DropdownMenuItem asChild>
                        <Link href={`/brand/campaigns/${campaign.id}/edit`}>
                          <Edit className="mr-2 h-4 w-4" />
                          Edit
                        </Link>
                      </DropdownMenuItem>
                      <DropdownMenuItem>
                        <Copy className="mr-2 h-4 w-4" />
                        Duplicate
                      </DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem className="text-destructive">
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
    </div>
  );
}
