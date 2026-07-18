import { CreatorLayout } from '@/components/creator/creator-layout';
import { AffiliateEarningsView } from '@/components/creator/AffiliateEarningsView';

/**
 * Creator-facing affiliate earnings page (ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md
 * §17). Live since 2026-07-17: AffiliateEarningsView renders real per-sale
 * commission rows + summary from GET /creator/affiliate-earnings via
 * useAffiliateEarnings — the Wave D backend (AffiliateEarningsService, V28
 * migration, settlement job) shipped and replaced the old coming-soon stub.
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
