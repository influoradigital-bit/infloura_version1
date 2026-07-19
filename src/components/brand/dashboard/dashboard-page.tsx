import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Eye,
  FileText,
  IndianRupee,
  MessageSquare,
  Target,
  Timer,
  Wallet,
  Zap,
} from 'lucide-react';

import { useAuthStore } from '@/lib/store';
import { api } from '@/lib/api';
import { cn } from '@/lib/utils';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { TrendSparkNudgeCard } from '@/components/trendspark/TrendSparkNudgeCard';

// ---------------------------------------------------------------------------
// Backend endpoints:
//   GET /dashboard/actions  → api.dashboard.actions('brand')
//   GET /dashboard/pipeline → api.dashboard.pipeline('brand')
//   GET /wallet             → api.wallet.get('brand')
// ---------------------------------------------------------------------------

interface ActionItem {
  id: string;
  type: 'deliverable_review' | 'counter_proposal' | 'payment_release' | 'sign_contract';
  title: string;
  subtitle: string;
  deadline: Date;
  priority: 'urgent' | 'high' | 'medium';
  amount: number;
  link: string;
}

const mockActionItems: ActionItem[] = [
  {
    id: '1',
    type: 'deliverable_review',
    title: 'Review deliverable from Priya Sharma',
    subtitle: 'Summer Fashion Campaign',
    deadline: new Date(Date.now() + 4 * 60 * 60 * 1000),
    priority: 'urgent',
    amount: 45000,
    link: '/brand/chat?deal=deal-1',
  },
  {
    id: '2',
    type: 'counter_proposal',
    title: 'Respond to counter-offer',
    subtitle: 'Arjun Kapoor — Tech Review Campaign',
    deadline: new Date(Date.now() + 18 * 60 * 60 * 1000),
    priority: 'high',
    amount: 75000,
    link: '/brand/chat?deal=deal-2',
  },
  {
    id: '3',
    type: 'payment_release',
    title: 'Release milestone payment',
    subtitle: 'Sneha Reddy — Wellness Series',
    deadline: new Date(Date.now() + 2 * 60 * 60 * 1000),
    priority: 'urgent',
    amount: 25000,
    link: '/brand/chat?deal=deal-3',
  },
  {
    id: '4',
    type: 'sign_contract',
    title: 'Sign pending contract',
    subtitle: 'Rahul Verma — Product Launch',
    deadline: new Date(Date.now() + 48 * 60 * 60 * 1000),
    priority: 'medium',
    amount: 120000,
    link: '/brand/chat?deal=deal-4',
  },
];

const mockWallet = {
  availableBalance: 285000,
  escrowLocked: 450000,
  runwayDays: 47,
};

const mockPipeline = [
  { stage: 'Outreach', count: 8 },
  { stage: 'Negotiating', count: 5 },
  { stage: 'Contracted', count: 3 },
  { stage: 'In Progress', count: 12 },
  { stage: 'Review', count: 4 },
  { stage: 'Settled', count: 28 },
];

const formatINR = (amount: number) => {
  if (amount >= 100000) return `₹${(amount / 100000).toFixed(1)}L`;
  if (amount >= 1000) return `₹${(amount / 1000).toFixed(0)}K`;
  return `₹${amount}`;
};

const getTimeRemaining = (deadline: Date) => {
  const diff = deadline.getTime() - Date.now();
  if (diff < 0) return 'Overdue';
  const hours = Math.floor(diff / (1000 * 60 * 60));
  const days = Math.floor(hours / 24);
  if (days > 0) return `${days}d ${hours % 24}h`;
  if (hours > 0) return `${hours}h`;
  return 'Soon';
};

const getActionIcon = (type: ActionItem['type']) => {
  switch (type) {
    case 'deliverable_review': return Eye;
    case 'counter_proposal': return MessageSquare;
    case 'payment_release': return IndianRupee;
    case 'sign_contract': return FileText;
    default: return Zap;
  }
};

const getPriorityBadge = (priority: ActionItem['priority']) => {
  switch (priority) {
    case 'urgent': return 'bg-destructive/15 text-destructive-foreground border-destructive-foreground/30';
    case 'high': return 'bg-warning/15 text-warning border-warning/30';
    default: return 'bg-primary/15 text-primary border-primary/30';
  }
};

export function DashboardPage() {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [actionItems, setActionItems] = React.useState<ActionItem[]>(mockActionItems);
  const [wallet, setWallet] = React.useState(mockWallet);
  const [pipeline, setPipeline] = React.useState(mockPipeline);

  // Load real data when the API switches to live mode
  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [actions, walletData, pipelineData] = await Promise.all([
          api.dashboard.actions('brand'),
          api.wallet.get('brand'),
          api.dashboard.pipeline('brand'),
        ]);
        if (cancelled) return;
        if (Array.isArray(actions) && actions.length > 0) {
          setActionItems(
            actions.map((a) => ({ ...a, deadline: new Date(a.deadline) })) as ActionItem[],
          );
        }
        if (walletData) {
          setWallet({
            availableBalance: walletData.availableBalance ?? mockWallet.availableBalance,
            escrowLocked: walletData.escrowLocked ?? mockWallet.escrowLocked,
            runwayDays: walletData.runwayDays ?? mockWallet.runwayDays,
          });
        }
        if (Array.isArray(pipelineData) && pipelineData.length > 0) {
          setPipeline(pipelineData);
        }
      } catch (err) {
        console.error('Dashboard data load failed', err);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const greeting = React.useMemo(() => {
    const hour = new Date().getHours();
    if (hour < 12) return 'Good morning';
    if (hour < 17) return 'Good afternoon';
    return 'Good evening';
  }, []);

  const urgentCount = actionItems.filter((a) => a.priority === 'urgent').length;
  const walletHealth =
    wallet.runwayDays > 30 ? 'healthy' : wallet.runwayDays > 14 ? 'warning' : 'critical';
  const walletHealthBadge =
    walletHealth === 'healthy' ? 'Healthy' : walletHealth === 'warning' ? 'Low' : 'Critical';
  const pipelineTotal = pipeline.reduce((s, p) => s + p.count, 0);

  return (
    <div className="p-6 space-y-6 max-w-5xl mx-auto">
      {/* Header — single primary CTA */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">
            {greeting}, {user?.firstName || 'there'}
          </h1>
          <p className="text-muted-foreground mt-1 text-sm">
            {urgentCount > 0
              ? `${urgentCount} urgent action${urgentCount > 1 ? 's' : ''} need your attention`
              : 'You\'re all caught up.'}
          </p>
        </div>
        <Button onClick={() => navigate('/brand/campaigns/new')} size="lg" className="gap-2">
          <Target className="h-4 w-4" />
          New Campaign
        </Button>
      </div>

      {/* HERO: Action Stack — full width, the only thing above the fold */}
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between">
            <CardTitle className="text-lg font-medium flex items-center gap-2">
              <Timer className="h-5 w-5 text-orange-500" />
              Requires Your Action
            </CardTitle>
            <Badge variant="secondary">{actionItems.length} pending</Badge>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          {actionItems.map((item) => {
            const Icon = getActionIcon(item.type);
            const timeLeft = getTimeRemaining(item.deadline);
            const isOverdue = item.deadline.getTime() < Date.now();
            const isUrgent = item.priority === 'urgent';
            return (
              <button
                key={item.id}
                onClick={() => navigate(item.link)}
                className={cn(
                  'flex w-full items-center gap-4 p-4 rounded-lg border text-left transition-all hover:shadow-sm',
                  isUrgent ? 'bg-destructive/10 border-destructive-foreground/30' : 'bg-card',
                )}
              >
                <div
                  className={cn(
                    'h-10 w-10 rounded-full flex items-center justify-center flex-shrink-0',
                    isUrgent ? 'bg-destructive/15 text-destructive-foreground' : 'bg-muted text-muted-foreground',
                  )}
                >
                  <Icon className="h-5 w-5" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium truncate">{item.title}</p>
                  <p className="text-sm text-muted-foreground truncate">{item.subtitle}</p>
                </div>
                <div className="hidden sm:flex flex-col items-end shrink-0 gap-1">
                  <p className="text-sm font-medium">{formatINR(item.amount)}</p>
                  <p
                    className={cn(
                      'text-xs',
                      isOverdue ? 'text-destructive-foreground' : isUrgent ? 'text-warning' : 'text-muted-foreground',
                    )}
                  >
                    {isOverdue ? 'Overdue' : timeLeft}
                  </p>
                </div>
                <Badge className={cn('hidden md:flex', getPriorityBadge(item.priority))}>
                  {item.priority}
                </Badge>
                <ArrowRight className="h-4 w-4 text-muted-foreground shrink-0" />
              </button>
            );
          })}

          {actionItems.length === 0 && (
            <div className="text-center py-12">
              <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-success/15">
                <CheckCircle2 className="h-6 w-6 text-success" />
              </div>
              <p className="font-medium">All caught up!</p>
              <p className="text-sm text-muted-foreground">No pending actions right now.</p>
            </div>
          )}
        </CardContent>
      </Card>

      {/* SECONDARY ROW: pipeline + wallet — single line, dense */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <PipelineCard
          pipeline={pipeline}
          total={pipelineTotal}
          onClickStage={(stage) =>
            navigate(`/brand/campaigns?status=${stage.toLowerCase().replace(' ', '-')}`)
          }
          onViewAll={() => navigate('/brand/campaigns')}
        />

        <WalletCard
          balance={wallet.availableBalance}
          escrow={wallet.escrowLocked}
          runwayDays={wallet.runwayDays}
          health={walletHealth}
          healthLabel={walletHealthBadge}
          onManage={() => navigate('/brand/wallet')}
        />
      </div>

      {/* Trend-Spark soft nudge (T7) — renders nothing while loading/on error/204/dismissed,
          so it never leaves a gap when there's nothing to suggest. */}
      <TrendSparkNudgeCard />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Pipeline summary — segmented bar + legend, no SLA noise
// ---------------------------------------------------------------------------

interface PipelineCardProps {
  pipeline: Array<{ stage: string; count: number }>;
  total: number;
  onClickStage: (stage: string) => void;
  onViewAll: () => void;
}

const STAGE_COLOR: Record<string, string> = {
  Outreach: 'bg-stage-outreach',
  Negotiating: 'bg-stage-negotiating',
  Contracted: 'bg-stage-contracted',
  'In Progress': 'bg-stage-progress',
  Review: 'bg-stage-review',
  Settled: 'bg-stage-approved',
};

function PipelineCard({ pipeline, total, onClickStage, onViewAll }: PipelineCardProps) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base font-medium">Pipeline</CardTitle>
          <Button variant="ghost" size="sm" onClick={onViewAll}>
            View all <ArrowRight className="h-3.5 w-3.5 ml-1" />
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        <div className="flex h-6 rounded-lg overflow-hidden mb-3">
          {pipeline.map((stage) => (
            <button
              key={stage.stage}
              type="button"
              className={cn(
                'flex items-center justify-center text-[10px] font-medium text-white hover:opacity-90 transition-opacity',
                STAGE_COLOR[stage.stage] || 'bg-muted',
              )}
              style={{ width: total ? `${(stage.count / total) * 100}%` : '0%' }}
              title={`${stage.stage}: ${stage.count}`}
              onClick={() => onClickStage(stage.stage)}
            >
              {stage.count > 3 && stage.count}
            </button>
          ))}
        </div>
        <div className="grid grid-cols-3 gap-1.5 text-xs">
          {pipeline.map((stage) => (
            <div key={stage.stage} className="flex items-center gap-1.5">
              <span className={cn('h-2 w-2 rounded-full shrink-0', STAGE_COLOR[stage.stage] || 'bg-muted')} />
              <span className="font-medium">{stage.count}</span>
              <span className="text-muted-foreground truncate">{stage.stage}</span>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Wallet — compact card, just the runway + balance + one CTA
// ---------------------------------------------------------------------------

interface WalletCardProps {
  balance: number;
  escrow: number;
  runwayDays: number;
  health: 'healthy' | 'warning' | 'critical';
  healthLabel: string;
  onManage: () => void;
}

function WalletCard({ balance, escrow, runwayDays, health, healthLabel, onManage }: WalletCardProps) {
  return (
    <Card
      className={cn(
        health === 'critical' && 'border-destructive-foreground/30',
        health === 'warning' && 'border-warning/30',
      )}
    >
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base font-medium flex items-center gap-2">
            <Wallet className="h-4 w-4" />
            Wallet
          </CardTitle>
          <Badge
            variant={health === 'healthy' ? 'secondary' : 'destructive'}
            className="text-xs"
          >
            {healthLabel}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-end justify-between">
          <div>
            <p className="text-2xl font-bold leading-none">{formatINR(balance)}</p>
            <p className="text-xs text-muted-foreground mt-1">Available · {formatINR(escrow)} in escrow</p>
          </div>
          <div className="text-right">
            <p
              className={cn(
                'text-sm font-semibold',
                health === 'healthy' && 'text-success',
                health === 'warning' && 'text-warning',
                health === 'critical' && 'text-destructive-foreground',
              )}
            >
              {runwayDays}d runway
            </p>
            {health !== 'healthy' && (
              <p className="text-[10px] text-muted-foreground flex items-center gap-0.5 justify-end">
                <AlertTriangle className="h-2.5 w-2.5" /> Low
              </p>
            )}
          </div>
        </div>
        <Progress
          value={Math.min((runwayDays / 60) * 100, 100)}
          className={cn(
            'h-1.5',
            health === 'critical' && '[&>div]:bg-destructive',
            health === 'warning' && '[&>div]:bg-warning',
          )}
        />
        <Button
          size="sm"
          variant={health === 'healthy' ? 'outline' : 'default'}
          className="w-full"
          onClick={onManage}
        >
          {health === 'healthy' ? 'Manage wallet' : 'Recharge now'}
        </Button>
      </CardContent>
    </Card>
  );
}

// Default export — used by /brand/dashboard page wrapper
export default DashboardPage;
