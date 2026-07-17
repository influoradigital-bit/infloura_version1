import { AlertTriangle, Loader2, Ticket } from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { CreatorCampaignCard } from '@/components/creator/CreatorCampaignCard';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useCreatorCoupons } from '@/hooks/creator/useCreatorCoupons';

/**
 * Creator-facing "My Coupons" page — Wave A task A4
 * (wiki/tech/REMAINING_WORK_PLAN.md, ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md §15).
 *
 * Backend gap: no creator-authed read endpoint exists yet for "my coupons
 * across all campaigns" — see the gap note in src/lib/api.ts above
 * `creatorCoupons`. `useCreatorCoupons` surfaces that as `notImplemented`,
 * rendered below as an explicit "API not yet available" banner rather than a
 * silent empty state or fabricated data. In demo/mock mode
 * (VITE_API_MODE !== 'live') the hook returns clearly-labeled illustrative
 * rows so the shell can be reviewed without a live backend.
 */
export default function CreatorCouponsPage() {
  const { data, loading, error, notImplemented, refresh } = useCreatorCoupons();

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-3xl px-4 py-6">
        <div className="mb-6">
          <h1 className="text-2xl font-bold">My Coupons</h1>
          <p className="text-muted-foreground">
            Coupon codes and tracking links assigned to you across campaigns — copy and share
            with your audience.
          </p>
        </div>

        {notImplemented && (
          <Alert className="mb-5 border-amber-300 bg-amber-50">
            <AlertTriangle className="h-4 w-4 text-amber-700" />
            <AlertTitle className="text-amber-900">API not yet available</AlertTitle>
            <AlertDescription className="text-amber-800">
              The backend endpoint that lists a creator's coupons across all campaigns
              (<code className="rounded bg-amber-100 px-1 py-0.5 font-mono text-xs">GET /creator/coupons</code>)
              has not been built yet — brands can already generate coupons, but creators can't read
              them back yet. This screen is a UI shell so the design can be reviewed early; it will
              light up once that endpoint ships.
            </AlertDescription>
          </Alert>
        )}

        {error && (
          <Alert className="mb-5" variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>Couldn't load your coupons</AlertTitle>
            <AlertDescription>
              {error}
              <div className="mt-2">
                <Button size="sm" variant="outline" onClick={() => refresh()}>
                  Try again
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : data.length === 0 && !notImplemented && !error ? (
          <EmptyState />
        ) : (
          <div className="space-y-4">
            {data.map((coupon) => (
              <CreatorCampaignCard key={coupon.id} coupon={coupon} />
            ))}
          </div>
        )}
      </div>
    </CreatorLayout>
  );
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <Ticket className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">No coupons yet</p>
      <p className="mt-1 max-w-xs text-sm text-muted-foreground">
        Once a brand assigns you a coupon code for a campaign, it'll show up here ready to copy
        and share.
      </p>
    </div>
  );
}
