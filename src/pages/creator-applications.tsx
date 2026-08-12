import * as React from 'react';
import { Link } from 'react-router-dom';
import { ClipboardList, Loader2 } from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { CreatorApplicationCard } from '@/components/creator/CreatorApplicationCard';
import { FadeUp } from '@/components/motion/FadeUp';
import { StaggerContainer, StaggerItem } from '@/components/motion/StaggerContainer';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { APPLICATION_BUCKETS, bucketOf, type ApplicationBucket } from '@/lib/application-status';
import type { CreatorApplicationRow } from '@/lib/api';
import { api, ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const PAGE_SIZE = 50;

type FilterTab = 'all' | ApplicationBucket;

const TABS: Array<{ id: FilterTab; label: string }> = [
  { id: 'all', label: 'All' },
  ...APPLICATION_BUCKETS.map((b) => ({ id: b.id as FilterTab, label: b.label })),
];

export default function CreatorApplicationsPage() {
  const { toast } = useToast();
  const [applications, setApplications] = React.useState<CreatorApplicationRow[]>([]);
  const [activeFilter, setActiveFilter] = React.useState<FilterTab>('all');
  const [page, setPage] = React.useState(1);
  const [hasMore, setHasMore] = React.useState(false);
  const [loading, setLoading] = React.useState(true);
  const [loadingMore, setLoadingMore] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const fetchApplications = React.useCallback(
    async (pageNum: number, append: boolean) => {
      if (append) {
        setLoadingMore(true);
      } else {
        setLoading(true);
        setError(null);
      }
      try {
        const result = await api.creatorApplications.list(pageNum, PAGE_SIZE);
        setApplications((prev) => (append ? [...prev, ...result.applications] : result.applications));
        setHasMore(result.meta.hasMore);
        setPage(pageNum);
      } catch (e) {
        const message = e instanceof ApiError ? e.message : 'Could not load your applications. Try again.';
        if (!append) {
          setApplications([]);
          setError(message);
        } else {
          // Load-more failure shouldn't wipe the already-loaded page — keep it, surface a toast.
          toast({ title: 'Couldn’t load more applications', description: message, variant: 'destructive' });
        }
      } finally {
        setLoading(false);
        setLoadingMore(false);
      }
    },
    [toast],
  );

  React.useEffect(() => {
    void fetchApplications(1, false);
  }, [fetchApplications]);

  const counts = React.useMemo(() => {
    const map = new Map<FilterTab, number>();
    map.set('all', applications.length);
    APPLICATION_BUCKETS.forEach((b) => {
      map.set(
        b.id,
        applications.filter((a) => bucketOf(a.status) === b.id).length,
      );
    });
    return map;
  }, [applications]);

  const filtered = React.useMemo(() => {
    if (activeFilter === 'all') return applications;
    return applications.filter((a) => bucketOf(a.status) === activeFilter);
  }, [applications, activeFilter]);

  const hasAnyApplications = applications.length > 0;

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-4xl px-4 py-6">
        <FadeUp className="mb-6">
          <h1 className="text-2xl font-bold tracking-tight">My applications</h1>
          <p className="text-muted-foreground">
            Every campaign you&rsquo;ve applied to, and where it stands.
          </p>
          {/* CR-59 — this page's source (Collaboration.source = APPLICATION) deliberately
              excludes brand-initiated invites by design (my-applications-plan-2026-07-24.md).
              That's not a dead end, though: an invite is a `Collaboration` with
              status=INVITED/source=INVITATION, which already has its own dedicated,
              fully-actionable surface — the "New" tab on /creator/deals (DealService's
              "new" filter resolves to exactly `[INVITED]` for CREATOR role, and
              `DealRow` there renders real Accept/Decline buttons wired to
              POST /deals/:id/accept and /reject — gated on `deal.status === 'new'`, i.e.
              deal-stage.ts's mapCollaborationStatusToDealStage (re-exported, not
              redefined, by creator-deal-mappers.ts), NOT that same file's
              ACCEPTABLE_COLLABORATION_STATUSES, which this page never imports;
              harmless for INVITED since both agree, but don't extend this cross-link's
              reasoning to other statuses without checking the DealStage mapping first —
              APPLIED/SHORTLISTED/IN_NEGOTIATION are server-acceptable but map to
              'negotiating', which renders no Accept button here at all).
              The previous version of this cross-link pointed at Discover Campaigns, which
              only ever renders a static "Invited" badge (CreatorBrowseCampaignCard.tsx)
              with no action at all — a real dead end. Point at the surface that actually
              lets a creator act on the invite instead of reinventing that flow here. */}
          <p className="mt-1 text-xs text-muted-foreground">
            Brand invites don&rsquo;t show up here — check the{' '}
            <Link
              to="/creator/deals?status=new"
              className="font-medium underline underline-offset-2 hover:text-foreground"
            >
              New tab in Deals
            </Link>
            {' '}to view and respond to any pending invites.
          </p>
        </FadeUp>

        {hasAnyApplications && (
          <FadeUp delay={0.05} className="mb-5">
            <div className="flex flex-wrap gap-2" role="tablist" aria-label="Filter applications by status">
              {TABS.map((tab) => {
                const active = activeFilter === tab.id;
                const count = counts.get(tab.id) ?? 0;
                return (
                  <button
                    key={tab.id}
                    type="button"
                    role="tab"
                    aria-pressed={active}
                    aria-selected={active}
                    onClick={() => setActiveFilter(tab.id)}
                    className={cn(
                      'flex items-center gap-1.5 whitespace-nowrap rounded-full border px-3 py-1.5 text-xs font-medium transition-colors',
                      active
                        ? 'border-primary bg-primary text-primary-foreground'
                        : 'border-border bg-background text-muted-foreground hover:bg-muted',
                    )}
                  >
                    {tab.label}
                    <Badge
                      variant="secondary"
                      className={cn(
                        'h-4 min-w-4 rounded-full px-1.5 text-[10px]',
                        active && 'bg-primary-foreground/20 text-primary-foreground',
                      )}
                    >
                      {count}
                    </Badge>
                  </button>
                );
              })}
            </div>
          </FadeUp>
        )}

        {error && (
          <Alert className="mb-5" variant="destructive">
            <AlertTitle>Could not load your applications</AlertTitle>
            <AlertDescription>
              {error}
              <div className="mt-2">
                <Button size="sm" variant="outline" onClick={() => void fetchApplications(1, false)}>
                  Try again
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}

        <div aria-live="polite">
          {loading ? (
            <div className="space-y-4">
              {Array.from({ length: 3 }).map((_, i) => (
                <Skeleton key={i} className="h-32 w-full rounded-xl" />
              ))}
            </div>
          ) : !error && applications.length === 0 ? (
            <EmptyState />
          ) : !error && filtered.length === 0 ? (
            <FilteredEmptyState filterLabel={TABS.find((t) => t.id === activeFilter)?.label ?? ''} />
          ) : !error ? (
            <>
              <StaggerContainer className="space-y-4">
                {filtered.map((application) => (
                  <StaggerItem key={application.dealId}>
                    <CreatorApplicationCard application={application} />
                  </StaggerItem>
                ))}
              </StaggerContainer>

              {hasMore && (
                <div className="mt-6 flex justify-center">
                  <Button
                    variant="outline"
                    disabled={loadingMore}
                    onClick={() => void fetchApplications(page + 1, true)}
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
          ) : null}
        </div>
      </div>
    </CreatorLayout>
  );
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <ClipboardList className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">No applications yet</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">
        Apply to a campaign and it will show up here so you can track its status.
      </p>
      <Button className="mt-4" asChild>
        <Link to="/creator/campaigns">Explore Campaigns</Link>
      </Button>
    </div>
  );
}

function FilteredEmptyState({ filterLabel }: { filterLabel: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <ClipboardList className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">No {filterLabel.toLowerCase()} applications</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">
        Try a different filter to see your other applications.
      </p>
    </div>
  );
}
