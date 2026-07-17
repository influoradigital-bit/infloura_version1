import * as React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  Briefcase,
  CheckCircle2,
  FileSignature,
  IndianRupee,
  Loader2,
  Megaphone,
  MessageCircle,
  Sparkles,
  Upload,
  Wallet,
} from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  api,
  ApiError,
  isApiLive,
  type CreatorDeliverableListItem,
  type WalletSummaryResponse,
} from '@/lib/api';
import {
  mapDealToDealsPageRow,
  type CreatorDealsPageRow,
} from '@/lib/creator-deal-mappers';
import { mockDeals } from '@/pages/creator-deals';
import { useAuthStore } from '@/lib/store';
import { cn, formatINR } from '@/lib/utils';

// ---------------------------------------------------------------------------
// Rollup helpers — all numbers derive from existing clients (no new backend).
// ---------------------------------------------------------------------------

function isActiveDeal(row: CreatorDealsPageRow): boolean {
  return row.status === 'contracted' || row.status === 'in_progress' || row.status === 'review';
}

function countSubmittableDeliverables(items: CreatorDeliverableListItem[]): number {
  return items.filter(
    (d) =>
      !d.completed &&
      (d.status === 'PENDING' || d.status === 'DRAFT' || d.status === 'REVISION_REQUESTED'),
  ).length;
}

interface PendingBreakdown {
  unreadMessages: number;
  awaitingSignature: number;
  submittableDeliverables: number;
  total: number;
}

interface DashboardData {
  wallet: WalletSummaryResponse;
  deals: CreatorDealsPageRow[];
  pending: PendingBreakdown;
}

const EMPTY_WALLET: WalletSummaryResponse = {
  availableBalance: 0,
  escrowLocked: 0,
  pendingPayouts: 0,
  runwayDays: null,
};

const EMPTY_PENDING: PendingBreakdown = {
  unreadMessages: 0,
  awaitingSignature: 0,
  submittableDeliverables: 0,
  total: 0,
};

async function loadDeliverablePendingCount(dealIds: string[]): Promise<number> {
  if (dealIds.length === 0) return 0;
  const lists = await Promise.all(
    dealIds.map((id) =>
      api.creatorDeliverables.listForDeal(id).catch(() => [] as CreatorDeliverableListItem[]),
    ),
  );
  return lists.reduce((sum, items) => sum + countSubmittableDeliverables(items), 0);
}

async function fetchDashboardData(): Promise<DashboardData> {
  if (!isApiLive()) {
    const deals = mockDeals;
    const wallet = await api.wallet.get('creator');
    const unreadMessages = deals.reduce((sum, d) => sum + d.unreadCount, 0);
    const activeIds = deals.filter(isActiveDeal).map((d) => d.id);
    const submittableDeliverables = await loadDeliverablePendingCount(activeIds);
    const awaitingSignature = 0;
    const pending: PendingBreakdown = {
      unreadMessages,
      awaitingSignature,
      submittableDeliverables,
      total: unreadMessages + awaitingSignature + submittableDeliverables,
    };
    return { wallet, deals, pending };
  }

  const [wallet, dealRows] = await Promise.all([
    api.wallet.get('creator'),
    api.deals.list('creator', 'all'),
  ]);

  const deals = dealRows.map(mapDealToDealsPageRow);
  const unreadMessages = deals.reduce((sum, d) => sum + d.unreadCount, 0);
  // `api.contracts` has no bulk/"unsigned" list endpoint on the real backend
  // (verified against ContractController.java — only get/generate/sign
  // exist, all brand-workspace-scoped). Deal rows already carry
  // `contractStatus` from a real, working endpoint (`GET /deals`), so derive
  // the pending-signature count from that instead of inventing a fake
  // `listUnsigned` call — consistent with this file's own "no new backend"
  // rollup-helper convention above.
  const awaitingSignature = dealRows.filter((d) => d.contractStatus === 'PENDING_SIGNATURES').length;
  const activeIds = deals.filter(isActiveDeal).map((d) => d.id);
  const submittableDeliverables = await loadDeliverablePendingCount(activeIds);

  const pending: PendingBreakdown = {
    unreadMessages,
    awaitingSignature,
    submittableDeliverables,
    total: unreadMessages + awaitingSignature + submittableDeliverables,
  };

  return { wallet, deals, pending };
}

const quickLinks = [
  {
    label: 'Deals',
    description: 'Proposals, negotiations & active collabs',
    href: '/creator/deals',
    icon: Briefcase,
  },
  {
    label: 'Campaigns',
    description: 'Browse open brand campaigns',
    href: '/creator/campaigns',
    icon: Megaphone,
  },
  {
    label: 'Wallet',
    description: 'Balance, payouts & transaction history',
    href: '/creator/wallet',
    icon: Wallet,
  },
] as const;

export default function CreatorDashboardPage() {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [wallet, setWallet] = React.useState<WalletSummaryResponse>(EMPTY_WALLET);
  const [deals, setDeals] = React.useState<CreatorDealsPageRow[]>([]);
  const [pending, setPending] = React.useState<PendingBreakdown>(EMPTY_PENDING);

  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const data = await fetchDashboardData();
        if (cancelled) return;
        setWallet(data.wallet);
        setDeals(data.deals);
        setPending(data.pending);
      } catch (e) {
        if (cancelled) return;
        setWallet(EMPTY_WALLET);
        setDeals([]);
        setPending(EMPTY_PENDING);
        setError(e instanceof ApiError ? e.message : 'Could not load dashboard. Try again.');
      } finally {
        if (!cancelled) setLoading(false);
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

  const displayName =
    user?.displayName?.split(/\s+/)[0] || user?.firstName || 'there';
  const activeDealCount = deals.filter(isActiveDeal).length;
  const isEmptyCreator = !loading && deals.length === 0;

  return (
    <CreatorLayout>
      <div className="mx-auto max-w-5xl space-y-6 p-4 sm:p-6">
        <FadeUp>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-2xl font-semibold text-foreground sm:text-3xl">
                {greeting}, {displayName}
              </h1>
              <p className="mt-1 text-sm text-muted-foreground">
                {loading
                  ? 'Loading your workspace…'
                  : pending.total > 0
                    ? `${pending.total} item${pending.total === 1 ? '' : 's'} need your attention`
                    : isEmptyCreator
                      ? 'Welcome — your creator workspace is ready.'
                      : "You're all caught up."}
              </p>
            </div>
            {!isEmptyCreator && (
              <Button
                variant="outline"
                className="gap-2 self-start"
                onClick={() => navigate('/creator/campaigns')}
              >
                <Megaphone className="h-4 w-4" />
                Browse campaigns
              </Button>
            )}
          </div>
        </FadeUp>

        {error && (
          <Alert variant="destructive">
            <AlertTitle>Could not refresh dashboard</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <StaggerContainer className="grid gap-4 sm:grid-cols-3">
          <StaggerItem>
            <Card>
              <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                <CardTitle className="text-sm font-medium text-muted-foreground">
                  Available balance
                </CardTitle>
                <IndianRupee className="h-4 w-4 text-primary" aria-hidden />
              </CardHeader>
              <CardContent>
                {loading ? (
                  <Skeleton className="h-8 w-28" />
                ) : (
                  <>
                    <p className="text-2xl font-semibold tabular-nums">
                      {formatINR(wallet.availableBalance)}
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {wallet.escrowLocked > 0
                        ? `${formatINR(wallet.escrowLocked)} in escrow`
                        : 'Ready to withdraw'}
                    </p>
                  </>
                )}
              </CardContent>
            </Card>
          </StaggerItem>

          <StaggerItem>
            <Card>
              <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                <CardTitle className="text-sm font-medium text-muted-foreground">
                  Active deals
                </CardTitle>
                <Briefcase className="h-4 w-4 text-primary" aria-hidden />
              </CardHeader>
              <CardContent>
                {loading ? (
                  <Skeleton className="h-8 w-12" />
                ) : (
                  <>
                    <p className="text-2xl font-semibold tabular-nums">{activeDealCount}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {deals.length === 0
                        ? 'No collaborations yet'
                        : `${deals.length} total in your pipeline`}
                    </p>
                  </>
                )}
              </CardContent>
            </Card>
          </StaggerItem>

          <StaggerItem>
            <Card
              className={cn(
                pending.total > 0 && !loading && 'border-warning/40 bg-warning/5',
              )}
            >
              <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                <CardTitle className="text-sm font-medium text-muted-foreground">
                  Pending actions
                </CardTitle>
                <Sparkles className="h-4 w-4 text-primary" aria-hidden />
              </CardHeader>
              <CardContent>
                {loading ? (
                  <Skeleton className="h-8 w-12" />
                ) : (
                  <>
                    <p className="text-2xl font-semibold tabular-nums">{pending.total}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {pending.total === 0
                        ? 'Nothing waiting on you'
                        : 'Messages, contracts & deliverables'}
                    </p>
                  </>
                )}
              </CardContent>
            </Card>
          </StaggerItem>
        </StaggerContainer>

        {!loading && pending.total > 0 && (
          <FadeUp delay={0.05}>
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base font-medium">Action breakdown</CardTitle>
                <CardDescription>Rollup from your deals, contracts & deliverables</CardDescription>
              </CardHeader>
              <CardContent className="grid gap-3 sm:grid-cols-3">
                <button
                  type="button"
                  onClick={() => navigate('/creator/deals')}
                  className="flex items-center gap-3 rounded-lg border border-border p-3 text-left transition-[box-shadow,background-color] duration-150 ease-out hover:bg-muted/50 hover:shadow-sm"
                >
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                    <MessageCircle className="h-4 w-4" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium">Unread messages</p>
                    <p className="text-xs text-muted-foreground">Across deal rooms</p>
                  </div>
                  <Badge variant="secondary">{pending.unreadMessages}</Badge>
                </button>

                <button
                  type="button"
                  onClick={() => navigate('/creator/deals?status=in_progress')}
                  className="flex items-center gap-3 rounded-lg border border-border p-3 text-left transition-[box-shadow,background-color] duration-150 ease-out hover:bg-muted/50 hover:shadow-sm"
                >
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                    <FileSignature className="h-4 w-4" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium">Awaiting signature</p>
                    <p className="text-xs text-muted-foreground">Contracts to sign</p>
                  </div>
                  <Badge variant="secondary">{pending.awaitingSignature}</Badge>
                </button>

                <button
                  type="button"
                  onClick={() => navigate('/creator/deals?status=in_progress')}
                  className="flex items-center gap-3 rounded-lg border border-border p-3 text-left transition-[box-shadow,background-color] duration-150 ease-out hover:bg-muted/50 hover:shadow-sm"
                >
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                    <Upload className="h-4 w-4" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium">Deliverables due</p>
                    <p className="text-xs text-muted-foreground">Ready to upload or submit</p>
                  </div>
                  <Badge variant="secondary">{pending.submittableDeliverables}</Badge>
                </button>
              </CardContent>
            </Card>
          </FadeUp>
        )}

        <FadeUp delay={0.1}>
          <div>
            <h2 className="mb-3 text-sm font-medium text-muted-foreground">Quick links</h2>
            <div className="grid gap-3 sm:grid-cols-3">
              {quickLinks.map((link) => {
                const Icon = link.icon;
                return (
                  <Link
                    key={link.href}
                    to={link.href}
                    className="group flex items-start gap-3 rounded-xl border border-border bg-card p-4 transition-[box-shadow,border-color] duration-150 ease-out hover:border-primary/30 hover:shadow-sm"
                  >
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                      <Icon className="h-5 w-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="font-medium text-foreground group-hover:text-primary">
                        {link.label}
                      </p>
                      <p className="mt-0.5 text-xs text-muted-foreground">{link.description}</p>
                    </div>
                    <ArrowRight className="mt-1 h-4 w-4 shrink-0 text-muted-foreground opacity-0 transition-opacity duration-150 group-hover:opacity-100" />
                  </Link>
                );
              })}
            </div>
          </div>
        </FadeUp>

        {isEmptyCreator && (
          <FadeUp delay={0.15}>
            <Card className="border-dashed">
              <CardContent className="flex flex-col items-center py-12 text-center">
                <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-primary/10">
                  <Sparkles className="h-7 w-7 text-primary" />
                </div>
                <h2 className="text-lg font-semibold">No deals yet — that&apos;s normal</h2>
                <p className="mt-2 max-w-md text-sm text-muted-foreground">
                  Brand proposals and campaign invites will show up here once you start collaborating.
                  Browse open campaigns or polish your profile so brands can find you.
                </p>
                <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
                  <Button onClick={() => navigate('/creator/campaigns')} className="gap-2">
                    <Megaphone className="h-4 w-4" />
                    Explore campaigns
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => navigate('/creator/profile')}
                    className="gap-2"
                  >
                    Complete profile
                  </Button>
                </div>
              </CardContent>
            </Card>
          </FadeUp>
        )}

        {!loading && !isEmptyCreator && pending.total === 0 && (
          <FadeUp delay={0.15}>
            <div className="flex items-center justify-center gap-2 rounded-lg border border-border bg-muted/30 px-4 py-6 text-sm text-muted-foreground">
              <CheckCircle2 className="h-4 w-4 text-success" aria-hidden />
              All caught up — check back when brands reach out or deadlines approach.
            </div>
          </FadeUp>
        )}

        {loading && (
          <div className="flex items-center justify-center gap-2 py-4 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            Syncing wallet & deals…
          </div>
        )}
      </div>
    </CreatorLayout>
  );
}
