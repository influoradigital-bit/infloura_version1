'use client';

import * as React from 'react';
import Link from 'next/link';
import {
  ArrowUpRight,
  ArrowDownRight,
  TrendingUp,
  Users,
  Megaphone,
  DollarSign,
  FileCheck,
  Clock,
  ChevronRight,
  MoreHorizontal,
  Eye,
  MessageSquare,
  CheckCircle2,
  AlertCircle,
  Calendar,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { useAuthStore } from '@/lib/store';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Progress } from '@/components/ui/progress';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

// Mock data for demonstration
const stats = [
  {
    name: 'Active Campaigns',
    value: '12',
    change: '+2',
    changeType: 'positive' as const,
    icon: Megaphone,
  },
  {
    name: 'Active Collaborations',
    value: '48',
    change: '+8',
    changeType: 'positive' as const,
    icon: Users,
  },
  {
    name: 'Total Spent',
    value: '$124,500',
    change: '+12.5%',
    changeType: 'positive' as const,
    icon: DollarSign,
  },
  {
    name: 'Pending Deliverables',
    value: '23',
    change: '-5',
    changeType: 'positive' as const,
    icon: FileCheck,
  },
];

const activeCampaigns = [
  {
    id: '1',
    title: 'Summer Collection Launch',
    status: 'ACTIVE',
    budget: '$25,000',
    collaborators: 8,
    maxCollaborators: 10,
    progress: 65,
    deadline: '2024-06-15',
  },
  {
    id: '2',
    title: 'Holiday Gift Guide',
    status: 'ACTIVE',
    budget: '$15,000',
    collaborators: 5,
    maxCollaborators: 6,
    progress: 40,
    deadline: '2024-07-01',
  },
  {
    id: '3',
    title: 'Brand Ambassador Program',
    status: 'DRAFT',
    budget: '$50,000',
    collaborators: 0,
    maxCollaborators: 20,
    progress: 0,
    deadline: '2024-08-01',
  },
];

const recentActivity = [
  {
    id: '1',
    type: 'deliverable_submitted',
    title: 'New deliverable submitted',
    description: '@sarahcreates submitted content for Summer Collection',
    time: '2 hours ago',
    icon: FileCheck,
    iconColor: 'text-chart-2',
  },
  {
    id: '2',
    type: 'proposal_received',
    title: 'New proposal received',
    description: '@mikephoto sent a proposal for Holiday Guide',
    time: '4 hours ago',
    icon: MessageSquare,
    iconColor: 'text-primary',
  },
  {
    id: '3',
    type: 'contract_signed',
    title: 'Contract signed',
    description: '@lifestylejane signed the collaboration contract',
    time: '6 hours ago',
    icon: CheckCircle2,
    iconColor: 'text-chart-2',
  },
  {
    id: '4',
    type: 'revision_requested',
    title: 'Revision requested',
    description: 'You requested changes on @techreview content',
    time: '1 day ago',
    icon: AlertCircle,
    iconColor: 'text-chart-3',
  },
];

const pendingActions = [
  {
    id: '1',
    title: 'Review deliverable from @sarahcreates',
    campaign: 'Summer Collection Launch',
    dueDate: 'Due in 2 days',
    priority: 'high',
  },
  {
    id: '2',
    title: 'Respond to proposal from @mikephoto',
    campaign: 'Holiday Gift Guide',
    dueDate: 'Due in 3 days',
    priority: 'medium',
  },
  {
    id: '3',
    title: 'Release payment for @lifestylejane',
    campaign: 'Brand Ambassador Program',
    dueDate: 'Due today',
    priority: 'high',
  },
];

const topCreators = [
  {
    id: '1',
    name: 'Sarah Creates',
    handle: '@sarahcreates',
    avatar: null,
    campaigns: 5,
    totalEarned: '$12,500',
    rating: 4.9,
  },
  {
    id: '2',
    name: 'Mike Photography',
    handle: '@mikephoto',
    avatar: null,
    campaigns: 3,
    totalEarned: '$8,200',
    rating: 4.8,
  },
  {
    id: '3',
    name: 'Lifestyle Jane',
    handle: '@lifestylejane',
    avatar: null,
    campaigns: 4,
    totalEarned: '$9,800',
    rating: 4.7,
  },
];

export function DashboardPage() {
  const { user, workspace } = useAuthStore();

  const getGreeting = () => {
    const hour = new Date().getHours();
    if (hour < 12) return 'Good morning';
    if (hour < 18) return 'Good afternoon';
    return 'Good evening';
  };

  return (
    <div className="flex flex-col gap-6 p-4 lg:p-6">
      {/* Header */}
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight lg:text-3xl">
          {getGreeting()}, {user?.firstName || user?.displayName || 'there'}
        </h1>
        <p className="text-muted-foreground">
          Here&apos;s what&apos;s happening with your campaigns today.
        </p>
      </div>

      {/* Stats Grid */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((stat) => (
          <Card key={stat.name} className="relative overflow-hidden">
            <CardHeader className="flex flex-row items-center justify-between pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">
                {stat.name}
              </CardTitle>
              <stat.icon className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{stat.value}</div>
              <div className="flex items-center gap-1 text-xs">
                {stat.changeType === 'positive' ? (
                  <ArrowUpRight className="h-3 w-3 text-chart-2" />
                ) : (
                  <ArrowDownRight className="h-3 w-3 text-destructive" />
                )}
                <span
                  className={cn(
                    stat.changeType === 'positive'
                      ? 'text-chart-2'
                      : 'text-destructive'
                  )}
                >
                  {stat.change}
                </span>
                <span className="text-muted-foreground">from last month</span>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Main Content Grid */}
      <div className="grid gap-6 lg:grid-cols-3">
        {/* Active Campaigns */}
        <Card className="lg:col-span-2">
          <CardHeader className="flex flex-row items-center justify-between">
            <div>
              <CardTitle>Active Campaigns</CardTitle>
              <CardDescription>Your ongoing marketing campaigns</CardDescription>
            </div>
            <Button variant="ghost" size="sm" asChild>
              <Link href="/brand/campaigns">
                View all
                <ChevronRight className="ml-1 h-4 w-4" />
              </Link>
            </Button>
          </CardHeader>
          <CardContent className="space-y-4">
            {activeCampaigns.map((campaign) => (
              <div
                key={campaign.id}
                className="flex flex-col gap-3 rounded-lg border border-border p-4 transition-colors hover:bg-muted/50"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="flex-1 space-y-1">
                    <div className="flex items-center gap-2">
                      <Link
                        href={`/brand/campaigns/${campaign.id}`}
                        className="font-medium hover:underline"
                      >
                        {campaign.title}
                      </Link>
                      <Badge
                        variant={
                          campaign.status === 'ACTIVE' ? 'default' : 'secondary'
                        }
                        className="text-xs"
                      >
                        {campaign.status}
                      </Badge>
                    </div>
                    <div className="flex flex-wrap items-center gap-4 text-sm text-muted-foreground">
                      <span className="flex items-center gap-1">
                        <DollarSign className="h-3.5 w-3.5" />
                        {campaign.budget}
                      </span>
                      <span className="flex items-center gap-1">
                        <Users className="h-3.5 w-3.5" />
                        {campaign.collaborators}/{campaign.maxCollaborators} creators
                      </span>
                      <span className="flex items-center gap-1">
                        <Calendar className="h-3.5 w-3.5" />
                        {campaign.deadline}
                      </span>
                    </div>
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
                      <DropdownMenuItem>Edit Campaign</DropdownMenuItem>
                      <DropdownMenuItem className="text-destructive">
                        Pause Campaign
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-muted-foreground">Campaign Progress</span>
                    <span className="font-medium">{campaign.progress}%</span>
                  </div>
                  <Progress value={campaign.progress} className="h-1.5" />
                </div>
              </div>
            ))}
          </CardContent>
        </Card>

        {/* Pending Actions */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Clock className="h-5 w-5" />
              Pending Actions
            </CardTitle>
            <CardDescription>Tasks that need your attention</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {pendingActions.map((action) => (
              <div
                key={action.id}
                className="flex flex-col gap-2 rounded-lg border border-border p-3 transition-colors hover:bg-muted/50"
              >
                <div className="flex items-start justify-between gap-2">
                  <span className="text-sm font-medium leading-tight">
                    {action.title}
                  </span>
                  {action.priority === 'high' && (
                    <Badge variant="destructive" className="text-xs shrink-0">
                      Urgent
                    </Badge>
                  )}
                </div>
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>{action.campaign}</span>
                  <span className={cn(
                    action.dueDate.includes('today') && 'text-destructive font-medium'
                  )}>
                    {action.dueDate}
                  </span>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>

      {/* Bottom Grid */}
      <div className="grid gap-6 lg:grid-cols-2">
        {/* Recent Activity */}
        <Card>
          <CardHeader>
            <CardTitle>Recent Activity</CardTitle>
            <CardDescription>Latest updates from your collaborations</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {recentActivity.map((activity, index) => (
                <div key={activity.id} className="flex gap-4">
                  <div
                    className={cn(
                      'flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted',
                      activity.iconColor
                    )}
                  >
                    <activity.icon className="h-4 w-4" />
                  </div>
                  <div className="flex-1 space-y-1">
                    <p className="text-sm font-medium leading-none">
                      {activity.title}
                    </p>
                    <p className="text-sm text-muted-foreground">
                      {activity.description}
                    </p>
                    <p className="text-xs text-muted-foreground">{activity.time}</p>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* Top Creators */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <div>
              <CardTitle>Top Creators</CardTitle>
              <CardDescription>Your most active collaborators</CardDescription>
            </div>
            <Button variant="ghost" size="sm" asChild>
              <Link href="/brand/discover">
                Find more
                <ChevronRight className="ml-1 h-4 w-4" />
              </Link>
            </Button>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {topCreators.map((creator) => (
                <div
                  key={creator.id}
                  className="flex items-center justify-between gap-4"
                >
                  <div className="flex items-center gap-3">
                    <Avatar>
                      <AvatarImage src={creator.avatar || undefined} />
                      <AvatarFallback className="bg-primary/10 text-primary">
                        {creator.name.charAt(0)}
                      </AvatarFallback>
                    </Avatar>
                    <div>
                      <p className="text-sm font-medium leading-none">
                        {creator.name}
                      </p>
                      <p className="text-sm text-muted-foreground">
                        {creator.handle}
                      </p>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-medium">{creator.totalEarned}</p>
                    <p className="text-xs text-muted-foreground">
                      {creator.campaigns} campaigns
                    </p>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
