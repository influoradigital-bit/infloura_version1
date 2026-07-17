import { CreatorLayout } from '@/components/creator/creator-layout';
import { AffiliateEarningsView } from '@/components/creator/AffiliateEarningsView';

/**
 * Creator-facing affiliate earnings page — Wave A task A4 stub
 * (ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md §17). Affiliate is a Wave D feature
 * that hasn't started on the backend (see AffiliateEarningsView.tsx javadoc-
 * style comment for the full gap explanation) — this route exists so the
 * placeholder is discoverable now and can be swapped for the real view once
 * Wave D ships, without a routing change.
 */
export default function CreatorAffiliateEarningsPage() {
  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-3xl px-4 py-6">
        <AffiliateEarningsView />
      </div>
    </CreatorLayout>
  );
}
