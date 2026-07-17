import { IndianRupee } from 'lucide-react';

import { Card } from '@/components/ui/card';

/**
 * Affiliate earnings — placeholder shell only.
 *
 * ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md §17 describes a full
 * `AffiliateEarningsView` backed by `useAffiliateEarnings`/`useAffiliateSummary`
 * hooks hitting a commission/settlement API. None of that exists on the
 * backend: per wiki/tech/REMAINING_WORK_PLAN.md, affiliate revenue-share is
 * Wave D (D4 — `AffiliateEarningsService` + migration + monthly settlement
 * job), which has **not started**. There is no `AffiliateEarning` entity, no
 * commission table, no settlement job — nothing to call.
 *
 * Building the spec's version now would mean inventing a REST contract
 * (`GET /affiliate/:campaignId/earnings`, `GET /affiliate/:campaignId/summary`)
 * that Vikram hasn't designed and that would very likely mismatch whatever
 * D4 actually ships (money-adjacent, so it also needs Kabir + Rohan review
 * before any contract is real). So this is intentionally just a coming-soon
 * card — no fabricated earnings numbers, no invented hook, no invented
 * endpoint path.
 */
export function AffiliateEarningsView() {
  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Affiliate Earnings</h2>
        <p className="text-muted-foreground">
          Track commission from affiliate-model campaigns in one place.
        </p>
      </div>

      <Card className="border-dashed p-8 text-center">
        <div className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
          <IndianRupee className="h-7 w-7 text-muted-foreground" />
        </div>
        <p className="font-medium text-muted-foreground">Coming soon</p>
        <p className="mx-auto mt-1 max-w-sm text-sm text-muted-foreground">
          Affiliate revenue-share campaigns aren't live yet — this view will show your commission
          per sale, monthly totals, and payout schedule once that feature ships.
        </p>
      </Card>
    </div>
  );
}

export default AffiliateEarningsView;
