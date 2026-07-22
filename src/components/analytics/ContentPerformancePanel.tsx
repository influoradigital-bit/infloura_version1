import { AlertTriangle, Image as ImageIcon, TrendingUp } from 'lucide-react';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import type { ContentPerformanceItem } from '@/lib/api';

interface ContentPerformancePanelProps {
  data: ContentPerformanceItem[] | null;
  loading?: boolean;
  error?: string | null;
  notImplemented?: boolean;
  className?: string;
}

/**
 * `n` is `number | null | undefined` because the wire DTO omits `reach`/
 * `impressions` entirely when Meta didn't report them (`@JsonInclude(NON_NULL)`
 * on `AnalyticsDtos.ContentPerformanceResponse`) rather than sending JSON
 * `null` — so both the explicit-null and omitted-key cases land here and
 * must render the same "no data" fallback instead of the literal text
 * "undefined" (Priya's brand-fixes review, fix #4).
 */
function formatCompact(n: number | null | undefined): string {
  if (n == null) return '—';
  return n >= 1000 ? `${(n / 1000).toFixed(1)}K` : String(n);
}

/**
 * Content-performance panel — Wave B task B5. There is no brand-facing
 * per-post media-metrics endpoint on the backend today (checked
 * AnalyticsController directly: only /metrics, /scores, /demographics
 * exist) — see the gap note above `contentPerformance` in src/lib/api.ts.
 *
 * Built against the proposed shape, same "API not yet available" amber
 * banner convention already established by CreatorCouponsPage (A4) for an
 * identical honest-gap situation, rather than a plain "coming soon" stub —
 * this reflects that the underlying data (MediaMetric rows) already exists
 * server-side from B1, it's only the brand-facing read surface that's
 * missing.
 */
export function ContentPerformancePanel({
  data,
  loading = false,
  error,
  notImplemented = false,
  className,
}: ContentPerformancePanelProps) {
  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <Skeleton className="h-4 w-40" />
        </CardHeader>
        <CardContent className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-14 w-full" />
          ))}
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          Content Performance
        </CardTitle>
      </CardHeader>
      <CardContent>
        {notImplemented && (
          <Alert className="mb-4 border-amber-300 bg-amber-50">
            <AlertTriangle className="h-4 w-4 text-amber-700" />
            <AlertTitle className="text-amber-900">API not yet available</AlertTitle>
            <AlertDescription className="text-amber-800">
              The backend endpoint that lists a creator's per-post performance
              (<code className="rounded bg-amber-100 px-1 py-0.5 font-mono text-xs">
                GET /analytics/creators/{'{id}'}/media
              </code>
              ) has not been built yet — per-post metrics are already persisted server-side
              (B1's media polling), but there's no brand-facing read surface for them yet. This
              panel is a UI shell so the design can be reviewed early; it will light up once that
              endpoint ships.
            </AlertDescription>
          </Alert>
        )}

        {!notImplemented && error && (
          <Alert className="mb-4" variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>Couldn't load content performance</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        {!error && data && data.length === 0 && (
          <Empty className="border-0 p-0">
            <EmptyHeader>
              <ImageIcon className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
              <EmptyTitle>No posts yet</EmptyTitle>
              <EmptyDescription>
                Per-post performance will appear once media metrics have been polled.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        )}

        {data && data.length > 0 && (
          <div className="space-y-2" role="list" aria-label="Per-post content performance">
            {data.map((item) => (
              <div
                key={item.mediaId}
                role="listitem"
                className="flex items-center justify-between gap-4 rounded-lg border border-border p-3"
              >
                <div className="flex min-w-0 items-center gap-3">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-muted">
                    <ImageIcon className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
                  </div>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">{item.mediaType}</p>
                    <p className="text-xs text-muted-foreground">
                      {new Date(item.postedAt).toLocaleDateString()}
                    </p>
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-4 text-sm">
                  <div className="text-right">
                    <p className="font-medium">{formatCompact(item.reach)}</p>
                    <p className="text-xs text-muted-foreground">Reach</p>
                  </div>
                  <div className="text-right">
                    <p className="font-medium">{formatCompact(item.impressions)}</p>
                    <p className="text-xs text-muted-foreground">Impressions</p>
                  </div>
                  <div className="text-right">
                    <p className="flex items-center justify-end gap-1 font-medium">
                      <TrendingUp className="h-3.5 w-3.5 text-success-foreground" aria-hidden="true" />
                      {/* Loose null check: an omitted (NON_NULL) key arrives as `undefined`,
                          not `null` — `!== null` never caught that case (Priya's brand-fixes
                          review, fix #4). */}
                      {item.engagementRate != null ? `${item.engagementRate}%` : '—'}
                    </p>
                    <p className="text-xs text-muted-foreground">Eng. rate</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default ContentPerformancePanel;
