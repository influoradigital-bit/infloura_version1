import * as React from 'react';
import { useParams, Link } from 'react-router-dom';
import { BadgeCheck, Loader2, ArrowLeft } from 'lucide-react';

import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { api, ApiError, type PublicCreatorVerifiedResponse } from '@/lib/api';

/**
 * T-MEERA-CREATOR-PHASE-A (A9, SPEC.md §2.8/§4.6) — public, no-auth verified-metrics snapshot.
 * Route: /c/:username/verified. Deliberately NO rates, NO floors, NO PAN/GSTIN — only what
 * `GET /public/creators/:username/verified` sends, rendered as-is (server-data-only per the
 * spec's "no frontend arithmetic over trust-bearing numbers" discipline).
 */

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString('en-IN', { year: 'numeric', month: 'long', day: 'numeric' });
  } catch {
    return iso;
  }
}

/**
 * T-MEERA-CREATOR-PHASE-A (fix round 1, item 3) — `reach_30d`/`engagement_rate`/`verified_at`
 * are all nullable on the wire (see api.ts's `PublicVerifiedMetrics` doc comment): a
 * Meta-connected creator whose metrics haven't been polled yet sends none of the three. Render
 * an honest "not available yet" instead of crashing `.toLocaleString()` on `undefined` — this
 * is a public, unauthenticated page, so a crash here is a white screen for anyone with the link.
 */
const NOT_AVAILABLE_YET = 'Not available yet';

export default function CreatorVerifiedMetricsPage() {
  const { username } = useParams<{ username: string }>();
  const [data, setData] = React.useState<PublicCreatorVerifiedResponse | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [notFound, setNotFound] = React.useState(false);

  React.useEffect(() => {
    if (!username) return;
    let cancelled = false;
    setLoading(true);
    setNotFound(false);
    api.publicCreators
      .getVerifiedMetrics(username)
      .then((res) => {
        if (!cancelled) setData(res);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 404) {
          setNotFound(true);
        } else {
          setNotFound(true);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [username]);

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (notFound || !data) {
    return (
      <div className="flex min-h-[60vh] flex-col items-center justify-center gap-3 p-6 text-center">
        <p className="text-lg font-semibold">Creator not found</p>
        <p className="text-sm text-muted-foreground">
          This creator isn't discoverable, or hasn't connected a verified account yet.
        </p>
        <Button variant="outline" asChild>
          <Link to="/">
            <ArrowLeft className="mr-2 h-4 w-4" />
            Back to Influora
          </Link>
        </Button>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-xl px-4 py-10">
      <Card>
        <CardHeader className="space-y-2">
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold">{data.display_name}</h1>
            <BadgeCheck className="h-5 w-5 text-primary" aria-label="Meta-verified metrics" />
          </div>
          <p className="text-sm text-muted-foreground">
            @{data.username}
            {data.city ? ` · ${data.city}` : ''}
          </p>
          {data.categories.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {data.categories.map((c) => (
                <Badge key={c} variant="outline">
                  {c}
                </Badge>
              ))}
            </div>
          )}
        </CardHeader>
        <CardContent className="space-y-6">
          <div>
            <p className="mb-3 text-sm font-semibold">Verified Metrics</p>
            <div className="grid grid-cols-3 gap-4 text-center">
              <div>
                <p className="text-xl font-bold">{data.verified_metrics.followers.toLocaleString('en-IN')}</p>
                <p className="text-xs text-muted-foreground">Followers</p>
              </div>
              <div>
                <p className="text-xl font-bold">
                  {data.verified_metrics.reach_30d != null
                    ? data.verified_metrics.reach_30d.toLocaleString('en-IN')
                    : <span className="text-sm font-normal text-muted-foreground">{NOT_AVAILABLE_YET}</span>}
                </p>
                <p className="text-xs text-muted-foreground">30-Day Reach</p>
              </div>
              <div>
                <p className="text-xl font-bold">
                  {data.verified_metrics.engagement_rate != null
                    ? `${data.verified_metrics.engagement_rate}%`
                    : <span className="text-sm font-normal text-muted-foreground">{NOT_AVAILABLE_YET}</span>}
                </p>
                <p className="text-xs text-muted-foreground">Engagement</p>
              </div>
            </div>
            <p className="mt-3 text-center text-xs text-muted-foreground">
              {data.verified_metrics.verified_at
                ? `Verified on ${formatDate(data.verified_metrics.verified_at)} via connected Meta account`
                : 'Verification pending — connected Meta account, metrics not yet available'}
            </p>
          </div>

          <div className="border-t border-border pt-4 text-center">
            <p className="text-lg font-semibold">{data.platform_deal_count}</p>
            <p className="text-xs text-muted-foreground">Deals completed on Influora</p>
          </div>

          <div className="space-y-1 border-t border-border pt-4 text-center text-xs text-muted-foreground">
            <p>Snapshot from {formatDate(data.snapshot_date)}</p>
            <p>No rates shown. Contact the creator directly for pricing.</p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
