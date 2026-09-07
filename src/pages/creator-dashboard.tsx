import * as React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  Briefcase,
  CheckCircle2,
  Eye,
  FileSignature,
  Globe,
  IndianRupee,
  Megaphone,
  MessageCircle,
  Share2,
  Sparkles,
  TrendingUp,
  Upload,
  Wallet,
} from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { CreatorFirstRunChecklist } from '@/components/creator/CreatorFirstRunChecklist';
import { FadeUp } from '@/components/motion';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  api,
  ApiError,
  isApiLive,
  type ContractApiRecord,
  type CreatorDeliverableListItem,
  type PortfolioAnalytics,
  type WalletSummaryResponse,
} from '@/lib/api';
import {
  mapDealToDealsPageRow,
  type CreatorDealsPageRow,
} from '@/lib/creator-deal-mappers';
import { mockDeals } from '@/pages/creator-deals';
import { useAuthStore } from '@/lib/store';
import { cn, formatINR, publicProfileUrl, publicProfileLabel } from '@/lib/utils';

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

/**
 * F-0631 (two-queries-can-disagree) — this breakdown deliberately does NOT carry a
 * signature count or a total any more. The "awaiting signature" number now comes from
 * `unsignedContracts` (component state, fed by `GET /contracts/unsigned` — the same F-0623
 * query the "Contracts awaiting your signature" list below renders) rather than from a
 * second, looser definition re-filtering `GET /deals` by `contractStatus`. See
 * `awaitingSignatureCount`/`pendingTotal` in `CreatorDashboardPage` below, where the two are
 * recombined for display.
 */
interface PendingBreakdown {
  unreadMessages: number;
  submittableDeliverables: number;
}

interface DashboardData {
  wallet: WalletSummaryResponse;
  deals: CreatorDealsPageRow[];
  pending: PendingBreakdown;
  /** Portfolio reach + handle — supplementary, null if the portfolio API is unreachable. */
  analytics: PortfolioAnalytics | null;
  username: string | null;
}

const EMPTY_WALLET: WalletSummaryResponse = {
  availableBalance: 0,
  escrowLocked: 0,
  pendingPayouts: 0,
  runwayDays: null,
};

const EMPTY_PENDING: PendingBreakdown = {
  unreadMessages: 0,
  submittableDeliverables: 0,
};

/**
 * CR-51 — was one `listForDeal` HTTP call per active deal (N+1 waterfall on every dashboard
 * load). Now a single batched `listForDeals` call covering every active deal id at once; see
 * CreatorDeliverableService#listForCollaborations for the server-side query-count reasoning.
 */
async function loadDeliverablePendingCount(dealIds: string[]): Promise<number> {
  if (dealIds.length === 0) return 0;
  const byDeal = await api.creatorDeliverables
    .listForDeals(dealIds)
    .catch(() => ({}) as Record<string, CreatorDeliverableListItem[]>);
  return dealIds.reduce(
    (sum, id) => sum + countSubmittableDeliverables(byDeal[id] ?? []),
    0,
  );
}

/**
 * Portfolio reach + handle for the "Your public page" card. Supplementary to
 * the core dashboard — both calls self-gate mock/live and each fails soft to
 * null so a portfolio hiccup never blocks wallet/deals from rendering.
 */
async function fetchPortfolioExtras(): Promise<Pick<DashboardData, 'analytics' | 'username'>> {
  const [analytics, mine] = await Promise.all([
    api.portfolio.analytics().catch(() => null),
    api.portfolio.getMine().catch(() => null),
  ]);
  return { analytics, username: mine?.username ?? null };
}

async function fetchDashboardData(): Promise<DashboardData> {
  if (!isApiLive()) {
    const deals = mockDeals;
    const [wallet, extras] = await Promise.all([api.wallet.get('creator'), fetchPortfolioExtras()]);
    const unreadMessages = deals.reduce((sum, d) => sum + d.unreadCount, 0);
    const activeIds = deals.filter(isActiveDeal).map((d) => d.id);
    const submittableDeliverables = await loadDeliverablePendingCount(activeIds);
    const pending: PendingBreakdown = { unreadMessages, submittableDeliverables };
    return { wallet, deals, pending, ...extras };
  }

  const [wallet, dealRows, extras] = await Promise.all([
    api.wallet.get('creator'),
    api.deals.list('creator', 'all'),
    fetchPortfolioExtras(),
  ]);

  const deals = dealRows.map(mapDealToDealsPageRow);
  const unreadMessages = deals.reduce((sum, d) => sum + d.unreadCount, 0);
  // F-0631 (two-queries-can-disagree) — this used to derive its own "awaiting signature" count
  // by filtering `dealRows` for `contractStatus === 'PENDING_SIGNATURES'`. That status alone
  // (per ContractApiRecord's own doc comment in lib/api.ts) collapses "the brand is waiting on
  // the OTHER party" and "this creator is signed but the collaboration was cancelled" into the
  // same value as "this creator genuinely has a contract to sign" — so this tile and the
  // "Contracts awaiting your signature" list below it (which reads the real `GET
  // /contracts/unsigned` / F-0623 query: status=PENDING_SIGNATURES AND creatorSignedAt IS NULL
  // AND the collaboration is not CANCELLED) could disagree. The ruling: an action tile counts
  // only what the viewer can act on, so the signature figure is no longer computed here at
  // all — `awaitingSignatureCount` below derives it from the SAME `unsignedContracts` state the
  // list renders, which makes the two numbers structurally unable to drift apart again.
  const activeIds = deals.filter(isActiveDeal).map((d) => d.id);
  const submittableDeliverables = await loadDeliverablePendingCount(activeIds);

  const pending: PendingBreakdown = { unreadMessages, submittableDeliverables };

  return { wallet, deals, pending, ...extras };
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
  {
    label: 'Affiliate earnings',
    description: 'Commission from every sale you drove',
    href: '/creator/affiliate',
    icon: IndianRupee,
  },
  {
    label: 'Your public page',
    description: 'Edit and share your public profile',
    href: '/creator/portfolio',
    icon: Globe,
  },
] as const;

/**
 * "Your public page" — the growth nudge. Answers "who's looking at me" using
 * the live /me/portfolio/analytics reach numbers, and makes sharing the
 * public profile link one tap (native share sheet on mobile, clipboard
 * copy elsewhere). Reach stats render only when analytics loaded; the share
 * action always works from the handle alone.
 */
function PublicPageCard({
  username,
  analytics,
}: {
  username: string;
  analytics: PortfolioAnalytics | null;
}) {
  const [copied, setCopied] = React.useState(false);
  const publicUrl = publicProfileUrl(username);

  const share = React.useCallback(async () => {
    if (navigator.share) {
      try {
        await navigator.share({ title: `@${username} on Influora`, url: publicUrl });
        return;
      } catch {
        // dismissed — fall through to clipboard
      }
    }
    try {
      await navigator.clipboard.writeText(publicUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard blocked — no-op */
    }
  }, [publicUrl, username]);

  const views = analytics?.pageViews.last30Days ?? null;
  const delta = analytics?.pageViews.deltaPercent ?? null;
  const inquiries = analytics?.brandInquiries ?? null;

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base font-medium">Your public page</CardTitle>
        <CardDescription>
          Share your link so brands can find and hire you.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <a
            href={`/@${username}`}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
          >
            <Globe className="h-4 w-4" aria-hidden />
            {publicProfileLabel(username)}
          </a>
          <div className="flex gap-2">
            <Button size="sm" onClick={share} className="gap-1.5">
              {copied ? (
                <>
                  <CheckCircle2 className="h-4 w-4" aria-hidden />
                  Link copied
                </>
              ) : (
                <>
                  <Share2 className="h-4 w-4" aria-hidden />
                  Share page
                </>
              )}
            </Button>
            <Button size="sm" variant="outline" asChild>
              <Link to="/creator/portfolio">Edit</Link>
            </Button>
          </div>
        </div>

        {(views !== null || inquiries !== null) && (
          <div className="grid grid-cols-2 gap-3 border-t border-border pt-4">
            <div className="flex items-center gap-2.5">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <Eye className="h-4 w-4" aria-hidden />
              </div>
              <div className="min-w-0">
                <p className="text-lg font-semibold leading-none tabular-nums">
                  {views !== null ? views.toLocaleString('en-IN') : '—'}
                </p>
                <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
                  Profile views · 30d
                  {typeof delta === 'number' && delta > 0 && (
                    <span className="inline-flex items-center gap-0.5 text-success-foreground">
                      <TrendingUp className="h-3 w-3" aria-hidden />
                      {delta}%
                    </span>
                  )}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2.5">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <MessageCircle className="h-4 w-4" aria-hidden />
              </div>
              <div className="min-w-0">
                <p className="text-lg font-semibold leading-none tabular-nums">
                  {inquiries !== null ? inquiries.toLocaleString('en-IN') : '—'}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">Brand inquiries · 30d</p>
              </div>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * F-0637: `ContractResponse` (backend) now returns `campaignTitle` and `brandWorkspaceName` so a
 * creator can tell same-amount unsigned contracts apart without opening each one — added this same
 * wave by the companion backend fix (`ContractService#resolveCampaignTitle` /
 * `#resolveBrandWorkspaceName`), both best-effort: null when unresolvable, never thrown.
 *
 * Both fields are declared on `ContractApiRecord` itself (lib/api.ts). They were briefly read here
 * through a local intersection type + `as` cast, which a fresh-context review correctly flagged as
 * the very FE-type-vs-DTO drift class this wave was closing (F-0435/F-0464): a cast leaves `tsc`
 * unable to notice if the server field is renamed.
 *
 * Prefers the campaign's own title; falls back to the brand workspace name; never fabricates a
 * label when the backend's best-effort resolution came back with neither.
 */
function contractIdentityLabel(contract: ContractApiRecord): string | null {
  const label = contract.campaignTitle?.trim() || contract.brandWorkspaceName?.trim();
  return label ? label : null;
}

/**
 * F-0638: `collaborationId` can come back null/missing for a contract (the same best-effort
 * resolution above can fail to find a linked collaboration) even though `ContractApiRecord` types
 * it as a required `string`. Interpolating it unguarded into the row's href used to produce a
 * literal `/creator/chat?deal=undefined&tab=contract` (or `...=null...`) link instead of failing
 * safely.
 */
function linkedDealId(contract: ContractApiRecord): string | null {
  const id = contract.collaborationId as string | null | undefined;
  return typeof id === 'string' && id.length > 0 ? id : null;
}

export default function CreatorDashboardPage() {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [wallet, setWallet] = React.useState<WalletSummaryResponse>(EMPTY_WALLET);
  const [deals, setDeals] = React.useState<CreatorDealsPageRow[]>([]);
  const [pending, setPending] = React.useState<PendingBreakdown>(EMPTY_PENDING);
  const [analytics, setAnalytics] = React.useState<PortfolioAnalytics | null>(null);
  const [username, setUsername] = React.useState<string | null>(null);

  // Contracts awaiting the creator's own signature (GET /contracts/unsigned,
  // ContractController.java:58 — creator-only, api.contracts.listUnsigned). Fetched
  // independently of fetchDashboardData above: it is a discovery list, not part of the
  // wallet/deals/pending rollup, and its loading/error states must stay genuinely distinct from
  // that rollup's (empty-state-misleads is a recurring defect class in this codebase — an error
  // here must never render as "nothing to sign", and vice versa).
  const [unsignedContracts, setUnsignedContracts] = React.useState<ContractApiRecord[]>([]);
  const [unsignedLoading, setUnsignedLoading] = React.useState(true);
  const [unsignedError, setUnsignedError] = React.useState<string | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      setUnsignedLoading(true);
      setUnsignedError(null);
      try {
        const list = await api.contracts.listUnsigned('creator');
        if (cancelled) return;
        setUnsignedContracts(list);
      } catch (e) {
        if (cancelled) return;
        setUnsignedContracts([]);
        setUnsignedError(
          e instanceof ApiError ? e.message : "We couldn't load your contracts. Please try again.",
        );
      } finally {
        if (!cancelled) setUnsignedLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

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
        setAnalytics(data.analytics);
        setUsername(data.username);
      } catch (e) {
        if (cancelled) return;
        setWallet(EMPTY_WALLET);
        setDeals([]);
        setPending(EMPTY_PENDING);
        setAnalytics(null);
        setUsername(null);
        setError(e instanceof ApiError ? e.message : 'Could not load dashboard. Try again.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * F-0631 (two-queries-can-disagree) — the single source of truth for "how many contracts
   * does this creator have to sign". `unsignedContracts` is the F-0623 `GET /contracts/unsigned`
   * list (status=PENDING_SIGNATURES AND creatorSignedAt IS NULL AND collaboration not
   * CANCELLED) — the exact same state the "Contracts awaiting your signature" card below
   * renders row-for-row. Deriving the tile from its length rather than maintaining a second
   * filter over `GET /deals`' `contractStatus` is what makes the tile and the list structurally
   * unable to disagree: there is only one count in this component now, not two.
   *
   * `pendingLoading` folds in `unsignedLoading` alongside the main `loading` flag so the tile
   * never displays a total computed before this count has actually arrived — the two fetches
   * are independent (see the state declarations above), and without this the tile could flash
   * a too-low number while `unsignedContracts` was still in flight.
   */
  const awaitingSignatureCount = unsignedContracts.length;
  const pendingLoading = loading || unsignedLoading;
  const pendingTotal = pending.unreadMessages + awaitingSignatureCount + pending.submittableDeliverables;

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
        <FadeUp y={0}>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-2xl font-semibold text-foreground sm:text-3xl">
                {greeting}, {displayName}
              </h1>
              <p className="mt-1 text-sm text-muted-foreground">
                {pendingLoading
                  ? 'Loading your workspace…'
                  : pendingTotal > 0
                    ? `${pendingTotal} item${pendingTotal === 1 ? '' : 's'} need your attention`
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

        {/* First-run ladder — the ordered "what do I do next" the tiles below cannot give, since
            they report state that a brand-new creator has none of. Retires itself once every
            step is provably done, or on dismissal. */}
        <CreatorFirstRunChecklist deals={deals} username={username} ready={!loading && !error} />

        <FadeUp y={0} className="grid gap-4 sm:grid-cols-3">
          <div>
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
                        ? `${formatINR(wallet.escrowLocked)} secured`
                        : 'Ready to withdraw'}
                    </p>
                  </>
                )}
              </CardContent>
            </Card>
          </div>

          <div>
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
          </div>

          <div>
            <Card
              className={cn(
                pendingTotal > 0 && !pendingLoading && 'border-warning/40 bg-warning/5',
              )}
            >
              <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                <CardTitle className="text-sm font-medium text-muted-foreground">
                  Pending actions
                </CardTitle>
                <Sparkles className="h-4 w-4 text-primary" aria-hidden />
              </CardHeader>
              <CardContent>
                {pendingLoading ? (
                  <Skeleton className="h-8 w-12" />
                ) : (
                  <>
                    <p className="text-2xl font-semibold tabular-nums">{pendingTotal}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {pendingTotal === 0
                        ? 'Nothing waiting on you'
                        : 'Messages, contracts & deliverables'}
                    </p>
                  </>
                )}
              </CardContent>
            </Card>
          </div>
        </FadeUp>

        {/* Contracts awaiting your signature — discovery only. Signing itself already works on
            each deal's own contract tab (CreatorDealContractTab, via api.contracts.sign); every
            row here links there rather than reimplementing a signing UI. */}
        <FadeUp y={0} delay={0.02}>
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-medium">
                Contracts awaiting your signature
              </CardTitle>
              <CardDescription>
                The brand has signed — these are waiting on you to countersign.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {unsignedLoading ? (
                <div className="space-y-2" aria-hidden>
                  <Skeleton className="h-14 w-full rounded-lg" />
                  <Skeleton className="h-14 w-full rounded-lg" />
                </div>
              ) : unsignedError ? (
                <Alert variant="destructive">
                  <AlertTitle>Could not load contracts</AlertTitle>
                  <AlertDescription>{unsignedError}</AlertDescription>
                </Alert>
              ) : unsignedContracts.length === 0 ? (
                <div className="flex items-center gap-2 rounded-lg border border-border bg-muted/30 px-4 py-6 text-sm text-muted-foreground">
                  <CheckCircle2 className="h-4 w-4 text-success-foreground" aria-hidden />
                  No contracts waiting on your signature.
                </div>
              ) : (
                <div className="space-y-2">
                  {unsignedContracts.map((contract) => {
                    const contractLabel = contractIdentityLabel(contract);
                    const dealId = linkedDealId(contract);

                    const rowBody = (
                      <>
                        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-warning/10 text-warning">
                          <FileSignature className="h-4 w-4" aria-hidden />
                        </div>
                        <div className="min-w-0 flex-1">
                          {/* F-0637: campaignTitle (falling back to brandWorkspaceName) — the
                              identifying line that used to be missing, leaving same-amount rows
                              visually identical. Omitted, not fabricated, when the backend's
                              best-effort resolution found neither. */}
                          {contractLabel && (
                            <p className="truncate text-sm font-medium text-foreground">
                              {contractLabel}
                            </p>
                          )}
                          <p className="text-sm font-medium tabular-nums">
                            {formatINR(contract.totalAmount)}
                          </p>
                          <p className="text-xs text-muted-foreground">
                            {contract.milestones?.length ?? 0} milestone
                            {(contract.milestones?.length ?? 0) === 1 ? '' : 's'} ·{' '}
                            {/* F-0623: this used to hardcode "brand signed, your turn" for every row.
                                The backend list is now scoped to PENDING_SIGNATURES contracts (a real
                                status transition only reached once someone has signed), but the FE
                                tells the truth off the record's own brandSignedAt field rather than
                                assume the backend invariant — a field this record already carries,
                                never fabricated. */}
                            {contract.brandSignedAt
                              ? 'brand signed, your turn'
                              : 'awaiting your signature'}
                          </p>
                        </div>
                      </>
                    );

                    // F-0638: no real collaboration id to link to — render the row's info without
                    // a clickable wrapper rather than ship a link to the literal string
                    // "undefined"/"null".
                    if (!dealId) {
                      return (
                        <div
                          key={contract.id}
                          className="flex cursor-default items-center gap-3 rounded-lg border border-dashed border-border/60 p-3 opacity-80"
                        >
                          {rowBody}
                        </div>
                      );
                    }

                    return (
                      <Link
                        key={contract.id}
                        to={`/creator/chat?deal=${dealId}&tab=contract`}
                        className="flex items-center gap-3 rounded-lg border border-border p-3 transition-[box-shadow,background-color] duration-150 ease-out hover:bg-muted/50 hover:shadow-sm"
                      >
                        {rowBody}
                        <ArrowRight className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
                      </Link>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        </FadeUp>

        {/* Placeholder for the sections that mount only after the fetch resolves
            (public-page card / action breakdown) — reserving their space keeps
            the page from reflowing downward when the data lands. */}
        {loading && (
          <div className="space-y-6" aria-hidden>
            <Skeleton className="h-44 w-full rounded-xl" />
            <Skeleton className="h-36 w-full rounded-xl" />
          </div>
        )}

        {!loading && username && (
          <FadeUp y={0} delay={0.05}>
            <PublicPageCard username={username} analytics={analytics} />
          </FadeUp>
        )}

        {!pendingLoading && pendingTotal > 0 && (
          <FadeUp y={0} delay={0.05}>
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
                  <Badge variant="secondary">{awaitingSignatureCount}</Badge>
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

        <FadeUp y={0} delay={0.1}>
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
          <FadeUp y={0} delay={0.15}>
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

        {!pendingLoading && !isEmptyCreator && pendingTotal === 0 && (
          <FadeUp y={0} delay={0.15}>
            <div className="flex items-center justify-center gap-2 rounded-lg border border-border bg-muted/30 px-4 py-6 text-sm text-muted-foreground">
              <CheckCircle2 className="h-4 w-4 text-success-foreground" aria-hidden />
              All caught up — check back when brands reach out or deadlines approach.
            </div>
          </FadeUp>
        )}

      </div>
    </CreatorLayout>
  );
}
