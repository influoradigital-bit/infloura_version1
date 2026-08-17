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
import { api, ApiError } from '@/lib/api';
import { walletRunwayHealth } from '@/lib/wallet-runway';
import { toast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
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

/** Wallet summary shape held in local component state. `runwayDays` mirrors
 * `WalletSummaryResponse` (src/lib/api.ts) — it is `number | null`, never a fabricated
 * number: the backend (`WalletService#computeRunwayDays`) returns `null` when there has
 * been no spend in the trailing window, since runway is undefined/effectively-infinite
 * for a dormant-but-funded wallet. Treat `null` as "healthy", never as 0/critical. */
interface WalletSummaryState {
  availableBalance: number;
  escrowLocked: number;
  runwayDays: number | null;
}

/** Real zero-state — used until the dashboard loads, and again for any endpoint that
 * fails to load (never left showing fabricated rows/figures on an error).
 *
 * F-0099: `runwayDays` is `null` here, NOT `0`. Per the contract above, `null` = "healthy"
 * (unknown / no spend), while `0` falls through the health computation to `'critical'` — so a
 * `0` default flashed a false red CRITICAL runway alarm on every load and left it stuck red on a
 * brand-new workspace whose `GET /wallet` 404s. Balance/escrow legitimately default to `0`. */
export const EMPTY_WALLET: WalletSummaryState = {
  availableBalance: 0,
  escrowLocked: 0,
  runwayDays: null,
};

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

/** F-0245 — every data-backed region on this page tracks its own three-way status so a
 * still-loading or failed fetch can never render as the same screen as a genuinely-empty
 * one. `'loading'` is the initial value for all three; nothing here defaults straight to
 * `'ready'`. */
type LoadStatus = 'loading' | 'error' | 'ready';

export function DashboardPage() {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [actionItems, setActionItems] = React.useState<ActionItem[]>([]);
  const [wallet, setWallet] = React.useState<WalletSummaryState>(EMPTY_WALLET);
  const [pipeline, setPipeline] = React.useState<Array<{ stage: string; count: number }>>([]);
  const [actionsStatus, setActionsStatus] = React.useState<LoadStatus>('loading');
  const [walletStatus, setWalletStatus] = React.useState<LoadStatus>('loading');
  const [pipelineStatus, setPipelineStatus] = React.useState<LoadStatus>('loading');

  // Load each of the three data sources independently — `Promise.allSettled` (not
  // `Promise.all`) so that one endpoint rejecting (e.g. a brand-new workspace's
  // `GET /wallet` 404ing) can't discard the other two calls that DID succeed. Each
  // rejected source falls back to a real empty/zero state, never a fabricated one.
  //
  // F-0245 — extracted into a callback (mirrors brand-settings.tsx's `loadWorkspaceInfo`)
  // so each card's Retry button can re-run it, and re-armed to 'loading' on every call so a
  // retry doesn't leave the previous error/empty screen up while the new request is in flight.
  const loadDashboard = React.useCallback(() => {
    let cancelled = false;
    setActionsStatus('loading');
    setWalletStatus('loading');
    setPipelineStatus('loading');
    (async () => {
      const [actionsResult, walletResult, pipelineResult] = await Promise.allSettled([
        api.dashboard.actions('brand'),
        api.wallet.get('brand'),
        api.dashboard.pipeline('brand'),
      ]);
      if (cancelled) return;

      // Array.isArray alone, deliberately no `.length > 0` -- a brand-new account's real
      // answer is a legitimately empty array (no actions pending, no pipeline yet), and both
      // render a proper empty state below. Requiring non-empty treated that correct empty
      // response the same as "API call didn't return usable data," so it silently kept the
      // mock/demo rows forever instead of ever showing the account's real (empty) state.
      // F-0245 — status is set from the settle outcome itself, independent of the data
      // fallback above: a rejected source still gets its real empty state (never a fabricated
      // one, per the comments above) AND is flagged 'error' so the UI shows a retry affordance
      // instead of silently rendering that empty state as if it were a confirmed real zero.
      if (actionsResult.status === 'fulfilled' && Array.isArray(actionsResult.value)) {
        setActionItems(
          actionsResult.value.map((a) => ({ ...a, deadline: new Date(a.deadline) })) as ActionItem[],
        );
        setActionsStatus('ready');
      } else if (actionsResult.status === 'rejected') {
        setActionItems([]);
        setActionsStatus('error');
      } else {
        setActionsStatus('ready');
      }

      if (walletResult.status === 'fulfilled' && walletResult.value) {
        setWallet({
          availableBalance: walletResult.value.availableBalance ?? 0,
          escrowLocked: walletResult.value.escrowLocked ?? 0,
          // `null` means "no spend in window" (dormant wallet, undefined/infinite runway)
          // per WalletService#computeRunwayDays javadoc — never coerce to 0, which the
          // health computation below would treat as a "critical, out of runway" wallet.
          runwayDays: walletResult.value.runwayDays ?? null,
        });
        setWalletStatus('ready');
      } else if (walletResult.status === 'rejected') {
        setWallet(EMPTY_WALLET);
        setWalletStatus('error');
      } else {
        setWalletStatus('ready');
      }

      if (pipelineResult.status === 'fulfilled' && Array.isArray(pipelineResult.value)) {
        setPipeline(pipelineResult.value);
        setPipelineStatus('ready');
      } else if (pipelineResult.status === 'rejected') {
        setPipeline([]);
        setPipelineStatus('error');
      } else {
        setPipelineStatus('ready');
      }

      const failures = [actionsResult, walletResult, pipelineResult].filter(
        (r): r is PromiseRejectedResult => r.status === 'rejected',
      );
      // Was console.error only — on failure the dashboard kept showing mock
      // wallet/actions/pipeline as if they were the brand's real data.
      if (failures.length > 0) {
        const firstError = failures[0].reason;
        toast({
          title: 'Couldn’t load your dashboard',
          description:
            firstError instanceof ApiError ? firstError.message : 'Some figures may be out of date — refresh to retry.',
          variant: 'destructive',
        });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  React.useEffect(() => loadDashboard(), [loadDashboard]);

  const greeting = React.useMemo(() => {
    const hour = new Date().getHours();
    if (hour < 12) return 'Good morning';
    if (hour < 17) return 'Good afternoon';
    return 'Good evening';
  }, []);

  const urgentCount = actionItems.filter((a) => a.priority === 'urgent').length;
  // F-0103: `runwayDays === null` (dormant/unknown/pre-load/404'd wallet) must resolve to
  // 'healthy', not fall through to 'critical' the way `0` would. The rule lives in the shared,
  // regression-pinned helper (src/lib/wallet-runway.ts) so it can't silently regress.
  const walletHealth = walletRunwayHealth(wallet.runwayDays);
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
            {/* F-0245 — never assert "caught up" (a real-zero claim) while the fetch that would
                confirm it is still loading or has failed. */}
            {actionsStatus === 'loading'
              ? 'Loading your latest activity…'
              : actionsStatus === 'error'
                ? 'Some figures may be out of date.'
                : urgentCount > 0
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
            <Badge variant="secondary">
              {actionsStatus === 'ready' ? `${actionItems.length} pending` : '—'}
            </Badge>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          {actionsStatus === 'loading' && (
            <div className="space-y-3" role="status" aria-label="Loading pending actions">
              <Skeleton className="h-16 w-full rounded-lg" />
              <Skeleton className="h-16 w-full rounded-lg" />
              <Skeleton className="h-16 w-full rounded-lg" />
            </div>
          )}

          {actionsStatus === 'error' && (
            <DashboardCardError
              message="Couldn’t load your pending actions."
              onRetry={loadDashboard}
            />
          )}

          {actionsStatus === 'ready' && actionItems.map((item) => {
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

          {actionsStatus === 'ready' && actionItems.length === 0 && (
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
        {pipelineStatus === 'loading' && <PipelineCardSkeleton />}
        {pipelineStatus === 'error' && (
          <PipelineCardShell>
            <DashboardCardError message="Couldn’t load your pipeline." onRetry={loadDashboard} />
          </PipelineCardShell>
        )}
        {pipelineStatus === 'ready' && (
          <PipelineCard
            pipeline={pipeline}
            total={pipelineTotal}
            onClickStage={(stage) =>
              navigate(`/brand/campaigns?status=${stage.toLowerCase().replace(' ', '-')}`)
            }
            onViewAll={() => navigate('/brand/campaigns')}
          />
        )}

        {walletStatus === 'loading' && <WalletCardSkeleton />}
        {walletStatus === 'error' && (
          <WalletCardShell>
            <DashboardCardError message="Couldn’t load your wallet." onRetry={loadDashboard} />
          </WalletCardShell>
        )}
        {walletStatus === 'ready' && (
          <WalletCard
            balance={wallet.availableBalance}
            escrow={wallet.escrowLocked}
            runwayDays={wallet.runwayDays}
            health={walletHealth}
            healthLabel={walletHealthBadge}
            onManage={() => navigate('/brand/wallet')}
          />
        )}
      </div>

      {/* Trend-Spark soft nudge (T7) — renders nothing while loading/on error/204/dismissed,
          so it never leaves a gap when there's nothing to suggest. */}
      <TrendSparkNudgeCard />
    </div>
  );
}

// ---------------------------------------------------------------------------
// F-0245 — shared loading/error primitives for the dashboard's data-backed cards.
// `text-destructive-foreground` (not `text-destructive`) per this theme's pale-bg/strong-fg
// palette — `text-destructive` renders effectively invisible here.
// ---------------------------------------------------------------------------

function DashboardCardError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 py-8 text-center" role="alert">
      <AlertTriangle className="h-5 w-5 text-destructive-foreground" />
      <p className="text-sm text-destructive-foreground">{message}</p>
      <Button type="button" variant="outline" size="sm" onClick={onRetry}>
        Retry
      </Button>
    </div>
  );
}

/** Card chrome only — used to host loading/error content in place of PipelineCard so the
 * header (title + "View all") never renders live pipeline data before it's confirmed loaded. */
function PipelineCardShell({ children }: { children: React.ReactNode }) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base font-medium">Pipeline</CardTitle>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

function PipelineCardSkeleton() {
  return (
    <PipelineCardShell>
      <div role="status" aria-label="Loading pipeline">
        <Skeleton className="h-6 w-full rounded-lg mb-3" />
        <div className="grid grid-cols-3 gap-1.5">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-full" />
        </div>
      </div>
    </PipelineCardShell>
  );
}

/** Same shell-only pattern as PipelineCardShell, for the Wallet card. */
function WalletCardShell({ children }: { children: React.ReactNode }) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base font-medium flex items-center gap-2">
          <Wallet className="h-4 w-4" />
          Wallet
        </CardTitle>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

function WalletCardSkeleton() {
  return (
    <WalletCardShell>
      <div className="space-y-3" role="status" aria-label="Loading wallet">
        <Skeleton className="h-8 w-32" />
        <Skeleton className="h-1.5 w-full" />
        <Skeleton className="h-8 w-full" />
      </div>
    </WalletCardShell>
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

// PL-2/PL-3 (BrandF.md §69): since PL-2, `DashboardService#bucketFor` emits exactly these
// six labels (matching src/lib/brand-pipeline-stage.ts's board vocabulary) — `Completed` is
// now the dead key (bucketFor emits `Settled` for a completed collaboration), kept only as a
// harmless fallback in case another caller ever passes the older label.
const STAGE_COLOR: Record<string, string> = {
  Outreach: 'bg-stage-outreach',
  Negotiating: 'bg-stage-negotiating',
  Contracted: 'bg-stage-contracted',
  'In Progress': 'bg-stage-progress',
  Review: 'bg-stage-review',
  Settled: 'bg-stage-approved',
  Completed: 'bg-stage-approved',
};

// PL-3 follow-up (Priya review): every `--stage-*` background (src/app/globals.css) is a pale
// pastel meant to pair with its own dark `-fg` token (src/lib/stage-colors.ts's
// STAGE_BADGE_CLASS does exactly this) — the segment bar was hardcoding `text-white` on top of
// those pastels instead, which is ~1:1 contrast, worse than the `bg-muted` fallback it replaced.
const STAGE_TEXT: Record<string, string> = {
  Outreach: 'text-stage-outreach-fg',
  Negotiating: 'text-stage-negotiating-fg',
  Contracted: 'text-stage-contracted-fg',
  'In Progress': 'text-stage-progress-fg',
  Review: 'text-stage-review-fg',
  Settled: 'text-stage-approved-fg',
  Completed: 'text-stage-approved-fg',
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
        {/* F-0245 — this component only ever renders once pipelineStatus === 'ready', so an
            empty array here is a confirmed real empty pipeline, not a loading/error gap. */}
        {pipeline.length === 0 && (
          <p className="text-xs text-muted-foreground text-center py-6">
            No deals in your pipeline yet.
          </p>
        )}
        <div className="flex h-6 rounded-lg overflow-hidden mb-3">
          {pipeline.map((stage) => (
            <button
              key={stage.stage}
              type="button"
              className={cn(
                'flex items-center justify-center text-[10px] font-medium hover:opacity-90 transition-opacity w-[var(--stage-w)]',
                STAGE_COLOR[stage.stage] || 'bg-muted',
                STAGE_TEXT[stage.stage] || 'text-foreground',
              )}
              ref={cssVars({ '--stage-w': total ? `${(stage.count / total) * 100}%` : '0%' })}
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
  /** `null` = dormant wallet, no spend in window → undefined/infinite runway, never "0d". */
  runwayDays: number | null;
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
              {runwayDays === null ? '—' : `${runwayDays}d runway`}
            </p>
            {health !== 'healthy' && (
              <p className="text-[10px] text-muted-foreground flex items-center gap-0.5 justify-end">
                <AlertTriangle className="h-2.5 w-2.5" /> Low
              </p>
            )}
          </div>
        </div>
        <Progress
          value={runwayDays === null ? 100 : Math.min((runwayDays / 60) * 100, 100)}
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
